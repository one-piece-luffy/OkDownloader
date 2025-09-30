package com.baofu.downloader.task;

import android.os.Build;
import android.os.Environment;
import android.text.TextUtils;
import android.util.Log;

import com.baofu.downloader.rules.VideoDownloadManager;
import com.baofu.downloader.common.VideoDownloadConstants;
import com.baofu.downloader.factory.Android10FastFactory;
import com.baofu.downloader.factory.Android9Factory;
import com.baofu.downloader.factory.IDownloadFactory;
import com.baofu.downloader.listener.IFactoryListener;
import com.baofu.downloader.model.VideoTaskItem;
import com.baofu.downloader.utils.DownloadExecutor;
import com.baofu.downloader.utils.VideoDownloadUtils;

import java.io.File;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;


public class AllDownloadTask extends VideoDownloadTask {

    private static final String TAG = "AllDownloadTask";

    private long mTotalLength;

    private final int THREAD_COUNT = 5;//线程数

//    private volatile boolean isDownloading = false;
    final AtomicBoolean isDownloading = new AtomicBoolean(false);
    IDownloadFactory downloadFactory;
    IFactoryListener iFactoryListener=new IFactoryListener() {
        @Override
        public void onError(Exception e) {
            notifyDownloadError(e);
        }

        @Override
        public void onFinish() {
            notifyDownloadFinish();
        }

        @Override
        public void onProgress(long progress,long total,boolean hasFileLength) {
            notifyDownloadProgress(progress,total,hasFileLength);
        }

        @Override
        public void onReset() {
            resetStutus();
        }

    };
    public AllDownloadTask(VideoTaskItem taskItem) {
        super(taskItem);


    }

    private void notifyDownloadProgress(long progress,long total,boolean hasFileLength) {
        mTotalLength=total;
        float p = progress * 1.0f * 100 / total;
        Log.e(TAG, "===cur:" + progress + " total:" + total + " pencent:" + p);
        if(!isDownloading.get()){
            return;
        }
        if (progress >= total) {
            mDownloadTaskListener.onTaskProgress(100, total, total, mSpeed);
            mPercent = 100.0f;
            notifyDownloadFinish();
        } else {
            long nowTime = System.currentTimeMillis();
            float percent = progress * 1.0f * 100 / total;
            if ((hasFileLength && !VideoDownloadUtils.isFloatEqual(percent, mPercent) && nowTime - mLastInvokeTime.get() >= 100) ||
                    (!hasFileLength && nowTime - mLastInvokeTime.get() >= 1000)) {
//                Log.i(TAG, "cur:" + progress + " mLastCachedSize:" + mLastCachedSize + " total:" + mTotalLength + " pencent:" + percent);
                if (progress > mLastCachedSize && nowTime > mLastInvokeTime.get()) {
                    mSpeed = (progress - mLastCachedSize) * 1000 * 1.0f / ((nowTime - mLastInvokeTime.get()));
                }
//                7677884
                mDownloadTaskListener.onTaskProgress(percent, progress, total, mSpeed);
                mPercent = percent;
                mLastInvokeTime.set(nowTime);
                mLastCachedSize = progress;
            }else{

            }

        }
    }

    private void notifyDownloadError(Exception e) {


        mPercent = 100.0f;
        String fileName = "";

        if (TextUtils.isEmpty(mTaskItem.suffix)) {
            fileName = mSaveName + VideoDownloadUtils.VIDEO_SUFFIX;
        } else {
            fileName = mSaveName + mTaskItem.suffix;
        }
        String relativePath= Environment.DIRECTORY_DOWNLOADS + "/" + VideoDownloadManager.getInstance().downloadDir+"/";
        String filePath=mTaskItem.getSaveDir()+ File.separator +fileName;
        VideoDownloadUtils.deleteFile(VideoDownloadManager.getInstance().mConfig.context,filePath,relativePath,fileName);
        notifyOnTaskFailed(e);
        e.printStackTrace();
        Log.e("asdf", "error url:" + mTaskItem.getUrl());
        Log.e("asdf", "error message:" + e.getMessage());
        isDownloading.set(false);
        mDownloadTaskListener.onTaskProgress(100, mTotalLength, mTotalLength, VideoDownloadConstants.ERROR_SPEED);
    }

    private void notifyDownloadFinish() {
//        if (confirmStatus(childFinshCount))
//            return;
//        childFinshCount.set(0);
        if(TextUtils.isEmpty(mTaskItem.getFilePath())){
            return;
        }
        resetStutus();
        mDownloadTaskListener.onTaskFinished(mTotalLength);
        Log.e(TAG, "notifyDownloadFinish ");
    }


    @Override
    public void startDownload() {
        isDownloading.set(false);
        mDownloadTaskListener.onTaskStart(mTaskItem.getUrl());

        start();
    }

    @Override
    public void pauseDownload() {
        if(downloadFactory!=null){
            downloadFactory.pause();
        }
        notifyOnTaskPaused();
    }

    @Override
    public void cancle() {
        if(downloadFactory!=null){
            downloadFactory.cancel();
        }
        resetStutus();
    }

    @Override
    public void delete() {
        if (downloadFactory != null) {
            downloadFactory.delete();
        }
    }

    @Override
    public void initSaveDir() {
        //todo
        if (mTaskItem.privateFile) {
            mSaveDir = new File(VideoDownloadManager.getInstance().mConfig.privatePath);
        } else {
            mSaveDir = new File(VideoDownloadManager.getInstance().mConfig.publicPath);
        }

        if (!mSaveDir.exists()) {
            mSaveDir.mkdir();
        }
        mTaskItem.setSaveDir(mSaveDir.getAbsolutePath());
    }

    @Override
    public void resumeDownload() {
        startDownload();
    }

    public synchronized void start() {
        try {
//            Log.e(TAG, "start: " + isDownloading + "\t" + mTaskItem.getUrl());
            if (isDownloading.get()) return;
            isDownloading.set(true);
            if (mTaskItem.privateFile) {
                downloadFactory = new Android9Factory(mTaskItem, mSaveDir, iFactoryListener);
                downloadFactory.download();
            } else {

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    downloadFactory = new Android10FastFactory(mTaskItem, iFactoryListener);
                    downloadFactory.download();
                } else {
                    downloadFactory = new Android9Factory(mTaskItem, mSaveDir, iFactoryListener);
                    downloadFactory.download();
                }
            }
            File coverFile = new File(mSaveDir, mTaskItem.mName+VideoDownloadConstants.COVER_SUFFIX);
            if (VideoDownloadManager.getInstance().mConfig.saveCover && (!coverFile.exists() || coverFile.length() == 0)) {
                //下载封面
                DownloadExecutor.execute(() -> {
                    try {
                        downloadCover(coverFile, mTaskItem.mCoverUrl);
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                });
            } 

        } catch (Exception e) {
            e.printStackTrace();
            resetStutus();
        }
    }



    /**
     * 重置下载状态
     */
    private void resetStutus() {
        isDownloading.set(false);
    }

    /**
     * 确认下载状态
     *
     * @param count
     * @return
     */
    private boolean confirmStatus(AtomicInteger count) {
        return count.incrementAndGet() % THREAD_COUNT != 0;
    }

    public boolean isDownloading() {
        return isDownloading.get();
    }


}
