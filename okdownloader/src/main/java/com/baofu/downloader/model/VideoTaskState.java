package com.baofu.downloader.model;

public class VideoTaskState {
    public static final int DEFAULT = 0;//默认状态

    public static final int QUEUING = -2;//排队中
    public static final int PENDING = -1;//正在链接
    public static final int PREPARE = 1;//下载准备中
    public static final int START = 2;  //开始下载
    public static final int DOWNLOADING = 3;//下载中
    public static final int PROXYREADY = 4; //视频可以边下边播
    public static final int SUCCESS = 5;//下载完成
    public static final int ERROR = 6;//下载出错
    public static final int PAUSE = 7;//下载暂停
    public static final int ENOSPC = 8;//空间不足
    public static final int MERGE = 9;//合并mp4中
}
