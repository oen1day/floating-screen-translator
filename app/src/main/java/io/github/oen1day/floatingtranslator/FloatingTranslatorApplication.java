package io.github.oen1day.floatingtranslator;

import android.app.Application;
import android.content.Context;

public class FloatingTranslatorApplication extends Application {
    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(AppLanguage.wrap(base));
    }
}
