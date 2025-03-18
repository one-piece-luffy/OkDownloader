package com.baofu.downloader.utils;


import static android.os.Environment.DIRECTORY_DOWNLOADS;
import static android.os.Environment.DIRECTORY_MOVIES;
import static android.os.Environment.DIRECTORY_MUSIC;
import static android.os.Environment.DIRECTORY_PICTURES;

import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.StatFs;
import android.provider.MediaStore;
import android.text.TextUtils;

import androidx.annotation.RequiresApi;

import com.baofu.downloader.model.Video;
import com.baofu.downloader.model.VideoTaskItem;
import com.baofu.downloader.rules.VideoDownloadManager;

import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.security.MessageDigest;
import java.text.DecimalFormat;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Random;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class VideoDownloadUtils {

    private static final String TAG = "VideoDownloadUtils";
    public static final int DEFAULT_BUFFER_SIZE = 8 * 1024;
    public static final String VIDEO_SUFFIX = ".video";
    public static final String LOCAL_M3U8 = "local.m3u8";
    public static final String REMOTE_M3U8 = "remote.m3u8";
    public static final String MERGED_MP4 = "merged.mp4";
    public static final String SEGMENT_PREFIX = "video_";
    public static final String M3U8_SUFFIX = ".m3u8";
    public static final String INIT_SEGMENT_PREFIX = "init_video_";

    public static boolean isM3U8Mimetype(String mimeType) {
        return !TextUtils.isEmpty(mimeType) &&
                (mimeType.contains(Video.Mime.MIME_TYPE_M3U8_1) ||
                        mimeType.contains(Video.Mime.MIME_TYPE_M3U8_2) ||
                        mimeType.contains(Video.Mime.MIME_TYPE_M3U8_3) ||
                        mimeType.contains(Video.Mime.MIME_TYPE_M3U8_4) ||
                        mimeType.contains(Video.Mime.MIME_TYPE_M3U8_5));
    }

    public static String computeMD5(String string) {
        try {
            MessageDigest messageDigest = MessageDigest.getInstance("MD5");
            byte[] digestBytes = messageDigest.digest(string.getBytes());
            return bytesToHexString(digestBytes);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String bytesToHexString(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    public static boolean isChinese(char c) {
        Character.UnicodeBlock ub = Character.UnicodeBlock.of(c);
        if (ub == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS ||
                ub == Character.UnicodeBlock.CJK_COMPATIBILITY_IDEOGRAPHS ||
                ub == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_A ||
                ub == Character.UnicodeBlock.GENERAL_PUNCTUATION ||
                ub == Character.UnicodeBlock.CJK_SYMBOLS_AND_PUNCTUATION ||
                ub == Character.UnicodeBlock.HALFWIDTH_AND_FULLWIDTH_FORMS) {
            return true;
        }
        return false;
    }

    public static boolean isMessyCode(String strName) {
        Pattern p = Pattern.compile("\\s*|t*|r*|n*");
        Matcher m = p.matcher(strName);
        String after = m.replaceAll("");
        String temp = after.replaceAll("\\p{P}", "");
        char[] ch = temp.trim().toCharArray();
        float chLength = ch.length;
        float count = 0;
        for (char c : ch) {
            if (!Character.isLetterOrDigit(c)) {
                if (!isChinese(c)) {
                    count = count + 1;
                }
            }
        }
        if (chLength <= 0)
            return false;
        float result = count / chLength;
        if (result > 0.4) {
            return true;
        } else {
            return false;
        }
    }

    public static void close(Closeable closeable) {
        if (closeable != null) {
            try {
                closeable.close();
            } catch (Exception e) {
                LogUtils.w(TAG, "VideoProxyCacheUtils close " + closeable + " failed, exception = " + e);
            }
        }
    }

    public static String getPercent(float percent) {
        DecimalFormat format = new DecimalFormat("###.00");
        return format.format(percent) + "%";
    }

    public static boolean isFloatEqual(float f1, float f2) {
        if (Math.abs(f1 - f2) < 0.01f) {
            return true;
        }
        return false;
    }

    public static String getSuffixName(String name) {
        if (TextUtils.isEmpty(name)) {
            return "";
        }
        int dotIndex = name.lastIndexOf('.');
        return (dotIndex >= 0 && dotIndex < name.length()) ? name.substring(dotIndex) : "";
    }

    /**
     * @param filePath     绝对路径
     * @param relativePath 相对路径（download/insave_album）
     * @param fileName     文件名
     */
    public static void deleteFile(Context context, String filePath, String relativePath, String fileName) {
        if (TextUtils.isEmpty(relativePath))
            return;
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            Cursor cursor = null;
            try {
                Uri extnerl = MediaStore.Downloads.EXTERNAL_CONTENT_URI;
                String selection = MediaStore.Downloads.DISPLAY_NAME + "=? and " + MediaStore.MediaColumns.RELATIVE_PATH + "=?";
                String[] args = new String[]{fileName, relativePath};
                String[] projections = new String[]{MediaStore.Downloads._ID};

                cursor = VideoDownloadManager.getInstance().mConfig.context.getContentResolver().query(
                        extnerl,
                        projections,
                        selection,
                        args,
                        null);

                if (cursor != null && cursor.moveToFirst()) {
                    Uri queryUir = ContentUris.withAppendedId(extnerl, cursor.getLong(0));
                    VideoDownloadManager.getInstance().mConfig.context.getContentResolver().delete(queryUir, null, null);
                    Context ct = context == null ? VideoDownloadManager.getInstance().mConfig.context : context;
                    if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.Q) {
                        MediaScannerConnection.scanFile(ct, new String[]{filePath}
                                , null, new MediaScannerConnection.MediaScannerConnectionClient() {
                                    @Override
                                    public void onMediaScannerConnected() {

                                    }

                                    @Override
                                    public void onScanCompleted(String path, Uri uri) {

                                    }
                                });
                    }
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
                    if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.Q) {

                        Context ct = context == null ? VideoDownloadManager.getInstance().mConfig.context : context;
                        MediaScannerConnection.scanFile(ct, new String[]{filePath}
                                , null, new MediaScannerConnection.MediaScannerConnectionClient() {
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

    public static void deleteFile(Context context, String filePath) {
        if (TextUtils.isEmpty(filePath))
            return;
        DownloadExecutor.execute(() -> {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                Cursor cursor = null;
                try {
                    Uri extnerl = MediaStore.Downloads.EXTERNAL_CONTENT_URI;
                    String selection = MediaStore.MediaColumns.DATA + "=?";
                    String[] args = new String[]{filePath};
                    String[] projections = new String[]{MediaStore.Downloads._ID};

                    cursor =context.getContentResolver().query(
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
                        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.Q) {
                            MediaScannerConnection.scanFile(context, new String[]{filePath}
                                    , null, new MediaScannerConnection.MediaScannerConnectionClient() {
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
        });

    }
    /**
     * 关闭资源
     *
     * @param closeables
     */
    public static void close(Closeable... closeables) {
        int length = closeables.length;
        try {
            for (int i = 0; i < length; i++) {
                Closeable closeable = closeables[i];
                if (null != closeable)
                    closeables[i].close();
            }
        } catch (IOException e) {
            e.printStackTrace();
        } finally {
            for (int i = 0; i < length; i++) {
                closeables[i] = null;
            }
        }
    }

    public static String getUserAgent() {
        String ua = VideoDownloadManager.getInstance().mConfig.userAgent;
        if (TextUtils.isEmpty(ua)) {
            ua = DownloadConstans.DEFAULT_UA;
        }
        return ua;
    }

    /**
     * 是否是非法连接
     */
    public static boolean isIllegalUrl(String url) {
        if (TextUtils.isEmpty(url) || !url.startsWith("http")) {
            return true;
        }
        return false;

    }

    /**
     * 计算分段数量
     *
     * @param totalLength 文件总大小
     * @return 分段数
     */
    public static int getBlockCount(long totalLength) {

        if (totalLength < DownloadConstans.ONE_CONNECTION_UPPER_LIMIT) {
            return 1;
        }

        if (totalLength < DownloadConstans.TWO_CONNECTION_UPPER_LIMIT) {
            return 2;
        }

        if (totalLength < DownloadConstans.THREE_CONNECTION_UPPER_LIMIT) {
            return 3;
        }

        if (totalLength < DownloadConstans.FOUR_CONNECTION_UPPER_LIMIT) {
            return 4;
        }

        return 5;
    }

    /**
     * 获取文件写入的uri
     *
     * @return uri
     */
    @RequiresApi(api = Build.VERSION_CODES.Q)
    public static Uri getUri(String inserType, String fileName, String mimeType) {
        Uri uri = null;
        String path;
        if (inserType.equals(DIRECTORY_PICTURES)) {
            path = Environment.DIRECTORY_PICTURES + "/" + VideoDownloadManager.getInstance().downloadDir + "/";
        } else if (inserType.equals(DIRECTORY_MOVIES)) {
            path = Environment.DIRECTORY_MOVIES + "/" + VideoDownloadManager.getInstance().downloadDir + "/";
        } else if (inserType.equals(DIRECTORY_MUSIC)) {
            path = Environment.DIRECTORY_MUSIC + "/" + VideoDownloadManager.getInstance().downloadDir + "/";
        } else {
            path = DIRECTORY_DOWNLOADS + "/" + VideoDownloadManager.getInstance().downloadDir + "/";
        }

        Cursor cursor = null;
        Uri extnerl = MediaStore.Downloads.EXTERNAL_CONTENT_URI;
        String selection = MediaStore.Downloads.DISPLAY_NAME + "=? and " + MediaStore.MediaColumns.RELATIVE_PATH + "=?";
        String[] args = new String[]{fileName, path};
        String[] projections = new String[]{MediaStore.Downloads._ID};
        try {
            /*
             * 通过查找，要插入的Uri已经存在，就无需再次插入
             * 否则会出现新插入的文件，文件名被系统更改的现象，因为insert不会执行覆盖操作
             */
            cursor = VideoDownloadManager.getInstance().mConfig.context.getContentResolver().query(extnerl, projections, selection, args, null);

            if (cursor != null && cursor.moveToFirst()) {
                uri = ContentUris.withAppendedId(extnerl, cursor.getLong(0));

            }
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }
        ContentValues contentValues = new ContentValues();
        if (uri == null) {
            try {
                if (inserType.equals(DIRECTORY_PICTURES)) {
                    contentValues.put(MediaStore.Images.Media.DISPLAY_NAME, fileName);
                    contentValues.put(MediaStore.MediaColumns.MIME_TYPE, mimeType);
                    contentValues.put(MediaStore.Images.Media.DATE_TAKEN, System.currentTimeMillis());
                    contentValues.put(MediaStore.Images.Media.RELATIVE_PATH, path);
                    //只是往 MediaStore 里面插入一条新的记录，MediaStore 会返回给我们一个空的 Content Uri
                    //接下来问题就转化为往这个 Content Uri 里面写入
                    uri = VideoDownloadManager.getInstance().mConfig.context.getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues);
                } else if (inserType.equals(DIRECTORY_MOVIES)) {
                    contentValues.put(MediaStore.MediaColumns.MIME_TYPE, mimeType);
                    contentValues.put(MediaStore.Video.Media.DISPLAY_NAME, fileName);
                    contentValues.put(MediaStore.Video.Media.DATE_TAKEN, System.currentTimeMillis());
                    contentValues.put(MediaStore.Video.Media.RELATIVE_PATH, path);
                    //只是往 MediaStore 里面插入一条新的记录，MediaStore 会返回给我们一个空的 Content Uri
                    //接下来问题就转化为往这个 Content Uri 里面写入
                    uri = VideoDownloadManager.getInstance().mConfig.context.getContentResolver().insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, contentValues);
                } else if (inserType.equals(DIRECTORY_MUSIC)) {
                    contentValues.put(MediaStore.MediaColumns.MIME_TYPE, mimeType);
                    contentValues.put(MediaStore.Audio.Media.DISPLAY_NAME, fileName);
                    contentValues.put(MediaStore.Audio.Media.DATE_TAKEN, System.currentTimeMillis());
                    contentValues.put(MediaStore.Audio.Media.RELATIVE_PATH, path);
                    //只是往 MediaStore 里面插入一条新的记录，MediaStore 会返回给我们一个空的 Content Uri
                    //接下来问题就转化为往这个 Content Uri 里面写入
                    uri = VideoDownloadManager.getInstance().mConfig.context.getContentResolver().insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, contentValues);
                } else {
                    contentValues.put(MediaStore.MediaColumns.DISPLAY_NAME, fileName);
                    contentValues.put(MediaStore.MediaColumns.MIME_TYPE, mimeType);
                    contentValues.put(MediaStore.MediaColumns.DATE_TAKEN, System.currentTimeMillis());
                    contentValues.put(MediaStore.MediaColumns.RELATIVE_PATH, path);
                    uri = VideoDownloadManager.getInstance().mConfig.context.getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues);
                }
            } catch (Exception e) {
//                java.lang.IllegalStateException: Failed to build unique file: /storage/emulated/0/Download/InSaver_Album 71854a569271885f4462d302260ccf78.jpg image/jpeg
                e.printStackTrace();
                uri = null;
            }

        }

        if (uri == null) {
            //uri插入失败，可能系统中有这条记录，先删掉再重建
            Cursor cs = null;
            try {
                extnerl = MediaStore.Downloads.EXTERNAL_CONTENT_URI;
                selection = MediaStore.Downloads.DISPLAY_NAME + "=?";
                args = new String[]{fileName};
                projections = new String[]{MediaStore.Downloads._ID};
                cs = VideoDownloadManager.getInstance().mConfig.context.getContentResolver().query(extnerl, projections, selection, args, null);

                if (cs != null && cs.moveToFirst()) {

                    Uri queryUir = ContentUris.withAppendedId(extnerl, cs.getLong(0));

                    VideoDownloadManager.getInstance().mConfig.context.getContentResolver().delete(queryUir, null, null);
                    uri = VideoDownloadManager.getInstance().mConfig.context.getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues);

                }

            } catch (Exception e) {
                e.printStackTrace();
            } finally {
                if (cs != null) {
                    cs.close();
                }
            }

        }
//        if (uri == null) {
//            //还是失败，则重命名
//            mSaveName = VideoDownloadUtils.computeMD5(mTaskItem.getUrl() + System.currentTimeMillis());
//            mTaskItem.setFileHash(mSaveName);
//            if (TextUtils.isEmpty(mTaskItem.suffix)) {
//                fileName = mSaveName + VideoDownloadUtils.VIDEO_SUFFIX;
//            } else {
//                fileName = mSaveName + mTaskItem.suffix;
//            }
//
//            contentValues.put(MediaStore.MediaColumns.DISPLAY_NAME, fileName);
//            uri = VideoDownloadManager.getInstance().mConfig.context.getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues);
//        }
        return uri;
    }

    /**
     * 文件大小转为字符串
     *
     * @param size
     * @return
     */
    public static String getSizeStr(long size) {
        try {
            StringBuffer sb = new StringBuffer();
            DecimalFormat format = new DecimalFormat("###.00");
            if (size >= 1024 * 1024 * 1024) {
                double i = (size / (1024.0 * 1024.0 * 1024.0));
                sb.append(format.format(i)).append("GB");
            } else if (size >= 1024 * 1024) {
                double i = (size / (1024.0 * 1024.0));
                sb.append(format.format(i)).append("MB");
            } else if (size >= 1024) {
                double i = (size / (1024.0));
                sb.append(format.format(i)).append("KB");
            } else if (size < 1024) {
                if (size <= 0) {
                    sb.append("0B");
                } else {
                    sb.append((int) size).append("B");
                }
            }
            return sb.toString();
        }catch (Exception e){
            e.printStackTrace();
        }

       return "";
    }

    /**
     * 获取可用存储空间
     */
    public static long getFreeSpaceBytes(final String path) {
        long freeSpaceBytes;
        final StatFs statFs = new StatFs(path);
        //noinspection deprecation
        freeSpaceBytes = statFs.getAvailableBlocks() * (long) statFs.getBlockSize();

        return freeSpaceBytes;
    }

    /**
     * 同步到相册
     */
    public static void scanAlbum(Context context,String filePath) {
        try {
            Handler handler=new Handler(Looper.getMainLooper());
            handler.postDelayed(() -> {
                if (  TextUtils.isEmpty(filePath) || context == null) {
                    return;
                }
                File file = new File(filePath);
                //适配android10公共目录下载后android10及以上可以不用刷新媒体库
                if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.Q) {
                    boolean ex = file.exists();
                    if (ex) {

                        MediaScannerConnection.scanFile(context, new String[]{filePath}
                                , null, null);
                    }
                }

                if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
                    //android9.0 有的手机相册不显示，这里多刷新一次相册
                    if (file.exists()) {
//                            Uri uri = FileProvider.getUriForFile(MainActivity.this, getPackageName() + ".provider", file);
                        Uri uri = Uri.fromFile(file);
                        context.sendBroadcast(new Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE, uri));
                    }

                }
            }, 2000);

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public static String getFileName(VideoTaskItem item, String appen, boolean appSuffix) {

        String fileName;
        if (!TextUtils.isEmpty(item.getFileName())) {
            fileName = item.getFileName();

        } else if (!TextUtils.isEmpty(item.getFileHash())) {
            fileName = item.getFileHash();
        } else {
            fileName = VideoDownloadUtils.getAndSetDefaultFileHash(item);
        }
        if (TextUtils.isEmpty(fileName) || "0".equals(fileName)) {
            UUID uuid = UUID.randomUUID();
            fileName = uuid.toString();
        }
        if (!TextUtils.isEmpty(fileName)&&fileName.length() > 35) {
            fileName = fileName.substring(0, 35);
        }
        if (TextUtils.isEmpty(fileName) || "0".equals(fileName)) {
            String arr = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
            Random random = new Random();
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 3; i++) {
                sb.append(arr.charAt(random.nextInt(arr.length())));
            }
            fileName = sb.toString() + System.currentTimeMillis();



        }
        if (!TextUtils.isEmpty(appen)) {
            fileName += appen;
        }

        if (appSuffix) {
            if (TextUtils.isEmpty(item.suffix)) {
                fileName = fileName + VideoDownloadUtils.VIDEO_SUFFIX;
            } else {
                fileName = fileName + item.suffix;
            }
        }

        return fileName;
    }

    public static String getFileNameWithSuffix(VideoTaskItem item) {

        String fileName = item.getFileHash();
        if (TextUtils.isEmpty(item.suffix)) {
            fileName = fileName + VideoDownloadUtils.VIDEO_SUFFIX;
        } else {
            fileName = fileName + item.suffix;
        }

        return fileName;
    }


    /**
     * 设置默认文件名
     */
    public static String getAndSetDefaultFileHash(VideoTaskItem item) {
        if (item == null) {
            return null;
        }
        String hash = VideoDownloadUtils.computeMD5(item.getUrl());
//        item.setFileHash(hash);
        return hash;
    }

    public static String getTimeFormat() {
        SimpleDateFormat sdf = new SimpleDateFormat("yyMMdd_HHmmss");//设置日期格式
        Date now = new Date();
        return sdf.format(now);
    }

    /**
     * 利用系统的写入功能创建文件夹
     */
    @RequiresApi(api = Build.VERSION_CODES.Q)
    public static void mkdir(){
        OutputStream os = null;
        try {
            String fileName = System.currentTimeMillis()+"";
            ContentValues contentValues = new ContentValues();
            contentValues.put(MediaStore.MediaColumns.DISPLAY_NAME, fileName);
            contentValues.put(MediaStore.MediaColumns.MIME_TYPE, "text/plain");
            contentValues.put(MediaStore.MediaColumns.DATE_TAKEN, System.currentTimeMillis());
            contentValues.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/"+VideoDownloadManager.getInstance().downloadDir);
            Uri uri = VideoDownloadManager.getInstance().mConfig.context.getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues);
            if (uri == null) {
                //文件创建失败
                return;
            }
            os =  VideoDownloadManager.getInstance().mConfig.context.getContentResolver().openOutputStream(uri);
            os.write("data".getBytes());
            os.flush();
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            close(os);
        }
    }
}
