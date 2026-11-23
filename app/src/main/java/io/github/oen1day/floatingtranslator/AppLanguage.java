package io.github.oen1day.floatingtranslator;

import android.app.LocaleManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.os.Build;
import android.os.LocaleList;

import java.util.Locale;

public final class AppLanguage {
    public static final String SIMPLIFIED_CHINESE = "zh-CN";
    public static final String TRADITIONAL_CHINESE = "zh-TW";
    public static final String ENGLISH = "en";
    public static final String RUSSIAN = "ru";

    private static final String PREFS = "app_language";
    private static final String KEY_LANGUAGE = "language";

    private AppLanguage() {
    }

    public static boolean isConfigured(Context context) {
        return prefs(context).contains(KEY_LANGUAGE);
    }

    public static String get(Context context) {
        String value = prefs(context).getString(
                KEY_LANGUAGE,
                defaultLanguage()
        );
        return normalize(value);
    }

    public static void set(Context context, String language) {
        String normalized = normalize(language);
        prefs(context).edit().putString(KEY_LANGUAGE, normalized).apply();
        apply(context, normalized);
    }

    public static Context wrap(Context context) {
        Locale locale = Locale.forLanguageTag(get(context));
        Locale.setDefault(locale);
        Configuration configuration = new Configuration(
                context.getResources().getConfiguration()
        );
        configuration.setLocale(locale);
        configuration.setLocales(new LocaleList(locale));
        return context.createConfigurationContext(configuration);
    }

    @SuppressWarnings("deprecation")
    public static void apply(Context context, String language) {
        Locale locale = Locale.forLanguageTag(normalize(language));
        Locale.setDefault(locale);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            LocaleManager localeManager =
                    context.getSystemService(LocaleManager.class);
            if (localeManager != null) {
                localeManager.setApplicationLocales(
                        LocaleList.forLanguageTags(locale.toLanguageTag())
                );
            }
        }
        Configuration configuration = new Configuration(
                context.getResources().getConfiguration()
        );
        configuration.setLocale(locale);
        configuration.setLocales(new LocaleList(locale));
        context.getResources().updateConfiguration(
                configuration,
                context.getResources().getDisplayMetrics()
        );
    }

    public static String nativeLabel(String language) {
        switch (normalize(language)) {
            case TRADITIONAL_CHINESE:
                return "繁體中文";
            case ENGLISH:
                return "English";
            case RUSSIAN:
                return "Русский";
            default:
                return "中文";
        }
    }

    public static String flag(String language) {
        switch (normalize(language)) {
            case TRADITIONAL_CHINESE:
                return "🇭🇰";
            case ENGLISH:
                return "🇺🇸";
            case RUSSIAN:
                return "🇷🇺";
            default:
                return "🇨🇳";
        }
    }

    public static String normalize(String value) {
        if (TRADITIONAL_CHINESE.equalsIgnoreCase(value)
                || "zh-HK".equalsIgnoreCase(value)
                || "zh-Hant".equalsIgnoreCase(value)) {
            return TRADITIONAL_CHINESE;
        }
        if (ENGLISH.equalsIgnoreCase(value)) {
            return ENGLISH;
        }
        if (RUSSIAN.equalsIgnoreCase(value)) {
            return RUSSIAN;
        }
        return SIMPLIFIED_CHINESE;
    }

    private static String defaultLanguage() {
        Locale locale = Locale.getDefault();
        if ("zh".equals(locale.getLanguage())) {
            String script = locale.getScript();
            String country = locale.getCountry();
            if ("Hant".equalsIgnoreCase(script)
                    || "TW".equalsIgnoreCase(country)
                    || "HK".equalsIgnoreCase(country)
                    || "MO".equalsIgnoreCase(country)) {
                return TRADITIONAL_CHINESE;
            }
            return SIMPLIFIED_CHINESE;
        }
        if ("ru".equals(locale.getLanguage())) {
            return RUSSIAN;
        }
        return ENGLISH;
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
