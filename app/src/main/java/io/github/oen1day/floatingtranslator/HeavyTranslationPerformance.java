package io.github.oen1day.floatingtranslator;

import android.content.Context;
import android.content.SharedPreferences;

public final class HeavyTranslationPerformance {
    private static final String PREFS = "heavy_translation_performance";
    private static final String KEY_THREADS = "threads";
    private static final String KEY_CONTEXT = "context";
    private static final String KEY_BATCH = "batch";
    private static final String KEY_UBATCH = "ubatch";
    private static final String KEY_REPACK = "repack";
    private static final String KEY_BIG_CORES = "big_cores";
    private static final String KEY_Q8_KV = "q8_kv";

    private HeavyTranslationPerformance() {
    }

    public static int threads(Context context) {
        return clamp(prefs(context).getInt(KEY_THREADS, 6), 1, 8);
    }

    public static int contextTokens(Context context) {
        return clamp(prefs(context).getInt(KEY_CONTEXT, 2048), 768, 4096);
    }

    public static int batchTokens(Context context) {
        return clamp(prefs(context).getInt(KEY_BATCH, 2048), 128, 4096);
    }

    public static int ubatchTokens(Context context) {
        return clamp(prefs(context).getInt(KEY_UBATCH, 512), 64, 2048);
    }

    public static boolean repack(Context context) {
        return prefs(context).getBoolean(KEY_REPACK, false);
    }

    public static boolean bigCores(Context context) {
        return prefs(context).getBoolean(KEY_BIG_CORES, false);
    }

    public static boolean q8Kv(Context context) {
        return prefs(context).getBoolean(KEY_Q8_KV, false);
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
