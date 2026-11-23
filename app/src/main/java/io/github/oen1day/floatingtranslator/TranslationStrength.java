package io.github.oen1day.floatingtranslator;

import android.content.Context;

public final class TranslationStrength {
    public static final int FAST = 0;
    public static final int BALANCED = 1;
    public static final int PRECISE = 2;
    public static final int API = 3;
    public static final int ULTRA = 4;

    public static final String[] BASE_LABELS = {
            "快速",
            "均衡",
            "精细",
            "API"
    };
    public static final String ULTRA_LABEL = "日→中";

    private TranslationStrength() {
    }

    public static int clamp(int value) {
        return Math.max(FAST, Math.min(ULTRA, value));
    }

    public static String label(int value) {
        int normalized = clamp(value);
        return normalized == ULTRA ? ULTRA_LABEL : BASE_LABELS[normalized];
    }

    public static String[] uiLabels(Context context) {
        return new String[]{
                context.getString(R.string.strength_fast),
                context.getString(R.string.strength_balanced),
                context.getString(R.string.strength_precise),
                context.getString(R.string.strength_api)
        };
    }

    public static String uiLabel(Context context, int value) {
        switch (clamp(value)) {
            case BALANCED:
                return context.getString(R.string.strength_balanced);
            case PRECISE:
                return context.getString(R.string.strength_precise);
            case API:
                return context.getString(R.string.strength_api);
            case ULTRA:
                return context.getString(R.string.strength_ja_zh);
            default:
                return context.getString(R.string.strength_fast);
        }
    }
}
