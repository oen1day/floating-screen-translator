package io.github.oen1day.floatingtranslator;

import android.app.DownloadManager;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Environment;
import android.os.StatFs;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;

public final class UltraLocalModelManager {
    public static final String MODEL_FILENAME =
            "sakura-7b-qwen2.5-v1.0-iq4xs.gguf";
    public static final long MODEL_SIZE_BYTES = 4_250_298_208L;
    private static final String MODEL_SHA256 =
            "30e82ca13bc1e321f4f7b77459d533d483d46200522dc5c31eef9d2f2a6335d6";
    private static final String MODEL_URL =
            "https://huggingface.co/SakuraLLM/Sakura-7B-Qwen2.5-v1.0-GGUF/"
                    + "resolve/main/sakura-7b-qwen2.5-v1.0-iq4xs.gguf";

    public interface ProgressListener {
        void onProgress(int percent, long downloadedBytes, long totalBytes);

        void onVerifying();
    }

    private static final Object DOWNLOAD_LOCK = new Object();
    private static volatile long activeDownloadId = -1L;

    private UltraLocalModelManager() {
    }

    public static File modelFile(Context context) {
        File root = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
        File dir = root == null
                ? new File(context.getFilesDir(), "models")
                : new File(root, "models");
        if (!dir.exists() && !dir.mkdirs()) {
            return new File(context.getFilesDir(), MODEL_FILENAME);
        }
        return new File(dir, MODEL_FILENAME);
    }

    public static boolean hasModelFile(Context context) {
        File file = modelFile(context);
        return file.isFile() && file.length() == MODEL_SIZE_BYTES;
    }

    public static boolean isReady(Context context) {
        File marker = markerFile(context);
        if (!hasModelFile(context) || !marker.isFile()) {
            return false;
        }
        try (FileInputStream input = new FileInputStream(marker)) {
            byte[] bytes = new byte[64];
            int count = input.read(bytes);
            return count > 0
                    && MODEL_SHA256.equalsIgnoreCase(
                    new String(bytes, 0, count, StandardCharsets.US_ASCII).trim()
            );
        } catch (Exception ignored) {
            return false;
        }
    }

    public static File downloadAndVerifyBlocking(
            Context context,
            ProgressListener listener
    ) throws Exception {
        File target = modelFile(context);
        synchronized (DOWNLOAD_LOCK) {
            if (isReady(context)) {
                return target;
            }

            if (target.exists() && target.length() != MODEL_SIZE_BYTES) {
                if (!target.delete()) {
                    throw new IllegalStateException("无法删除不完整的日→中专项模型文件");
                }
            }

            if (!target.isFile()) {
                StatFs statFs = new StatFs(target.getParentFile().getAbsolutePath());
                long required = MODEL_SIZE_BYTES + 512L * 1024L * 1024L;
                if (statFs.getAvailableBytes() < required) {
                    throw new IllegalStateException(
                            "日→中专项模型约 4.25GB，下载与校验至少需要约 4.75GB 可用空间"
                    );
                }
                download(context, target, listener);
            }

            if (!target.isFile() || target.length() != MODEL_SIZE_BYTES) {
                throw new IllegalStateException("日→中专项模型大小校验失败");
            }
            if (listener != null) {
                listener.onVerifying();
            }
            String actual = sha256(target);
            if (!MODEL_SHA256.equalsIgnoreCase(actual)) {
                throw new IllegalStateException("日→中专项模型 SHA-256 校验失败");
            }
            try (FileOutputStream output = new FileOutputStream(markerFile(context))) {
                output.write(MODEL_SHA256.getBytes(StandardCharsets.US_ASCII));
            }
            return target;
        }
    }

    private static void download(
            Context context,
            File target,
            ProgressListener listener
    ) throws Exception {
        DownloadManager manager =
                (DownloadManager) context.getSystemService(Context.DOWNLOAD_SERVICE);
        if (manager == null) {
            throw new IllegalStateException("系统下载服务不可用");
        }
        long downloadId = activeDownloadId;
        if (downloadId < 0L || !isDownloadActive(manager, downloadId)) {
            DownloadManager.Request request = new DownloadManager.Request(
                    Uri.parse(MODEL_URL)
            )
                    .setTitle("浮屏译 日→中专项翻译模型")
                    .setDescription("Sakura 7B Qwen2.5 IQ4_XS，约 4.25GB")
                    .setNotificationVisibility(
                            DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED
                    )
                    .setAllowedOverMetered(true)
                    .setAllowedOverRoaming(false)
                    .setDestinationInExternalFilesDir(
                            context,
                            Environment.DIRECTORY_DOWNLOADS,
                            "models/" + MODEL_FILENAME
                    );
            downloadId = manager.enqueue(request);
            activeDownloadId = downloadId;
        }

        while (true) {
            DownloadManager.Query query = new DownloadManager.Query()
                    .setFilterById(downloadId);
            try (Cursor cursor = manager.query(query)) {
                if (cursor == null || !cursor.moveToFirst()) {
                    throw new IllegalStateException("找不到日→中专项模型下载任务");
                }
                int status = cursor.getInt(
                        cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)
                );
                long downloaded = cursor.getLong(
                        cursor.getColumnIndexOrThrow(
                                DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR
                        )
                );
                if (listener != null) {
                    int percent = (int) Math.max(
                            0L,
                            Math.min(100L, downloaded * 100L / MODEL_SIZE_BYTES)
                    );
                    listener.onProgress(percent, downloaded, MODEL_SIZE_BYTES);
                }
                if (status == DownloadManager.STATUS_SUCCESSFUL) {
                    break;
                }
                if (status == DownloadManager.STATUS_FAILED) {
                    int reason = cursor.getInt(
                            cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON)
                    );
                    throw new IllegalStateException("日→中专项模型下载失败，原因码 " + reason);
                }
            }
            Thread.sleep(1000L);
        }
        activeDownloadId = -1L;
    }

    private static boolean isDownloadActive(DownloadManager manager, long downloadId) {
        DownloadManager.Query query = new DownloadManager.Query().setFilterById(downloadId);
        try (Cursor cursor = manager.query(query)) {
            if (cursor == null || !cursor.moveToFirst()) {
                return false;
            }
            int status = cursor.getInt(
                    cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)
            );
            return status == DownloadManager.STATUS_RUNNING
                    || status == DownloadManager.STATUS_PENDING
                    || status == DownloadManager.STATUS_PAUSED;
        }
    }

    private static String sha256(File file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] buffer = new byte[1024 * 1024];
        try (FileInputStream input = new FileInputStream(file)) {
            int count;
            while ((count = input.read(buffer)) >= 0) {
                if (count > 0) {
                    digest.update(buffer, 0, count);
                }
            }
        }
        StringBuilder value = new StringBuilder(64);
        for (byte part : digest.digest()) {
            value.append(String.format(Locale.US, "%02X", part & 0xFF));
        }
        return value.toString();
    }

    private static File markerFile(Context context) {
        return new File(modelFile(context).getAbsolutePath() + ".sha256");
    }
}
