package com.baofu.downloader.utils;

import android.content.ContentUris;
import android.content.Context;
import android.database.Cursor;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.Looper;
import android.provider.MediaStore;
import android.text.TextUtils;

import com.baofu.downloader.R;
import com.baofu.downloader.rules.VideoDownloadManager;

import java.io.File;
import java.io.IOException;


public class VideoStorageUtils {

    public static void clearVideoDownloadDir() throws IOException {
        if (ContextUtils.getApplicationContext() != null) {
            File videoCacheDir = new File(VideoDownloadManager.getInstance().mConfig.publicPath);
            cleanDirectory(videoCacheDir);
        }
    }

    /**
     * 私有目录/m3u8缓存目录
     * @param context
     * @return
     */
    public static File getPrivateDir(Context context) {
        File cacheFile=new File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "bp");
        if (!cacheFile.exists()) {
            cacheFile.mkdir();
        }
        return cacheFile;
    }

    /**
     * 私有目录/m3u8缓存目录
     * @param context
     * @return
     */
    public static File getTempDir(Context context) {
        File cacheFile=new File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "cache");
        if (!cacheFile.exists()) {
            cacheFile.mkdir();
        }
        return cacheFile;
    }

    /**
     * 清除临时缓存的m3u8
     * @throws IOException
     */
    public static void clearVideoCacheDir() throws IOException {
        if (ContextUtils.getApplicationContext() != null) {
            File videoCacheDir = getPrivateDir(ContextUtils.getApplicationContext());
            cleanDirectory(videoCacheDir);
        }
    }

    private static void cleanDirectory(File file) throws IOException {
        if (!file.exists()) {
            return;
        }
        File[] contentFiles = file.listFiles();
        if (contentFiles != null) {
            for (File contentFile : contentFiles) {
                delete(contentFile);
            }
        }
    }

    public static void deleteFile(Context context, String filePath) {
        if (TextUtils.isEmpty(filePath))
            return;
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q && !filePath.startsWith(VideoDownloadManager.getInstance().mConfig.privatePath)) {
            Cursor cursor = null;
            try {
                Uri extnerl = MediaStore.Downloads.EXTERNAL_CONTENT_URI;
                String selection = MediaStore.MediaColumns.DATA + "=?";
                String args[] = new String[]{filePath};
                String projections[] = new String[]{MediaStore.Downloads._ID};

                cursor = context.getContentResolver().query(
                        extnerl,
                        projections,
                        selection,
                        args,
                        null);

                if (cursor != null && cursor.moveToFirst()) {
                    Uri queryUir = ContentUris.withAppendedId(extnerl, cursor.getLong(0));
                    context.getContentResolver().delete(queryUir, null, null);
                }
            } catch (Exception e) {
                e.printStackTrace();
            } finally {
                if (cursor != null) {
                    cursor.close();
                }
            }
        }
        try {
            File file = new File(filePath);
            if (file.exists()) {
                boolean del = file.delete();
                if (del) {
                    if (context == null)
                        return;
                    if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.Q) {

                        MediaScannerConnection.scanFile(context, new String[]{filePath}
                                , new String[]{null}, new MediaScannerConnection.MediaScannerConnectionClient() {
                                    @Override
                                    public void onMediaScannerConnected() {

                                    }

                                    @Override
                                    public void onScanCompleted(String path, Uri uri) {

                                    }
                                });
                    }

                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }


    }
    public static boolean isMainThread() {
        return Looper.getMainLooper() == Looper.myLooper();
    }
    public static void delete(File file) throws IOException {
        if(file==null)
            return;
        try {
            if (file.isFile() && file.exists()) {
                if (VideoDownloadManager.getInstance().mConfig.context != null) {
                    deleteFile(VideoDownloadManager.getInstance().mConfig.context, file.getAbsolutePath());
                } else {
                    deleteOrThrow(file);
                }

            } else {
                cleanDirectory(file);
                if (VideoDownloadManager.getInstance().mConfig.context != null) {
                    deleteFile(VideoDownloadManager.getInstance().mConfig.context, file.getAbsolutePath());
                } else {
                    deleteOrThrow(file);
                }
            }
        }catch (Exception e){
            e.printStackTrace();
        }

    }

    private static void deleteOrThrow(File file) throws IOException {
        if (file.exists()) {
            boolean isDeleted = file.delete();
            if (!isDeleted) {
                throw new IOException(
                        String.format("File %s can't be deleted", file.getAbsolutePath()));
            }
        }
    }

    public static long countTotalSize(File file) {
        if (file.isDirectory()) {
            long totalSize = 0;
            for (File f : file.listFiles()) {
                totalSize += countTotalSize(f);
            }
            return totalSize;
        } else {
            return file.length();
        }
    }



}
