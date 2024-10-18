package com.baofu.downloader.listener;

public interface IDownloadTaskListener {

    void onTaskStart(String url);

    void onTaskProgress(float percent, long cachedSize, long totalSize, float speed);

    void onTaskProgressForM3U8(float percent, long cachedSize, int curTs, int totalTs, float speed);
    void onTaskM3U8Merge();

    void onTaskPaused();

    void onTaskFinished(long totalSize);

    void onTaskFailed(Exception e);

}
