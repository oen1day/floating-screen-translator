package io.github.oen1day.floatingtranslator;

import android.graphics.Bitmap;
import android.graphics.Rect;
import android.util.Log;

import com.google.mlkit.nl.translate.TranslateLanguage;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.Text;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions;
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions;
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions;

public class TextEngine {
    private static final String TAG = "ScreenTranslatorOCR";

    public interface Callback {
        void onResult(String text);

        void onError(Exception error);
    }

    private final TextRecognizer chineseRecognizer =
            TextRecognition.getClient(new ChineseTextRecognizerOptions.Builder().build());
    private final TextRecognizer japaneseRecognizer =
            TextRecognition.getClient(new JapaneseTextRecognizerOptions.Builder().build());
    private final TextRecognizer koreanRecognizer =
            TextRecognition.getClient(new KoreanTextRecognizerOptions.Builder().build());

    public void recognize(
            Bitmap bitmap,
            Rect excludedPanelRect,
            String sourceMode,
            int recognitionStrength,
            Callback callback
    ) {
        InputImage image = InputImage.fromBitmap(bitmap, 0);
        String source = LanguageCatalog.normalizeSource(sourceMode);
        if (TranslateLanguage.JAPANESE.equals(source)) {
            recognizeJapaneseOnly(
                    image,
                    bitmap,
                    excludedPanelRect,
                    recognitionStrength,
                    callback
            );
            return;
        }
        if (TranslateLanguage.KOREAN.equals(source)) {
            recognizeKoreanOnly(image, bitmap, excludedPanelRect, callback);
            return;
        }
        if (!LanguageCatalog.AUTO.equals(source)) {
            recognizeChineseOrLatinOnly(image, bitmap, excludedPanelRect, callback);
            return;
        }

        chineseRecognizer.process(image)
                .addOnSuccessListener(chineseText -> {
                    String chineseResult = collectText(chineseText, excludedPanelRect);
                    if (containsKana(chineseResult)) {
                        recognizeJapaneseAndMaybeKorean(
                                image,
                                bitmap,
                                excludedPanelRect,
                                chineseResult,
                                callback
                        );
                    } else if (chineseResult.length() >= 4) {
                        finish(bitmap, chineseResult, callback);
                    } else {
                        recognizeJapaneseAndMaybeKorean(
                                image,
                                bitmap,
                                excludedPanelRect,
                                chineseResult,
                                callback
                        );
                    }
                })
                .addOnFailureListener(error ->
                        recognizeJapaneseAndMaybeKorean(
                                image,
                                bitmap,
                                excludedPanelRect,
                                "",
                                callback
                        )
                );
    }

    private void recognizeChineseOrLatinOnly(
            InputImage image,
            Bitmap bitmap,
            Rect excludedPanelRect,
            Callback callback
    ) {
        chineseRecognizer.process(image)
                .addOnSuccessListener(text ->
                        finish(bitmap, collectText(text, excludedPanelRect), callback)
                )
                .addOnFailureListener(error -> fail(bitmap, error, callback));
    }

    private void recognizeJapaneseOnly(
            InputImage image,
            Bitmap bitmap,
            Rect excludedPanelRect,
            int recognitionStrength,
            Callback callback
    ) {
        japaneseRecognizer.process(image)
                .addOnSuccessListener(text ->
                        handleJapaneseFullResult(
                                bitmap,
                                excludedPanelRect,
                                repairJapaneseOcr(collectText(text, excludedPanelRect)),
                                recognitionStrength,
                                callback
                        )
                )
                .addOnFailureListener(error -> fail(bitmap, error, callback));
    }

    private void handleJapaneseFullResult(
            Bitmap bitmap,
            Rect excludedPanelRect,
            String normalText,
            int recognitionStrength,
            Callback callback
    ) {
        if (recognitionStrength <= TranslationStrength.FAST) {
            finish(bitmap, normalText, callback);
            return;
        }
        recognizeJapaneseUpscaled(
                bitmap,
                excludedPanelRect,
                normalText,
                recognitionStrength,
                callback
        );
    }

    private void recognizeJapaneseUpscaled(
            Bitmap bitmap,
            Rect excludedPanelRect,
            String normalText,
            int recognitionStrength,
            Callback callback
    ) {
        int maxDimension = Math.max(bitmap.getWidth(), bitmap.getHeight());
        if (maxDimension > 1400 || maxDimension * 2 > 2600) {
            recognizeJapaneseBottomBand(
                    bitmap,
                    excludedPanelRect,
                    normalText,
                    recognitionStrength,
                    callback
            );
            return;
        }
        Bitmap upscaled = Bitmap.createScaledBitmap(
                bitmap,
                bitmap.getWidth() * 2,
                bitmap.getHeight() * 2,
                true
        );
        Rect upscaledExcluded = null;
        if (excludedPanelRect != null) {
            upscaledExcluded = new Rect(
                    excludedPanelRect.left * 2,
                    excludedPanelRect.top * 2,
                    excludedPanelRect.right * 2,
                    excludedPanelRect.bottom * 2
            );
        }
        final Rect upscaledExcludedForCapture = upscaledExcluded;
        japaneseRecognizer.process(InputImage.fromBitmap(upscaled, 0))
                .addOnSuccessListener(text -> {
                    String upscaledText = repairJapaneseOcr(
                            collectText(text, upscaledExcludedForCapture)
                    );
                    upscaled.recycle();
                    recognizeJapaneseContrast(
                            bitmap,
                            excludedPanelRect,
                            chooseJapaneseCandidate(normalText, upscaledText),
                            recognitionStrength,
                            callback
                    );
                })
                .addOnFailureListener(error -> {
                    upscaled.recycle();
                    recognizeJapaneseContrast(
                            bitmap,
                            excludedPanelRect,
                            normalText,
                            recognitionStrength,
                            callback
                    );
                });
    }

    private void recognizeJapaneseContrast(
            Bitmap bitmap,
            Rect excludedPanelRect,
            String currentText,
            int recognitionStrength,
            Callback callback
    ) {
        if (recognitionStrength < TranslationStrength.PRECISE) {
            recognizeJapaneseBottomBand(
                    bitmap,
                    excludedPanelRect,
                    currentText,
                    recognitionStrength,
                    callback
            );
            return;
        }
        Bitmap contrast = createContrastBitmap(bitmap);
        japaneseRecognizer.process(InputImage.fromBitmap(contrast, 0))
                .addOnSuccessListener(text -> {
                    String contrastText = repairJapaneseOcr(
                            collectText(text, excludedPanelRect)
                    );
                    contrast.recycle();
                    recognizeJapaneseBottomBand(
                            bitmap,
                            excludedPanelRect,
                            chooseJapaneseCandidate(currentText, contrastText),
                            recognitionStrength,
                            callback
                    );
                })
                .addOnFailureListener(error -> {
                    contrast.recycle();
                    recognizeJapaneseBottomBand(
                            bitmap,
                            excludedPanelRect,
                            currentText,
                            recognitionStrength,
                            callback
                    );
                });
    }

    private Bitmap createContrastBitmap(Bitmap source) {
        Bitmap output = Bitmap.createBitmap(
                source.getWidth(),
                source.getHeight(),
                Bitmap.Config.ARGB_8888
        );
        android.graphics.Canvas canvas = new android.graphics.Canvas(output);
        android.graphics.Paint paint = new android.graphics.Paint(
                android.graphics.Paint.ANTI_ALIAS_FLAG
        );
        android.graphics.ColorMatrix matrix = new android.graphics.ColorMatrix();
        matrix.setSaturation(0f);
        float contrast = 1.65f;
        float translate = 128f * (1f - contrast);
        matrix.postConcat(new android.graphics.ColorMatrix(new float[]{
                contrast, 0f, 0f, 0f, translate,
                0f, contrast, 0f, 0f, translate,
                0f, 0f, contrast, 0f, translate,
                0f, 0f, 0f, 1f, 0f
        }));
        paint.setColorFilter(new android.graphics.ColorMatrixColorFilter(matrix));
        canvas.drawBitmap(source, 0f, 0f, paint);
        return output;
    }

    private void recognizeJapaneseBottomBand(
            Bitmap bitmap,
            Rect excludedPanelRect,
            String fullText,
            int recognitionStrength,
            Callback callback
    ) {
        boolean needsLowerBand = recognitionStrength >= TranslationStrength.PRECISE
                || countNonEmptyLines(fullText) < 2;
        if (!needsLowerBand || bitmap.getHeight() < 40) {
            finish(bitmap, fullText, callback);
            return;
        }
        int top = Math.max(0, Math.round(bitmap.getHeight() * 0.35f));
        Bitmap lower = Bitmap.createBitmap(
                bitmap,
                0,
                top,
                bitmap.getWidth(),
                bitmap.getHeight() - top
        );
        Bitmap scanBitmap = lower;
        float scanScale = 1f;
        if (recognitionStrength >= TranslationStrength.PRECISE) {
            int maxDimension = Math.max(lower.getWidth(), lower.getHeight());
            if (maxDimension * 2 <= 2600) {
                Bitmap upscaled = Bitmap.createScaledBitmap(
                        lower,
                        lower.getWidth() * 2,
                        lower.getHeight() * 2,
                        true
                );
                scanBitmap = createContrastBitmap(upscaled);
                if (upscaled != lower) {
                    upscaled.recycle();
                }
                scanScale = 2f;
            }
        }
        Rect lowerExcluded = null;
        if (excludedPanelRect != null) {
            lowerExcluded = new Rect(
                    Math.round(excludedPanelRect.left * scanScale),
                    Math.round((excludedPanelRect.top - top) * scanScale),
                    Math.round(excludedPanelRect.right * scanScale),
                    Math.round((excludedPanelRect.bottom - top) * scanScale)
            );
        }
        final Rect lowerExcludedForCapture = lowerExcluded;
        final Bitmap scanBitmapForCapture = scanBitmap;
        japaneseRecognizer.process(InputImage.fromBitmap(scanBitmapForCapture, 0))
                .addOnSuccessListener(text -> {
                    String lowerText = repairJapaneseOcr(
                            collectText(text, lowerExcludedForCapture)
                    );
                    Log.i(
                            TAG,
                            "bottom full=" + fullText.replace('\n', '|')
                                    + " lower=" + lowerText.replace('\n', '|')
                    );
                    String merged = mergeUniqueLines(
                            fullText,
                            lowerText
                    );
                    String cleaned = deduplicateJapaneseLines(merged);
                    Log.i(
                            TAG,
                            "merged=" + merged.replace('\n', '|')
                                    + " cleaned=" + cleaned.replace('\n', '|')
                    );
                    recycleBottomScan(lower, scanBitmapForCapture);
                    finish(bitmap, cleaned, callback);
                })
                .addOnFailureListener(error -> {
                    recycleBottomScan(lower, scanBitmapForCapture);
                    finish(bitmap, fullText, callback);
                });
    }

    private void recycleBottomScan(Bitmap lower, Bitmap scan) {
        if (scan != lower && !scan.isRecycled()) {
            scan.recycle();
        }
        if (!lower.isRecycled()) {
            lower.recycle();
        }
    }

    private int countNonEmptyLines(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        int count = 0;
        for (String line : text.split("\\n")) {
            if (!line.trim().isEmpty()) {
                count++;
            }
        }
        return count;
    }

    private String mergeUniqueLines(String first, String second) {
        StringBuilder merged = new StringBuilder(first == null ? "" : first.trim());
        if (second == null) {
            return merged.toString();
        }
        String firstKey = compactOcrKey(merged.toString());
        java.util.ArrayList<String> existingLineKeys =
                new java.util.ArrayList<>();
        for (String existingLine : merged.toString().split("\\n")) {
            String existingKey = compactOcrKey(existingLine);
            if (!existingKey.isEmpty()) {
                existingLineKeys.add(existingKey);
            }
        }
        for (String line : second.split("\\n")) {
            String value = line.trim();
            String valueKey = compactOcrKey(value);
            boolean nearDuplicate = false;
            for (String existingKey : existingLineKeys) {
                if (isNearDuplicateOcrLine(existingKey, valueKey)) {
                    nearDuplicate = true;
                    break;
                }
            }
            if (valueKey.length() < 3
                    || firstKey.contains(valueKey)
                    || merged.toString().contains(value)
                    || nearDuplicate) {
                continue;
            }
            if (merged.length() > 0) {
                merged.append('\n');
            }
            merged.append(value);
            firstKey = compactOcrKey(merged.toString());
            existingLineKeys.add(valueKey);
        }
        return merged.toString();
    }

    private boolean isNearDuplicateOcrLine(String first, String second) {
        if (first == null || second == null) {
            return false;
        }
        int firstLength = first.codePointCount(0, first.length());
        int secondLength = second.codePointCount(0, second.length());
        int shorter = Math.min(firstLength, secondLength);
        int longer = Math.max(firstLength, secondLength);
        if (shorter < 10 || longer - shorter > 2) {
            return false;
        }
        int allowedDistance = longer >= 20 ? 2 : 1;
        return codePointEditDistance(first, second) <= allowedDistance;
    }

    private int codePointEditDistance(String first, String second) {
        int[] left = first.codePoints().toArray();
        int[] right = second.codePoints().toArray();
        if (left.length > right.length) {
            int[] swap = left;
            left = right;
            right = swap;
        }
        int[] previous = new int[left.length + 1];
        int[] current = new int[left.length + 1];
        for (int i = 0; i <= left.length; i++) {
            previous[i] = i;
        }
        for (int row = 1; row <= right.length; row++) {
            current[0] = row;
            for (int column = 1; column <= left.length; column++) {
                int substitution = previous[column - 1]
                        + (left[column - 1] == right[row - 1] ? 0 : 1);
                current[column] = Math.min(
                        Math.min(
                                previous[column] + 1,
                                current[column - 1] + 1
                        ),
                        substitution
                );
            }
            int[] swap = previous;
            previous = current;
            current = swap;
        }
        return previous[left.length];
    }

    private String compactOcrKey(String text) {
        return text == null
                ? ""
                : text
                        .replaceAll("[\\u200B-\\u200D\\uFEFF]", "")
                        .replaceAll("[\\s。、！？!?\\.…~～]+", "")
                        .trim();
    }

    private String deduplicateJapaneseLines(String text) {
        java.util.ArrayList<String> kept = new java.util.ArrayList<>();
        if (text == null) {
            return "";
        }
        for (String line : text.split("\\n")) {
            String value = line.trim();
            String key = compactOcrKey(value);
            if (key.isEmpty()) {
                continue;
            }
            boolean covered = false;
            for (int i = 0; i < kept.size(); i++) {
                String existing = kept.get(i);
                String existingKey = compactOcrKey(existing);
                if (existingKey.equals(key)) {
                    if (japaneseTextScore(value) > japaneseTextScore(existing)) {
                        kept.set(i, value);
                    }
                    covered = true;
                    break;
                }
                if (existingKey.contains(key)) {
                    covered = true;
                    break;
                }
                if (key.contains(existingKey)) {
                    kept.set(i, value);
                    covered = true;
                    break;
                }
            }
            if (!covered) {
                kept.add(value);
            }
        }
        return String.join("\n", kept);
    }

    private void recognizeKoreanOnly(
            InputImage image,
            Bitmap bitmap,
            Rect excludedPanelRect,
            Callback callback
    ) {
        koreanRecognizer.process(image)
                .addOnSuccessListener(text ->
                        finish(bitmap, collectText(text, excludedPanelRect), callback)
                )
                .addOnFailureListener(error -> fail(bitmap, error, callback));
    }

    private void recognizeJapaneseAndMaybeKorean(
            InputImage image,
            Bitmap bitmap,
            Rect excludedPanelRect,
            String chineseResult,
            Callback callback
    ) {
        japaneseRecognizer.process(image)
                .addOnSuccessListener(japaneseText -> {
                    String japaneseResult = collectText(japaneseText, excludedPanelRect);
                    japaneseResult = repairJapaneseOcr(japaneseResult);
                    String bestJapanese = chooseBetter(chineseResult, japaneseResult);
                    if (containsKana(japaneseResult)) {
                        finish(bitmap, bestJapanese, callback);
                        return;
                    }
                    koreanRecognizer.process(image)
                            .addOnSuccessListener(koreanText -> {
                                String koreanResult = collectText(koreanText, excludedPanelRect);
                                finish(bitmap, chooseLonger(bestJapanese, koreanResult), callback);
                            })
                            .addOnFailureListener(error ->
                                    finish(bitmap, bestJapanese, callback)
                            );
                })
                .addOnFailureListener(error ->
                        koreanRecognizer.process(image)
                                .addOnSuccessListener(koreanText ->
                                        finish(
                                                bitmap,
                                                chooseLonger(
                                                        chineseResult,
                                                collectText(koreanText, excludedPanelRect)
                                                ),
                                                callback
                                        )
                                )
                                .addOnFailureListener(koreanError -> {
                                    if (chineseResult.isEmpty()) {
                                        fail(bitmap, koreanError, callback);
                                    } else {
                                        finish(bitmap, chineseResult, callback);
                                    }
                                })
                );
    }

    private void finish(Bitmap bitmap, String text, Callback callback) {
        if (!bitmap.isRecycled()) {
            bitmap.recycle();
        }
        String normalized = text == null ? "" : text.trim();
        Log.i(
                TAG,
                "lines=" + countNonEmptyLines(normalized)
                        + " text=" + normalized.replace('\n', '|')
        );
        callback.onResult(text);
    }

    private void fail(Bitmap bitmap, Exception error, Callback callback) {
        if (!bitmap.isRecycled()) {
            bitmap.recycle();
        }
        callback.onError(error);
    }

    private String collectText(Text text, Rect excludedPanelRect) {
        StringBuilder builder = new StringBuilder();
        for (Text.TextBlock block : text.getTextBlocks()) {
            Rect box = block.getBoundingBox();
            if (box != null && excludedPanelRect != null
                    && Rect.intersects(box, excludedPanelRect)) {
                continue;
            }
            for (Text.Line line : block.getLines()) {
                String value = line.getText()
                        .replace('\t', ' ')
                        .replaceAll(" {2,}", " ")
                        .trim();
                if (value.isEmpty()) {
                    continue;
                }
                if (builder.length() > 0) {
                    builder.append('\n');
                }
                builder.append(value);
            }
        }
        return builder.toString().trim();
    }

    private String chooseBetter(String chineseResult, String japaneseResult) {
        boolean japaneseHasKana = containsKana(japaneseResult);
        boolean chineseHasKana = containsKana(chineseResult);
        if (japaneseHasKana && !chineseHasKana) {
            return japaneseResult;
        }
        return chooseLonger(chineseResult, japaneseResult);
    }

    private String chooseLonger(String first, String second) {
        return second.length() > first.length() + 2 ? second : first;
    }

    private String repairJapaneseOcr(String text) {
        // Common visually similar OCR substitutions in small Japanese subtitles.
        return text
                .replace("端ぎ", "喘ぎ")
                .replace("悲嶋", "悲鳴")
                .replace("▼", "")
                .replace("▲", "")
                .replaceAll("\\.{2,}", "……")
                .replaceAll("…{2,}", "……")
                .replace('~', '～');
    }

    private String chooseJapaneseCandidate(String first, String second) {
        if (first == null || first.trim().isEmpty()) {
            return second == null ? "" : second;
        }
        if (second == null || second.trim().isEmpty()) {
            return first;
        }
        int firstScore = japaneseTextScore(first);
        int secondScore = japaneseTextScore(second);
        return secondScore > firstScore ? second : first;
    }

    private int japaneseTextScore(String text) {
        int score = countNonEmptyLines(text) * 100;
        for (int i = 0; i < text.length(); i++) {
            char value = text.charAt(i);
            Character.UnicodeBlock block = Character.UnicodeBlock.of(value);
            if (block == Character.UnicodeBlock.HIRAGANA
                    || block == Character.UnicodeBlock.KATAKANA
                    || block == Character.UnicodeBlock.KATAKANA_PHONETIC_EXTENSIONS
                    || block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS
                    || block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_A) {
                score += 3;
            } else if (value == '。'
                    || value == '、'
                    || value == '！'
                    || value == '？'
                    || value == '…'
                    || value == '～'
                    || value == '「'
                    || value == '」'
                    || value == '『'
                    || value == '』') {
                score += 12;
            } else if (Character.isLetterOrDigit(value)) {
                score -= 4;
            }
        }
        return score;
    }

    private boolean containsKana(String text) {
        for (int i = 0; i < text.length(); i++) {
            Character.UnicodeBlock block = Character.UnicodeBlock.of(text.charAt(i));
            if (block == Character.UnicodeBlock.HIRAGANA
                    || block == Character.UnicodeBlock.KATAKANA
                    || block == Character.UnicodeBlock.KATAKANA_PHONETIC_EXTENSIONS) {
                return true;
            }
        }
        return false;
    }

    public void close() {
        chineseRecognizer.close();
        japaneseRecognizer.close();
        koreanRecognizer.close();
    }
}
