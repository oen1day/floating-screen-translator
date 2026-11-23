package io.github.oen1day.floatingtranslator;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Point;
import android.graphics.Rect;
import android.graphics.RectF;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.Toast;

import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

public class FloatingTranslateService extends Service {
    public static final int INPUT_MODE_MEDIA_PROJECTION = 0;
    public static final int INPUT_MODE_ACCESSIBILITY = 1;
    public static final String EXTRA_RESULT_CODE = "screen_capture_result_code";
    public static final String EXTRA_CAPTURE_DATA = "screen_capture_data";
    public static final String EXTRA_INPUT_MODE = "screen_capture_input_mode";

    private static final String ACTION_STOP = "io.github.oen1day.floatingtranslator.STOP";
    private static final String TAG = "ScreenTranslator";
    private static final String STATE_PREFS = "floating_translate_state";
    private static final String KEY_HAS_REGION = "has_region";
    private static final String KEY_REGION_LEFT = "region_left";
    private static final String KEY_REGION_TOP = "region_top";
    private static final String KEY_REGION_RIGHT = "region_right";
    private static final String KEY_REGION_BOTTOM = "region_bottom";
    private static final String KEY_SOURCE_MODE = "source_mode";
    private static final String KEY_TARGET_LANGUAGE = "target_language";
    private static final String KEY_TRANSLATION_STRENGTH = "translation_strength";
    private static final int NOTIFICATION_ID = 42;
    private static final long CAPTURE_INTERVAL_MS = 1450L;
    private static final long LARGE_ENGINE_IDLE_RELEASE_MS = 120000L;
    private static final long DISPLAY_SETTLE_DELAY_MS = 650L;
    private static final long MEDIA_PROJECTION_FALLBACK_WINDOW_MS = 6000L;
    private static final int MAX_OCR_DIMENSION = 1600;

    private static volatile boolean running;

    public static boolean isRunning() {
        return running;
    }

    private WindowManager windowManager;
    private OverlayPanel panel;
    private RegionPickerOverlay regionPicker;
    private View regionOutline;
    private WindowManager.LayoutParams regionOutlineParams;
    private SharedPreferences statePrefs;
    private TextEngine textEngine;
    private TranslationEngine translationEngine;
    private LocalLlmEngine localLlmEngine;
    private LiteRtLlmEngine largeLlmEngine;
    private HeavyTranslationEngine ultraLlmEngine;
    private CloudApiTranslationEngine cloudApiEngine;

    private MediaProjection mediaProjection;
    private VirtualDisplay virtualDisplay;
    private ImageReader imageReader;
    private HandlerThread workerThread;
    private Handler workerHandler;
    private Handler mainHandler;
    private DisplayManager displayManager;
    private ScreenCaptureAccessibilityService accessibilityCaptureService;
    private int inputMode = INPUT_MODE_MEDIA_PROJECTION;

    private final AtomicBoolean ocrInFlight = new AtomicBoolean(false);
    private final AtomicBoolean restartingCapture = new AtomicBoolean(false);
    private long lastCaptureAt;
    private long lastAccessibilityReloadAt;
    private long lastDisplayChangeAt;
    private boolean ocrEdgeWarningActive;
    private volatile long suppressOcrUntil;
    private int captureWidth;
    private int captureHeight;
    private int captureDensity;
    private int captureRestartRetries;
    private volatile boolean translationEnabled = true;
    private String lastSourceText = "";
    private String pendingStableOcrText = "";
    private String pendingStableOcrKey = "";
    private long pendingStableOcrAt;
    private boolean ocrStabilityWaitActive;
    private String pendingText;
    private boolean translationInFlight;
    private int translationStrength = TranslationStrength.FAST;
    private long lastStrengthSwitchAt;
    private int strengthSwitchBurst;
    private boolean translationReadyForCurrentDirection;
    private int translationGeneration;
    private int localRefinementGeneration;
    private String lastContextText = "";
    private String lastDraftSourceText = "";
    private String lastDraftTranslation = "";
    private String pendingLocalSourceText;
    private String pendingLocalDraftTranslation;
    private String pendingLocalContextText;
    private String pendingLocalSourceLanguage;
    private String pendingLocalTargetLanguage;
    private int pendingLocalStrength;
    private boolean localRefinementInFlight;
    private RectF normalizedRegion;
    private String sourceMode = TranslationEngine.MODE_AUTO;
    private String targetLanguage = TranslationEngine.LANGUAGE_CHINESE;
    private int accessibilityCaptureErrorCount;
    private long accessibilityDisconnectedSince;
    private long lastAccessibilityOverlayRebuildAt;
    private Boolean accessibilityWindowCapture;
    private boolean applicationOverlayFallback;
    private final Runnable releaseLargeEngineTask = () -> {
        if (largeLlmEngine != null) {
            largeLlmEngine.releaseEngine();
            Log.i(TAG, "Scheduled 3B idle release");
        }
    };

    private final Runnable accessibilityCaptureLoop = new Runnable() {
        @Override
        public void run() {
            if (!running
                    || inputMode != INPUT_MODE_ACCESSIBILITY
                    || !translationEnabled
                    || !isCaptureSourceReady()) {
                return;
            }

            ScreenCaptureAccessibilityService captureService =
                    ScreenCaptureAccessibilityService.getInstance();
            if (captureService == null) {
                accessibilityCaptureService = null;
                long now = System.currentTimeMillis();
                if (accessibilityDisconnectedSince == 0L) {
                    accessibilityDisconnectedSince = now;
                }
                if (now - accessibilityDisconnectedSince < 2500L) {
                    scheduleAccessibilityCapture(300L);
                } else {
                    showStatusOnMain("无障碍已断开");
                    stopSelf();
                }
                return;
            }
            accessibilityDisconnectedSince = 0L;
            if (accessibilityCaptureService != captureService) {
                accessibilityCaptureService = captureService;
                mainHandler.post(
                        FloatingTranslateService.this::rebuildAccessibilityOverlay
                );
            }

            long now = System.currentTimeMillis();
            if (ocrInFlight.get()) {
                scheduleAccessibilityCapture(180L);
                return;
            }
            if (now < suppressOcrUntil || now - lastCaptureAt < CAPTURE_INTERVAL_MS) {
                long wait = Math.max(80L, CAPTURE_INTERVAL_MS - (now - lastCaptureAt));
                scheduleAccessibilityCapture(wait);
                return;
            }

            lastCaptureAt = now;
            ocrInFlight.set(true);
            try {
                captureService.captureSoftwareBitmap(
                        command -> workerHandler.post(command),
                        new ScreenCaptureAccessibilityService.FrameCallback() {
                            @Override
                            public void onBitmap(
                                    Bitmap bitmap,
                                    Rect captureBounds,
                                    boolean overlaysExcluded
                            ) {
                                if (!running
                                        || inputMode != INPUT_MODE_ACCESSIBILITY
                                        || !translationEnabled) {
                                    if (!bitmap.isRecycled()) {
                                        bitmap.recycle();
                                    }
                                    ocrInFlight.set(false);
                                    return;
                                }
                                boolean recovered = accessibilityCaptureErrorCount > 0;
                                accessibilityCaptureErrorCount = 0;
                                if (accessibilityWindowCapture == null
                                        || accessibilityWindowCapture != overlaysExcluded) {
                                    accessibilityWindowCapture = overlaysExcluded;
                                    Log.i(
                                            TAG,
                                            overlaysExcluded
                                                    ? "Accessibility frame: target window only"
                                                    : "Accessibility frame: full screen fallback"
                                    );
                                }
                                if (recovered) {
                                    showStatusOnMain(
                                            translationReadyForCurrentDirection
                                                    ? "运行中"
                                                    : "准备翻译模型"
                                    );
                                }
                                try {
                                    processBitmap(
                                            bitmap,
                                            captureBounds,
                                            overlaysExcluded
                                    );
                                } catch (RuntimeException error) {
                                    if (!bitmap.isRecycled()) {
                                        bitmap.recycle();
                                    }
                                    ocrInFlight.set(false);
                                    showStatusOnMain("兼容截图处理失败");
                                    Log.w(TAG, "Accessibility bitmap processing failed", error);
                                }
                                scheduleAccessibilityCapture(CAPTURE_INTERVAL_MS);
                            }

                            @Override
                            public void onError(int errorCode, Exception error) {
                                ocrInFlight.set(false);
                                accessibilityCaptureErrorCount++;
                                Log.w(
                                        TAG,
                                        "Accessibility screenshot failed: " + errorCode,
                                        error
                                );
                                if (accessibilityCaptureErrorCount == 3) {
                                    showStatusOnMain("兼容截图重试");
                                    recoverAccessibilityCaptureIfNeeded();
                                }
                                scheduleAccessibilityCapture(700L);
                            }
                        }
                );
            } catch (RuntimeException error) {
                ocrInFlight.set(false);
                accessibilityCaptureErrorCount++;
                Log.w(TAG, "Accessibility screenshot request failed", error);
                if (accessibilityCaptureErrorCount == 3) {
                    showStatusOnMain("兼容截图重试");
                    recoverAccessibilityCaptureIfNeeded();
                }
                scheduleAccessibilityCapture(700L);
            }
        }
    };

    private void recoverAccessibilityCaptureIfNeeded() {
        long now = System.currentTimeMillis();
        if (now - lastAccessibilityReloadAt < 12000L) {
            return;
        }
        lastAccessibilityReloadAt = now;
        AccessibilityHealer.Result result = AccessibilityHealer.heal(this, true);
        if (result == AccessibilityHealer.Result.RELOADING
                || result == AccessibilityHealer.Result.REPAIRED) {
            showStatusOnMain("正在恢复兼容截图");
        }
    }

    private final Runnable displaySettledTask = () -> {
        if (!isCaptureSourceReady()) {
            return;
        }
        Point size = getScreenSize();
        if (inputMode == INPUT_MODE_MEDIA_PROJECTION
                && size.x == captureWidth
                && size.y == captureHeight) {
            return;
        }
        if (panel != null) {
            try {
                if (inputMode == INPUT_MODE_ACCESSIBILITY) {
                    panel.clampToScreen();
                } else {
                    panel.refreshAfterDisplayChange();
                }
            } catch (RuntimeException error) {
                if (inputMode == INPUT_MODE_MEDIA_PROJECTION) {
                    restorePanelAfterPicker("旋转后恢复面板", false);
                } else {
                    Log.w(TAG, "Accessibility overlay resize after display change failed", error);
                }
            }
        }
        updateRegionOutline();
        lastSourceText = "";
        lastCaptureAt = 0;
        suppressOcrUntil = System.currentTimeMillis() + 2000L;
        if (inputMode == INPUT_MODE_MEDIA_PROJECTION) {
            restartCapture();
        } else {
            scheduleAccessibilityCapture(650L);
        }
    };

    private final Runnable overlayWatchdog = new Runnable() {
        @Override
        public void run() {
            if (isCaptureSourceReady()
                    && panel != null
                    && regionPicker == null
                    && !panel.isActuallyAttached()) {
                if (inputMode == INPUT_MODE_ACCESSIBILITY) {
                    rebuildAccessibilityOverlay();
                } else if (panel.ensureAttached()) {
                    updateRegionOutline();
                }
            }
            mainHandler.postDelayed(this, 1000L);
        }
    };

    private final DisplayManager.DisplayListener displayListener =
            new DisplayManager.DisplayListener() {
                @Override
                public void onDisplayAdded(int displayId) {
                }

                @Override
                public void onDisplayRemoved(int displayId) {
                }

                @Override
                public void onDisplayChanged(int displayId) {
                    if (displayId != 0 || !isCaptureSourceReady()) {
                        return;
                    }
                    lastDisplayChangeAt = System.currentTimeMillis();
                    mainHandler.removeCallbacks(displaySettledTask);
                    mainHandler.postDelayed(
                            displaySettledTask,
                            DISPLAY_SETTLE_DELAY_MS
                    );
                }
            };

    @Override
    public void onCreate() {
        super.onCreate();
        running = true;
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        mainHandler = new Handler(Looper.getMainLooper());
        workerThread = new HandlerThread("screen-capture-worker");
        workerThread.start();
        workerHandler = new Handler(workerThread.getLooper());
        textEngine = new TextEngine();
        translationEngine = new TranslationEngine();
        localLlmEngine = new LocalLlmEngine();
        largeLlmEngine = new LiteRtLlmEngine();
        ultraLlmEngine = new HeavyTranslationEngine();
        cloudApiEngine = new CloudApiTranslationEngine();
        statePrefs = getSharedPreferences(STATE_PREFS, MODE_PRIVATE);
        normalizedRegion = loadRegion();
        sourceMode = normalizeSourceMode(
                statePrefs.getString(KEY_SOURCE_MODE, TranslationEngine.MODE_AUTO)
        );
        targetLanguage = normalizeTargetLanguage(
                statePrefs.getString(
                        KEY_TARGET_LANGUAGE,
                        TranslationEngine.LANGUAGE_CHINESE
                )
        );
        translationStrength = TranslationStrength.clamp(
                statePrefs.getInt(
                        KEY_TRANSLATION_STRENGTH,
                        TranslationStrength.FAST
                )
        );
        if (translationStrength == TranslationStrength.ULTRA
                && !offersUltraForDirection(sourceMode, targetLanguage)) {
            translationStrength = TranslationStrength.PRECISE;
            statePrefs.edit()
                    .putInt(KEY_TRANSLATION_STRENGTH, translationStrength)
                    .apply();
        }
        if (translationStrength == TranslationStrength.PRECISE
                && LargeLocalModelManager.isReady(this)) {
            largeLlmEngine.warmUp(this, new LiteRtLlmEngine.WarmupCallback() {
                @Override
                public void onReady() {
                    Log.i(TAG, "3B prewarmed from persisted precise mode");
                }

                @Override
                public void onError(Exception error) {
                    Log.w(TAG, "3B persisted warm-up failed", error);
                }
            });
        }
        displayManager = (DisplayManager) getSystemService(DISPLAY_SERVICE);
        displayManager.registerDisplayListener(displayListener, mainHandler);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            stopSelf();
            return START_NOT_STICKY;
        }

        int requestedMode = intent == null
                ? inputMode
                : intent.getIntExtra(EXTRA_INPUT_MODE, INPUT_MODE_MEDIA_PROJECTION);
        inputMode = requestedMode == INPUT_MODE_ACCESSIBILITY
                ? INPUT_MODE_ACCESSIBILITY
                : INPUT_MODE_MEDIA_PROJECTION;
        startAsForeground(inputMode);

        if (inputMode == INPUT_MODE_ACCESSIBILITY) {
            if (!prepareAccessibilityMode()) {
                stopSelf();
                return START_NOT_STICKY;
            }
            startAccessibilityPanel(0);
            return START_NOT_STICKY;
        }

        if (mediaProjection != null) {
            if (panel != null) {
                panel.show();
            }
            return START_NOT_STICKY;
        }

        int resultCode = intent == null
                ? 0
                : intent.getIntExtra(EXTRA_RESULT_CODE, 0);
        Intent captureData;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            captureData = intent == null
                    ? null
                    : intent.getParcelableExtra(EXTRA_CAPTURE_DATA, Intent.class);
        } else {
            captureData = intent == null
                    ? null
                    : intent.getParcelableExtra(EXTRA_CAPTURE_DATA);
        }

        if (resultCode == 0 || captureData == null) {
            UiText.toast(this, "缺少屏幕捕获授权，服务未启动", Toast.LENGTH_LONG);
            stopSelf();
            return START_NOT_STICKY;
        }

        if (!android.provider.Settings.canDrawOverlays(this)) {
            UiText.toast(this, "没有悬浮窗权限，服务未启动", Toast.LENGTH_LONG);
            stopSelf();
            return START_NOT_STICKY;
        }

        try {
            MediaProjectionManager manager =
                    (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
            mediaProjection = manager.getMediaProjection(resultCode, captureData);
            mediaProjection.registerCallback(new MediaProjection.Callback() {
                @Override
                public void onStop() {
                    mainHandler.post(
                            FloatingTranslateService.this::handleProjectionStopped
                    );
                }
            }, mainHandler);
            createPanel();
            createCapture();
        } catch (Exception error) {
            UiText.toast(
                    this,
                    "启动屏幕捕获失败：" + safeMessage(error),
                    Toast.LENGTH_LONG
            );
            stopSelf();
        }
        return START_NOT_STICKY;
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        lastDisplayChangeAt = System.currentTimeMillis();
    }

    private void handleProjectionStopped() {
        if (!running) {
            return;
        }
        long now = System.currentTimeMillis();
        boolean stoppedDuringDisplayChange =
                now - lastDisplayChangeAt <= MEDIA_PROJECTION_FALLBACK_WINDOW_MS;
        boolean accessibilityAvailable =
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
                        && ScreenCaptureAccessibilityService.getInstance() != null;
        if (inputMode == INPUT_MODE_MEDIA_PROJECTION
                && stoppedDuringDisplayChange
                && accessibilityAvailable) {
            switchToAccessibilityAfterProjectionStop();
            return;
        }
        stopSelf();
    }

    private void switchToAccessibilityAfterProjectionStop() {
        Log.w(TAG, "HyperOS stopped MediaProjection during display change; switching capture mode");
        inputMode = INPUT_MODE_ACCESSIBILITY;
        releaseCapture();
        mediaProjection = null;
        startAsForeground(INPUT_MODE_ACCESSIBILITY);

        accessibilityCaptureService =
                ScreenCaptureAccessibilityService.getInstance();
        if (accessibilityCaptureService == null
                || accessibilityCaptureService.getOverlayWindowManager() == null) {
            stopSelf();
            return;
        }

        applicationOverlayFallback = false;
        lastAccessibilityOverlayRebuildAt = 0L;
        if (panel != null) {
            panel.destroy();
            panel = null;
        }
        removeRegionOutline();
        windowManager = accessibilityCaptureService.getOverlayWindowManager();
        lastSourceText = "";
        lastCaptureAt = 0L;
        suppressOcrUntil = System.currentTimeMillis() + 650L;
        UiText.toast(
                this,
                "旋转后已自动切换到无障碍兼容模式",
                Toast.LENGTH_SHORT
        );
        startAccessibilityPanel(0);
    }

    private boolean prepareAccessibilityMode() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            UiText.toast(
                    this,
                    "无障碍截图需要 Android 11 或更高版本",
                    Toast.LENGTH_LONG
            );
            return false;
        }
        accessibilityCaptureService = ScreenCaptureAccessibilityService.getInstance();
        if (accessibilityCaptureService == null) {
            UiText.toast(
                    this,
                    "无障碍截图服务未连接，兼容模式未启动",
                    Toast.LENGTH_LONG
            );
            return false;
        }
        WindowManager accessibilityWindowManager =
                accessibilityCaptureService.getOverlayWindowManager();
        if (accessibilityWindowManager == null) {
            UiText.toast(
                    this,
                    "无法取得无障碍悬浮窗管理器",
                    Toast.LENGTH_LONG
            );
            return false;
        }
        windowManager = accessibilityWindowManager;
        return true;
    }

    private void rebuildAccessibilityOverlay() {
        if (!running
                || inputMode != INPUT_MODE_ACCESSIBILITY
                || regionPicker != null) {
            return;
        }
        ScreenCaptureAccessibilityService captureService =
                ScreenCaptureAccessibilityService.getInstance();
        if (captureService == null) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now - lastAccessibilityOverlayRebuildAt < 900L) {
            return;
        }
        lastAccessibilityOverlayRebuildAt = now;

        if (panel != null) {
            panel.destroy();
            panel = null;
        }
        removeRegionOutline();
        accessibilityCaptureService = captureService;
        windowManager = applicationOverlayFallback
                ? (WindowManager) getSystemService(WINDOW_SERVICE)
                : captureService.getOverlayWindowManager();
        Log.i(TAG, "Rebuilding accessibility overlay after service rebind");
        startAccessibilityPanel(0);
    }

    private void startAccessibilityPanel(int attempt) {
        if (!running || inputMode != INPUT_MODE_ACCESSIBILITY) {
            return;
        }
        ScreenCaptureAccessibilityService captureService =
                ScreenCaptureAccessibilityService.getInstance();
        if (captureService == null) {
            if (attempt < 20) {
                mainHandler.postDelayed(
                        () -> startAccessibilityPanel(attempt + 1),
                        500L
                );
            }
            return;
        }
        accessibilityCaptureService = captureService;
        if (attempt >= 3 && !applicationOverlayFallback) {
            applicationOverlayFallback = true;
            if (panel != null) {
                panel.destroy();
                panel = null;
            }
            removeRegionOutline();
            Log.w(TAG, "Falling back to application overlay window");
        }

        WindowManager manager = applicationOverlayFallback
                ? (WindowManager) getSystemService(WINDOW_SERVICE)
                : captureService.getOverlayWindowManager();
        if (manager == null) {
            if (attempt < 20) {
                mainHandler.postDelayed(
                        () -> startAccessibilityPanel(attempt + 1),
                        500L
                );
            }
            return;
        }
        windowManager = manager;

        if (panel == null) {
            try {
                createPanel();
            } catch (RuntimeException error) {
                Log.w(TAG, "Accessibility overlay not ready; retrying", error);
                if (panel != null) {
                    panel.destroy();
                    panel = null;
                }
                if (attempt < 30) {
                    mainHandler.postDelayed(
                            () -> startAccessibilityPanel(attempt + 1),
                            500L
                    );
                } else {
                    showStatusOnMain("无障碍浮窗启动失败");
                    stopSelf();
                }
                return;
            }
        } else if (!panel.isActuallyAttached()) {
            try {
                panel.show();
            } catch (RuntimeException error) {
                panel.destroy();
                panel = null;
                if (attempt < 30) {
                    mainHandler.postDelayed(
                            () -> startAccessibilityPanel(attempt + 1),
                            500L
                    );
                }
                return;
            }
        }
        if (panel != null && panel.isActuallyAttached()) {
            scheduleAccessibilityCapture(80L);
        } else if (attempt < 30) {
            mainHandler.postDelayed(
                    () -> startAccessibilityPanel(attempt + 1),
                    500L
            );
        }
    }

    private void startAsForeground(int mode) {
        String channelId = "screen_translator";
        NotificationManager manager =
                (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        NotificationChannel channel = new NotificationChannel(
                channelId,
                getString(R.string.notification_channel),
                NotificationManager.IMPORTANCE_LOW
        );
        channel.setDescription("保持悬浮翻译的屏幕捕获服务运行");
        manager.createNotificationChannel(channel);

        Intent openIntent = new Intent(this, MainActivity.class);
        PendingIntent openPending = PendingIntent.getActivity(
                this,
                1,
                openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );
        Intent stopIntent = new Intent(this, FloatingTranslateService.class)
                .setAction(ACTION_STOP);
        PendingIntent stopPending = PendingIntent.getService(
                this,
                2,
                stopIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        Notification notification = new Notification.Builder(this, channelId)
                .setSmallIcon(R.drawable.ic_stat_translate)
                .setContentTitle(getString(R.string.notification_title))
                .setContentText(
                        mode == INPUT_MODE_ACCESSIBILITY
                                ? "无障碍兼容截图模式正在运行"
                                : getString(R.string.notification_text)
                )
                .setContentIntent(openPending)
                .setOngoing(true)
                .setCategory(Notification.CATEGORY_SERVICE)
                .addAction(
                        R.drawable.ic_stat_translate,
                        "停止",
                        stopPending
                )
                .build();

        if (mode == INPUT_MODE_ACCESSIBILITY
                && Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            );
        } else if (mode == INPUT_MODE_MEDIA_PROJECTION
                && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            );
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
    }

    private void createPanel() {
        Log.i(
                TAG,
                "createPanel mode=" + inputMode
                        + " windowType=" + overlayWindowType()
                        + " wm=" + (windowManager == null
                        ? "null"
                        : windowManager.getClass().getName())
        );
        panel = new OverlayPanel(
                overlayContext(),
                windowManager,
                overlayWindowType(),
                sourceMode,
                targetLanguage,
                translationStrength,
                new OverlayPanel.Listener() {
            @Override
            public void onTranslationChanged(boolean enabled) {
                translationEnabled = enabled;
                if (enabled) {
                    panel.setStatus(
                            translationReadyForCurrentDirection ? "运行中" : "准备翻译模型"
                    );
                    lastSourceText = "";
                    if (inputMode == INPUT_MODE_ACCESSIBILITY) {
                        scheduleAccessibilityCapture(80L);
                    } else {
                        restartCapture();
                    }
                } else {
                    panel.setStatus("已暂停");
                    pendingText = null;
                    translationGeneration++;
                    localRefinementGeneration++;
                    clearPendingStableOcr();
                    pendingLocalSourceText = null;
                    pendingLocalSourceLanguage = null;
                    pendingLocalTargetLanguage = null;
                }
                updateRegionOutline();
            }

            @Override
            public void onLanguagesChanged(String newSourceMode, String newTargetLanguage) {
                sourceMode = normalizeSourceMode(newSourceMode);
                targetLanguage = normalizeTargetLanguage(newTargetLanguage);
                boolean dropUnavailableUltra =
                        translationStrength == TranslationStrength.ULTRA
                                && !offersUltraForDirection(
                                sourceMode,
                                targetLanguage
                        );
                statePrefs.edit()
                        .putString(KEY_SOURCE_MODE, sourceMode)
                        .putString(KEY_TARGET_LANGUAGE, targetLanguage)
                        .apply();
                lastSourceText = "";
                lastCaptureAt = 0;
                suppressOcrUntil = System.currentTimeMillis() + 600L;
                pendingText = null;
                translationGeneration++;
                localRefinementGeneration++;
                clearPendingStableOcr();
                pendingLocalSourceText = null;
                pendingLocalSourceLanguage = null;
                pendingLocalTargetLanguage = null;
                translationReadyForCurrentDirection = false;
                if (dropUnavailableUltra) {
                    changeTranslationStrength(TranslationStrength.PRECISE);
                    panel.setStatus("当前方向无日→中专项，已切换精细");
                } else {
                    panel.setStatus(directionLabel());
                }
            }

            @Override
            public void onTranslationStrengthChanged(int strength) {
                changeTranslationStrength(strength);
            }

            @Override
            public void onFullScreenRequested() {
                normalizedRegion = null;
                ocrEdgeWarningActive = false;
                saveRegion();
                panel.setRegionLabel("全屏");
                panel.setStatus("全屏模式");
                lastSourceText = "";
                lastCaptureAt = 0;
                updateRegionOutline();
            }

            @Override
            public void onRegionRequested() {
                openRegionPicker();
            }

            @Override
            public void onStopRequested() {
                stopSelf();
            }

            @Override
            public void onCollapsedChanged(boolean collapsed) {
                if (collapsed) {
                    removeRegionOutline();
                } else {
                    updateRegionOutline();
                }
            }
        });
        panel.setTranslationChecked(true);
        panel.setRegionLabel(normalizedRegion == null ? "全屏" : "区域模式");
        panel.setStatus(directionLabel());
        panel.show();
        updateRegionOutline();
        mainHandler.removeCallbacks(overlayWatchdog);
        mainHandler.postDelayed(overlayWatchdog, 1000L);
    }

    private void changeTranslationStrength(int strength) {
        int requested = TranslationStrength.clamp(strength);
        if (requested == TranslationStrength.ULTRA
                && !offersUltraForDirection(sourceMode, targetLanguage)) {
            showStatusOnMain("日→中专项仅支持日文翻译成简体中文");
            requested = TranslationStrength.PRECISE;
        }
        if (requested == translationStrength) {
            return;
        }

        long now = System.currentTimeMillis();
        if (now - lastStrengthSwitchAt < 1500L) {
            strengthSwitchBurst++;
        } else {
            strengthSwitchBurst = 1;
        }
        lastStrengthSwitchAt = now;

        boolean hadPending = pendingText != null || pendingLocalSourceText != null;
        Log.i(
                TAG,
                "Strength switch to " + TranslationStrength.label(requested)
                        + " busy=" + (translationInFlight || localRefinementInFlight)
                        + " hadPending=" + hadPending
                        + " burst=" + strengthSwitchBurst
        );
        translationStrength = requested;
        if (localRefinementInFlight) {
            abortActiveLocalRefinement();
        }
        if (requested == TranslationStrength.PRECISE
                && LargeLocalModelManager.isReady(this)) {
            mainHandler.removeCallbacks(releaseLargeEngineTask);
            largeLlmEngine.warmUp(this, new LiteRtLlmEngine.WarmupCallback() {
                @Override
                public void onReady() {
                    if (translationEnabled
                            && translationStrength == TranslationStrength.PRECISE) {
                        showStatusOnMain("3B 已预热");
                    }
                }

                @Override
                public void onError(Exception error) {
                    Log.w(TAG, "3B warm-up failed", error);
                }
            });
        } else if (requested != TranslationStrength.ULTRA) {
            largeLlmEngine.cancelCurrent();
            mainHandler.removeCallbacks(releaseLargeEngineTask);
            mainHandler.postDelayed(
                    releaseLargeEngineTask,
                    LARGE_ENGINE_IDLE_RELEASE_MS
            );
        }
        if (requested != TranslationStrength.ULTRA) {
            ultraLlmEngine.releaseEngine();
        } else {
            localLlmEngine.releaseEngine(null);
            mainHandler.removeCallbacks(releaseLargeEngineTask);
            largeLlmEngine.releaseEngine();
        }
        if (requested != TranslationStrength.API) {
            cloudApiEngine.cancelCurrent();
        }
        statePrefs.edit()
                .putInt(KEY_TRANSLATION_STRENGTH, translationStrength)
                .apply();
        translationGeneration++;
        localRefinementGeneration++;
        pendingText = null;
        clearPendingStableOcr();
        pendingLocalSourceText = null;
        pendingLocalDraftTranslation = null;
        pendingLocalContextText = null;
        pendingLocalSourceLanguage = null;
        pendingLocalTargetLanguage = null;

        panel.setStatus(getString(
                R.string.strength_current,
                TranslationStrength.uiLabel(this, translationStrength)
        ));
        if (!translationEnabled || lastSourceText.isEmpty()) {
            return;
        }
        if (translationInFlight) {
            pendingText = lastSourceText;
        } else {
            requestTranslation(lastSourceText);
        }
    }

    private void createCapture() {
        Point size = getScreenSize();
        DisplayMetrics metrics = getResources().getDisplayMetrics();
        captureWidth = size.x;
        captureHeight = size.y;
        captureDensity = metrics.densityDpi;

        imageReader = ImageReader.newInstance(
                captureWidth,
                captureHeight,
                PixelFormat.RGBA_8888,
                2
        );
        imageReader.setOnImageAvailableListener(this::onImageAvailable, workerHandler);
        virtualDisplay = mediaProjection.createVirtualDisplay(
                "ScreenTranslatorCapture",
                captureWidth,
                captureHeight,
                captureDensity,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader.getSurface(),
                null,
                workerHandler
        );
        captureRestartRetries = 0;
        lastCaptureAt = 0;
    }

    private void restartCapture() {
        if (inputMode != INPUT_MODE_MEDIA_PROJECTION) {
            return;
        }
        if (!restartingCapture.compareAndSet(false, true)) {
            return;
        }
        workerHandler.postDelayed(() -> {
            try {
                releaseCapture();
                if (mediaProjection != null && translationEnabled) {
                    createCapture();
                }
            } catch (RuntimeException error) {
                if (captureRestartRetries < 2 && mediaProjection != null) {
                    captureRestartRetries++;
                    workerHandler.postDelayed(this::restartCapture, 800L);
                    showStatusOnMain("旋转后正在恢复捕获");
                } else {
                    showStatusOnMain("旋转后捕获恢复失败");
                }
            } finally {
                restartingCapture.set(false);
            }
        }, 120L);
    }

    private void onImageAvailable(ImageReader reader) {
        Image image;
        try {
            image = reader.acquireLatestImage();
        } catch (IllegalStateException error) {
            return;
        }
        if (image == null) {
            return;
        }

        long now = System.currentTimeMillis();
        if (!translationEnabled
                || ocrInFlight.get()
                || now < suppressOcrUntil
                || now - lastCaptureAt < CAPTURE_INTERVAL_MS) {
            image.close();
            return;
        }

        lastCaptureAt = now;
        ocrInFlight.set(true);
        try {
            Bitmap fullBitmap = imageToBitmap(image);
            image.close();
            if (fullBitmap == null) {
                ocrInFlight.set(false);
                return;
            }
            processBitmap(fullBitmap);
        } catch (Exception error) {
            ocrInFlight.set(false);
            image.close();
            showStatusOnMain("截图处理失败");
        }
    }

    private Bitmap imageToBitmap(Image image) {
        Image.Plane plane = image.getPlanes()[0];
        ByteBuffer buffer = plane.getBuffer();
        int pixelStride = plane.getPixelStride();
        int rowStride = plane.getRowStride();
        int rowPadding = rowStride - pixelStride * image.getWidth();
        int paddedWidth = image.getWidth() + Math.max(0, rowPadding / pixelStride);

        Bitmap padded = Bitmap.createBitmap(paddedWidth, image.getHeight(), Bitmap.Config.ARGB_8888);
        padded.copyPixelsFromBuffer(buffer);
        if (paddedWidth == image.getWidth()) {
            return padded;
        }
        Bitmap cropped = Bitmap.createBitmap(
                padded,
                0,
                0,
                image.getWidth(),
                image.getHeight()
        );
        padded.recycle();
        return cropped;
    }

    private void processBitmap(Bitmap fullBitmap) {
        processBitmap(
                fullBitmap,
                new Rect(0, 0, fullBitmap.getWidth(), fullBitmap.getHeight()),
                false
        );
    }

    private void processBitmap(
            Bitmap screenshot,
            Rect captureBounds,
            boolean overlaysExcluded
    ) {
        Rect fullImageBounds = new Rect(
                0,
                0,
                screenshot.getWidth(),
                screenshot.getHeight()
        );
        Rect screenshotBounds = captureBounds == null || captureBounds.isEmpty()
                ? new Rect(fullImageBounds)
                : new Rect(captureBounds);
        Rect cropScreenRect = new Rect(screenshotBounds);
        if (normalizedRegion != null) {
            Point screen = getScreenSize();
            Rect selectedScreen = new Rect(
                    Math.round(normalizedRegion.left * screen.x),
                    Math.round(normalizedRegion.top * screen.y),
                    Math.round(normalizedRegion.right * screen.x),
                    Math.round(normalizedRegion.bottom * screen.y)
            );
            if (!cropScreenRect.intersect(selectedScreen)) {
                cropScreenRect.set(screenshotBounds);
            }
        }

        float imageScaleX = screenshot.getWidth()
                / (float) Math.max(1, screenshotBounds.width());
        float imageScaleY = screenshot.getHeight()
                / (float) Math.max(1, screenshotBounds.height());
        Rect cropRect = new Rect(
                clamp(
                        Math.round(
                                (cropScreenRect.left - screenshotBounds.left) * imageScaleX
                        ),
                        0,
                        screenshot.getWidth() - 2
                ),
                clamp(
                        Math.round(
                                (cropScreenRect.top - screenshotBounds.top) * imageScaleY
                        ),
                        0,
                        screenshot.getHeight() - 2
                ),
                clamp(
                        Math.round(
                                (cropScreenRect.right - screenshotBounds.left) * imageScaleX
                        ),
                        2,
                        screenshot.getWidth()
                ),
                clamp(
                        Math.round(
                                (cropScreenRect.bottom - screenshotBounds.top) * imageScaleY
                        ),
                        2,
                        screenshot.getHeight()
                )
        );
        if (cropRect.width() < 2 || cropRect.height() < 2) {
            cropRect.set(fullImageBounds);
            cropScreenRect.set(screenshotBounds);
        }
        float edgeGuardPx = 2f * getResources().getDisplayMetrics().density;
        final boolean ocrEdgeRisk = normalizedRegion != null
                && (cropScreenRect.left - screenshotBounds.left <= edgeGuardPx
                || screenshotBounds.right - cropScreenRect.right <= edgeGuardPx);

        Bitmap regionBitmap;
        if (cropRect.equals(fullImageBounds)) {
            regionBitmap = screenshot;
        } else {
            regionBitmap = Bitmap.createBitmap(
                    screenshot,
                    cropRect.left,
                    cropRect.top,
                    cropRect.width(),
                    cropRect.height()
            );
            screenshot.recycle();
        }

        float scale = Math.min(
                1f,
                MAX_OCR_DIMENSION / (float) Math.max(regionBitmap.getWidth(), regionBitmap.getHeight())
        );
        Bitmap ocrBitmap = regionBitmap;
        if (scale < 1f) {
            int scaledWidth = Math.max(1, Math.round(regionBitmap.getWidth() * scale));
            int scaledHeight = Math.max(1, Math.round(regionBitmap.getHeight() * scale));
            ocrBitmap = Bitmap.createScaledBitmap(
                    regionBitmap,
                    scaledWidth,
                    scaledHeight,
                    true
            );
            if (ocrBitmap != regionBitmap) {
                regionBitmap.recycle();
            }
        }

        Rect excludedPanel = null;
        if (!overlaysExcluded && panel != null && panel.isShowing()) {
            Rect window = panel.getWindowBounds();
            Rect overlap = new Rect(
                    Math.max(cropScreenRect.left, window.left),
                    Math.max(cropScreenRect.top, window.top),
                    Math.min(cropScreenRect.right, window.right),
                    Math.min(cropScreenRect.bottom, window.bottom)
            );
            if (!overlap.isEmpty()) {
                float scaleX = ocrBitmap.getWidth()
                        / (float) Math.max(1, cropScreenRect.width());
                float scaleY = ocrBitmap.getHeight()
                        / (float) Math.max(1, cropScreenRect.height());
                excludedPanel = new Rect(
                        Math.round((overlap.left - cropScreenRect.left) * scaleX),
                        Math.round((overlap.top - cropScreenRect.top) * scaleY),
                        Math.round((overlap.right - cropScreenRect.left) * scaleX),
                        Math.round((overlap.bottom - cropScreenRect.top) * scaleY)
                );
            }
        }

        textEngine.recognize(
                ocrBitmap,
                excludedPanel,
                sourceMode,
                translationStrength,
                new TextEngine.Callback() {
            @Override
            public void onResult(String text) {
                ocrInFlight.set(false);
                if (!translationEnabled) {
                    return;
                }
                String normalized = normalizeText(text);
                Log.i(
                        TAG,
                        "OCR strength=" + TranslationStrength.label(translationStrength)
                                + " text=" + normalized.replace('\n', '|')
                );
                if (normalized.isEmpty()) {
                    pendingStableOcrText = "";
                    pendingStableOcrKey = "";
                    pendingStableOcrAt = 0L;
                    ocrStabilityWaitActive = false;
                    if (!lastSourceText.isEmpty()) {
                        lastSourceText = "";
                        showStatusOnMain("未识别到文字");
                    }
                    return;
                }
                if (ocrEdgeRisk) {
                    Log.w(TAG, "OCR edge risk: selected region touches screen edge");
                    if (!ocrEdgeWarningActive) {
                        ocrEdgeWarningActive = true;
                        mainHandler.post(() -> UiText.toast(
                                FloatingTranslateService.this,
                                "选区贴近屏幕边缘，文字可能被裁切",
                                Toast.LENGTH_SHORT
                        ));
                    }
                } else {
                    ocrEdgeWarningActive = false;
                }
                if (normalized.equals(lastSourceText)) {
                    return;
                }
                if (localRefinementInFlight) {
                    abortActiveLocalRefinement();
                }
                if (translationStrength >= TranslationStrength.BALANCED
                        && !isStableOcrCandidate(normalized)) {
                    return;
                }
                lastContextText = lastSourceText;
                lastSourceText = normalized;
                requestTranslation(normalized);
            }

            @Override
            public void onError(Exception error) {
                ocrInFlight.set(false);
                showStatusOnMain("OCR 失败");
            }
                }
        );
    }

    private void requestTranslation(String sourceText) {
        if (!translationEnabled) {
            return;
        }
        if (translationInFlight) {
            pendingText = sourceText;
            showStatusOnMain("画面变化，等待更新");
            return;
        }

        translationInFlight = true;
        pendingText = null;
        final int generation = ++translationGeneration;
        final int requestedStrength = translationStrength;
        if (!translationReadyForCurrentDirection) {
            showStatusOnMain("准备翻译模型");
        }
        try {
            translationEngine.translate(
                    sourceText,
                    sourceMode,
                    targetLanguage,
                    new TranslationEngine.Callback() {
            @Override
            public void onPreparing(String message) {
                if (generation == translationGeneration
                        && !translationReadyForCurrentDirection) {
                    showStatusOnMain(message);
                }
            }

            @Override
            public void onSuccess(
                    String translatedText,
                    String sourceLanguage,
                    String resultTargetLanguage,
                    boolean unchanged
            ) {
                if (generation == translationGeneration && pendingText == null) {
                    if (!translationReadyForCurrentDirection) {
                        showStatusOnMain(unchanged ? "源语言与目标相同" : "运行中");
                    }
                    translationReadyForCurrentDirection = true;
                    panel.setResult(translatedText.isEmpty()
                            ? UiText.translate(
                            FloatingTranslateService.this,
                            "未识别到可翻译文字"
                    )
                            : translatedText);
                    lastDraftSourceText = sourceText;
                    lastDraftTranslation = translatedText;
                    if (!unchanged && requestedStrength >= TranslationStrength.BALANCED) {
                        scheduleLocalRefinement(
                                sourceText,
                                "",
                                "",
                                sourceLanguage,
                                resultTargetLanguage,
                                requestedStrength,
                                generation
                        );
                    }
                }
                finishTranslation(sourceText);
            }

            @Override
            public void onError(Exception error) {
                if (generation == translationGeneration) {
                    translationReadyForCurrentDirection = false;
                    showStatusOnMain("翻译模型不可用");
                    panel.setResult(UiText.translate(
                            FloatingTranslateService.this,
                            "翻译失败：" + safeMessage(error)
                                    + "\n首次使用请联网下载语言模型。"
                    ));
                }
                finishTranslation(sourceText);
            }
                    }
            );
        } catch (RuntimeException error) {
            translationInFlight = false;
            pendingText = null;
            showStatusOnMain("翻译引擎启动失败");
            panel.setResult(UiText.translate(
                    this,
                    "切换语言后无法启动翻译模型："
                            + safeMessage(error)
                            + "\n可切回其他语言或重启悬浮服务。"
            ));
        }
    }

    private void finishTranslation(String completedSource) {
        translationInFlight = false;
        String queued = pendingText;
        pendingText = null;
        if (queued != null
                && !queued.equals(completedSource)
                && translationEnabled) {
            requestTranslation(queued);
        }
    }

    private void scheduleLocalRefinement(
            String sourceText,
            String draftTranslation,
            String contextText,
            String sourceLanguage,
            String targetLanguageCode,
            int strength,
            int translationGenerationToken
    ) {
        if (!translationEnabled || strength < TranslationStrength.BALANCED) {
            return;
        }
        LocalRefinementRequest request = new LocalRefinementRequest(
                sourceText,
                draftTranslation,
                contextText,
                sourceLanguage,
                targetLanguageCode,
                strength,
                translationGenerationToken,
                ++localRefinementGeneration
        );
        if (localRefinementInFlight) {
            Log.i(
                    TAG,
                    "Local refinement queue replacing previous pending task"
            );
            pendingLocalSourceText = request.sourceText;
            pendingLocalDraftTranslation = request.draftTranslation;
            pendingLocalContextText = request.contextText;
            pendingLocalSourceLanguage = request.sourceLanguage;
            pendingLocalTargetLanguage = request.targetLanguageCode;
            pendingLocalStrength = request.strength;
            showStatusOnMain("已保留最新精修任务");
            return;
        }
        startLocalRefinement(request);
    }

    private void startLocalRefinement(LocalRefinementRequest request) {
        localRefinementInFlight = true;
        if (request.strength == TranslationStrength.API) {
            if (CloudApiSettings.isConfigured(this)) {
                startCloudRefinement(request);
            } else {
                showStatusOnMain("API 未配置，保留快速译文");
                finishLocalRefinement();
            }
        } else if (request.strength == TranslationStrength.ULTRA) {
            startUltraRefinement(request);
        } else if (request.strength == TranslationStrength.PRECISE
                && LargeLocalModelManager.isReady(this)) {
            startLargeRefinement(request);
        } else {
            if (request.strength == TranslationStrength.PRECISE) {
                showStatusOnMain("3B 未就绪，使用 1.5B 精修");
            } else {
                showStatusOnMain("本地精修准备中");
            }
            startSmallRefinement(request);
        }
    }

    private void startCloudRefinement(LocalRefinementRequest request) {
        cloudApiEngine.translate(
                this,
                LanguageCatalog.displayName(this, request.sourceLanguage),
                LanguageCatalog.displayName(this, request.targetLanguageCode),
                request.sourceText,
                request.contextText,
                request.draftTranslation,
                request.strength,
                new LocalTranslationEngine.Callback() {
                    @Override
                    public void onStatus(String message) {
                        if (request.localGenerationToken == localRefinementGeneration
                                && translationEnabled) {
                            showStatusOnMain(message);
                        }
                    }

                    @Override
                    public void onSuccess(String translatedText) {
                        handleLocalSuccess(request, translatedText, "API 翻译完成");
                    }

                    @Override
                    public void onError(Exception error) {
                        mainHandler.post(() -> {
                            if (!isCurrentLocalRequest(request)) {
                                return;
                            }
                            Log.w(TAG, "Cloud API refinement failed", error);
                            showStatusOnMain("API 失败，保留快速译文");
                            finishLocalRefinement();
                        });
                    }
                }
        );
    }

    private void startUltraRefinement(LocalRefinementRequest request) {
        if (!TranslationEngine.LANGUAGE_JAPANESE.equals(request.sourceLanguage)
                || !TranslationEngine.LANGUAGE_CHINESE.equals(
                request.targetLanguageCode
        )) {
            showStatusOnMain("日→中专项仅支持日文译成简体中文");
            startLargeRefinement(request);
            return;
        }
        if (!UltraLocalModelManager.isReady(this)) {
            showStatusOnMain("日→中专项模型未就绪，已回退精细");
            startLargeRefinement(request);
            return;
        }

        showStatusOnMain("释放小型模型，独享内存给日→中专项");
        final AtomicInteger remaining = new AtomicInteger(2);
        Runnable afterRelease = () -> {
            if (remaining.decrementAndGet() != 0) {
                return;
            }
            mainHandler.post(() -> {
                if (!isCurrentLocalRequest(request)
                        || translationStrength != TranslationStrength.ULTRA
                ) {
                    return;
                }
                ultraLlmEngine.translate(
                        this,
                        LanguageCatalog.displayName(this, request.sourceLanguage),
                        LanguageCatalog.displayName(this, request.targetLanguageCode),
                        request.sourceText,
                        request.contextText,
                        request.draftTranslation,
                        request.strength,
                        new LocalTranslationEngine.Callback() {
                            @Override
                            public void onStatus(String message) {
                                if (request.localGenerationToken
                                        == localRefinementGeneration
                                        && translationEnabled) {
                                    showStatusOnMain(message);
                                }
                            }

                            @Override
                            public void onSuccess(String translatedText) {
                                handleLocalSuccess(
                                        request,
                                        translatedText,
                                        "日→中专项翻译完成"
                                );
                            }

                            @Override
                            public void onError(Exception error) {
                                mainHandler.post(() -> {
                                    if (!isCurrentLocalRequest(request)) {
                                        return;
                                    }
                                    Log.w(TAG, "Ultra refinement failed", error);
                                    showStatusOnMain("日→中专项失败，保留快速译文");
                                    finishLocalRefinement();
                                });
                            }
                        }
                );
            });
        };
        localLlmEngine.releaseEngine(afterRelease);
        largeLlmEngine.releaseEngine(afterRelease);
    }

    private void startLargeRefinement(LocalRefinementRequest request) {
        largeLlmEngine.translate(
                this,
                LanguageCatalog.displayName(this, request.sourceLanguage),
                LanguageCatalog.displayName(this, request.targetLanguageCode),
                request.sourceText,
                request.contextText,
                request.draftTranslation,
                request.strength,
                new LocalTranslationEngine.Callback() {
                    @Override
                    public void onStatus(String message) {
                        if (request.localGenerationToken == localRefinementGeneration
                                && translationEnabled) {
                            showStatusOnMain(message);
                        }
                    }

                    @Override
                    public void onSuccess(String translatedText) {
                        handleLocalSuccess(request, translatedText, "3B 精修完成");
                    }

                    @Override
                    public void onError(Exception error) {
                        mainHandler.post(() -> {
                            if (!isCurrentLocalRequest(request)) {
                                return;
                            }
                            Log.w(
                                    TAG,
                                    "3B refinement failed; falling back to 1.5B",
                                    error
                            );
                            showStatusOnMain("3B 失败，回退 1.5B");
                            startSmallRefinement(request);
                        });
                    }
                }
        );
    }

    private void startSmallRefinement(LocalRefinementRequest request) {
        localLlmEngine.translate(
                this,
                LanguageCatalog.displayName(this, request.sourceLanguage),
                LanguageCatalog.displayName(this, request.targetLanguageCode),
                request.sourceText,
                request.contextText,
                request.draftTranslation,
                request.strength,
                new LocalTranslationEngine.Callback() {
                    @Override
                    public void onStatus(String message) {
                        if (request.localGenerationToken == localRefinementGeneration
                                && translationEnabled) {
                            showStatusOnMain(message);
                        }
                    }

                    @Override
                    public void onSuccess(String translatedText) {
                        handleLocalSuccess(request, translatedText, "精修完成");
                    }

                    @Override
                    public void onError(Exception error) {
                        mainHandler.post(() -> {
                            if (!isCurrentLocalRequest(request)) {
                                return;
                            }
                            if (request.translationGenerationToken == translationGeneration
                                    && panel != null) {
                                panel.setStatus("本地精修失败，保留快速译文");
                            }
                            Log.w(TAG, "Local refinement failed", error);
                            finishLocalRefinement();
                        });
                    }
                }
        );
    }

    private void handleLocalSuccess(
            LocalRefinementRequest request,
            String translatedText,
            String status
    ) {
        mainHandler.post(() -> {
            if (request.localGenerationToken == localRefinementGeneration
                    && request.translationGenerationToken == translationGeneration
                    && translationStrength >= TranslationStrength.BALANCED
                    && request.sourceText.equals(lastSourceText)
                    && panel != null) {
                lastDraftTranslation = translatedText;
                panel.setResult(translatedText);
                panel.setStatus(status);
                Log.i(TAG, "Refined translation=" + translatedText);
            }
            if (isCurrentLocalRequest(request)) {
                finishLocalRefinement();
            }
        });
    }

    private boolean isCurrentLocalRequest(LocalRefinementRequest request) {
        return request.localGenerationToken == localRefinementGeneration
                && translationEnabled
                && request.sourceText.equals(lastSourceText);
    }

    private void abortActiveLocalRefinement() {
        localRefinementGeneration++;
        localRefinementInFlight = false;
        pendingLocalSourceText = null;
        pendingLocalDraftTranslation = null;
        pendingLocalContextText = null;
        pendingLocalSourceLanguage = null;
        pendingLocalTargetLanguage = null;
        if (largeLlmEngine != null) {
            largeLlmEngine.cancelCurrent();
        }
        if (ultraLlmEngine != null) {
            ultraLlmEngine.cancelCurrent();
        }
        if (cloudApiEngine != null) {
            cloudApiEngine.cancelCurrent();
        }
    }

    private void finishLocalRefinement() {
        localRefinementInFlight = false;
        if (pendingLocalSourceText == null
                || !translationEnabled
                || translationStrength < TranslationStrength.BALANCED) {
            return;
        }
        LocalRefinementRequest next = new LocalRefinementRequest(
                pendingLocalSourceText,
                pendingLocalDraftTranslation,
                pendingLocalContextText,
                pendingLocalSourceLanguage,
                pendingLocalTargetLanguage,
                pendingLocalStrength,
                translationGeneration,
                ++localRefinementGeneration
        );
        pendingLocalSourceText = null;
        pendingLocalDraftTranslation = null;
        pendingLocalContextText = null;
        pendingLocalSourceLanguage = null;
        pendingLocalTargetLanguage = null;
        startLocalRefinement(next);
    }

    private void openRegionPicker() {
        if (regionPicker != null) {
            return;
        }
        if (panel != null) {
            panel.hide();
        }
        removeRegionOutline();
        try {
            regionPicker = new RegionPickerOverlay(
                    overlayContext(),
                    windowManager,
                    overlayWindowType(),
                    normalizedRegion,
                    new RegionPickerOverlay.Listener() {
                    @Override
                    public void onRegionSelected(RectF selectedRegion) {
                        normalizedRegion = sanitizeRegion(selectedRegion);
                        ocrEdgeWarningActive = false;
                        saveRegion();
                        regionPicker = null;
                        lastSourceText = "";
                        lastCaptureAt = 0;
                        restorePanelAfterPicker("区域已更新", true);
                        updateRegionOutline();
                    }

                    @Override
                    public void onCancelled() {
                        regionPicker = null;
                        restorePanelAfterPicker(
                                translationEnabled ? "继续翻译" : "已暂停",
                                false
                        );
                        updateRegionOutline();
                    }
                    }
            );
            regionPicker.show();
        } catch (RuntimeException error) {
            regionPicker = null;
            if (panel != null) {
                panel.show();
                panel.setStatus("框选模式加载失败");
            }
            updateRegionOutline();
            UiText.toast(
                    this,
                    "无法打开框选模式：" + safeMessage(error),
                    Toast.LENGTH_LONG
            );
        }
    }

    private void restorePanelAfterPicker(String status, boolean regionChanged) {
        restorePanelAfterPicker(status, regionChanged, 0);
    }

    private void restorePanelAfterPicker(
            String status,
            boolean regionChanged,
            int attempt
    ) {
        if (panel == null) {
            return;
        }
        try {
            panel.show();
            if (regionChanged) {
                panel.setRegionLabel("区域模式");
            }
            panel.setStatus(status);
        } catch (RuntimeException error) {
            if (attempt < 3) {
                mainHandler.postDelayed(
                        () -> restorePanelAfterPicker(status, regionChanged, attempt + 1),
                        250L
                );
            } else {
                UiText.toast(
                        this,
                        "恢复悬浮面板失败：" + safeMessage(error),
                        Toast.LENGTH_LONG
                );
            }
        }
    }

    private void updateRegionOutline() {
        if (normalizedRegion == null
                || !translationEnabled
                || (panel != null && panel.isCollapsed())) {
            removeRegionOutline();
            return;
        }
        Point screen = getScreenSize();
        RectF region = sanitizeRegion(normalizedRegion);
        int left = Math.round(region.left * screen.x);
        int top = Math.round(region.top * screen.y);
        int right = Math.round(region.right * screen.x);
        int bottom = Math.round(region.bottom * screen.y);
        if (right - left < 2 || bottom - top < 2) {
            removeRegionOutline();
            return;
        }

        if (inputMode == INPUT_MODE_ACCESSIBILITY
                && regionOutline != null
                && regionOutlineParams != null
                && regionOutline.isAttachedToWindow()) {
            regionOutlineParams.width = right - left;
            regionOutlineParams.height = bottom - top;
            regionOutlineParams.x = left;
            regionOutlineParams.y = top;
            try {
                windowManager.updateViewLayout(regionOutline, regionOutlineParams);
                return;
            } catch (RuntimeException error) {
                Log.w(TAG, "Accessibility region outline resize failed", error);
                removeRegionOutline();
            }
        }

        removeRegionOutline();
        regionOutlineParams = new WindowManager.LayoutParams(
                right - left,
                bottom - top,
                overlayWindowType(),
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
        );
        regionOutlineParams.gravity = Gravity.TOP | Gravity.START;
        regionOutlineParams.x = left;
        regionOutlineParams.y = top;
        regionOutline = new RegionOutlineView(overlayContext());
        try {
            windowManager.addView(regionOutline, regionOutlineParams);
        } catch (RuntimeException error) {
            regionOutline = null;
        }
    }

    private void removeRegionOutline() {
        if (regionOutline == null) {
            return;
        }
        try {
            windowManager.removeView(regionOutline);
        } catch (RuntimeException ignored) {
            // Already removed.
        }
        regionOutline = null;
        regionOutlineParams = null;
    }

    private RectF loadRegion() {
        if (!statePrefs.getBoolean(KEY_HAS_REGION, false)) {
            return null;
        }
        return sanitizeRegion(new RectF(
                statePrefs.getFloat(KEY_REGION_LEFT, 0f),
                statePrefs.getFloat(KEY_REGION_TOP, 0f),
                statePrefs.getFloat(KEY_REGION_RIGHT, 1f),
                statePrefs.getFloat(KEY_REGION_BOTTOM, 1f)
        ));
    }

    private String normalizeSourceMode(String mode) {
        return LanguageCatalog.normalizeSource(mode);
    }

    private String normalizeTargetLanguage(String language) {
        return LanguageCatalog.normalizeTarget(language);
    }

    private boolean offersUltraForDirection(String source, String target) {
        if (!TranslationEngine.LANGUAGE_CHINESE.equals(target)) {
            return false;
        }
        return TranslationEngine.MODE_AUTO.equals(source)
                || TranslationEngine.LANGUAGE_JAPANESE.equals(source);
    }

    private String directionLabel() {
        return LanguageCatalog.displayName(this, sourceMode)
                + " → "
                + LanguageCatalog.displayName(this, targetLanguage);
    }

    private boolean isCaptureSourceReady() {
        if (!running) {
            return false;
        }
        if (inputMode == INPUT_MODE_ACCESSIBILITY) {
            return accessibilityCaptureService != null
                    && ScreenCaptureAccessibilityService.isConnected();
        }
        return mediaProjection != null;
    }

    private int overlayWindowType() {
        if (inputMode == INPUT_MODE_ACCESSIBILITY && !applicationOverlayFallback) {
            return WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY;
        }
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;
    }

    private Context overlayContext() {
        if (inputMode == INPUT_MODE_ACCESSIBILITY
                && !applicationOverlayFallback
                && accessibilityCaptureService != null) {
            return accessibilityCaptureService;
        }
        return this;
    }

    private void scheduleAccessibilityCapture(long delayMs) {
        if (workerHandler == null
                || !running
                || inputMode != INPUT_MODE_ACCESSIBILITY
                || !translationEnabled
                || !isCaptureSourceReady()) {
            return;
        }
        workerHandler.removeCallbacks(accessibilityCaptureLoop);
        workerHandler.postDelayed(accessibilityCaptureLoop, Math.max(0L, delayMs));
    }

    private void saveRegion() {
        SharedPreferences.Editor editor = statePrefs.edit();
        if (normalizedRegion == null) {
            editor.putBoolean(KEY_HAS_REGION, false);
        } else {
            editor.putBoolean(KEY_HAS_REGION, true)
                    .putFloat(KEY_REGION_LEFT, normalizedRegion.left)
                    .putFloat(KEY_REGION_TOP, normalizedRegion.top)
                    .putFloat(KEY_REGION_RIGHT, normalizedRegion.right)
                    .putFloat(KEY_REGION_BOTTOM, normalizedRegion.bottom);
        }
        editor.apply();
    }

    private RectF sanitizeRegion(RectF region) {
        if (region == null) {
            return null;
        }
        float left = Math.max(0f, Math.min(1f, region.left));
        float top = Math.max(0f, Math.min(1f, region.top));
        float right = Math.max(left + 0.002f, Math.min(1f, region.right));
        float bottom = Math.max(top + 0.002f, Math.min(1f, region.bottom));
        return new RectF(left, top, right, bottom);
    }

    private String normalizeText(String text) {
        return text
                .replace('\u00A0', ' ')
                .replaceAll("[\\t\\r]+", " ")
                .replaceAll(" {2,}", " ")
                .replaceAll("\\n{3,}", "\n\n")
                .trim();
    }

    private boolean isStableOcrCandidate(String text) {
        long now = System.currentTimeMillis();
        String key = text
                .replaceAll("[\\s。、！？!?\\.…~～]+", "")
                .trim();
        if (!pendingStableOcrKey.isEmpty()
                && now - pendingStableOcrAt >= 450L
                && ocrTextSimilarity(pendingStableOcrKey, key) >= 0.78f) {
            pendingStableOcrText = text;
            pendingStableOcrKey = key;
            pendingStableOcrAt = now;
            ocrStabilityWaitActive = false;
            return true;
        }
        pendingStableOcrText = text;
        pendingStableOcrKey = key;
        pendingStableOcrAt = now;
        if (!ocrStabilityWaitActive) {
            ocrStabilityWaitActive = true;
            showStatusOnMain("等待画面稳定");
        }
        return false;
    }

    private void clearPendingStableOcr() {
        pendingStableOcrText = "";
        pendingStableOcrKey = "";
        pendingStableOcrAt = 0L;
        ocrStabilityWaitActive = false;
    }

    private float ocrTextSimilarity(String first, String second) {
        if (first.equals(second)) {
            return 1f;
        }
        int maxLength = Math.max(first.length(), second.length());
        if (maxLength == 0) {
            return 1f;
        }
        if (Math.min(first.length(), second.length()) < 4
                || Math.abs(first.length() - second.length()) > Math.max(8, maxLength / 3)) {
            return 0f;
        }
        int[] previous = new int[second.length() + 1];
        int[] current = new int[second.length() + 1];
        for (int j = 0; j <= second.length(); j++) {
            previous[j] = j;
        }
        for (int i = 1; i <= first.length(); i++) {
            current[0] = i;
            for (int j = 1; j <= second.length(); j++) {
                int substitution = previous[j - 1]
                        + (first.charAt(i - 1) == second.charAt(j - 1) ? 0 : 1);
                current[j] = Math.min(
                        Math.min(previous[j] + 1, current[j - 1] + 1),
                        substitution
                );
            }
            int[] swap = previous;
            previous = current;
            current = swap;
        }
        return 1f - previous[second.length()] / (float) maxLength;
    }

    private void showStatusOnMain(String text) {
        mainHandler.post(() -> {
            if (panel != null) {
                panel.setStatus(text);
            }
        });
    }

    private Point getScreenSize() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Rect bounds = windowManager.getCurrentWindowMetrics().getBounds();
            return new Point(bounds.width(), bounds.height());
        }
        Point size = new Point();
        windowManager.getDefaultDisplay().getRealSize(size);
        return size;
    }

    private String safeMessage(Exception error) {
        String message = error.getMessage();
        if (message == null || message.trim().isEmpty()) {
            return error.getClass().getSimpleName();
        }
        return message.length() > 120 ? message.substring(0, 120) : message;
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private void releaseCapture() {
        if (virtualDisplay != null) {
            virtualDisplay.release();
            virtualDisplay = null;
        }
        if (imageReader != null) {
            imageReader.close();
            imageReader = null;
        }
    }

    @Override
    public void onDestroy() {
        running = false;
        if (mainHandler != null) {
            mainHandler.removeCallbacks(overlayWatchdog);
            mainHandler.removeCallbacks(displaySettledTask);
            mainHandler.removeCallbacks(releaseLargeEngineTask);
        }
        translationEnabled = false;
        pendingText = null;
        translationGeneration++;
        if (regionPicker != null) {
            regionPicker.destroy();
            regionPicker = null;
        }
        if (panel != null) {
            panel.destroy();
            panel = null;
        }
        removeRegionOutline();
        if (displayManager != null) {
            displayManager.unregisterDisplayListener(displayListener);
        }
        releaseCapture();
        if (mediaProjection != null) {
            try {
                mediaProjection.stop();
            } catch (RuntimeException ignored) {
                // The projection may already be stopped by the system.
            }
            mediaProjection = null;
        }
        if (textEngine != null) {
            textEngine.close();
            textEngine = null;
        }
        if (translationEngine != null) {
            translationEngine.close();
            translationEngine = null;
        }
        if (localLlmEngine != null) {
            localLlmEngine.close();
            localLlmEngine = null;
        }
        if (largeLlmEngine != null) {
            largeLlmEngine.close();
            largeLlmEngine = null;
        }
        if (ultraLlmEngine != null) {
            ultraLlmEngine.close();
            ultraLlmEngine = null;
        }
        if (cloudApiEngine != null) {
            cloudApiEngine.close();
            cloudApiEngine = null;
        }
        if (workerThread != null) {
            workerHandler.removeCallbacks(accessibilityCaptureLoop);
            workerThread.quitSafely();
            workerThread = null;
        }
        stopForeground(STOP_FOREGROUND_REMOVE);
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private static class LocalRefinementRequest {
        final String sourceText;
        final String draftTranslation;
        final String contextText;
        final String sourceLanguage;
        final String targetLanguageCode;
        final int strength;
        final int translationGenerationToken;
        final int localGenerationToken;

        LocalRefinementRequest(
                String sourceText,
                String draftTranslation,
                String contextText,
                String sourceLanguage,
                String targetLanguageCode,
                int strength,
                int translationGenerationToken,
                int localGenerationToken
        ) {
            this.sourceText = sourceText;
            this.draftTranslation = draftTranslation;
            this.contextText = contextText;
            this.sourceLanguage = sourceLanguage;
            this.targetLanguageCode = targetLanguageCode;
            this.strength = strength;
            this.translationGenerationToken = translationGenerationToken;
            this.localGenerationToken = localGenerationToken;
        }
    }

    private static class RegionOutlineView extends View {
        private final Paint borderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

        RegionOutlineView(Context context) {
            super(context);
            borderPaint.setColor(Color.rgb(83, 215, 217));
            borderPaint.setStyle(Paint.Style.STROKE);
            borderPaint.setStrokeWidth(2f * context.getResources().getDisplayMetrics().density);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            float inset = borderPaint.getStrokeWidth() / 2f;
            canvas.drawRect(
                    inset,
                    inset,
                    getWidth() - inset,
                    getHeight() - inset,
                    borderPaint
            );
        }
    }
}
