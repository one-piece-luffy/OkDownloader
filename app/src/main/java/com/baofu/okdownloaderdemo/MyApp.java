package com.baofu.okdownloaderdemo;

import android.app.Application;
import android.os.Environment;

import com.baofu.downloader.common.DownloadMode;
import com.baofu.downloader.utils.VideoDownloadConfig;
import com.baofu.downloader.rules.VideoDownloadManager;
import com.baofu.downloader.utils.VideoStorageUtils;

import java.io.File;

public class MyApp extends Application {
    @Override
    public void onCreate() {
        super.onCreate();

        //下载器初始化
        VideoDownloadManager.getInstance().downloadDir = AppConfig.DOWNLOAD_DIR;

        try {
            File pathname = Environment.getExternalStorageDirectory();
            File directory_download = new File(pathname, "Download");
            if (!directory_download.exists()) {
                directory_download.mkdir();
            }
            File file = new File(directory_download.getAbsolutePath() + "/"
                    + VideoDownloadManager.getInstance().downloadDir);
            if (!file.exists()) {
                file.mkdir();
            }
            VideoDownloadConfig config = new VideoDownloadConfig.Builder(this)
                    .publicPath(file.getAbsolutePath())
                    .privatePath(VideoStorageUtils.getPrivateDir(this).getAbsolutePath())
                    .connTimeOut(20)
                    .readTimeOut(20)
                    .writeTimeOut(20)
                    .retryCount(2)
                    .concurrentCount(3) //并发数
                    .context(this)
                    .saveCover(true)
                    .openDb(true)
                    .userAgent(AppConfig.UserAgent)
                    .decryptM3u8(true)
                    .mergeM3u8(false)
                    .threadSchedule(false)
                    .downloadMode(DownloadMode.DEFAULT)
                    .build();
            VideoDownloadManager.getInstance().initConfig(config);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
