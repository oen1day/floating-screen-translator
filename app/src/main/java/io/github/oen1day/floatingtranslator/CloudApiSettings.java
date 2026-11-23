package io.github.oen1day.floatingtranslator;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

public final class CloudApiSettings {
    public static final String DEFAULT_ENDPOINT =
            "https://api.openai.com/v1/chat/completions";
    public static final String DEFAULT_MODEL = "gpt-4o-mini";

    private static final String PREFS = "cloud_api_settings";
    private static final String KEY_ENDPOINT = "endpoint";
    private static final String KEY_MODEL = "model";
    private static final String KEY_PROVIDER = "provider";
    private static final String KEY_ENCRYPTED_API_KEY = "encrypted_api_key";
    private static final String KEY_CONSENT = "cloud_consent";
    private static final String KEYSTORE = "AndroidKeyStore";
    private static final String KEY_ALIAS = "screen_translator_api_key";

    private CloudApiSettings() {
    }

    public static String endpoint(Context context) {
        return prefs(context).getString(KEY_ENDPOINT, DEFAULT_ENDPOINT);
    }

    public static String model(Context context) {
        return prefs(context).getString(KEY_MODEL, DEFAULT_MODEL);
    }

    public static String providerId(Context context) {
        return prefs(context).getString(KEY_PROVIDER, "openai");
    }

    public static boolean hasConsent(Context context) {
        return prefs(context).getBoolean(KEY_CONSENT, false);
    }

    public static boolean hasApiKey(Context context) {
        String encrypted = prefs(context).getString(KEY_ENCRYPTED_API_KEY, "");
        return encrypted != null && !encrypted.isEmpty();
    }

    public static boolean isConfigured(Context context) {
        return hasConsent(context)
                && hasApiKey(context)
                && isHttpsEndpoint(endpoint(context))
                && !model(context).trim().isEmpty();
    }

    public static void save(
            Context context,
            String endpoint,
            String model,
            String apiKey,
            boolean consent,
            String providerId
    ) throws Exception {
        String normalizedEndpoint = endpoint == null ? "" : endpoint.trim();
        if (!isHttpsEndpoint(normalizedEndpoint)) {
            throw new IllegalArgumentException("API 地址必须是完整的 HTTPS URL");
        }
        String normalizedModel = model == null ? "" : model.trim();
        if (normalizedModel.isEmpty()) {
            throw new IllegalArgumentException("模型名称不能为空");
        }

        SharedPreferences.Editor editor = prefs(context).edit()
                .putString(KEY_ENDPOINT, normalizedEndpoint)
                .putString(KEY_MODEL, normalizedModel)
                .putString(KEY_PROVIDER, providerId == null ? "custom" : providerId)
                .putBoolean(KEY_CONSENT, consent);
        if (apiKey != null && !apiKey.trim().isEmpty()) {
            editor.putString(
                    KEY_ENCRYPTED_API_KEY,
                    encrypt(apiKey.trim())
            );
        }
        editor.apply();
    }

    public static String readApiKey(Context context) {
        String encrypted = prefs(context).getString(KEY_ENCRYPTED_API_KEY, "");
        if (encrypted == null || encrypted.isEmpty()) {
            return "";
        }
        try {
            return decrypt(encrypted);
        } catch (Exception ignored) {
            return "";
        }
    }

    public static boolean isHttpsEndpoint(String value) {
        if (value == null || value.trim().isEmpty()) {
            return false;
        }
        try {
            URI uri = URI.create(value.trim());
            return "https".equalsIgnoreCase(uri.getScheme())
                    && uri.getHost() != null
                    && !uri.getHost().isEmpty();
        } catch (IllegalArgumentException error) {
            return false;
        }
    }

    private static String encrypt(String plainText) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, secretKey());
        byte[] encrypted = cipher.doFinal(plainText.getBytes(StandardCharsets.UTF_8));
        return Base64.encodeToString(cipher.getIV(), Base64.NO_WRAP)
                + ":"
                + Base64.encodeToString(encrypted, Base64.NO_WRAP);
    }

    private static String decrypt(String value) throws Exception {
        int separator = value.indexOf(':');
        if (separator <= 0 || separator >= value.length() - 1) {
            throw new IllegalArgumentException("Invalid encrypted API key");
        }
        byte[] iv = Base64.decode(value.substring(0, separator), Base64.NO_WRAP);
        byte[] encrypted = Base64.decode(value.substring(separator + 1), Base64.NO_WRAP);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(
                Cipher.DECRYPT_MODE,
                secretKey(),
                new GCMParameterSpec(128, iv)
        );
        return new String(cipher.doFinal(encrypted), StandardCharsets.UTF_8);
    }

    private static SecretKey secretKey() throws Exception {
        KeyStore keyStore = KeyStore.getInstance(KEYSTORE);
        keyStore.load(null);
        if (keyStore.containsAlias(KEY_ALIAS)) {
            return (SecretKey) keyStore.getKey(KEY_ALIAS, null);
        }
        KeyGenerator generator = KeyGenerator.getInstance(
                KeyProperties.KEY_ALGORITHM_AES,
                KEYSTORE
        );
        generator.init(
                new KeyGenParameterSpec.Builder(
                        KEY_ALIAS,
                        KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT
                )
                        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                        .build()
        );
        return generator.generateKey();
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
