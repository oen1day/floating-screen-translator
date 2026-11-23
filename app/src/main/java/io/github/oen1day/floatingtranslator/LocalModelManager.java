package io.github.oen1day.floatingtranslator;

import android.content.Context;
import android.app.DownloadManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Environment;
import android.os.StatFs;

import java.io.File;

public final class LocalModelManager {
    public static final String MODEL_FILENAME =
            "Qwen2.5-1.5B-Instruct_multi-prefill-seq_q8_ekv1280.task";
    public static final long MODEL_SIZE_BYTES = 1_597_913_616L;
    private static final String MODEL_URL =
            "https://huggingface.co/litert-community/Qwen2.5-1.5B-Instruct/resolve/main/"
                    + MODEL_FILENAME;

    public interface ProgressListener {
        void onProgress(int percent, long downloadedBytes, long totalBytes);
    }

    private static final Object DOWNLOAD_LOCK = new Object();
    private static volatile long activeDownloadId = -1L;

    private LocalModelManager() {
    }

    public static File modelFile(Context context) {
        File dir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
        if (dir == null) {
            dir = new File(context.getFilesDir(), "models");
        } else {
            dir = new File(dir, "models");
        }
        if (!dir.exists() && !dir.mkdirs()) {
            return new File(context.getFilesDir(), MODEL_FILENAME);
        }
        return new File(dir, MODEL_FILENAME);
    }

    public static boolean isReady(Context context) {
        File file = modelFile(context);
        return file.isFile() && file.length() == MODEL_SIZE_BYTES;
    }

    public static File downloadBlocking(
            Context context,
            ProgressListener listener
    ) throws Exception {
        File target = modelFile(context);
        if (isReady(context)) {
            return target;
        }

        StatFs statFs = new StatFs(target.getParentFile().getAbsolutePath());
        long available = statFs.getAvailableBytes();
        if (available < MODEL_SIZE_BYTES + 64L * 1024L * 1024L) {
            throw new IllegalStateException("本地模型需要约 1.6GB，当前存储空间不足");
        }

        synchronized (DOWNLOAD_LOCK) {
            if (isReady(context)) {
                return target;
            }
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
                        .setTitle("浮屏译本地精修模型")
                        .setDescription("Qwen2.5 1.5B，约 1.6GB")
                        .setNotificationVisibility(
                                DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED
                        )
                        .setAllowedOverMetered(true)
                        .setAllowedOverRoaming(false);
                if (context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) != null) {
                    request.setDestinationInExternalFilesDir(
                            context,
                            Environment.DIRECTORY_DOWNLOADS,
                            "models/" + MODEL_FILENAME
                    );
                } else {
                    request.setDestinationUri(Uri.fromFile(target));
                }
                downloadId = manager.enqueue(request);
                activeDownloadId = downloadId;
            }

            while (true) {
                DownloadManager.Query query = new DownloadManager.Query()
                        .setFilterById(downloadId);
                try (Cursor cursor = manager.query(query)) {
                    if (cursor == null || !cursor.moveToFirst()) {
                        throw new IllegalStateException("找不到系统模型下载任务");
                    }
                    int status = cursor.getInt(cursor.getColumnIndexOrThrow(
                            DownloadManager.COLUMN_STATUS
                    ));
                    long downloaded = cursor.getLong(cursor.getColumnIndexOrThrow(
                            DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR
                    ));
                    long total = cursor.getLong(cursor.getColumnIndexOrThrow(
                            DownloadManager.COLUMN_TOTAL_SIZE_BYTES
                    ));
                    if (total <= 0L) {
                        total = MODEL_SIZE_BYTES;
                    }
                    if (listener != null) {
                        int percent = (int) Math.max(
                                0L,
                                Math.min(100L, downloaded * 100L / total)
                        );
                        listener.onProgress(percent, downloaded, total);
                    }
                    if (status == DownloadManager.STATUS_SUCCESSFUL) {
                        break;
                    }
                    if (status == DownloadManager.STATUS_FAILED) {
                        int reason = cursor.getInt(cursor.getColumnIndexOrThrow(
                                DownloadManager.COLUMN_REASON
                        ));
                        throw new IllegalStateException("系统下载失败，原因码 " + reason);
                    }
                }
                Thread.sleep(1000L);
            }

            if (!isReady(context)) {
                throw new IllegalStateException("模型下载完成但文件校验失败");
            }
            if (listener != null) {
                listener.onProgress(100, MODEL_SIZE_BYTES, MODEL_SIZE_BYTES);
            }
            activeDownloadId = -1L;
            return target;
        }
    }

    private static boolean isDownloadActive(DownloadManager manager, long downloadId) {
        DownloadManager.Query query = new DownloadManager.Query().setFilterById(downloadId);
        try (Cursor cursor = manager.query(query)) {
            if (cursor == null || !cursor.moveToFirst()) {
                return false;
            }
            int status = cursor.getInt(cursor.getColumnIndexOrThrow(
                    DownloadManager.COLUMN_STATUS
            ));
            return status == DownloadManager.STATUS_RUNNING
                    || status == DownloadManager.STATUS_PENDING
                    || status == DownloadManager.STATUS_PAUSED;
        }
    }
}
