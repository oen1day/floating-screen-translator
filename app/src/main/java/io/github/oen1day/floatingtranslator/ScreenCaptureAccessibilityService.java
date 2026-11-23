package io.github.oen1day.floatingtranslator;

import android.accessibilityservice.AccessibilityService;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.ColorSpace;
import android.graphics.Rect;
import android.hardware.HardwareBuffer;
import android.os.Build;
import android.util.Log;
import android.view.Display;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;

import java.util.concurrent.Executor;

public class ScreenCaptureAccessibilityService extends AccessibilityService {
    public interface FrameCallback {
        void onBitmap(Bitmap bitmap, Rect captureBounds, boolean overlaysExcluded);

        void onError(int errorCode, Exception error);
    }

    private static final String TAG = "ScreenTranslatorA11y";
    private static volatile ScreenCaptureAccessibilityService instance;
    private int lastLoggedWindowId = -1;
    private volatile int lastApplicationWindowId = -1;
    private volatile Rect lastApplicationWindowBounds = new Rect();

    public static boolean isConnected() {
        return instance != null;
    }

    public static ScreenCaptureAccessibilityService getInstance() {
        return instance;
    }

    public WindowManager getOverlayWindowManager() {
        return (WindowManager) getSystemService(Context.WINDOW_SERVICE);
    }

    @android.annotation.TargetApi(Build.VERSION_CODES.R)
    public void captureSoftwareBitmap(Executor executor, FrameCallback callback) {
        WindowTarget target = findTargetWindow();
        if (target != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            captureWindow(executor, callback, target);
            return;
        }
        captureFullScreen(executor, callback);
    }

    @android.annotation.TargetApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    private void captureWindow(
            Executor executor,
            FrameCallback callback,
            WindowTarget target
    ) {
        if (lastLoggedWindowId != target.id) {
            lastLoggedWindowId = target.id;
            Log.i(
                    TAG,
                    "Capturing application window id=" + target.id
                            + " bounds=" + target.bounds
            );
        }
        takeScreenshotOfWindow(
                target.id,
                executor,
                new TakeScreenshotCallback() {
                    @Override
                    public void onSuccess(ScreenshotResult screenshot) {
                        emitBitmap(screenshot, target.bounds, true, callback);
                    }

                    @Override
                    public void onFailure(int errorCode) {
                        Log.w(
                                TAG,
                                "Window screenshot failed; falling back to full screen: "
                                        + errorCode
                        );
                        captureFullScreen(executor, callback);
                    }
                }
        );
    }

    @android.annotation.TargetApi(Build.VERSION_CODES.R)
    private void captureFullScreen(Executor executor, FrameCallback callback) {
        takeScreenshot(
                Display.DEFAULT_DISPLAY,
                executor,
                new TakeScreenshotCallback() {
                    @Override
                    public void onSuccess(ScreenshotResult screenshot) {
                        emitBitmap(screenshot, null, false, callback);
                    }

                    @Override
                    public void onFailure(int errorCode) {
                        callback.onError(
                                errorCode,
                                new IllegalStateException(
                                        "Accessibility screenshot failed: " + errorCode
                                )
                        );
                    }
                }
        );
    }

    @android.annotation.TargetApi(Build.VERSION_CODES.R)
    private void emitBitmap(
            ScreenshotResult screenshot,
            Rect windowBounds,
            boolean overlaysExcluded,
            FrameCallback callback
    ) {
        HardwareBuffer buffer = null;
        Bitmap hardwareBitmap = null;
        try {
            buffer = screenshot.getHardwareBuffer();
            ColorSpace colorSpace = screenshot.getColorSpace();
            hardwareBitmap = Bitmap.wrapHardwareBuffer(buffer, colorSpace);
            if (hardwareBitmap == null) {
                callback.onError(
                        -1,
                        new IllegalStateException("wrapHardwareBuffer returned null")
                );
                return;
            }
            Bitmap softwareBitmap = hardwareBitmap.copy(
                    Bitmap.Config.ARGB_8888,
                    false
            );
            if (softwareBitmap == null) {
                callback.onError(
                        -1,
                        new IllegalStateException("Bitmap copy failed")
                );
                return;
            }
            Rect captureBounds = windowBounds == null
                    ? new Rect(0, 0, softwareBitmap.getWidth(), softwareBitmap.getHeight())
                    : new Rect(windowBounds);
            callback.onBitmap(softwareBitmap, captureBounds, overlaysExcluded);
        } catch (Exception error) {
            callback.onError(-1, error);
        } finally {
            if (hardwareBitmap != null && !hardwareBitmap.isRecycled()) {
                hardwareBitmap.recycle();
            }
            if (buffer != null) {
                buffer.close();
            }
        }
    }

    private WindowTarget findTargetWindow() {
        int cachedWindowId = lastApplicationWindowId;
        Rect cachedBounds = lastApplicationWindowBounds;
        if (cachedWindowId >= 0
                && cachedBounds != null
                && !cachedBounds.isEmpty()) {
            return new WindowTarget(cachedWindowId, new Rect(cachedBounds));
        }
        try {
            for (AccessibilityWindowInfo window : getWindows()) {
                if (window.getType() == AccessibilityWindowInfo.TYPE_APPLICATION
                        && (window.isActive() || window.getId() == cachedWindowId)) {
                    Rect bounds = new Rect();
                    window.getBoundsInScreen(bounds);
                    if (bounds.isEmpty()) {
                        continue;
                    }
                    lastApplicationWindowId = window.getId();
                    lastApplicationWindowBounds = new Rect(bounds);
                    return new WindowTarget(
                            window.getId(),
                            bounds
                    );
                }
            }
        } catch (RuntimeException error) {
            Log.w(TAG, "Unable to enumerate application windows", error);
        }

        AccessibilityNodeInfo root = null;
        try {
            root = getRootInActiveWindow();
            if (root == null || root.getPackageName() == null) {
                return null;
            }
            if (getPackageName().contentEquals(root.getPackageName())) {
                return null;
            }
            Rect bounds = new Rect();
            root.getBoundsInScreen(bounds);
            if (bounds.isEmpty()) {
                return null;
            }
            return new WindowTarget(root.getWindowId(), bounds);
        } catch (RuntimeException error) {
            Log.w(TAG, "Unable to resolve active application window", error);
        } finally {
            if (root != null) {
                root.recycle();
            }
        }
        return null;
    }

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
        AccessibilityHealer.markEverEnabled(this);
        Log.i(TAG, "SERVICE_CONNECTED");
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        CharSequence packageName = event.getPackageName();
        if (packageName == null || getPackageName().contentEquals(packageName)) {
            return;
        }
        String packageValue = packageName.toString();
        if ("android".equals(packageValue)
                || "com.android.systemui".equals(packageValue)
                || "com.android.settings".equals(packageValue)
                || "com.miui.securitycenter".equals(packageValue)
                || packageValue.contains("inputmethod")) {
            return;
        }
        int windowId = event.getWindowId();
        if (windowId >= 0) {
            if (windowId != lastApplicationWindowId) {
                Log.i(
                        TAG,
                        "Tracking application window id=" + windowId
                                + " package=" + packageName
                );
            }
            lastApplicationWindowId = windowId;
            lastApplicationWindowBounds = new Rect();
        }
    }

    @Override
    public void onInterrupt() {
        Log.w(TAG, "INTERRUPT");
    }

    @Override
    public boolean onUnbind(android.content.Intent intent) {
        Log.w(TAG, "UNBIND");
        instance = null;
        return super.onUnbind(intent);
    }

    @Override
    public void onDestroy() {
        instance = null;
        Log.w(TAG, "SERVICE_DESTROYED");
        super.onDestroy();
    }

    private static class WindowTarget {
        final int id;
        final Rect bounds;

        WindowTarget(int id, Rect bounds) {
            this.id = id;
            this.bounds = bounds;
        }
    }
}
