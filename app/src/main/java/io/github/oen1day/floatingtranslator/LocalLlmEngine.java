package io.github.oen1day.floatingtranslator;

import android.content.Context;
import android.util.Log;

import com.google.mediapipe.tasks.genai.llminference.LlmInference;
import com.google.mediapipe.tasks.genai.llminference.LlmInferenceSession;

import java.io.File;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class LocalLlmEngine implements LocalTranslationEngine {
    private static final String TAG = "ScreenTranslatorLocalLlm";
    private static final int MODEL_MAX_TOKENS = 2048;
    private static final int MAX_SOURCE_CHARACTERS = 520;
    private static final int MAX_CONTEXT_CHARACTERS = 160;
    private static final int MAX_REFERENCE_CHARACTERS = 180;
    private final ExecutorService executor = Executors.newSingleThreadExecutor(
            runnable -> new Thread(runnable, "local-llm")
    );
    private LlmInference inference;

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
            try {
                if (sourceText == null
                        || sourceText.trim().isEmpty()
                        || sourceText.length() > MAX_SOURCE_CHARACTERS) {
                    throw new IllegalArgumentException(
                            "文本超出本地模型安全长度，已跳过以免原生崩溃"
                    );
                }
                File model = LocalModelManager.downloadBlocking(
                        context.getApplicationContext(),
                        (percent, downloaded, total) ->
                                callback.onStatus("下载本地模型 " + percent + "%")
                );
                ensureInference(context, model);
                callback.onStatus("本地精修中");

                LlmInferenceSession.LlmInferenceSessionOptions options =
                        LlmInferenceSession.LlmInferenceSessionOptions.builder()
                                .setTopK(20)
                                .setTopP(0.90f)
                                .setTemperature(strength >= TranslationStrength.PRECISE
                                        ? 0.20f
                                        : 0.10f)
                                .setRandomSeed(17)
                                .build();
                TranslationQuality.PreparedText prepared =
                        TranslationQuality.prepareProtectedNames(sourceText);
                String prompt = buildPrompt(
                        sourceLanguage,
                        targetLanguage,
                        prepared,
                        contextText,
                        referenceTranslation,
                        strength
                );
                String result = prepared.restore(generate(
                        inference,
                        prompt,
                        prepared.text,
                        options
                ));
                if (!isAcceptable(result, targetLanguage, sourceText)) {
                    Log.w(
                            TAG,
                            "Rejected local output for target=" + targetLanguage
                                    + " output=" + result
                    );
                    String retryPrompt = buildPrompt(
                            sourceLanguage,
                            targetLanguage,
                            prepared,
                            contextText,
                            referenceTranslation,
                            strength,
                            true
                    );
                    result = prepared.restore(generate(
                            inference,
                            retryPrompt,
                            prepared.text,
                            options
                    ));
                }
                if (result.isEmpty()) {
                    callback.onError(new IllegalStateException("本地模型返回空译文"));
                } else if (!isAcceptable(result, targetLanguage, sourceText)) {
                    callback.onError(new IllegalStateException(
                            "本地模型未遵守目标语言要求"
                    ));
                } else {
                    callback.onSuccess(result);
                }
            } catch (Throwable error) {
                Log.w(TAG, "Local translation failed", error);
                callback.onError(error instanceof Exception
                        ? (Exception) error
                        : new IllegalStateException(error));
            }
        });
    }

    private void ensureInference(Context context, File model) {
        if (inference != null) {
            return;
        }
        LlmInference.LlmInferenceOptions options =
                LlmInference.LlmInferenceOptions.builder()
                        .setModelPath(model.getAbsolutePath())
                        .setMaxTokens(MODEL_MAX_TOKENS)
                        .setMaxTopK(40)
                        .setPreferredBackend(LlmInference.Backend.CPU)
                        .build();
        inference = LlmInference.createFromOptions(context, options);
    }

    public void releaseEngine(Runnable completion) {
        executor.execute(() -> {
            try {
                if (inference != null) {
                    inference.close();
                    inference = null;
                    Log.i(TAG, "1.5B engine released");
                }
            } finally {
                if (completion != null) {
                    completion.run();
                }
            }
        });
    }

    private String buildPrompt(
            String sourceLanguage,
            String targetLanguage,
            TranslationQuality.PreparedText prepared,
            String contextText,
            String referenceTranslation,
            int strength
    ) {
        return buildPrompt(
                sourceLanguage,
                targetLanguage,
                prepared,
                contextText,
                referenceTranslation,
                strength,
                false
        );
    }

    private String buildPrompt(
            String sourceLanguage,
            String targetLanguage,
            TranslationQuality.PreparedText prepared,
            String contextText,
            String referenceTranslation,
            int strength,
            boolean languageRetry
    ) {
        StringBuilder system = new StringBuilder();
        if (languageRetry) {
            system.append("上次未遵守语言要求。本次必须只输出")
                    .append(targetLanguage)
                    .append("。\n");
        }
        system.append("你是离线文学翻译引擎。只输出最终译文，不得解释、审查、删减、改写或概括。\n")
                .append("逐句覆盖原文，保留事实、动作、因果、人物、情绪、语气、人称、否定、推测和省略。")
                .append("犹豫不得改写成已执行动作，也不得擅自改成疑问句。\n")
                .append("人名等专有名词保持一致，无法确定译名时保留原文。\n")
                .append("「AともBとも言い難い」表示“难以分辨是A还是B”，不能翻成“既不是A也不是B”。\n")
                .append("「どうしていいか分からない」表示“不知道该怎么办”，不能翻成“怎么知道该怎么办”。\n")
                .append("把").append(sourceLanguage).append("翻译成自然、准确的")
                .append(targetLanguage).append("。\n");
        if ("中文".equals(targetLanguage)) {
            system.append("输出语言硬性要求：最终译文必须全部使用简体中文。")
                    .append("不得输出英文、日文、拼音或双语对照。")
                    .append("人名没有可靠译名时保留原文，不要把日文人名意译成英文。\n");
        } else {
            system.append("输出语言硬性要求：最终译文必须只使用")
                    .append(targetLanguage)
                    .append("，不得输出源语言或双语对照。\n");
        }

        StringBuilder user = new StringBuilder();
        user.append(prepared.promptHint());
        if (strength >= TranslationStrength.BALANCED
                && contextText != null
                && !contextText.trim().isEmpty()) {
            user.append("前文只用于理解代词和语气，不要重复翻译前文：\n")
                    .append(limitTail(
                            contextText.trim(),
                            strength >= TranslationStrength.PRECISE
                                    ? MAX_CONTEXT_CHARACTERS
                                    : 120
                    ))
                    .append("\n");
        }
        user.append("当前原文：\n")
                .append(prepared.text.trim());
        if (referenceTranslation != null && !referenceTranslation.trim().isEmpty()) {
            user.append("\n快速翻译参考（可能有错，必须以原文为准）：\n")
                    .append(limitTail(
                            referenceTranslation.trim(),
                            MAX_REFERENCE_CHARACTERS
                    ))
                    .append("\n");
        }
        user.append("\n完整覆盖每个分句，不得省略或补充。")
                .append("\n只输出这段原文的")
                .append(targetLanguage)
                .append("译文：");
        return "<|im_start|>system\n"
                + system
                + "<|im_end|>\n<|im_start|>user\n"
                + user
                + "<|im_end|>\n<|im_start|>assistant\n";
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

    private String generate(
            LlmInference model,
            String prompt,
            String sourceText,
            LlmInferenceSession.LlmInferenceSessionOptions options
    ) {
        int promptTokens = model.sizeInTokens(prompt);
        int sourceTokens = model.sizeInTokens(sourceText);
        int outputReserve = Math.max(
                256,
                Math.min(720, sourceTokens * 2 + 128)
        );
        Log.i(
                TAG,
                "Local prompt token check inputTokens=" + promptTokens
                        + " reserve=" + outputReserve
                        + " maxTokens=" + MODEL_MAX_TOKENS
        );
        if (promptTokens <= 0
                || promptTokens + outputReserve >= MODEL_MAX_TOKENS) {
            Log.w(
                    TAG,
                    "Skipped unsafe local prompt promptTokens=" + promptTokens
                            + " reserve=" + outputReserve
                            + " maxTokens=" + MODEL_MAX_TOKENS
            );
            throw new IllegalArgumentException(
                    "本地模型输入过长，已安全跳过"
            );
        }
        try (LlmInferenceSession session =
                     LlmInferenceSession.createFromOptions(model, options)) {
            session.addQueryChunk(prompt);
            return cleanOutput(session.generateResponse());
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

    private String cleanOutput(String result) {
        if (result == null) {
            return "";
        }
        String output = result.trim();
        if (output.startsWith("译文：")) {
            output = output.substring(3).trim();
        }
        int endToken = output.indexOf("<|im_end|>");
        if (endToken >= 0) {
            output = output.substring(0, endToken).trim();
        }
        return output;
    }

    public void close() {
        releaseEngine(null);
        executor.shutdown();
    }
}
