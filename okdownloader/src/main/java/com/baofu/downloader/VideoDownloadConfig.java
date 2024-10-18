package com.baofu.downloader;

import android.content.Context;

import com.baofu.downloader.common.DownloadConstants;
import com.baofu.downloader.utils.ContextUtils;

public class VideoDownloadConfig {

    //公共目录保存路径
    public String publicPath;
    //私有目录保存路径
    public String privatePath;
    //保存到公共目录
    public boolean saveAsPublic;
    private int readTimeOut;
    private int connTimeOut;
    private int writeTimeOut;
    public boolean ignoreAllCertErrors;
    //并发下载数
    public int concurrentCount;
    public boolean shouldM3U8Merged;
    public boolean rangeDownload;
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


    public int getReadTimeOut(){
        if(readTimeOut==0){
            return DownloadConstants.READ_TIMEOUT;
        }
        return readTimeOut;
    }
    public int getConnTimeOut(){
        if(connTimeOut==0){
            return DownloadConstants.CONN_TIMEOUT;
        }
        return connTimeOut;
    }
    public int getWriteTimeOut(){
        if(writeTimeOut==0){
            return DownloadConstants.WRITE_TIMEOUT;
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

        public Builder ignoreAllCertErrors(boolean ignoreAllCertErrors) {
            mConfig.ignoreAllCertErrors = ignoreAllCertErrors;
            return this;
        }


        public Builder concurrentCount(int concurrentCount) {
            mConfig.concurrentCount = concurrentCount;
            return this;
        }


        public Builder shouldM3U8Merged(boolean shouldM3U8Merged) {
            mConfig.shouldM3U8Merged = shouldM3U8Merged;
            return this;
        }

        public Builder rangeDownload(boolean rangeDownload) {
            mConfig.rangeDownload = rangeDownload;
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
        public Builder saveAsPublic(boolean saveAsPublic) {
            mConfig.saveAsPublic = saveAsPublic;
            return this;
        }
        public Builder retryCount(int retryCount) {
            mConfig.retryCount = retryCount;
            return this;
        }
        public VideoDownloadConfig build() {
            return mConfig;
        }
    }

}
