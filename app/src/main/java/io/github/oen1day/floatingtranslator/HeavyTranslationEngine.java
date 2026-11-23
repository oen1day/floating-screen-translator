package io.github.oen1day.floatingtranslator;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.Messenger;
import android.os.RemoteException;
import android.util.Log;

import java.util.concurrent.atomic.AtomicInteger;

public class HeavyTranslationEngine implements LocalTranslationEngine {
    private static final String TAG = "ScreenTranslatorSakuraClient";
    private static final long REQUEST_TIMEOUT_MS = 240000L;
    private static final int MAX_SOURCE_CHARACTERS = 900;
    private static final int MAX_CONTEXT_CHARACTERS = 180;
    private static final String SYSTEM_PROMPT =
            "你是一个轻小说翻译模型，可以流畅通顺地以轻小说风格将日语翻译成"
                    + "简体中文，并保留原文中的情感和语境。只输出译文。";

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final AtomicInteger requestSequence = new AtomicInteger();
    private final Messenger responseMessenger = new Messenger(
            new Handler(Looper.getMainLooper(), this::handleResponse)
    );

    private Context appContext;
    private Messenger serviceMessenger;
    private boolean registered;
    private boolean bound;
    private int activeRequestId = -1;
    private PendingRequest pendingRequest;

    @Override
    public void translate(
            Context context,
            String sourceLanguage,
            String targetLanguage,
            String sourceText,
            String contextText,
            String referenceTranslation,
            int strength,
            Callback callback
    ) {
        appContext = context.getApplicationContext();
        if (sourceText == null
                || sourceText.trim().isEmpty()
                || sourceText.length() > MAX_SOURCE_CHARACTERS) {
            callback.onError(new IllegalArgumentException(
                    "日→中专项模型输入过长，已安全跳过"
            ));
            return;
        }

        String prompt = buildPrompt(sourceText, contextText);
        int requestId = requestSequence.incrementAndGet();
        cancelCurrentInternal();
        activeRequestId = requestId;
        pendingRequest = new PendingRequest(
                requestId,
                prompt,
                sourceText,
                targetLanguage,
                callback
        );
        mainHandler.postDelayed(
                pendingRequest.timeout,
                REQUEST_TIMEOUT_MS
        );
        callback.onStatus("日→中专项模型准备中");
        ensureBound();
    }

    private String buildPrompt(
            String sourceText,
            String contextText
    ) {
        StringBuilder user = new StringBuilder();
        if (contextText != null && !contextText.trim().isEmpty()) {
            user.append("前文仅用于理解人称和语气，不要重复翻译：\n")
                    .append(limitTail(
                            contextText.trim(),
                            MAX_CONTEXT_CHARACTERS
                    ))
                    .append("\n\n");
        }
        user.append(sourceText.trim());
        return "<|im_start|>system\n"
                + SYSTEM_PROMPT
                + "\n<|im_end|>\n<|im_start|>user\n"
                + user
                + "\n<|im_end|>\n<|im_start|>assistant\n";
    }

    private void ensureBound() {
        if (bound) {
            dispatchPending();
            return;
        }
        if (registered) {
            return;
        }
        Intent intent = new Intent(appContext, HeavyTranslationService.class);
        boolean accepted = appContext.bindService(
                intent,
                connection,
                Context.BIND_AUTO_CREATE | Context.BIND_IMPORTANT
        );
        registered = accepted;
        if (!accepted) {
            failPending("无法启动日→中专项独立进程");
        }
    }

    private final ServiceConnection connection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
            registered = true;
            bound = true;
            serviceMessenger = new Messenger(binder);
            Log.i(TAG, "Heavy translation process connected");
            dispatchPending();
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            bound = false;
            serviceMessenger = null;
            failPending("日→中专项独立进程已断开");
        }

        @Override
        public void onBindingDied(ComponentName name) {
            bound = false;
            registered = false;
            serviceMessenger = null;
            unbindQuietly();
            failPending("日→中专项独立进程崩溃，已与悬浮窗隔离");
        }

        @Override
        public void onNullBinding(ComponentName name) {
            bound = false;
            registered = false;
            serviceMessenger = null;
            unbindQuietly();
            failPending("日→中专项独立进程未能启动");
        }
    };

    private void dispatchPending() {
        PendingRequest request = pendingRequest;
        if (request == null || serviceMessenger == null) {
            return;
        }
        Message message = Message.obtain(null, HeavyTranslationService.MSG_TRANSLATE);
        Bundle data = new Bundle();
        data.putInt(HeavyTranslationService.KEY_REQUEST_ID, request.requestId);
        data.putString(HeavyTranslationService.KEY_PROMPT, request.prompt);
        data.putInt(HeavyTranslationService.KEY_MAX_TOKENS, 512);
        message.setData(data);
        message.replyTo = responseMessenger;
        try {
            serviceMessenger.send(message);
        } catch (RemoteException error) {
            failPending("无法把文本交给日→中专项独立进程");
        }
    }

    private boolean handleResponse(Message message) {
        Bundle data = message.getData();
        int requestId = data.getInt(HeavyTranslationService.KEY_REQUEST_ID, -1);
        PendingRequest request = pendingRequest;
        if (request == null || requestId != request.requestId) {
            return true;
        }
        String text = data.getString(HeavyTranslationService.KEY_TEXT, "");
        if (message.what == HeavyTranslationService.MSG_STATUS) {
            request.callback.onStatus(text);
        } else if (message.what == HeavyTranslationService.MSG_RESULT) {
            String translated = cleanOutput(text);
            if (!TranslationQuality.isAcceptableOutput(
                    translated,
                    request.targetLanguage
            )
                    || !TranslationQuality.isCompleteEnough(
                    request.sourceText,
                    translated
            )) {
                failPending("Sakura 7B 输出未通过完整性检查");
            } else {
                finishRequest();
                request.callback.onSuccess(translated);
            }
        } else if (message.what == HeavyTranslationService.MSG_ERROR) {
            failPending(text == null || text.isEmpty()
                    ? "Sakura 7B 翻译失败"
                    : text);
        }
        return true;
    }

    private String cleanOutput(String text) {
        String output = text == null ? "" : text.trim();
        int endToken = output.indexOf("<|im_end|>");
        if (endToken >= 0) {
            output = output.substring(0, endToken).trim();
        }
        if (output.startsWith("译文：")) {
            output = output.substring(3).trim();
        }
        return output;
    }

    public void cancelCurrent() {
        cancelCurrentInternal();
        sendCancel();
    }

    private void sendCancel() {
        if (serviceMessenger != null) {
            try {
                serviceMessenger.send(Message.obtain(
                        null,
                        HeavyTranslationService.MSG_CANCEL
                ));
            } catch (RemoteException ignored) {
                // The isolated process may already be gone.
            }
        }
    }

    private void cancelCurrentInternal() {
        PendingRequest previous = pendingRequest;
        pendingRequest = null;
        activeRequestId = -1;
        if (previous != null) {
            mainHandler.removeCallbacks(previous.timeout);
        }
    }

    public void releaseEngine() {
        cancelCurrent();
        if (serviceMessenger != null) {
            try {
                serviceMessenger.send(Message.obtain(
                        null,
                        HeavyTranslationService.MSG_UNLOAD
                ));
            } catch (RemoteException ignored) {
                // The isolated process may already be gone.
            }
        }
        unbind();
    }

    private void unbind() {
        if (!registered) {
            return;
        }
        unbindQuietly();
        bound = false;
        registered = false;
        serviceMessenger = null;
    }

    private void unbindQuietly() {
        if (appContext == null) {
            return;
        }
        try {
            appContext.unbindService(connection);
        } catch (IllegalArgumentException ignored) {
            // Already unbound.
        }
    }

    private void failPending(String message) {
        PendingRequest failed = pendingRequest;
        finishRequest();
        if (failed != null) {
            failed.callback.onError(new IllegalStateException(message));
        }
    }

    private void finishRequest() {
        PendingRequest finished = pendingRequest;
        pendingRequest = null;
        activeRequestId = -1;
        if (finished != null) {
            mainHandler.removeCallbacks(finished.timeout);
        }
    }

    private String limitTail(String text, int maxCharacters) {
        if (text.length() <= maxCharacters) {
            return text;
        }
        int start = text.length() - maxCharacters;
        int newline = text.indexOf('\n', start);
        if (newline >= 0 && newline < text.length() - 1) {
            start = newline + 1;
        }
        return text.substring(start);
    }

    @Override
    public void close() {
        releaseEngine();
    }

    private final class PendingRequest {
        final int requestId;
        final String prompt;
        final String sourceText;
        final String targetLanguage;
        final Callback callback;
        final Runnable timeout;

        PendingRequest(
                int requestId,
                String prompt,
                String sourceText,
                String targetLanguage,
                Callback callback
        ) {
            this.requestId = requestId;
            this.prompt = prompt;
            this.sourceText = sourceText;
            this.targetLanguage = targetLanguage;
            this.callback = callback;
            this.timeout = () -> {
                if (pendingRequest == this) {
                    sendCancel();
                    failPending("日→中专项翻译超过 240 秒，已取消");
                }
            };
        }
    }
}
