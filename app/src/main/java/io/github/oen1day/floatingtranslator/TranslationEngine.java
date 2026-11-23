package io.github.oen1day.floatingtranslator;

import com.google.mlkit.nl.translate.TranslateLanguage;
import com.google.mlkit.nl.translate.Translation;
import com.google.mlkit.nl.translate.Translator;
import com.google.mlkit.nl.translate.TranslatorOptions;

public class TranslationEngine {
    public static final String MODE_AUTO = LanguageCatalog.AUTO;
    public static final String LANGUAGE_ENGLISH = TranslateLanguage.ENGLISH;
    public static final String LANGUAGE_JAPANESE = TranslateLanguage.JAPANESE;
    public static final String LANGUAGE_CHINESE = TranslateLanguage.CHINESE;

    public interface Callback {
        void onPreparing(String message);

        void onSuccess(
                String translatedText,
                String sourceLanguage,
                String targetLanguage,
                boolean unchanged
        );

        void onError(Exception error);
    }

    private Translator translator;
    private String activeSourceLanguage;
    private String activeTargetLanguage;

    public void translate(
            String text,
            String sourceMode,
            String targetLanguage,
            Callback callback
    ) {
        String sourceLanguage = MODE_AUTO.equals(sourceMode)
                ? detectSourceLanguage(text)
                : normalizeSource(sourceMode);
        String target = normalizeTarget(targetLanguage);
        callback.onPreparing(
                "准备 " + sourceLabel(sourceLanguage) + " → " + targetLabel(target) + " 模型"
        );

        if (sourceLanguage.equals(target)) {
            callback.onSuccess(text, sourceLanguage, target, true);
            return;
        }

        ensureTranslator(sourceLanguage, target);
        translator.downloadModelIfNeeded()
                .addOnSuccessListener(unused -> {
                    StringBuilder output = new StringBuilder();
                    translateChunk(
                            text,
                            0,
                            output,
                            sourceLanguage,
                            target,
                            callback
                    );
                })
                .addOnFailureListener(callback::onError);
    }

    private void translateChunk(
            String source,
            int offset,
            StringBuilder output,
            String sourceLanguage,
            String targetLanguage,
            Callback callback
    ) {
        if (offset >= source.length()) {
            callback.onSuccess(
                    output.toString().trim(),
                    sourceLanguage,
                    targetLanguage,
                    false
            );
            return;
        }

        int end = Math.min(source.length(), offset + 850);
        if (end < source.length()) {
            int newline = source.lastIndexOf('\n', end);
            if (newline > offset + 150) {
                end = newline;
            }
        }
        final int chunkEnd = end;
        String chunk = source.substring(offset, end);
        translator.translate(chunk)
                .addOnSuccessListener(result -> {
                    if (offset > 0 && output.length() > 0) {
                        output.append('\n');
                    }
                    output.append(result);
                    translateChunk(
                            source,
                            chunkEnd,
                            output,
                            sourceLanguage,
                            targetLanguage,
                            callback
                    );
                })
                .addOnFailureListener(callback::onError);
    }

    private void ensureTranslator(String sourceLanguage, String targetLanguage) {
        if (translator != null
                && sourceLanguage.equals(activeSourceLanguage)
                && targetLanguage.equals(activeTargetLanguage)) {
            return;
        }
        closeTranslator();
        TranslatorOptions options = new TranslatorOptions.Builder()
                .setSourceLanguage(sourceLanguage)
                .setTargetLanguage(targetLanguage)
                .build();
        translator = Translation.getClient(options);
        activeSourceLanguage = sourceLanguage;
        activeTargetLanguage = targetLanguage;
    }

    private String detectSourceLanguage(String text) {
        boolean hasKana = false;
        boolean hasHangul = false;
        boolean hasHan = false;

        for (int i = 0; i < text.length(); i++) {
            Character.UnicodeBlock block = Character.UnicodeBlock.of(text.charAt(i));
            if (block == Character.UnicodeBlock.HIRAGANA
                    || block == Character.UnicodeBlock.KATAKANA
                    || block == Character.UnicodeBlock.KATAKANA_PHONETIC_EXTENSIONS) {
                hasKana = true;
            }
            if (block == Character.UnicodeBlock.HANGUL_SYLLABLES
                    || block == Character.UnicodeBlock.HANGUL_JAMO
                    || block == Character.UnicodeBlock.HANGUL_COMPATIBILITY_JAMO) {
                hasHangul = true;
            }
            if (block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS
                    || block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_A
                    || block == Character.UnicodeBlock.CJK_COMPATIBILITY_IDEOGRAPHS) {
                hasHan = true;
            }
        }

        if (hasKana) {
            return LANGUAGE_JAPANESE;
        }
        if (hasHangul) {
            return TranslateLanguage.KOREAN;
        }
        if (hasHan) {
            return LANGUAGE_CHINESE;
        }
        return LANGUAGE_ENGLISH;
    }

    private String normalizeSource(String sourceLanguage) {
        String normalized = LanguageCatalog.normalizeSource(sourceLanguage);
        return MODE_AUTO.equals(normalized) ? LANGUAGE_ENGLISH : normalized;
    }

    private String normalizeTarget(String targetLanguage) {
        return LanguageCatalog.normalizeTarget(targetLanguage);
    }

    private String sourceLabel(String language) {
        return LanguageCatalog.sourceLabel(language);
    }

    private String targetLabel(String language) {
        return LanguageCatalog.targetLabel(language);
    }

    private void closeTranslator() {
        if (translator != null) {
            translator.close();
            translator = null;
        }
        activeSourceLanguage = null;
        activeTargetLanguage = null;
    }

    public void close() {
        closeTranslator();
    }
}
