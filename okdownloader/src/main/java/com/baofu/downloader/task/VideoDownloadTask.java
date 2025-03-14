package com.baofu.downloader.task;

import com.baofu.downloader.listener.IDownloadTaskListener;
import com.baofu.downloader.model.VideoTaskItem;
import com.baofu.downloader.utils.VideoDownloadUtils;
import java.io.File;
import java.util.HashMap;
import java.util.concurrent.ExecutorService;

public abstract class VideoDownloadTask {
    //参数初始化
    public static final int CPU_COUNT = Runtime.getRuntime().availableProcessors();
    //线程池最大容纳线程数
    public static final int maximumPoolSize = CPU_COUNT * 2 + 1;
    protected static final int THREAD_COUNT = maximumPoolSize;
    protected static final int BUFFER_SIZE = VideoDownloadUtils.DEFAULT_BUFFER_SIZE;
    protected final VideoTaskItem mTaskItem;
    protected final String mFinalUrl;
    protected File mSaveDir;
    protected String mSaveName;
    protected ExecutorService mDownloadExecutor;
    protected IDownloadTaskListener mDownloadTaskListener;
    protected long mLastCachedSize = 0L;
    protected long mCurrentCachedSize = 0L;
    protected long mLastInvokeTime = 0L;
    protected float mSpeed = 0.0f;
    protected float mPercent = 0.01f;

    protected VideoDownloadTask(VideoTaskItem taskItem) {
        mTaskItem = taskItem;
        mFinalUrl = taskItem.getFinalUrl();
        initSaveDir();
    }

    public void setDownloadTaskListener(IDownloadTaskListener listener) {
        mDownloadTaskListener = listener;
    }

    public abstract void startDownload();

    public abstract void resumeDownload();

    public abstract void pauseDownload();

    public abstract void cancle();

    public abstract void delete();

    protected void notifyOnTaskPaused() {
        if (mDownloadTaskListener != null) {
            mDownloadTaskListener.onTaskPaused();
        }
    }

    protected void notifyOnTaskFailed(Exception e) {

        if(mDownloadTaskListener!=null){
            mDownloadTaskListener.onTaskFailed(e);
        }
    }

    public abstract void initSaveDir();
}
