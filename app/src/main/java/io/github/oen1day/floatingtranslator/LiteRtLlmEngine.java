package io.github.oen1day.floatingtranslator;

import android.content.Context;
import android.os.SystemClock;
import android.util.Log;

import com.google.ai.edge.litertlm.Backend;
import com.google.ai.edge.litertlm.Content;
import com.google.ai.edge.litertlm.Contents;
import com.google.ai.edge.litertlm.Conversation;
import com.google.ai.edge.litertlm.ConversationConfig;
import com.google.ai.edge.litertlm.Engine;
import com.google.ai.edge.litertlm.EngineConfig;
import com.google.ai.edge.litertlm.Message;

import java.io.File;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class LiteRtLlmEngine implements LocalTranslationEngine {
    public interface WarmupCallback {
        void onReady();

        void onError(Exception error);
    }

    private static final String TAG = "ScreenTranslator3BLlm";
    private static final int MODEL_MAX_TOKENS = 2048;
    private static final int MAX_SOURCE_CHARACTERS = 650;
    private static final int MAX_CONTEXT_CHARACTERS = 180;
    private static final int MAX_PROMPT_CHARACTERS = 1400;
    private static final String SYSTEM_PROMPT =
            "你是离线文学翻译引擎。只输出最终译文，不要解释、不要标题、不要Markdown。\n"
                    + "逐句翻译并覆盖每一个分句，不得把具体描写概括成泛泛的一句话。\n"
                    + "保持原文叙述人称，以及肯定、否定、推测、犹豫和可能性的语气。"
                    + "不得把犹豫改写成已经执行的动作，不得擅自改成疑问句。\n"
                    + "片假名连续字符若作为人名使用，必须按专有名词处理，保持前后一致，"
                    + "不得改译成“路人”等普通名词；无法确定译名时保留原文。\n"
                    + "「AともBとも言い難い」表示“难以分辨是A还是B”，不能翻成“既不是A也不是B”。\n"
                    + "「どうしていいか分からない」表示“不知道该怎么办”，不能翻成“怎么知道该怎么办”。\n"
                    + "只输出简体中文，不得输出英文、日文或双语对照。";

    private final ExecutorService executor = Executors.newSingleThreadExecutor(
            runnable -> new Thread(runnable, "litert-3b")
    );
    private Engine engine;
    private boolean useCpuBackend;
    private volatile Conversation activeConversation;
    private volatile int cancellationGeneration;

    public void warmUp(Context context, WarmupCallback callback) {
        executor.execute(() -> {
            try {
                File model = LargeLocalModelManager.modelFile(context.getApplicationContext());
                if (!LargeLocalModelManager.isReady(context.getApplicationContext())) {
                    throw new IllegalStateException("3B 模型未下载或尚未校验");
                }
                ensureEngine(context.getApplicationContext(), model);
                callback.onReady();
            } catch (Throwable error) {
                callback.onError(error instanceof Exception
                        ? (Exception) error
                        : new IllegalStateException(error));
            }
        });
    }

    public void cancelCurrent() {
        cancellationGeneration++;
        Conversation conversation = activeConversation;
        if (conversation != null) {
            try {
                conversation.cancelProcess();
            } catch (RuntimeException error) {
                Log.w(TAG, "Unable to cancel 3B conversation", error);
            }
        }
    }

    public void releaseEngine() {
        releaseEngine(null);
    }

    public void releaseEngine(Runnable completion) {
        executor.execute(() -> {
            try {
                cancelCurrent();
                if (engine != null) {
                    engine.close();
                    engine = null;
                    Log.i(TAG, "3B engine released");
                }
            } finally {
                if (completion != null) {
                    completion.run();
                }
            }
        });
    }

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
        executor.execute(() -> {
            long startedAt = SystemClock.elapsedRealtime();
            int requestGeneration = cancellationGeneration;
            try {
                if (sourceText == null
                        || sourceText.trim().isEmpty()
                        || sourceText.length() > MAX_SOURCE_CHARACTERS) {
                    throw new IllegalArgumentException(
                            "文本超出 3B 模型安全长度，已回退"
                    );
                }
                File model = LargeLocalModelManager.modelFile(context.getApplicationContext());
                if (!LargeLocalModelManager.isReady(context.getApplicationContext())) {
                    throw new IllegalStateException("3B 模型未下载或尚未校验");
                }
                callback.onStatus("3B 精细翻译准备中");
                ensureEngine(context.getApplicationContext(), model);
                if (requestGeneration != cancellationGeneration) {
                    throw new CancellationException("3B request superseded");
                }
                callback.onStatus("3B 精细翻译中");

                TranslationQuality.PreparedText prepared =
                        TranslationQuality.prepareProtectedNames(sourceText);
                String prompt = buildUserPrompt(
                        sourceLanguage,
                        targetLanguage,
                        prepared,
                        contextText
                );
                String result = prepared.restore(generate(prompt, false));
                if (!isAcceptable(result, targetLanguage, sourceText)) {
                    Log.w(
                            TAG,
                            "Rejected 3B output for target=" + targetLanguage
                                    + " output=" + result
                    );
                    result = prepared.restore(generate(prompt, true));
                }
                if (result.isEmpty()) {
                    throw new IllegalStateException("3B 模型返回空译文");
                }
                if (!isAcceptable(result, targetLanguage, sourceText)) {
                    throw new IllegalStateException("3B 模型未遵守目标语言要求");
                }
                Log.i(
                        TAG,
                        "3B translation elapsedMs="
                                + (SystemClock.elapsedRealtime() - startedAt)
                                + " backend=" + (useCpuBackend ? "CPU" : "GPU")
                                + " output=" + result.replace('\n', '|')
                );
                callback.onSuccess(result);
            } catch (Throwable error) {
                Log.w(TAG, "3B translation failed", error);
                callback.onError(error instanceof Exception
                        ? (Exception) error
                        : new IllegalStateException(error));
            }
        });
    }

    private synchronized void ensureEngine(Context context, File model) {
        if (engine != null) {
            return;
        }
        long startedAt = SystemClock.elapsedRealtime();
        if (!useCpuBackend) {
            try {
                Engine candidate = new Engine(
                        new EngineConfig(
                                model.getAbsolutePath(),
                                new Backend.GPU(),
                                null,
                                null,
                                MODEL_MAX_TOKENS,
                                null
                        )
                );
                candidate.initialize();
                engine = candidate;
                Log.i(
                        TAG,
                        "3B GPU ready loadMs=" + (SystemClock.elapsedRealtime() - startedAt)
                );
                return;
            } catch (Throwable error) {
                useCpuBackend = true;
                Log.w(TAG, "3B GPU unavailable; falling back to CPU", error);
            }
        }
        Engine candidate = new Engine(
                new EngineConfig(
                        model.getAbsolutePath(),
                        new Backend.CPU(),
                        null,
                        null,
                        MODEL_MAX_TOKENS,
                        null
                )
        );
        candidate.initialize();
        engine = candidate;
        Log.i(
                TAG,
                "3B CPU ready loadMs=" + (SystemClock.elapsedRealtime() - startedAt)
        );
    }

    private String generate(String userPrompt, boolean languageRetry) {
        String prompt = languageRetry
                ? "上一次输出没有遵守语言或专有名词要求。本次必须只输出简体中文，"
                + "并原样保留提示中列出的专有名词。\n\n" + userPrompt
                : userPrompt;
        if (prompt.length() > MAX_PROMPT_CHARACTERS) {
            Log.w(
                    TAG,
                    "Skipped unsafe 3B prompt chars=" + prompt.length()
                            + " maxChars=" + MAX_PROMPT_CHARACTERS
            );
            throw new IllegalArgumentException("3B 输入过长，已安全跳过");
        }
        try (Conversation conversation = engine.createConversation(
                new ConversationConfig(
                        Contents.Companion.of(SYSTEM_PROMPT),
                        Collections.emptyList()
                )
        )) {
            activeConversation = conversation;
            Message message = conversation.sendMessage(prompt);
            return cleanOutput(extractText(message));
        } finally {
            activeConversation = null;
        }
    }

    private String buildUserPrompt(
            String sourceLanguage,
            String targetLanguage,
            TranslationQuality.PreparedText prepared,
            String contextText
    ) {
        StringBuilder prompt = new StringBuilder();
        if (contextText != null && !contextText.trim().isEmpty()) {
            prompt.append("前文只用于理解人称和语气，不要重复翻译：\n")
                    .append(limitTail(
                            contextText.trim(),
                            MAX_CONTEXT_CHARACTERS
                    ))
                    .append("\n\n");
        }
        prompt.append(prepared.promptHint());
        prompt.append("把以下").append(sourceLanguage).append("原文翻译成")
                .append(targetLanguage).append("。\n")
                .append("必须保留全部句子和分句，不得删减或补充。\n\n原文：\n")
                .append(prepared.text.trim())
                .append("\n\n只输出")
                .append(targetLanguage)
                .append("译文：");
        return prompt.toString();
    }

    private boolean isAcceptable(
            String result,
            String targetLanguage,
            String sourceText
    ) {
        return TranslationQuality.isAcceptableOutput(result, targetLanguage)
                && TranslationQuality.preservesProtectedNames(sourceText, result)
                && TranslationQuality.isCompleteEnough(sourceText, result);
    }

    private String extractText(Message message) {
        if (message == null || message.getContents() == null) {
            return "";
        }
        StringBuilder result = new StringBuilder();
        List<Content> parts = message.getContents().getContents();
        for (Content part : parts) {
            if (part instanceof Content.Text) {
                if (result.length() > 0) {
                    result.append('\n');
                }
                result.append(((Content.Text) part).getText());
            }
        }
        return result.toString();
    }

    private String cleanOutput(String result) {
        String output = result == null ? "" : result.trim();
        if (output.startsWith("```")) {
            int firstLine = output.indexOf('\n');
            int lastFence = output.lastIndexOf("```");
            if (firstLine >= 0 && lastFence > firstLine) {
                output = output.substring(firstLine + 1, lastFence).trim();
            }
        }
        if (output.startsWith("译文：")) {
            output = output.substring(3).trim();
        }
        return output;
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
        executor.shutdown();
    }
}
