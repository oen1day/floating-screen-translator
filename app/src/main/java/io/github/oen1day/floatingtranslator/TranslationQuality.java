package io.github.oen1day.floatingtranslator;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class TranslationQuality {
    private static final Pattern KATAKANA_NAME_CANDIDATE =
            Pattern.compile("[\\p{IsKatakana}\\u30FC]{2,12}");

    private TranslationQuality() {
    }

    public static boolean isAcceptableOutput(String result, String targetLanguage) {
        if (result == null || result.trim().isEmpty()) {
            return false;
        }
        if (!"中文".equals(targetLanguage)) {
            return true;
        }
        int hanCharacters = 0;
        int latinLetters = 0;
        for (int i = 0; i < result.length(); i++) {
            char value = result.charAt(i);
            Character.UnicodeBlock block = Character.UnicodeBlock.of(value);
            if (block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS
                    || block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_A) {
                hanCharacters++;
            } else if ((value >= 'A' && value <= 'Z')
                    || (value >= 'a' && value <= 'z')) {
                latinLetters++;
            }
        }
        return hanCharacters >= 2 && hanCharacters * 2 >= latinLetters;
    }

    public static List<String> protectedNameCandidates(String sourceText) {
        List<String> result = new ArrayList<>();
        if (sourceText == null) {
            return result;
        }
        Matcher matcher = KATAKANA_NAME_CANDIDATE.matcher(sourceText);
        while (matcher.find() && result.size() < 6) {
            String value = matcher.group();
            boolean hasKatakana = false;
            for (int i = 0; i < value.length(); i++) {
                if (value.charAt(i) != '\u30FC') {
                    hasKatakana = true;
                    break;
                }
            }
            if (hasKatakana && !result.contains(value)) {
                result.add(value);
            }
        }
        return result;
    }

    public static boolean preservesProtectedNames(String sourceText, String translatedText) {
        if (translatedText == null) {
            return false;
        }
        for (String name : protectedNameCandidates(sourceText)) {
            if (!translatedText.contains(name)) {
                return false;
            }
        }
        return true;
    }

    public static boolean isCompleteEnough(String sourceText, String translatedText) {
        if (sourceText == null || translatedText == null) {
            return false;
        }
        int sourceLength = meaningfulLength(sourceText);
        int outputLength = meaningfulLength(translatedText);
        if (sourceLength >= 20 && outputLength * 4 < sourceLength) {
            return false;
        }
        String source = sourceText.trim();
        String output = translatedText.trim();
        if (source.isEmpty() || output.isEmpty()) {
            return false;
        }
        char sourceEnd = source.charAt(source.length() - 1);
        boolean sourceHasTerminator = "。！？!?～…".indexOf(sourceEnd) >= 0;
        if (sourceHasTerminator) {
            char outputEnd = output.charAt(output.length() - 1);
            return "。！？!?～…」』”\"'".indexOf(outputEnd) >= 0;
        }
        return true;
    }

    public static String protectedNameHint(String sourceText) {
        List<String> names = protectedNameCandidates(sourceText);
        if (names.isEmpty()) {
            return "";
        }
        return "疑似专有名词（译文必须原样保留日文写法，不得当作普通名词）："
                + String.join("、", names)
                + "\n";
    }

    public static PreparedText prepareProtectedNames(String sourceText) {
        String prepared = sourceText == null ? "" : sourceText;
        List<String> names = protectedNameCandidates(prepared);
        List<String> placeholders = new ArrayList<>();
        for (int i = 0; i < names.size(); i++) {
            String placeholder = "XQZ" + (i + 1);
            prepared = prepared.replace(names.get(i), placeholder);
            placeholders.add(placeholder);
        }
        return new PreparedText(prepared, names, placeholders);
    }

    public static final class PreparedText {
        public final String text;
        public final List<String> names;
        private final List<String> placeholders;

        PreparedText(String text, List<String> names, List<String> placeholders) {
            this.text = text;
            this.names = List.copyOf(names);
            this.placeholders = List.copyOf(placeholders);
        }

        public String promptHint() {
            if (names.isEmpty()) {
                return "";
            }
            StringBuilder hint = new StringBuilder(
                    "以下编码占位符必须逐字保留在译文中，不得解释、翻译、改写或删除：\n"
            );
            for (String placeholder : placeholders) {
                hint.append(placeholder).append('\n');
            }
            return hint.toString();
        }

        public String restore(String translatedText) {
            String restored = translatedText == null ? "" : translatedText;
            for (int i = 0; i < names.size(); i++) {
                restored = restored.replace(placeholders.get(i), names.get(i));
            }
            return restored;
        }
    }

    private static int meaningfulLength(String text) {
        int count = 0;
        for (int i = 0; i < text.length(); i++) {
            char value = text.charAt(i);
            if (!Character.isWhitespace(value)
                    && "。、！？!?.,，…~～「」『』“”\"'()（）".indexOf(value) < 0) {
                count++;
            }
        }
        return count;
    }
}
