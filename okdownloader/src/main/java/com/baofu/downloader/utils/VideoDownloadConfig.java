package com.baofu.downloader.utils;

import android.content.Context;

import com.baofu.downloader.common.VideoDownloadConstants;

public class VideoDownloadConfig {

    //公共目录保存路径
    public String publicPath;
    //私有目录保存路径
    public String privatePath;
    private int readTimeOut;
    private int connTimeOut;
    private int writeTimeOut;
    //并发下载数
    public int concurrentCount;
    public Context context;
    public boolean saveCover;
    //开启数据库存储，会将下载进度等数据都保存到数据库
    public boolean openDb;
    public String userAgent;
    //对m3u8下载完后进行解密，解密后的ts都可以播放
    public boolean decryptM3u8;
    //下载完成后合成m3u8
    public boolean mergeM3u8;
    //下载失败重试次数
    public int retryCount = 1;
    //true:使用workmanager下载，false:使用service下载
    public int downloadMode;
    public boolean threadSchedule;


    public int getReadTimeOut(){
        if(readTimeOut==0){
            return VideoDownloadConstants.READ_TIMEOUT;
        }
        return readTimeOut;
    }
    public int getConnTimeOut(){
        if(connTimeOut==0){
            return VideoDownloadConstants.CONN_TIMEOUT;
        }
        return connTimeOut;
    }
    public int getWriteTimeOut(){
        if(writeTimeOut==0){
            return VideoDownloadConstants.WRITE_TIMEOUT;
        }
        return writeTimeOut;
    }
    public static class Builder {
        VideoDownloadConfig mConfig;

        public Builder( Context context) {
            mConfig = new VideoDownloadConfig();
            ContextUtils.initApplicationContext(context);
        }

        public Builder publicPath(String publicPath) {
            mConfig.publicPath = publicPath;
            return this;
        }


        public Builder privatePath(String privatePath) {
            mConfig.privatePath = privatePath;
            return this;
        }


        public Builder readTimeOut(int readTimeOut) {
            mConfig.readTimeOut = readTimeOut;
            return this;
        }


        public Builder connTimeOut(int connTimeOut) {
            mConfig.connTimeOut = connTimeOut;
            return this;
        }
        public Builder writeTimeOut(int writeTimeOut) {
            mConfig.writeTimeOut = writeTimeOut;
            return this;
        }



        public Builder concurrentCount(int concurrentCount) {
            mConfig.concurrentCount = concurrentCount;
            return this;
        }


        public Builder context(Context context) {
            mConfig.context = context;
            return this;
        }

        public Builder saveCover(boolean saveCover) {
            mConfig.saveCover = saveCover;
            return this;
        }
        public Builder openDb(boolean openDb) {
            mConfig.openDb = openDb;
            return this;
        }
        public Builder userAgent(String userAgent) {
            mConfig.userAgent = userAgent;
            return this;
        }
        public Builder decryptM3u8(boolean decryptM3u8) {
            mConfig.decryptM3u8 = decryptM3u8;
            return this;
        }

        public Builder mergeM3u8(boolean mergeM3u8) {
            mConfig.mergeM3u8 = mergeM3u8;
            return this;
        }
        public Builder downloadMode(int downloadMode) {
            mConfig.downloadMode = downloadMode;
            return this;
        }
        public Builder retryCount(int retryCount) {
            mConfig.retryCount = retryCount;
            return this;
        }
        public Builder threadSchedule(boolean threadSchedule) {
            mConfig.threadSchedule = threadSchedule;
            return this;
        }
        public VideoDownloadConfig build() {
            return mConfig;
        }
    }

}
