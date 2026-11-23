package io.github.oen1day.floatingtranslator;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.TextView;

public class LanguageSetupActivity extends Activity {
    public static final String EXTRA_CHANGE_LANGUAGE = "change_language";

    private static final String[] LANGUAGES = {
            AppLanguage.SIMPLIFIED_CHINESE,
            AppLanguage.TRADITIONAL_CHINESE,
            AppLanguage.ENGLISH,
            AppLanguage.RUSSIAN
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_language_setup);

        int[] rowIds = {
                R.id.language_zh_cn,
                R.id.language_zh_tw,
                R.id.language_en,
                R.id.language_ru
        };
        for (int index = 0; index < rowIds.length; index++) {
            final String language = LANGUAGES[index];
            View row = findViewById(rowIds[index]);
            row.setOnClickListener(view -> selectLanguage(language));
        }

        boolean changing = getIntent().getBooleanExtra(EXTRA_CHANGE_LANGUAGE, false);
        TextView subtitle = findViewById(R.id.language_setup_subtitle);
        subtitle.setText(changing
                ? "选择界面语言 / Choose interface language / "
                + "選擇介面語言 / Выберите язык интерфейса"
                : "首次使用，请选择界面语言\nChoose your interface language\n"
                + "選擇介面語言\nВыберите язык интерфейса");
    }

    private void selectLanguage(String language) {
        AppLanguage.set(this, language);
        Intent intent = new Intent(this, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(intent);
        finish();
    }
}
