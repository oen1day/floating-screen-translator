package io.github.oen1day.floatingtranslator;

import android.content.Context;

import com.google.mlkit.nl.translate.TranslateLanguage;

import java.util.Locale;

public final class LanguageCatalog {
    public static final String AUTO = "auto";

    public static final class Option {
        public final String code;
        public final String label;

        Option(String code, String label) {
            this.code = code;
            this.label = label;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    public static final Option[] SOURCE_OPTIONS = {
            new Option(AUTO, "自动识别"),
            new Option(TranslateLanguage.ENGLISH, "英语"),
            new Option(TranslateLanguage.JAPANESE, "日语"),
            new Option(TranslateLanguage.KOREAN, "韩语"),
            new Option(TranslateLanguage.CHINESE, "中文"),
            new Option(TranslateLanguage.FRENCH, "法语"),
            new Option(TranslateLanguage.GERMAN, "德语"),
            new Option(TranslateLanguage.SPANISH, "西班牙语"),
            new Option(TranslateLanguage.ITALIAN, "意大利语"),
            new Option(TranslateLanguage.PORTUGUESE, "葡萄牙语"),
            new Option(TranslateLanguage.DUTCH, "荷兰语"),
            new Option(TranslateLanguage.POLISH, "波兰语"),
            new Option(TranslateLanguage.TURKISH, "土耳其语"),
            new Option(TranslateLanguage.VIETNAMESE, "越南语"),
            new Option(TranslateLanguage.INDONESIAN, "印尼语"),
            new Option(TranslateLanguage.MALAY, "马来语"),
            new Option(TranslateLanguage.TAGALOG, "菲律宾语"),
            new Option(TranslateLanguage.ROMANIAN, "罗马尼亚语"),
            new Option(TranslateLanguage.CZECH, "捷克语"),
            new Option(TranslateLanguage.HUNGARIAN, "匈牙利语"),
            new Option(TranslateLanguage.DANISH, "丹麦语"),
            new Option(TranslateLanguage.SWEDISH, "瑞典语"),
            new Option(TranslateLanguage.NORWEGIAN, "挪威语"),
            new Option(TranslateLanguage.FINNISH, "芬兰语"),
            new Option(TranslateLanguage.CROATIAN, "克罗地亚语"),
            new Option(TranslateLanguage.SLOVAK, "斯洛伐克语"),
            new Option(TranslateLanguage.SLOVENIAN, "斯洛文尼亚语"),
            new Option(TranslateLanguage.CATALAN, "加泰罗尼亚语"),
            new Option(TranslateLanguage.GALICIAN, "加利西亚语"),
            new Option(TranslateLanguage.WELSH, "威尔士语"),
            new Option(TranslateLanguage.SWAHILI, "斯瓦希里语"),
            new Option(TranslateLanguage.HAITIAN_CREOLE, "海地克里奥尔语")
    };

    public static final Option[] TARGET_OPTIONS = {
            new Option(TranslateLanguage.CHINESE, "中文"),
            new Option(TranslateLanguage.ENGLISH, "English"),
            new Option(TranslateLanguage.JAPANESE, "日本語"),
            new Option(TranslateLanguage.KOREAN, "한국어"),
            new Option(TranslateLanguage.FRENCH, "Français"),
            new Option(TranslateLanguage.GERMAN, "Deutsch"),
            new Option(TranslateLanguage.SPANISH, "Español"),
            new Option(TranslateLanguage.ITALIAN, "Italiano"),
            new Option(TranslateLanguage.PORTUGUESE, "Português"),
            new Option(TranslateLanguage.RUSSIAN, "Русский"),
            new Option(TranslateLanguage.UKRAINIAN, "Українська"),
            new Option(TranslateLanguage.POLISH, "Polski"),
            new Option(TranslateLanguage.DUTCH, "Nederlands"),
            new Option(TranslateLanguage.TURKISH, "Türkçe"),
            new Option(TranslateLanguage.ARABIC, "العربية"),
            new Option(TranslateLanguage.HINDI, "हिन्दी"),
            new Option(TranslateLanguage.URDU, "اردو"),
            new Option(TranslateLanguage.PERSIAN, "فارسی"),
            new Option(TranslateLanguage.THAI, "ไทย"),
            new Option(TranslateLanguage.VIETNAMESE, "Tiếng Việt"),
            new Option(TranslateLanguage.INDONESIAN, "Bahasa Indonesia"),
            new Option(TranslateLanguage.MALAY, "Bahasa Melayu"),
            new Option(TranslateLanguage.TAGALOG, "Filipino"),
            new Option(TranslateLanguage.SWEDISH, "Svenska"),
            new Option(TranslateLanguage.DANISH, "Dansk"),
            new Option(TranslateLanguage.NORWEGIAN, "Norsk"),
            new Option(TranslateLanguage.FINNISH, "Suomi"),
            new Option(TranslateLanguage.ROMANIAN, "Română"),
            new Option(TranslateLanguage.CZECH, "Čeština"),
            new Option(TranslateLanguage.HUNGARIAN, "Magyar"),
            new Option(TranslateLanguage.GREEK, "Ελληνικά"),
            new Option(TranslateLanguage.HEBREW, "עברית"),
            new Option(TranslateLanguage.BULGARIAN, "Български"),
            new Option(TranslateLanguage.CROATIAN, "Hrvatski"),
            new Option(TranslateLanguage.SLOVAK, "Slovenčina"),
            new Option(TranslateLanguage.SLOVENIAN, "Slovenščina"),
            new Option(TranslateLanguage.LITHUANIAN, "Lietuvių"),
            new Option(TranslateLanguage.LATVIAN, "Latviešu"),
            new Option(TranslateLanguage.ESTONIAN, "Eesti")
    };

    private LanguageCatalog() {
    }

    public static String normalizeSource(String sourceMode) {
        for (Option option : SOURCE_OPTIONS) {
            if (option.code.equals(sourceMode)) {
                return sourceMode;
            }
        }
        return AUTO;
    }

    public static String normalizeTarget(String targetLanguage) {
        for (Option option : TARGET_OPTIONS) {
            if (option.code.equals(targetLanguage)) {
                return targetLanguage;
            }
        }
        return TranslateLanguage.CHINESE;
    }

    public static int indexOfSource(String sourceMode) {
        String normalized = normalizeSource(sourceMode);
        for (int i = 0; i < SOURCE_OPTIONS.length; i++) {
            if (SOURCE_OPTIONS[i].code.equals(normalized)) {
                return i;
            }
        }
        return 0;
    }

    public static int indexOfTarget(String targetLanguage) {
        String normalized = normalizeTarget(targetLanguage);
        for (int i = 0; i < TARGET_OPTIONS.length; i++) {
            if (TARGET_OPTIONS[i].code.equals(normalized)) {
                return i;
            }
        }
        return 0;
    }

    public static String sourceLabel(String sourceMode) {
        String normalized = normalizeSource(sourceMode);
        for (Option option : SOURCE_OPTIONS) {
            if (option.code.equals(normalized)) {
                return option.label;
            }
        }
        return "自动识别";
    }

    public static String targetLabel(String targetLanguage) {
        String normalized = normalizeTarget(targetLanguage);
        for (Option option : TARGET_OPTIONS) {
            if (option.code.equals(normalized)) {
                return option.label;
            }
        }
        return "中文";
    }

    public static String displayName(Context context, String languageCode) {
        String normalized = languageCode == null ? AUTO : languageCode;
        if (AUTO.equals(normalized)) {
            return context.getString(R.string.language_auto);
        }
        String tag = TranslateLanguage.CHINESE.equals(normalized)
                ? "zh-CN"
                : normalized;
        Locale displayLocale = Locale.forLanguageTag(AppLanguage.get(context));
        String name = Locale.forLanguageTag(tag).getDisplayName(displayLocale);
        return name == null || name.isEmpty() ? normalized : name;
    }
}
