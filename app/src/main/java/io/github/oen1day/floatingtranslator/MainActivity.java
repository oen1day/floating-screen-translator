package io.github.oen1day.floatingtranslator;

import android.accessibilityservice.AccessibilityServiceInfo;
import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityManager;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import java.util.List;

public class MainActivity extends Activity {
    private static final int REQUEST_SCREEN_CAPTURE = 1001;
    private static final int REQUEST_OVERLAY_PERMISSION = 1002;
    private static final int REQUEST_NOTIFICATIONS = 1003;

    private TextView statusText;
    private TextView permissionText;
    private TextView accessibilityStatus;
    private TextView localModelStatus;
    private TextView largeModelStatus;
    private TextView ultraModelStatus;
    private TextView vipStatus;
    private Button startButton;
    private Button accessibilityButton;
    private Button localModelButton;
    private Button largeModelButton;
    private Button ultraModelButton;
    private EditText apiEndpointInput;
    private EditText apiModelInput;
    private EditText apiKeyInput;
    private Spinner apiProviderSpinner;
    private CheckBox apiConsentCheck;
    private TextView apiSettingsStatus;
    private Button apiSaveButton;
    private Button vipButton;
    private Button stopButton;
    private Button interfaceLanguageButton;
    private MediaProjectionManager projectionManager;
    private int pendingStartMode = FloatingTranslateService.INPUT_MODE_MEDIA_PROJECTION;
    private boolean localModelDownloading;
    private boolean largeModelDownloading;
    private boolean ultraModelDownloading;
    private boolean updatingApiProvider;
    private final Runnable refreshUiTask = this::updateUi;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (!AppLanguage.isConfigured(this)) {
            startActivity(new Intent(this, LanguageSetupActivity.class));
            finish();
            return;
        }
        setContentView(R.layout.activity_main);
        localizeUi();

        statusText = findViewById(R.id.status_text);
        permissionText = findViewById(R.id.permission_text);
        accessibilityStatus = findViewById(R.id.accessibility_status);
        localModelStatus = findViewById(R.id.local_model_status);
        largeModelStatus = findViewById(R.id.large_model_status);
        ultraModelStatus = findViewById(R.id.ultra_model_status);
        vipStatus = findViewById(R.id.vip_status);
        startButton = findViewById(R.id.start_button);
        accessibilityButton = findViewById(R.id.accessibility_mode_button);
        localModelButton = findViewById(R.id.local_model_button);
        largeModelButton = findViewById(R.id.large_model_button);
        ultraModelButton = findViewById(R.id.ultra_model_button);
        apiEndpointInput = findViewById(R.id.api_endpoint_input);
        apiModelInput = findViewById(R.id.api_model_input);
        apiKeyInput = findViewById(R.id.api_key_input);
        apiProviderSpinner = findViewById(R.id.api_provider_spinner);
        apiConsentCheck = findViewById(R.id.api_consent_check);
        apiSettingsStatus = findViewById(R.id.api_settings_status);
        apiSaveButton = findViewById(R.id.api_save_button);
        vipButton = findViewById(R.id.vip_button);
        stopButton = findViewById(R.id.stop_button);
        interfaceLanguageButton = findViewById(R.id.interface_language_button);
        projectionManager =
                (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);

        startButton.setOnClickListener(v -> prepareAndStart());
        accessibilityButton.setOnClickListener(v -> prepareAccessibilityStart());
        localModelButton.setOnClickListener(v -> startLocalModelDownload());
        largeModelButton.setOnClickListener(v -> startLargeModelDownload());
        ultraModelButton.setOnClickListener(v -> startUltraModelDownload());
        apiSaveButton.setOnClickListener(v -> saveCloudApiSettings());
        vipButton.setOnClickListener(v -> toggleVipAutoHeal());
        stopButton.setOnClickListener(v -> {
            stopService(new Intent(this, FloatingTranslateService.class));
            UiText.toast(this, "悬浮翻译服务已停止", Toast.LENGTH_SHORT);
            updateUi();
        });
        interfaceLanguageButton.setOnClickListener(v -> {
            stopService(new Intent(this, FloatingTranslateService.class));
            startActivity(new Intent(this, LanguageSetupActivity.class)
                    .putExtra(LanguageSetupActivity.EXTRA_CHANGE_LANGUAGE, true));
        });
        setupCloudApiProviderSpinner();
        loadCloudApiSettingsUi();
    }

    @Override
    protected void onResume() {
        super.onResume();
        AccessibilityHealer.heal(this, false);
        updateUi();
        statusText.removeCallbacks(refreshUiTask);
        statusText.postDelayed(refreshUiTask, 500L);
        statusText.postDelayed(refreshUiTask, 1600L);
    }

    @Override
    protected void onPause() {
        statusText.removeCallbacks(refreshUiTask);
        super.onPause();
    }

    private void prepareAndStart() {
        if (FloatingTranslateService.isRunning()) {
            UiText.toast(this, "悬浮翻译已经在运行", Toast.LENGTH_SHORT);
            return;
        }

        pendingStartMode = FloatingTranslateService.INPUT_MODE_MEDIA_PROJECTION;
        if (!Settings.canDrawOverlays(this)) {
            Intent intent = new Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getPackageName())
            );
            startActivityForResult(intent, REQUEST_OVERLAY_PERMISSION);
            return;
        }

        if (!requestNotificationPermissionIfNeeded()) {
            return;
        }

        requestScreenCapture();
    }

    private void prepareAccessibilityStart() {
        if (FloatingTranslateService.isRunning()) {
            UiText.toast(this, "悬浮翻译已经在运行", Toast.LENGTH_SHORT);
            return;
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            UiText.toast(
                    this,
                    "无障碍截图需要 Android 11 或更高版本",
                    Toast.LENGTH_LONG
            );
            return;
        }

        pendingStartMode = FloatingTranslateService.INPUT_MODE_ACCESSIBILITY;
        attemptAccessibilityStart(0);
    }

    private void attemptAccessibilityStart(int attempt) {
        boolean enabled = isAccessibilityServiceEnabled();
        boolean connected = ScreenCaptureAccessibilityService.isConnected();
        if (enabled && connected) {
            if (!requestNotificationPermissionIfNeeded()) {
                return;
            }
            startAccessibilityMode();
            return;
        }

        AccessibilityHealer.Result healResult = AccessibilityHealer.heal(this, true);
        if (healResult == AccessibilityHealer.Result.REPAIRED
                || healResult == AccessibilityHealer.Result.RELOADING) {
            statusText.setText("正在恢复本应用的无障碍服务…");
            if (attempt < 6) {
                statusText.postDelayed(
                        () -> attemptAccessibilityStart(attempt + 1),
                        700L
                );
            } else {
            UiText.toast(
                    this,
                    "自动恢复未完成，请稍后重试或手动打开无障碍",
                    Toast.LENGTH_LONG
            );
                updateUi();
            }
            return;
        }

        if (!enabled) {
            UiText.toast(
                    this,
                    "请先在系统设置中开启“浮屏译无障碍截图”",
                    Toast.LENGTH_LONG
            );
            openAccessibilitySettings();
            return;
        }

            UiText.toast(
                    this,
                    "无障碍已启用，但服务尚未连接；请关闭后重新开启，再返回本页",
                    Toast.LENGTH_LONG
            );
        openAccessibilitySettings();
        updateUi();
    }

    private boolean requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(
                    new String[]{Manifest.permission.POST_NOTIFICATIONS},
                    REQUEST_NOTIFICATIONS
            );
            return false;
        }
        return true;
    }

    private void startAccessibilityMode() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R
                || !ScreenCaptureAccessibilityService.isConnected()) {
            UiText.toast(
                    this,
                    "无障碍截图服务未连接，未启动兼容模式",
                    Toast.LENGTH_LONG
            );
            updateUi();
            return;
        }
        Intent serviceIntent = new Intent(this, FloatingTranslateService.class)
                .putExtra(
                        FloatingTranslateService.EXTRA_INPUT_MODE,
                        FloatingTranslateService.INPUT_MODE_ACCESSIBILITY
                );
        startForegroundService(serviceIntent);
        UiText.toast(
                this,
                AccessibilityHealer.hasPermission(this)
                        && AccessibilityHealer.isAutoHealEnabled(this)
                        ? "无障碍兼容模式已启动；强制停止后重新打开 App 会自动恢复"
                        : "无障碍兼容模式已启动；强制停止 App 后需重新开启无障碍",
                Toast.LENGTH_LONG
        );
        updateUi();
    }

    private void openAccessibilitySettings() {
        startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
    }

    private void startLocalModelDownload() {
        if (localModelDownloading || LocalModelManager.isReady(this)) {
            return;
        }
        localModelDownloading = true;
        localModelButton.setEnabled(false);
        localModelStatus.setText("本地精修模型：准备下载（约 1.6GB）");
        new Thread(() -> {
            try {
                LocalModelManager.downloadBlocking(
                        getApplicationContext(),
                        (percent, downloaded, total) -> runOnUiThread(() -> {
                            localModelStatus.setText(
                                    "本地精修模型：下载中 " + percent + "%"
                                            + "\n" + formatBytes(downloaded)
                                            + " / " + formatBytes(total)
                            );
                            localizeUi();
                        })
                );
                runOnUiThread(() -> {
                    localModelDownloading = false;
                    localModelButton.setEnabled(true);
                    UiText.toast(
                            this,
                            "本地精修模型已下载完成，可离线使用",
                            Toast.LENGTH_LONG
                    );
                    updateUi();
                });
            } catch (Exception error) {
                runOnUiThread(() -> {
                    localModelDownloading = false;
                    localModelButton.setEnabled(true);
                    localModelStatus.setText(
                            "本地精修模型：下载失败\n" + error.getMessage()
                    );
                    localizeUi();
                    UiText.toast(
                            this,
                            "模型下载失败：" + error.getMessage(),
                            Toast.LENGTH_LONG
                    );
                });
            }
        }, "local-model-download").start();
    }

    private void updateLocalModelUi() {
        boolean ready = LocalModelManager.isReady(this);
        if (localModelDownloading) {
            localModelButton.setText("本地模型下载中");
            localModelButton.setEnabled(false);
            return;
        }
        if (ready) {
            localModelStatus.setText(
                    "本地精修模型：已就绪"
                            + "\nQwen2.5 1.5B，均衡档使用，完全离线运行，约 1.6GB。"
            );
            localModelButton.setText("本地精修模型已就绪");
            localModelButton.setEnabled(false);
        } else {
            localModelStatus.setText(
                    "本地精修模型：未下载"
                            + "\n悬浮窗切到“均衡”时使用；精细档会优先使用独立的 3B 模型。"
            );
            localModelButton.setText("下载本地精修模型");
            localModelButton.setEnabled(true);
        }
    }

    private void startLargeModelDownload() {
        if (largeModelDownloading || LargeLocalModelManager.isReady(this)) {
            return;
        }
        largeModelDownloading = true;
        largeModelButton.setEnabled(false);
        largeModelStatus.setText(
                LargeLocalModelManager.hasModelFile(this)
                        ? "3B 精细模型：校验 SHA-256"
                        : "3B 精细模型：准备下载（约 1.75GB）"
        );
        new Thread(() -> {
            try {
                LargeLocalModelManager.downloadAndVerifyBlocking(
                        getApplicationContext(),
                        new LargeLocalModelManager.ProgressListener() {
                            @Override
                            public void onProgress(
                                    int percent,
                                    long downloadedBytes,
                                    long totalBytes
                            ) {
                                runOnUiThread(() -> {
                                    largeModelStatus.setText(
                                            "3B 精细模型：下载中 " + percent + "%"
                                                    + "\n" + formatBytes(downloadedBytes)
                                                    + " / " + formatBytes(totalBytes)
                                    );
                                    localizeUi();
                                });
                            }

                            @Override
                            public void onVerifying() {
                                runOnUiThread(() -> {
                                    largeModelStatus.setText(
                                            "3B 精细模型：正在校验 SHA-256，请稍候"
                                    );
                                    localizeUi();
                                });
                            }
                        }
                );
                runOnUiThread(() -> {
                    largeModelDownloading = false;
                    UiText.toast(
                            this,
                            "3B 精细模型已就绪，精细档将优先使用",
                            Toast.LENGTH_LONG
                    );
                    updateUi();
                });
            } catch (Exception error) {
                runOnUiThread(() -> {
                    largeModelDownloading = false;
                    largeModelButton.setEnabled(true);
                    largeModelStatus.setText(
                            "3B 精细模型：不可用\n" + error.getMessage()
                    );
                    localizeUi();
                    UiText.toast(
                            this,
                            "3B 模型准备失败：" + error.getMessage(),
                            Toast.LENGTH_LONG
                    );
                });
            }
        }, "large-model-download").start();
    }

    private void updateLargeModelUi() {
        if (largeModelDownloading) {
            largeModelButton.setText("3B 精细模型处理中");
            largeModelButton.setEnabled(false);
            return;
        }
        if (LargeLocalModelManager.isReady(this)) {
            largeModelStatus.setText(
                    "3B 精细模型：已就绪"
                            + "\n精细档优先使用 GPU，失败时自动回退 CPU；质量仍属实验性。"
                            + "\n模型约 1.75GB，GPU 权重缓存约 1.7GB。"
            );
            largeModelButton.setText("3B 精细模型已就绪");
            largeModelButton.setEnabled(false);
            return;
        }
        if (LargeLocalModelManager.hasModelFile(this)) {
            largeModelStatus.setText(
                    "3B 精细模型：文件已存在，等待安全校验"
                            + "\n校验通过后精细档才会调用该模型。"
            );
            largeModelButton.setText("校验并启用 3B 模型");
            largeModelButton.setEnabled(true);
            return;
        }
        largeModelStatus.setText(
                "3B 精细模型：未下载"
                        + "\n模型约 1.75GB，运行时另需约 1.7GB 缓存。"
                        + "\n未就绪或加载失败时，精细档自动回退 1.5B。"
        );
        largeModelButton.setText("下载 3B 精细模型");
        largeModelButton.setEnabled(true);
    }

    private void startUltraModelDownload() {
        if (ultraModelDownloading || UltraLocalModelManager.isReady(this)) {
            return;
        }
        ultraModelDownloading = true;
        ultraModelButton.setEnabled(false);
        ultraModelStatus.setText(
                UltraLocalModelManager.hasModelFile(this)
                        ? "日→中专项模型：校验 SHA-256"
                        : "日→中专项模型：准备下载（约 4.25GB）"
        );
        new Thread(() -> {
            try {
                UltraLocalModelManager.downloadAndVerifyBlocking(
                        getApplicationContext(),
                        new UltraLocalModelManager.ProgressListener() {
                            @Override
                            public void onProgress(
                                    int percent,
                                    long downloadedBytes,
                                    long totalBytes
                            ) {
                                runOnUiThread(() -> {
                                    ultraModelStatus.setText(
                                            "日→中专项模型：下载中 " + percent + "%"
                                                    + "\n" + formatBytes(downloadedBytes)
                                                    + " / " + formatBytes(totalBytes)
                                    );
                                    localizeUi();
                                });
                            }

                            @Override
                            public void onVerifying() {
                                runOnUiThread(() -> {
                                    ultraModelStatus.setText(
                                            "日→中专项模型：正在校验 SHA-256，请稍候"
                                    );
                                    localizeUi();
                                });
                            }
                        }
                );
                runOnUiThread(() -> {
                    ultraModelDownloading = false;
                    UiText.toast(
                            this,
                            "Sakura 7B 日→中专项模型已就绪",
                            Toast.LENGTH_LONG
                    );
                    updateUi();
                });
            } catch (Exception error) {
                runOnUiThread(() -> {
                    ultraModelDownloading = false;
                    ultraModelButton.setEnabled(true);
                    ultraModelStatus.setText(
                            "日→中专项模型：不可用\n" + error.getMessage()
                    );
                    localizeUi();
                    UiText.toast(
                            this,
                            "日→中专项模型准备失败：" + error.getMessage(),
                            Toast.LENGTH_LONG
                    );
                });
            }
        }, "ultra-model-download").start();
    }

    private void updateUltraModelUi() {
        if (ultraModelDownloading) {
            ultraModelButton.setText("日→中专项模型处理中");
            ultraModelButton.setEnabled(false);
            return;
        }
        if (UltraLocalModelManager.isReady(this)) {
            ultraModelStatus.setText(
                    "日→中专项模型：已就绪"
                            + "\nSakura 7B Qwen2.5，日译中文学/Galgame 专项，IQ4_XS 约 4.25GB。"
                            + "\n选择“日文 → 简体中文”时，强度栏会额外显示“日→中”。"
                            + "\n与 1.5B、3B 互斥：使用前会释放小模型。"
                            + "\n模型许可证：CC-BY-NC-SA-4.0（仅限非商业使用）。"
            );
            ultraModelButton.setText("日→中专项模型已就绪");
            ultraModelButton.setEnabled(false);
            return;
        }
        if (UltraLocalModelManager.hasModelFile(this)) {
            ultraModelStatus.setText(
                    "日→中专项模型：文件已存在，等待安全校验"
                            + "\n校验通过后“日→中”强度才会加载。"
            );
            ultraModelButton.setText("校验并启用日→中专项模型");
            ultraModelButton.setEnabled(true);
            return;
        }
        ultraModelStatus.setText(
                "日→中专项模型：未下载"
                        + "\nSakura 7B 日译中专项，文件约 4.25GB；运行时需更多内存。"
                        + "\n选择日文 → 简体中文时才会出现“日→中”强度。"
        );
        ultraModelButton.setText("下载 Sakura 7B 日→中专项模型");
        ultraModelButton.setEnabled(true);
    }

    private void loadCloudApiSettingsUi() {
        String providerId = CloudApiSettings.providerId(this);
        CloudApiProvider.Provider provider = CloudApiProvider.findById(providerId);
        apiEndpointInput.setText(CloudApiSettings.endpoint(this));
        apiModelInput.setText(CloudApiSettings.model(this));
        updatingApiProvider = true;
        apiProviderSpinner.setSelection(
                CloudApiProvider.indexOf(provider.id),
                false
        );
        updatingApiProvider = false;
        setApiTechnicalFieldsEnabled(provider.isCustom());
        apiConsentCheck.setChecked(CloudApiSettings.hasConsent(this));
        apiKeyInput.setText("");
        apiKeyInput.setHint(
                CloudApiSettings.hasApiKey(this)
                        ? "API Key 已加密保存；留空保持不变"
                        : "填写 API Key"
        );
        updateCloudApiStatus();
    }

    private void setupCloudApiProviderSpinner() {
        ArrayAdapter<CloudApiProvider.Provider> adapter = new ArrayAdapter<>(
                this,
                android.R.layout.simple_spinner_item,
                CloudApiProvider.PRESETS
        ) {
            @Override
            public View getView(int position, View convertView, ViewGroup parent) {
                TextView view = (TextView) super.getView(position, convertView, parent);
                view.setTextColor(getColor(R.color.paper));
                view.setTextSize(14);
                view.setPadding(dp(12), 0, dp(12), 0);
                return view;
            }

            @Override
            public View getDropDownView(int position, View convertView, ViewGroup parent) {
                TextView view = (TextView) super.getDropDownView(
                        position,
                        convertView,
                        parent
                );
                view.setTextColor(getColor(R.color.ink_950));
                view.setTextSize(14);
                view.setPadding(dp(16), dp(12), dp(16), dp(12));
                return view;
            }
        };
        apiProviderSpinner.setAdapter(adapter);
        apiProviderSpinner.setOnItemSelectedListener(
                new AdapterView.OnItemSelectedListener() {
                    @Override
                    public void onItemSelected(
                            AdapterView<?> parent,
                            View view,
                            int position,
                            long id
                    ) {
                        if (updatingApiProvider) {
                            return;
                        }
                        CloudApiProvider.Provider provider =
                                CloudApiProvider.PRESETS[position];
                        if (!provider.isCustom()) {
                            apiEndpointInput.setText(provider.endpoint);
                            apiModelInput.setText(provider.model);
                        }
                        setApiTechnicalFieldsEnabled(provider.isCustom());
                        updateCloudApiStatus();
                    }

                    @Override
                    public void onNothingSelected(AdapterView<?> parent) {
                    }
                }
        );
        apiKeyInput.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(
                    CharSequence s,
                    int start,
                    int count,
                    int after
            ) {
            }

            @Override
            public void onTextChanged(
                    CharSequence s,
                    int start,
                    int before,
                    int count
            ) {
            }

            @Override
            public void afterTextChanged(Editable editable) {
                CloudApiProvider.Provider detected =
                        CloudApiProvider.detectFromApiKey(editable.toString());
                if (!detected.isCustom()) {
                    updatingApiProvider = true;
                    apiProviderSpinner.setSelection(
                            CloudApiProvider.indexOf(detected.id),
                            false
                    );
                    updatingApiProvider = false;
                    apiEndpointInput.setText(detected.endpoint);
                    apiModelInput.setText(detected.model);
                    setApiTechnicalFieldsEnabled(false);
                }
            }
        });
    }

    private void setApiTechnicalFieldsEnabled(boolean enabled) {
        apiEndpointInput.setEnabled(enabled);
        apiModelInput.setEnabled(enabled);
        int textColor = getColor(enabled ? R.color.paper : R.color.paper_muted);
        apiEndpointInput.setTextColor(textColor);
        apiModelInput.setTextColor(textColor);
        apiEndpointInput.setAlpha(enabled ? 1f : 0.72f);
        apiModelInput.setAlpha(enabled ? 1f : 0.72f);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private void saveCloudApiSettings() {
        String endpoint = apiEndpointInput.getText().toString();
        String model = apiModelInput.getText().toString();
        String apiKey = apiKeyInput.getText().toString();
        boolean consent = apiConsentCheck.isChecked();
        boolean hasSavedKey = CloudApiSettings.hasApiKey(this);
        CloudApiProvider.Provider provider = (CloudApiProvider.Provider)
                apiProviderSpinner.getSelectedItem();
        if (!consent && !hasSavedKey) {
            cloudConsentChecked(false);
            return;
        }
        if (apiKey.trim().isEmpty() && !hasSavedKey) {
        UiText.toast(this, "请填写 API Key", Toast.LENGTH_SHORT);
            return;
        }
        try {
            CloudApiSettings.save(
                    this,
                    endpoint,
                    model,
                    apiKey,
                    consent,
                    provider == null ? "custom" : provider.id
            );
            apiKeyInput.setText("");
            apiKeyInput.clearFocus();
            updateCloudApiStatus();
            UiText.toast(
                    this,
                    consent ? "API 设置已保存" : "API 联网翻译已关闭",
                    Toast.LENGTH_SHORT
            );
        } catch (Exception error) {
            UiText.toast(
                    this,
                    "保存失败：" + error.getMessage(),
                    Toast.LENGTH_LONG
            );
        }
    }

    private boolean cloudConsentChecked(boolean consent) {
        if (consent) {
            return true;
        }
        UiText.toast(
                this,
                "请先确认屏幕文字会发送到你填写的 API 服务",
                Toast.LENGTH_LONG
        );
        return false;
    }

    private void updateCloudApiStatus() {
        if (CloudApiSettings.isConfigured(this)) {
            apiSettingsStatus.setText(
                    "API 翻译：已配置"
                            + "\nAPI Key 使用 Android Keystore 加密保存；"
                            + "悬浮窗选择“API”后才会联网。"
            );
            apiSaveButton.setText("更新 API 设置");
        } else if (CloudApiSettings.hasApiKey(this)
                && !CloudApiSettings.hasConsent(this)) {
            apiSettingsStatus.setText(
                    "API 翻译：需要同意云端隐私说明"
                            + "\n当前不会发送任何屏幕文字。"
            );
            apiSaveButton.setText("保存并同意使用 API");
        } else {
            apiSettingsStatus.setText(
                    "API 翻译：未配置"
                            + "\n支持 OpenAI 兼容的 Chat Completions 完整 URL。"
            );
            apiSaveButton.setText("保存 API 设置");
        }
    }

    private void toggleVipAutoHeal() {
        if (!AccessibilityHealer.hasPermission(this)) {
            UiText.toast(
                    this,
                    "此功能需要先通过 ADB 授权 WRITE_SECURE_SETTINGS",
                    Toast.LENGTH_LONG
            );
            return;
        }
        boolean next = !AccessibilityHealer.isAutoHealEnabled(this);
        AccessibilityHealer.setAutoHealEnabled(this, next);
        UiText.toast(
                this,
                next ? "无障碍自动恢复已开启" : "无障碍自动恢复已关闭",
                Toast.LENGTH_SHORT
        );
        if (next) {
            AccessibilityHealer.heal(this, true);
        }
        updateUi();
        statusText.postDelayed(this::updateUi, 800L);
    }

    private void requestScreenCapture() {
        startActivityForResult(
                projectionManager.createScreenCaptureIntent(),
                REQUEST_SCREEN_CAPTURE
        );
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode == REQUEST_OVERLAY_PERMISSION) {
            if (Settings.canDrawOverlays(this)) {
                prepareAndStart();
            } else {
                UiText.toast(this, "需要悬浮窗权限才能显示翻译面板", Toast.LENGTH_LONG);
            }
            updateUi();
            return;
        }

        if (requestCode == REQUEST_SCREEN_CAPTURE) {
            if (resultCode == RESULT_OK && data != null) {
                Intent serviceIntent = new Intent(this, FloatingTranslateService.class)
                        .putExtra(
                                FloatingTranslateService.EXTRA_INPUT_MODE,
                                FloatingTranslateService.INPUT_MODE_MEDIA_PROJECTION
                        )
                        .putExtra(FloatingTranslateService.EXTRA_RESULT_CODE, resultCode)
                        .putExtra(FloatingTranslateService.EXTRA_CAPTURE_DATA, data);
                startForegroundService(serviceIntent);
                UiText.toast(this, "屏幕捕获已授权，正在启动悬浮窗", Toast.LENGTH_SHORT);
            } else {
                UiText.toast(this, "未授权屏幕捕获，无法翻译", Toast.LENGTH_LONG);
            }
            updateUi();
        }
    }

    @Override
    public void onRequestPermissionsResult(
            int requestCode,
            String[] permissions,
            int[] grantResults
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_NOTIFICATIONS) {
            // A denied notification permission does not prevent the foreground service.
            if (pendingStartMode == FloatingTranslateService.INPUT_MODE_ACCESSIBILITY) {
                startAccessibilityMode();
            } else {
                requestScreenCapture();
            }
        }
    }

    private void updateUi() {
        boolean overlayGranted = Settings.canDrawOverlays(this);
        boolean running = FloatingTranslateService.isRunning();
        boolean accessibilityEnabled = isAccessibilityServiceEnabled();
        boolean accessibilityConnected = ScreenCaptureAccessibilityService.isConnected();
        boolean vipPermission = AccessibilityHealer.hasPermission(this);
        boolean vipAuto = AccessibilityHealer.isAutoHealEnabled(this);
        boolean vipEverEnabled = AccessibilityHealer.hasEverBeenEnabled(this);
        updateLocalModelUi();
        updateLargeModelUi();
        updateUltraModelUi();
        interfaceLanguageButton.setText(getString(
                R.string.interface_language_current,
                AppLanguage.nativeLabel(AppLanguage.get(this))
        ));

        permissionText.setText(
                overlayGranted
                        ? "悬浮窗权限：已授权"
                        : "悬浮窗权限：未授权（点击启动后会打开系统设置）"
        );

        String accessibilityConnection;
        if (!accessibilityEnabled) {
            accessibilityConnection = "未启用";
        } else if (!accessibilityConnected) {
            accessibilityConnection = "已启用但未连接";
        } else {
            accessibilityConnection = "已连接";
        }
        accessibilityStatus.setText(
                "HyperOS 兼容模式：" + accessibilityConnection
                        + "\n仅 Android 16/HyperOS 录屏被系统终止时使用。"
                        + (vipPermission && vipAuto
                        ? "\nADB 自动恢复开启：force-stop 后重新打开 App 会补回本应用自己的无障碍服务。"
                        : "\n强制停止 App 后无障碍会失效，需要重新开启。")
        );

        if (!vipPermission) {
            vipStatus.setText(
                    "ADB 辅助自动恢复：未授权"
                            + "\n可选用 ADB 给本应用授予 WRITE_SECURE_SETTINGS。"
            );
            vipButton.setText("查看自动恢复授权说明");
        } else if (!vipAuto) {
            vipStatus.setText(
                    "ADB 辅助自动恢复：已关闭"
                            + "\n不会自动改写无障碍列表；可随时重新开启。"
            );
            vipButton.setText("开启无障碍自动恢复");
        } else if (!vipEverEnabled) {
            vipStatus.setText(
                    "ADB 辅助自动恢复：已授权，等待首次启用"
                            + "\n先手动开启一次无障碍，之后 App 才会自动恢复本应用服务。"
            );
            vipButton.setText("关闭无障碍自动恢复");
        } else {
            vipStatus.setText(
                    "ADB 辅助自动恢复：已启用"
                            + "\n强制停止后重新打开 App 会自动恢复；只会管理浮屏译自己的无障碍服务。"
            );
            vipButton.setText("关闭无障碍自动恢复");
        }

        if (running) {
            statusText.setText("运行中。可通过悬浮窗“翻译”开关暂停或恢复捕获。");
            startButton.setText("悬浮翻译运行中");
            startButton.setEnabled(false);
            accessibilityButton.setEnabled(false);
            stopButton.setEnabled(true);
        } else {
            statusText.setText("未运行。启动时需要允许一次系统屏幕捕获。");
            startButton.setText("授权并启动悬浮翻译");
            startButton.setEnabled(true);
            accessibilityButton.setEnabled(true);
            if (accessibilityConnected) {
                accessibilityButton.setText("启动无障碍兼容模式");
            } else if (accessibilityEnabled) {
                accessibilityButton.setText("无障碍已启用但未连接（点击尝试恢复）");
            } else {
                accessibilityButton.setText("开启无障碍兼容模式");
            }
            stopButton.setEnabled(false);
        }
        localizeUi();
    }

    private void localizeUi() {
        UiText.localizeTree(findViewById(android.R.id.content));
    }

    private boolean isAccessibilityServiceEnabled() {
        AccessibilityManager manager =
                (AccessibilityManager) getSystemService(ACCESSIBILITY_SERVICE);
        if (manager == null) {
            return false;
        }
        List<AccessibilityServiceInfo> services = manager
                .getEnabledAccessibilityServiceList(
                        AccessibilityServiceInfo.FEEDBACK_ALL_MASK
                );
        for (AccessibilityServiceInfo info : services) {
            if (info.getResolveInfo() == null
                    || info.getResolveInfo().serviceInfo == null) {
                continue;
            }
            String packageName = info.getResolveInfo().serviceInfo.packageName;
            String className = info.getResolveInfo().serviceInfo.name;
            if (getPackageName().equals(packageName)
                    && className != null
                    && className.endsWith("ScreenCaptureAccessibilityService")) {
                return true;
            }
        }
        return false;
    }

    private String formatBytes(long bytes) {
        if (bytes >= 1024L * 1024L * 1024L) {
            return String.format(java.util.Locale.US, "%.2fGB",
                    bytes / (1024d * 1024d * 1024d));
        }
        return String.format(java.util.Locale.US, "%.1fMB",
                bytes / (1024d * 1024d));
    }
}
