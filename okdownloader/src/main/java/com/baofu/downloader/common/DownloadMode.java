package com.baofu.downloader.common;

public interface DownloadMode {
    //默认模式，单纯启用线程下载
    int DEFAULT = 0;
    //用intent service下载
    int INTENT_SERVICE = 1;
    //用workmanager下载
    int WORKER = 2;
}
