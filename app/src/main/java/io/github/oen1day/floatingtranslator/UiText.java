package io.github.oen1day.floatingtranslator;

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class UiText {
    private static JSONObject translations;

    private UiText() {
    }

    public static String translate(Context context, String source) {
        if (source == null
                || source.isEmpty()
                || AppLanguage.SIMPLIFIED_CHINESE.equals(AppLanguage.get(context))) {
            return source;
        }
        JSONObject locale = translations(context).optJSONObject(
                AppLanguage.get(context)
        );
        if (locale != null) {
            String localized = find(locale, source);
            if (!localized.isEmpty()) {
                return localized;
            }
        }
        JSONObject english = translations(context).optJSONObject("en");
        String localized = english == null ? "" : find(english, source);
        return localized.isEmpty() ? source : localized;
    }

    private static String find(JSONObject locale, String source) {
        String exact = locale.optString(source, "");
        if (!exact.isEmpty()) {
            return exact;
        }
        List<String> keys = keys(locale);
        for (String key : keys) {
            if (key.endsWith("：") && source.startsWith(key)) {
                return locale.optString(key)
                        + source.substring(key.length());
            }
        }
        String replaced = source;
        for (String key : keys) {
            if (!key.endsWith("：") && replaced.contains(key)) {
                replaced = replaced.replace(key, locale.optString(key));
            }
        }
        if (!replaced.equals(source)) {
            return replaced;
        }
        return "";
    }

    public static void setText(TextView view, String source) {
        view.setText(translate(view.getContext(), source));
    }

    public static void toast(Context context, String source, int duration) {
        Toast.makeText(
                context,
                translate(context, source),
                duration
        ).show();
    }

    public static void localizeTree(View view) {
        if (view instanceof TextView) {
            TextView textView = (TextView) view;
            CharSequence text = textView.getText();
            if (text != null) {
                String localized = translate(view.getContext(), text.toString());
                if (!localized.contentEquals(text)) {
                    textView.setText(localized);
                }
            }
            if (textView instanceof EditText) {
                CharSequence hint = textView.getHint();
                if (hint != null) {
                    textView.setHint(translate(
                            view.getContext(),
                            hint.toString()
                    ));
                }
            }
            CharSequence description = textView.getContentDescription();
            if (description != null) {
                textView.setContentDescription(translate(
                        view.getContext(),
                        description.toString()
                ));
            }
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                localizeTree(group.getChildAt(i));
            }
        }
    }

    private static synchronized List<String> keys(JSONObject object) {
        ArrayList<String> values = new ArrayList<>();
        java.util.Iterator<String> iterator = object.keys();
        while (iterator.hasNext()) {
            values.add(iterator.next());
        }
        values.sort((left, right) -> Integer.compare(
                right.length(),
                left.length()
        ));
        return values;
    }

    private static synchronized JSONObject translations(Context context) {
        if (translations != null) {
            return translations;
        }
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                context.getAssets().open("ui_text.json"),
                java.nio.charset.StandardCharsets.UTF_8
        ))) {
            StringBuilder json = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                json.append(line);
            }
            translations = new JSONObject(json.toString());
        } catch (Exception error) {
            translations = new JSONObject();
        }
        return translations;
    }
}
