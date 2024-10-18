package com.baofu.downloader.utils;

import com.arthenica.mobileffmpeg.ExecuteCallback;
import com.arthenica.mobileffmpeg.FFmpeg;
import com.baofu.downloader.listener.IFFmpegCallback;
import com.baofu.downloader.m3u8.M3U8;
import com.baofu.downloader.m3u8.M3U8Seg;
import com.baofu.downloader.m3u8.M3U8Utils;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;

public class FFmpegUtils {
    /**
     * 利用ffmpeg将m3u8合成mp4
     */
    public static void covertM3u8ToMp4(String m3u8FilePath, String outputPath, IFFmpegCallback callback) {


        // 构建FFmpeg命令
        String[] command = new String[]{"-i", m3u8FilePath, "-c", "copy", outputPath};

        try {
            // 执行FFmpeg命令
            FFmpeg.executeAsync(command, (executionId, returnCode) -> {
                if (returnCode == 0) {
                    // 命令执行成功
                    if (callback != null) {
                        callback.onSuc();
                    }
                } else {
                    // 命令执行失败
//                    if (callback != null) {
//                        callback.onFail();
//                    }
//                    notifyDownloadError(new Exception("m3u8合并失败"));
                    doMerge(m3u8FilePath, outputPath, callback);

                }

            });

        } catch (Exception e) {
            e.printStackTrace();
            doMerge(m3u8FilePath, outputPath, callback);
        }

    }

    /**
     * 将m3u8合成mp4
     * 直接将ts拼接成mp4
     */
    public static void doMerge(String m3u8FilePath, String outputPath, IFFmpegCallback callback) {


        byte[] mMp4Header = null;
        File mergeFile = new File(outputPath);
        FileOutputStream fileOutputStream = null;
        try {
            fileOutputStream = new FileOutputStream(mergeFile);
        } catch (FileNotFoundException e) {
            e.printStackTrace();
        }
        if (fileOutputStream == null) {
            if (callback != null) {
                callback.onFail();
            }
            return;
        }
        try {
            File m3u8File = new File(m3u8FilePath);
            M3U8 m3u8 = M3U8Utils.parseLocalM3U8File(m3u8File);
            if (m3u8.getTsList() == null || m3u8.getTsList().isEmpty()) {
                if (callback != null) {
                    callback.onFail();
                }
                return;
            }
            for (M3U8Seg ts : m3u8.getTsList()) {
                if (ts.failed) {
                    continue;
                }
                String tsInitSegmentName = ts.getInitSegmentName();
                File tsInitSegmentFile = new File(m3u8File.getParent(), tsInitSegmentName);
                File tsFile = new File(m3u8File.getParent(), ts.getIndexName());

                if (ts.hasInitSegment() && mMp4Header == null) {
                    mMp4Header = AES128Utils.readFile(tsInitSegmentFile);
                }
                //下载的时候已经解密了，合并的时候无需再解密
                try {
                    if (mMp4Header != null) {
                        fileOutputStream.write(mMp4Header);
                    }
                    fileOutputStream.write(AES128Utils.readFile(tsFile));
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        } catch (IOException e) {
            e.printStackTrace();
            if (callback != null) {
                callback.onFail();
            }
            return;
        }

        try {
            fileOutputStream.close();
        } catch (IOException e) {
            e.printStackTrace();
        }
        if (callback != null) {
            callback.onSuc();
        }

    }

}
