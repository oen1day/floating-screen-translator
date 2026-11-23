package io.github.oen1day.floatingtranslator;

import android.content.Context;

public interface LocalTranslationEngine extends AutoCloseable {
    interface Callback {
        void onStatus(String message);

        void onSuccess(String translatedText);

        void onError(Exception error);
    }

    void translate(
            Context context,
            String sourceLanguage,
            String targetLanguage,
            String sourceText,
            String contextText,
            String referenceTranslation,
            int strength,
            Callback callback
    );

    @Override
    void close();
}
