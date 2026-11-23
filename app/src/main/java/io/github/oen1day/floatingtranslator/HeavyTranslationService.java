package io.github.oen1day.floatingtranslator;

import android.app.Service;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Message;
import android.os.Messenger;
import android.os.Process;
import android.os.RemoteException;
import android.os.SystemClock;
import android.util.Log;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

public class HeavyTranslationService extends Service {
    public static final int MSG_TRANSLATE = 1;
    public static final int MSG_CANCEL = 2;
    public static final int MSG_UNLOAD = 3;

    public static final int MSG_STATUS = 101;
    public static final int MSG_RESULT = 102;
    public static final int MSG_ERROR = 103;

    public static final String KEY_REQUEST_ID = "request_id";
    public static final String KEY_PROMPT = "prompt";
    public static final String KEY_MAX_TOKENS = "max_tokens";
    public static final String KEY_TEXT = "text";
    public static final String KEY_MESSAGE = "message";

    private static final String TAG = "ScreenTranslatorSakuraService";
    private final AtomicInteger generation = new AtomicInteger();
    private HandlerThread serviceThread;
    private ExecutorService executor;
    private Messenger messenger;

    static {
        System.loadLibrary("screen_translator_llama");
    }

    @Override
    public void onCreate() {
        super.onCreate();
        serviceThread = new HandlerThread("sakura-llm-handler", Process.THREAD_PRIORITY_BACKGROUND);
        serviceThread.start();
        executor = Executors.newSingleThreadExecutor(
                runnable -> new Thread(runnable, "sakura-7b-inference")
        );
        Handler handler = new Handler(serviceThread.getLooper(), this::handleMessage);
        messenger = new Messenger(handler);
        Log.i(TAG, "Heavy translation process created pid=" + Process.myPid());
    }

    private boolean handleMessage(Message message) {
        switch (message.what) {
            case MSG_TRANSLATE:
                handleTranslate(message);
                return true;
            case MSG_CANCEL:
                generation.incrementAndGet();
                nativeCancel();
                Log.i(TAG, "Heavy translation cancelled");
                return true;
            case MSG_UNLOAD:
                generation.incrementAndGet();
                nativeCancel();
                executor.execute(() -> {
                    nativeUnload();
                    stopSelf();
                });
                return true;
            default:
                return false;
        }
    }

    private void handleTranslate(Message message) {
        Bundle request = message.getData();
        int requestId = request.getInt(KEY_REQUEST_ID);
        String prompt = request.getString(KEY_PROMPT, "");
        int maxTokens = Math.max(64, Math.min(
                1024,
                request.getInt(KEY_MAX_TOKENS, 512)
        ));
        Messenger replyTo = message.replyTo;
        int requestGeneration = generation.incrementAndGet();
        nativeCancel();

        executor.execute(() -> {
            if (requestGeneration != generation.get()) {
                return;
            }
            try {
                if (!UltraLocalModelManager.isReady(this)) {
                    throw new IllegalStateException("日→中专项模型未下载或尚未通过校验");
                }
                if (!nativeIsLoaded()) {
                    sendStatus(replyTo, requestId, "日→中专项模型加载中");
                    long loadStartedAt = SystemClock.elapsedRealtime();
                    int result = nativeLoad(
                            UltraLocalModelManager.modelFile(this).getAbsolutePath(),
                            HeavyTranslationPerformance.contextTokens(this),
                            HeavyTranslationPerformance.threads(this),
                            HeavyTranslationPerformance.batchTokens(this),
                            HeavyTranslationPerformance.ubatchTokens(this),
                            HeavyTranslationPerformance.repack(this),
                            HeavyTranslationPerformance.bigCores(this),
                            HeavyTranslationPerformance.q8Kv(this)
                    );
                    if (result != 0) {
                        throw new IllegalStateException(
                                "日→中专项原生模型加载失败，代码 " + result
                        );
                    }
                    Log.i(
                            TAG,
                            "Sakura load elapsedMs="
                                    + (SystemClock.elapsedRealtime() - loadStartedAt)
                    );
                }
                if (requestGeneration != generation.get()) {
                    return;
                }
                sendStatus(replyTo, requestId, "Sakura 7B 翻译中");
                long generateStartedAt = SystemClock.elapsedRealtime();
                String translated = nativeGenerate(prompt, maxTokens);
                Log.i(
                        TAG,
                        "Sakura generate elapsedMs="
                                + (SystemClock.elapsedRealtime() - generateStartedAt)
                );
                if (requestGeneration != generation.get()) {
                    return;
                }
                if (translated == null || translated.trim().isEmpty()) {
                    throw new IllegalStateException("Sakura 7B 返回空译文");
                }
                sendResult(replyTo, requestId, translated);
            } catch (Throwable error) {
                Log.w(TAG, "Heavy translation failed", error);
                if (requestGeneration == generation.get()) {
                    sendError(
                            replyTo,
                            requestId,
                            error.getMessage() == null
                                    ? error.getClass().getSimpleName()
                                    : error.getMessage()
                    );
                }
            }
        });
    }

    private void sendStatus(Messenger replyTo, int requestId, String text) {
        send(replyTo, MSG_STATUS, requestId, text);
    }

    private void sendResult(Messenger replyTo, int requestId, String text) {
        send(replyTo, MSG_RESULT, requestId, text);
    }

    private void sendError(Messenger replyTo, int requestId, String text) {
        send(replyTo, MSG_ERROR, requestId, text);
    }

    private void send(Messenger replyTo, int what, int requestId, String text) {
        if (replyTo == null) {
            return;
        }
        Message message = Message.obtain(null, what);
        Bundle data = new Bundle();
        data.putInt(KEY_REQUEST_ID, requestId);
        data.putString(KEY_TEXT, text);
        message.setData(data);
        try {
            replyTo.send(message);
        } catch (RemoteException error) {
            Log.w(TAG, "Unable to reply to floating process", error);
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return messenger.getBinder();
    }

    @Override
    public void onDestroy() {
        generation.incrementAndGet();
        nativeCancel();
        if (!executor.isShutdown()) {
            executor.execute(this::nativeUnload);
            executor.shutdown();
        }
        if (serviceThread != null) {
            serviceThread.quitSafely();
        }
        super.onDestroy();
    }

    private native int nativeLoad(
            String modelPath,
            int contextSize,
            int threads,
            int batchSize,
            int ubatchSize,
            boolean repack,
            boolean bigCores,
            boolean q8Kv
    );

    private native boolean nativeIsLoaded();

    private native void nativeCancel();

    private native void nativeUnload();

    private native String nativeGenerate(String prompt, int maxTokens);
}
