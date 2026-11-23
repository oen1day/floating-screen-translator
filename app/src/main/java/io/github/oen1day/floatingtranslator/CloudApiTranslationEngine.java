package io.github.oen1day.floatingtranslator;

import android.content.Context;
import android.os.SystemClock;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class CloudApiTranslationEngine implements LocalTranslationEngine {
    private static final String TAG = "ScreenTranslatorCloudApi";
    private static final String SYSTEM_PROMPT =
            "你是文学翻译引擎。只输出最终译文，不要解释、标题或Markdown。\n"
                    + "逐句翻译并覆盖每一个分句，保持叙述人称、肯定、否定、推测、犹豫和可能性。"
                    + "不得把犹豫改写成已经执行的动作，不得擅自改成疑问句。\n"
                    + "专有名词占位符和原文中的片假名人名必须保持一致。"
                    + "只输出简体中文，不得输出英文、日文或双语对照。";

    private final ExecutorService executor = Executors.newSingleThreadExecutor(
            runnable -> new Thread(runnable, "cloud-api-translation")
    );
    private volatile HttpURLConnection activeConnection;
    private volatile int cancellationGeneration;

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
                if (!CloudApiSettings.isConfigured(context.getApplicationContext())) {
                    throw new IllegalStateException("API 未配置或未同意云端隐私说明");
                }
                String apiKey = CloudApiSettings.readApiKey(context.getApplicationContext());
                if (apiKey.isEmpty()) {
                    throw new IllegalStateException("API Key 无法读取，请重新保存");
                }
                callback.onStatus("API 翻译中");
                TranslationQuality.PreparedText prepared =
                        TranslationQuality.prepareProtectedNames(sourceText);
                String prompt = buildUserPrompt(
                        sourceLanguage,
                        targetLanguage,
                        prepared,
                        contextText
                );
                String result = prepared.restore(request(
                        CloudApiSettings.endpoint(context),
                        CloudApiSettings.model(context),
                        apiKey,
                        prompt,
                        requestGeneration
                ));
                if (!isAcceptable(result, targetLanguage, sourceText)) {
                    if (requestGeneration != cancellationGeneration) {
                        throw new CancellationException("API request superseded");
                    }
                    Log.w(TAG, "Rejected API output; retrying with stricter instructions");
                    String retryPrompt =
                            "上一次输出未满足语言、完整性或专有名词要求。"
                                    + "重新翻译，必须保留全部句子和列出的占位符，"
                                    + "只输出简体中文。\n\n" + prompt;
                    result = prepared.restore(request(
                            CloudApiSettings.endpoint(context),
                            CloudApiSettings.model(context),
                            apiKey,
                            retryPrompt,
                            requestGeneration
                    ));
                }
                if (!isAcceptable(result, targetLanguage, sourceText)) {
                    throw new IllegalStateException(
                            "API 输出未通过语言、完整性或人名检查"
                    );
                }
                Log.i(
                        TAG,
                        "API translation elapsedMs="
                                + (SystemClock.elapsedRealtime() - startedAt)
                                + " output=" + result.replace('\n', '|')
                );
                callback.onSuccess(result);
            } catch (Throwable error) {
                Log.w(TAG, "API translation failed", error);
                callback.onError(error instanceof Exception
                        ? (Exception) error
                        : new IllegalStateException(error));
            }
        });
    }

    public void cancelCurrent() {
        cancellationGeneration++;
        HttpURLConnection connection = activeConnection;
        if (connection != null) {
            try {
                connection.disconnect();
            } catch (RuntimeException ignored) {
                // The request may already be closed.
            }
        }
    }

    private String request(
            String endpoint,
            String model,
            String apiKey,
            String userPrompt,
            int requestGeneration
    ) throws Exception {
        if (requestGeneration != cancellationGeneration) {
            throw new CancellationException("API request superseded");
        }
        HttpURLConnection connection = (HttpURLConnection) new URL(endpoint).openConnection();
        activeConnection = connection;
        try {
            connection.setRequestMethod("POST");
            connection.setConnectTimeout(20_000);
            connection.setReadTimeout(75_000);
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            connection.setRequestProperty("Accept", "application/json");
            connection.setRequestProperty("Authorization", "Bearer " + apiKey);

            JSONArray messages = new JSONArray();
            messages.put(new JSONObject()
                    .put("role", "system")
                    .put("content", SYSTEM_PROMPT));
            messages.put(new JSONObject()
                    .put("role", "user")
                    .put("content", userPrompt));
            JSONObject payload = new JSONObject()
                    .put("model", model)
                    .put("messages", messages)
                    .put("temperature", 0.2)
                    .put("stream", false)
                    .put("max_tokens", 800);

            byte[] body = payload.toString().getBytes(StandardCharsets.UTF_8);
            try (OutputStream output = connection.getOutputStream()) {
                output.write(body);
            }
            int status = connection.getResponseCode();
            InputStream stream = status >= 200 && status < 300
                    ? connection.getInputStream()
                    : connection.getErrorStream();
            String response = readAll(stream);
            if (status < 200 || status >= 300) {
                String safeResponse = response == null
                        ? ""
                        : response.substring(0, Math.min(240, response.length()));
                throw new IllegalStateException(
                        "API HTTP " + status + "：" + safeResponse
                );
            }
            return parseOpenAiCompatibleResponse(response);
        } finally {
            if (activeConnection == connection) {
                activeConnection = null;
            }
            connection.disconnect();
        }
    }

    private String parseOpenAiCompatibleResponse(String response) throws Exception {
        JSONObject root = new JSONObject(response);
        JSONArray choices = root.optJSONArray("choices");
        if (choices == null || choices.length() == 0) {
            throw new IllegalStateException("API 响应中没有 choices");
        }
        JSONObject choice = choices.getJSONObject(0);
        JSONObject message = choice.optJSONObject("message");
        String content = message == null ? "" : message.optString("content", "");
        if (content.trim().isEmpty()) {
            content = choice.optString("text", "");
        }
        return cleanOutput(content);
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
                    .append(limitTail(contextText.trim(), 420))
                    .append("\n\n");
        }
        prompt.append(prepared.promptHint())
                .append("把以下").append(sourceLanguage).append("原文翻译成")
                .append(targetLanguage).append("。\n")
                .append("必须保留全部句子和分句，不得删减或补充。\n\n原文：\n")
                .append(limitTail(prepared.text.trim(), 900))
                .append("\n\n只输出简体中文译文：");
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

    private String readAll(InputStream input) throws Exception {
        if (input == null) {
            return "";
        }
        StringBuilder value = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(input, StandardCharsets.UTF_8)
        )) {
            String line;
            while ((line = reader.readLine()) != null) {
                value.append(line).append('\n');
            }
        }
        return value.toString().trim();
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
        cancelCurrent();
        executor.shutdownNow();
    }
}
