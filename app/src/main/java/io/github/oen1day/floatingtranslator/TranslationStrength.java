package io.github.oen1day.floatingtranslator;

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
}
