package com.baofu.downloader.rules;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.Message;
import android.text.TextUtils;
import android.util.Log;

import androidx.work.Constraints;
import androidx.work.Data;
import androidx.work.NetworkType;
import androidx.work.OneTimeWorkRequest;
import androidx.work.WorkManager;

import com.baofu.downloader.VideoDownloadQueue;
import com.baofu.downloader.VideoInfoParserManager;
import com.baofu.downloader.common.VideoDownloadConstants;
import com.baofu.downloader.database.VideoDownloadDatabaseHelper;
import com.baofu.downloader.listener.DownloadListener;
import com.baofu.downloader.listener.IDownloadInfosCallback;
import com.baofu.downloader.listener.IDownloadListener;
import com.baofu.downloader.listener.IDownloadTaskListener;
import com.baofu.downloader.listener.IVideoInfoListener;
import com.baofu.downloader.listener.IVideoInfoParseListener;
import com.baofu.downloader.m3u8.M3U8;
import com.baofu.downloader.model.Video;
import com.baofu.downloader.model.VideoTaskItem;
import com.baofu.downloader.model.VideoTaskState;
import com.baofu.downloader.service.DownloadService;
import com.baofu.downloader.task.AllDownloadTask;
import com.baofu.downloader.task.M3U8VideoDownloadTask;
import com.baofu.downloader.task.VideoDownloadTask;
import com.baofu.downloader.utils.ContextUtils;
import com.baofu.downloader.utils.DownloadExceptionUtils;
import com.baofu.downloader.utils.DownloadExecutor;
import com.baofu.downloader.utils.DownloadWorker;
import com.baofu.downloader.utils.LogUtils;
import com.baofu.downloader.utils.OkHttpUtil;
import com.baofu.downloader.utils.UniqueIdGenerator;
import com.baofu.downloader.utils.VideoDownloadConfig;
import com.baofu.downloader.utils.VideoDownloadUtils;
import com.baofu.downloader.utils.VideoStorageUtils;
import com.baofu.downloader.utils.WorkerThreadHandler;
import com.baofu.downloader.utils.notification.DownloadNotificationUtil;
import com.baofu.downloader.utils.notification.NotificationBuilderManager;

import java.io.BufferedReader;
import java.io.File;
import java.io.Reader;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import okhttp3.Response;

public class VideoDownloadManager {
    private static final String TAG = "VideoDownloadManager";
    private static final String TAG2 = "VideoDownloadManager: ";
    public String downloadDir="album";
    private static volatile VideoDownloadManager sInstance = null;
    private DownloadListener mGlobalDownloadListener = null;
    private VideoDownloadDatabaseHelper mVideoDatabaseHelper = null;
    //下载队列
    public VideoDownloadQueue mVideoDownloadQueue;
    private final Object mQueueLock = new Object();
    public VideoDownloadConfig mConfig;
    private final Object mLock = new Object();
    private VideoDownloadHandler mVideoDownloadHandler;
    //下载回调
    private final List<IDownloadInfosCallback> mDownloadInfoCallbacks = new CopyOnWriteArrayList<>();
    private final Map<String, VideoDownloadTask> mVideoDownloadTaskMap = new ConcurrentHashMap<>();
    public final Map<String, VideoTaskItem> mVideoItemTaskMap = new ConcurrentHashMap<>();
    public Map mDownloadReplace;
    public Map<String, IDownloadListener> mDownloadListener = new ConcurrentHashMap<>();
    //有下载任务就加到map里，用于获取打包下载时的进度
    public Map<String, VideoTaskItem> mDownloadTaskMap = new ConcurrentHashMap<>();

    //正在下载的队列
    public VideoDownloadQueue mRunningQueue = new VideoDownloadQueue();
    //下载队列
    public VideoDownloadQueue mDownloadQueue = new VideoDownloadQueue();
    int count = 0;


    public Map<String, VideoTaskItem> getDownloadTaskMap() {
        return mDownloadTaskMap;
    }

    public void addDownloadListener(String url, IDownloadListener listener) {
        mDownloadListener.put(url, listener);
    }

    public static VideoDownloadManager getInstance() {
        if (sInstance == null) {
            synchronized (VideoDownloadManager.class) {
                if (sInstance == null) {
                    sInstance = new VideoDownloadManager();
                }
            }
        }
        return sInstance;
    }

    private VideoDownloadManager() {
        mVideoDownloadQueue = new VideoDownloadQueue();
    }

    public void initConfig(VideoDownloadConfig config) {
        //如果为null, 会crash
        mConfig = config;
        mVideoDatabaseHelper = new VideoDownloadDatabaseHelper(ContextUtils.getApplicationContext());
        HandlerThread stateThread = new HandlerThread("Video_download_state_thread");
        stateThread.start();
        mVideoDownloadHandler = new VideoDownloadHandler(stateThread.getLooper());
    }



    public void removeDownloadInfosCallback(IDownloadInfosCallback callback) {
        mDownloadInfoCallbacks.remove(callback);
    }

    public void setGlobalDownloadListener(DownloadListener downloadListener) {
        mGlobalDownloadListener = downloadListener;
    }

    public void startDownload(Context context, VideoTaskItem taskItem) {
        if (taskItem.notify) {
            //启动前台服务下载
            if (taskItem.notificationId <= 0) {
                taskItem.notificationId = UniqueIdGenerator.generateUniqueId();
            }
            if (mConfig != null && !mConfig.useWorker) {
                Intent intent = new Intent(context, DownloadService.class);
                taskItem.putExtra(intent);
                context.startService(intent);
            } else {
                Data inputData = taskItem.putWorkerData();
                Constraints constraints=null;

                // 创建联网约束
                if (taskItem.onlyWifi) {
                    constraints = new Constraints.Builder()
                            .setRequiredNetworkType(NetworkType.UNMETERED) // 要求设备链接wifi
                            .build();
                } else {
                    constraints = new Constraints.Builder()
                            .setRequiredNetworkType(NetworkType.CONNECTED) // 要求设备联网
                            .build();
                }


                OneTimeWorkRequest workRequest = new OneTimeWorkRequest.Builder(DownloadWorker.class)
//                    .setInitialDelay(0, TimeUnit.MILLISECONDS)  // 立即执行，延迟0毫秒执行
                        .setInputData(inputData)
                        .setConstraints(constraints)
                        .build();
                // 将工作请求提交给 WorkManager
                WorkManager.getInstance(context).enqueue(workRequest);
            }
        } else {
            startDownload2(taskItem);
        }
    }

    public void startDownload2(VideoTaskItem taskItem) {
        if (taskItem == null || TextUtils.isEmpty(taskItem.getUrl()))
            return;
        taskItem.createTime=System.currentTimeMillis();
        synchronized (mQueueLock) {
            if (mVideoDownloadQueue.contains(taskItem)) {
                try {
                    VideoTaskItem item = mVideoDownloadQueue.getTaskItem(taskItem.getUrl());
                    if (item != null) {
                        taskItem = item;
                    }
                }catch (Exception e){
                    Log.e(TAG, "发生异常: ", e); 
                }
            } else {
                mVideoDownloadQueue.offer(taskItem);
                mDownloadQueue.offer(taskItem);
            }
        }

        taskItem.setPaused(false);
        taskItem.setDownloadCreateTime(taskItem.getDownloadCreateTime());
        taskItem.setTaskState(VideoTaskState.PENDING);
        VideoTaskItem tempTaskItem = (VideoTaskItem) taskItem.clone();
        mVideoDownloadHandler.obtainMessage(VideoDownloadConstants.MSG_DOWNLOAD_PENDING, tempTaskItem).sendToTarget();
        // 保存到数据库
        handleOnDownloadPrepare(taskItem);
        synchronized (mQueueLock) {
            //超过配置的并发数直接返回
            if (mRunningQueue.size() >= mConfig.concurrentCount) {
                taskItem.setTaskState(VideoTaskState.QUEUING);
                mVideoDownloadHandler.obtainMessage(VideoDownloadConstants.MSG_DOWNLOAD_QUEUING, taskItem).sendToTarget();
                return;
            }
//            if (mRunningQueue.size() >= mConfig.concurrentCount) {
//                taskItem.setTaskState(VideoTaskState.QUEUING);
//                mVideoDownloadHandler.obtainMessage(DownloadConstants.MSG_DOWNLOAD_QUEUING, taskItem).sendToTarget();
//                return;
//            }
            mRunningQueue.offer(taskItem);

        }
        count++;
        parseVideoDownloadInfo(taskItem);
        Log.e(TAG, "下载文件的个数:" + count);
    }






    private void parseVideoDownloadInfo(VideoTaskItem taskItem) {
        boolean taskExisted = taskItem.getDownloadCreateTime() != 0;

        if (taskExisted) {
            Log.i(TAG, "download from local");
            parseExistVideoDownloadInfo(taskItem);
        } else if (!mConfig.saveCover && taskItem.getTotalSize() > 0 && !".m3u8".equals(taskItem.suffix)) {
            startBaseVideoDownloadTask(taskItem);
        } else if (taskItem.skipM3u8) {
            startBaseVideoDownloadTask(taskItem);
        } else {
            parseNetworkVideoInfo(taskItem);
        }
    }

    private void parseExistVideoDownloadInfo(final VideoTaskItem taskItem) {
        if (taskItem.isHlsType()) {
            VideoInfoParserManager.getInstance().parseLocalM3U8File(taskItem, new IVideoInfoParseListener() {
                @Override
                public void onM3U8FileParseSuccess(VideoTaskItem info, M3U8 m3u8) {
                    startM3U8VideoDownloadTask(taskItem, m3u8);
                }

                @Override
                public void onM3U8FileParseFailed(VideoTaskItem info, Throwable error) {
                    parseNetworkVideoInfo(taskItem);
                }
            });
        } else {
            startBaseVideoDownloadTask(taskItem);
        }
    }

    private void parseNetworkVideoInfo(final VideoTaskItem taskItem) {
        DownloadExecutor.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    String method = OkHttpUtil.METHOD.GET;
                    if (OkHttpUtil.METHOD.POST.equalsIgnoreCase(taskItem.method)) {
                        method = OkHttpUtil.METHOD.POST;
                    }
                    Response response = OkHttpUtil.getInstance().requestSync(taskItem.getUrl(),method,VideoDownloadUtils.getTaskHeader(taskItem));
                    if (response == null) {
                        int errorCode = -1;
                        taskItem.setErrorCode(errorCode);
                        Exception e = new Exception(TAG2+"parseNetworkVideoInfo: response is null");
                        notifyError(taskItem, e);
                        return;
                    }
                    if (!response.isSuccessful()) {
                        int errorCode = response.code();
                        taskItem.setErrorCode(errorCode);
                        Exception e = new Exception(TAG2+"parseNetworkVideoInfo: error code:" + errorCode);
                        notifyError(taskItem, e);
                        return;
                    }
                    String contentType = response.header("Content-Type");
                    boolean isM3u8Txt = false;
                    if (contentType != null && contentType.startsWith("text")) {
                        //处理m3u8伪装成txt或者html的情况
                        try {
                            Reader reader = response.body().charStream();// 获取流 response.body().bytes().
                            BufferedReader bufferedReader = new BufferedReader(reader);
                            String result = bufferedReader.readLine();
                            if (result != null && result.equals("#EXTM3U")) {
                                isM3u8Txt = true;
                            }
                            VideoDownloadUtils.close(bufferedReader, reader);
                        } catch (Exception e) {
                            Log.e(TAG, "发生异常: ", e); 
                        }

                    }
                    VideoDownloadUtils.close(response);
                    if (taskItem.getUrl().contains(Video.TypeInfo.M3U8) || VideoDownloadUtils.isM3U8Mimetype(contentType) || isM3u8Txt) {
                        //这是M3U8视频类型
                        taskItem.setMimeType(Video.TypeInfo.M3U8);
                        VideoInfoParserManager.getInstance().parseNetworkM3U8Info(taskItem, VideoDownloadUtils.getTaskHeader(taskItem), new IVideoInfoListener() {
                            @Override
                            public void onFinalUrl(String finalUrl) {

                            }

                            @Override
                            public void onBaseVideoInfoSuccess(VideoTaskItem info) {

                            }

                            @Override
                            public void onBaseVideoInfoFailed(Exception error) {

                            }

                            @Override
                            public void onM3U8InfoSuccess(VideoTaskItem info, M3U8 m3u8) {
                                taskItem.setMimeType(info.getMimeType());
                                startM3U8VideoDownloadTask(taskItem, m3u8);
                            }

                            @Override
                            public void onLiveM3U8Callback(VideoTaskItem info) {
                                LogUtils.w(TAG, "onLiveM3U8Callback cannot be cached.");
                                taskItem.setErrorCode(DownloadExceptionUtils.LIVE_M3U8_ERROR);
                                taskItem.setTaskState(VideoTaskState.ERROR);
                                mVideoDownloadHandler.obtainMessage(VideoDownloadConstants.MSG_DOWNLOAD_ERROR, taskItem).sendToTarget();
                            }

                            @Override
                            public void onM3U8InfoFailed(Exception e) {
                                LogUtils.w(TAG, "onM3U8InfoFailed : " + e);
                                notifyError(taskItem,e);
                            }
                        });
                    } else {
                        //这不是M3U8视频类型
                        startBaseVideoDownloadTask(taskItem);
                    }
                } catch (Exception e) {
                    Log.e(TAG, "发生异常: ", e); 
//                    try {
//                        for (Call call : OkHttpUtil.getInstance().mOkHttpClient.dispatcher().queuedCalls()) {
//                            if (taskItem.getUrl().equals(call.request().url().url().toString())) {
//                                call.cancel();
//                            }
//                        }
//                    } catch (Exception ex) {
//                        ex.printStackTrace();
//                    }
                    Exception exception = new Exception(TAG2 + "parseNetworkVideoInfo: "+e.getMessage());
                    notifyError(taskItem, exception);
                }
            }
        });
    }

    /**
     * 解析已经预加载过的m3u8
     *
     * @param taskItem
     */
    private void parseCacheVideoDownloadInfo(final VideoTaskItem taskItem) {
        VideoInfoParserManager.getInstance().parseLocalM3U8File(taskItem, new IVideoInfoParseListener() {
            @Override
            public void onM3U8FileParseSuccess(VideoTaskItem info, M3U8 m3u8) {
                Log.i(TAG, "download from suc");
                String saveName = VideoDownloadUtils.computeMD5(info.getUrl());
                taskItem.setSaveDir(mConfig.publicPath + "/" + saveName);
                taskItem.setVideoType(Video.Type.HLS_TYPE);
                startM3U8VideoDownloadTask(taskItem, m3u8);
            }

            @Override
            public void onM3U8FileParseFailed(VideoTaskItem info, Throwable error) {
                Log.i(TAG, "download from fail");
                parseNetworkVideoInfo(taskItem);
            }
        });
    }

    private void startM3U8VideoDownloadTask(final VideoTaskItem taskItem, M3U8 m3u8) {

        if ( mVideoDownloadQueue.contains(taskItem)) {
            taskItem.setTaskState(VideoTaskState.PREPARE);
            mVideoItemTaskMap.put(taskItem.getUrl(), taskItem);
            VideoTaskItem tempTaskItem = (VideoTaskItem) taskItem.clone();
            mVideoDownloadHandler.obtainMessage(VideoDownloadConstants.MSG_DOWNLOAD_PREPARE, tempTaskItem).sendToTarget();
//        synchronized (mQueueLock) {
//            if (mVideoDownloadQueue.getDownloadingCount() >= mConfig.getConcurrentCount()) {
//                return;
//            }
//        }
            VideoDownloadTask downloadTask = mVideoDownloadTaskMap.get(taskItem.getUrl());
            if (downloadTask != null) {
                downloadTask.cancle();
            }

            downloadTask = new M3U8VideoDownloadTask(taskItem, m3u8);
            mVideoDownloadTaskMap.put(taskItem.getUrl(), downloadTask);
            startDownloadTask(downloadTask, taskItem);

        } else {
            //批量删除任务的时候，可能网络请求还没返回，任务还没开始下载就被删除了，那就不继续下载了
        }


    }

    private void startBaseVideoDownloadTask(VideoTaskItem taskItem) {


        if (mVideoDownloadQueue.contains(taskItem)) {
            taskItem.setTaskState(VideoTaskState.PREPARE);
            mVideoItemTaskMap.put(taskItem.getUrl(), taskItem);
            VideoTaskItem tempTaskItem = (VideoTaskItem) taskItem.clone();
            mVideoDownloadHandler.obtainMessage(VideoDownloadConstants.MSG_DOWNLOAD_PREPARE, tempTaskItem).sendToTarget();
//        synchronized (mQueueLock) {
//            if (mVideoDownloadQueue.getRunningCount() >= mConfig.getConcurrentCount()) {
//                return;
//            }
//        }
            VideoDownloadTask downloadTask = mVideoDownloadTaskMap.get(taskItem.getUrl());
            if (downloadTask != null) {
                downloadTask.cancle();
            }
            downloadTask = new AllDownloadTask(taskItem);
            mVideoDownloadTaskMap.put(taskItem.getUrl(), downloadTask);
            startDownloadTask(downloadTask, taskItem);

        } else {
            //todo
            //批量删除任务的时候，可能网络请求还没返回，任务还没开始下载就被删除了，那就不继续下载了
        }

    }

    private void startDownloadTask(VideoDownloadTask downloadTask, VideoTaskItem taskItem) {
        Log.w(TAG, "============startDownloadTask");


        if (downloadTask != null) {

            downloadTask.setDownloadTaskListener(new IDownloadTaskListener() {
                @Override
                public void onTaskStart(String url) {

                    synchronized (mQueueLock) {
                        taskItem.setTaskState(VideoTaskState.START);
                        mVideoDownloadHandler.obtainMessage(VideoDownloadConstants.MSG_DOWNLOAD_START, taskItem).sendToTarget();
//                            mDownloadTaskMap.put(taskItem.getUrl(), taskItem);
                        IDownloadListener listener = mDownloadListener.get(taskItem.getUrl());
                        if (listener != null) {
                            listener.onDownloadStart(taskItem);
                        }
                        if(taskItem.notify){
                            DownloadNotificationUtil util = new DownloadNotificationUtil();
                            util.createNotification(VideoDownloadManager.getInstance().mConfig.context, taskItem);
                        }


                    }
                }

                @Override
                public void onTaskProgress(float percent, long cachedSize, long totalSize, float speed) {
                    if (!taskItem.isPaused()) {
                        if (speed == VideoDownloadConstants.ERROR_SPEED) {
                            taskItem.setTaskState(VideoTaskState.ERROR);
                        } else {
                            taskItem.setTaskState(VideoTaskState.DOWNLOADING);
                        }
                        taskItem.setPercent(percent);
                        taskItem.setSpeed(speed);
                        taskItem.setIsCompleted(false);
                        taskItem.setDownloadSize(cachedSize);
                        taskItem.setTotalSize(totalSize);
                        //刷新map
                        mDownloadTaskMap.put(taskItem.getUrl(),taskItem);
                        mVideoDownloadHandler.obtainMessage(VideoDownloadConstants.MSG_DOWNLOAD_PROCESSING, taskItem).sendToTarget();
                        IDownloadListener listener = mDownloadListener.get(taskItem.getUrl());
                        if (listener != null) {
                            listener.onDownloadProgress(taskItem);
                        }
                        if(taskItem.notify){
                            NotificationBuilderManager.getInstance().updateNotification(VideoDownloadManager.getInstance().mConfig.context,null,taskItem);
                        }
                    }
                }

                @Override
                public void onTaskProgressForM3U8(float percent, long cachedSize, int curTs, int totalTs, float speed) {
                    if (!taskItem.isPaused()) {
                        taskItem.setTaskState(VideoTaskState.DOWNLOADING);
                        taskItem.setPercent(percent);
                        taskItem.setSpeed(speed);
                        taskItem.setIsCompleted(false);
                        taskItem.setDownloadSize(cachedSize);
                        taskItem.setCurTs(curTs);
                        taskItem.setTotalTs(totalTs);
                        mVideoDownloadHandler.obtainMessage(VideoDownloadConstants.MSG_DOWNLOAD_PROCESSING, taskItem).sendToTarget();
                        IDownloadListener listener = mDownloadListener.get(taskItem.getUrl());
                        if (listener != null) {
                            listener.onDownloadProgress(taskItem);
                        }
                        if(taskItem.notify){
                            NotificationBuilderManager.getInstance().updateNotification(VideoDownloadManager.getInstance().mConfig.context,null,taskItem);
                        }
                    }
                }

                @Override
                public void onTaskM3U8Merge() {
                    taskItem.setTaskState(VideoTaskState.MERGE);
                    mVideoDownloadHandler.obtainMessage(VideoDownloadConstants.MSG_DOWNLOAD_MERGE, taskItem).sendToTarget();
                    IDownloadListener listener = mDownloadListener.get(taskItem.getUrl());
                    if (listener != null) {
                        listener.onDownloadMerge(taskItem);
                    }
                }

                @Override
                public void onTaskPaused() {
                    if (!taskItem.isErrorState() || !taskItem.isSuccessState()) {
                        taskItem.setTaskState(VideoTaskState.PAUSE);
                        taskItem.setPaused(true);
                        mVideoDownloadHandler.obtainMessage(VideoDownloadConstants.MSG_DOWNLOAD_PAUSE, taskItem).sendToTarget();
                        mVideoDownloadHandler.removeMessages(VideoDownloadConstants.MSG_DOWNLOAD_PROCESSING);
                        IDownloadListener listener = mDownloadListener.get(taskItem.getUrl());
                        if (listener != null) {
                            listener.onDownloadPause(taskItem);
                        }
                    }
                }

                @Override
                public void onTaskFinished(long totalSize) {
                    if (taskItem.getTaskState() != VideoTaskState.SUCCESS) {
                        Log.e("asdf","onTaskFinished");
                        taskItem.newFile = 1;
                        taskItem.setDownloadSize(totalSize);
                        taskItem.setTotalSize(totalSize);
                        taskItem.setIsCompleted(true);
                        taskItem.setPercent(100f);
                        if (taskItem.merged) {
                            return;
                        }

                        if (taskItem.isHlsType()) {
                            Log.e(TAG, "finish:" + taskItem.getSaveDir());


                            markDownloadFinishEvent(taskItem);
                            taskItem.setTaskState(VideoTaskState.SUCCESS);
                            mDownloadTaskMap.put(taskItem.getUrl(),taskItem);  //刷新map
                            mVideoDownloadHandler.obtainMessage(VideoDownloadConstants.MSG_DOWNLOAD_SUCCESS, taskItem).sendToTarget();

                        } else {
                            Log.e(TAG, "finish filepath:" + taskItem.getFilePath());
                            taskItem.setTaskState(VideoTaskState.SUCCESS);
                            mDownloadTaskMap.put(taskItem.getUrl(),taskItem);  //刷新map
                            mVideoDownloadHandler.obtainMessage(VideoDownloadConstants.MSG_DOWNLOAD_SUCCESS, taskItem).sendToTarget();
                        }
                        //刷新相册
                        VideoDownloadUtils.scanAlbum(mConfig.context,taskItem.getFilePath());

                        IDownloadListener listener = mDownloadListener.get(taskItem.getUrl());
                        if (listener != null) {
                            listener.onDownloadSuccess(taskItem);
                        }
                        mVideoItemTaskMap.remove(taskItem.getUrl());
                        mDownloadListener.remove(taskItem.getUrl());

                        mVideoDownloadTaskMap.remove(taskItem.getUrl());
//                        mDownloadTaskMap.remove(taskItem.getUrl());

                        Bundle bundle = new Bundle();
                        bundle.putString("play_url", taskItem.getFilePath());
                        bundle.putString("m3u8_filepath", taskItem.mM3u8FilePath);
                        bundle.putString("play_name", taskItem.mName);
                        bundle.putString("sourceUrl", taskItem.sourceUrl);
                        taskItem.message = "done";
                        if(taskItem.notify){
                            NotificationBuilderManager.getInstance().updateNotification(VideoDownloadManager.getInstance().mConfig.context,bundle,taskItem);
                        }

                    }
                }

                @Override
                public void onTaskFailed(Exception e) {
                    Exception exception = new Exception(TAG2 + "startDownloadTask: "+e.getMessage());
                    Log.e("asdf","==onTaskFailed");
                    notifyError(taskItem,exception);
                }
            });

            downloadTask.startDownload();
        }
    }

    private void notifyError(VideoTaskItem taskItem,Exception e){
        if(e!=null){
            Log.e(TAG,"notify err:"+e.getMessage());
        }
        int errorCode = DownloadExceptionUtils.getErrorCode(e);
        taskItem.setErrorCode(errorCode);
        taskItem.setTaskState(VideoTaskState.ERROR);
        taskItem.exception=e;

        mDownloadTaskMap.put(taskItem.getUrl(),taskItem);

        mVideoDownloadHandler.obtainMessage(VideoDownloadConstants.MSG_DOWNLOAD_ERROR, taskItem).sendToTarget();
        IDownloadListener listener = mDownloadListener.get(taskItem.getUrl());
        if (listener != null) {
            listener.onDownloadError(taskItem);
        }

        mDownloadListener.remove(taskItem.getUrl());
        mVideoDownloadTaskMap.remove(taskItem.getUrl());
        taskItem.message="fail";
        if (taskItem.notify) {
            NotificationBuilderManager.getInstance().updateNotification(VideoDownloadManager.getInstance().mConfig.context, null, taskItem);
        }
    }

    public void deleteAllVideoFiles() {
        try {
            VideoStorageUtils.clearVideoDownloadDir();
            mVideoItemTaskMap.clear();
            mVideoDownloadTaskMap.clear();
            mDownloadTaskMap.clear();
            mVideoDownloadHandler.obtainMessage(VideoDownloadConstants.MSG_DELETE_ALL_FILES).sendToTarget();
        } catch (Exception e) {
            LogUtils.w(TAG, "clearVideoCacheDir failed, exception = " + e.getMessage());
        }
    }

    public void pauseAllDownloadTasks() {
        synchronized (mQueueLock) {
            List<VideoTaskItem> taskList = mVideoDownloadQueue.getDownloadList();
            for (VideoTaskItem taskItem : taskList) {
                if (taskItem.isPendingTask()) {
                    mVideoDownloadQueue.remove(taskItem);
                    mDownloadQueue.remove(taskItem);
                    mVideoDownloadHandler.obtainMessage(VideoDownloadConstants.MSG_DOWNLOAD_DEFAULT, taskItem).sendToTarget();
                } else if (taskItem.isRunningTask()) {
                    pauseDownloadTask(taskItem);
                }
            }
        }
    }

    public void pauseDownloadTask(List<String> urlList) {
        for (String url : urlList) {
            pauseDownloadTask(url);
        }
    }

    public void pauseDownloadTask(String videoUrl) {
        if (mVideoItemTaskMap.containsKey(videoUrl)) {
            VideoTaskItem taskItem = mVideoItemTaskMap.get(videoUrl);
            pauseDownloadTask(taskItem);
        }
    }

    public void pauseDownloadTask(VideoTaskItem taskItem) {
        if (taskItem == null || TextUtils.isEmpty(taskItem.getUrl()))
            return;
        synchronized (mQueueLock) {
            mVideoDownloadQueue.remove(taskItem);
            mRunningQueue.remove(taskItem);
            mDownloadQueue.remove(taskItem);
        }
        String url = taskItem.getUrl();
        VideoDownloadTask task = mVideoDownloadTaskMap.get(url);
        if (task != null) {
            task.pauseDownload();
        } else {
            taskItem.setTaskState(VideoTaskState.PAUSE);
            taskItem.setPaused(true);
            mVideoDownloadHandler.obtainMessage(VideoDownloadConstants.MSG_DOWNLOAD_PAUSE, taskItem).sendToTarget();
            mVideoDownloadHandler.removeMessages(VideoDownloadConstants.MSG_DOWNLOAD_PROCESSING);
        }
        updateDateBase(taskItem);
    }

    public void cancleTask(VideoTaskItem taskItem) {
        if (taskItem == null || TextUtils.isEmpty(taskItem.getUrl()))
            return;
        synchronized (mQueueLock) {
            mVideoDownloadQueue.remove(taskItem);
            mRunningQueue.remove(taskItem);
            mDownloadQueue.remove(taskItem);
            mVideoItemTaskMap.remove(taskItem);
            mDownloadTaskMap.remove(taskItem);
        }

        String url = taskItem.getUrl();
        VideoDownloadTask task = mVideoDownloadTaskMap.get(url);
        if (task != null) {
            task.cancle();
            task.delete();
        }

        deleteVideoTask(taskItem, true);
    }
    public boolean isMainThread() {
        return Looper.getMainLooper() == Looper.myLooper();
    }

    public void resumeDownload(String videoUrl) {
        if (mVideoItemTaskMap.containsKey(videoUrl)) {
            VideoTaskItem taskItem = mVideoItemTaskMap.get(videoUrl);
            synchronized (mQueueLock) {
                //超过配置的并发数暂停第一个下载任务，并开启指定的任务
                if (mRunningQueue.size() >= mConfig.concurrentCount) {
                    return;
                }
            }
            startDownload2(taskItem);

        }
    }



    //Delete one task
    private void deleteVideoTask(VideoTaskItem taskItem, boolean shouldDeleteSourceFile) {

        boolean a = isMainThread();
        pauseDownloadTask(taskItem);
//                String saveName = VideoDownloadUtils.getFileName(taskItem, null, false);
        File privateFile = new File(VideoDownloadManager.getInstance().mConfig.privatePath + File.separator + taskItem.mFileHash);


        File publicFile = new File(VideoDownloadManager.getInstance().mConfig.publicPath + File.separator + taskItem.mFileHash);
        // 删除任务同时删除数据库数据
        if (mConfig.openDb) {
            mVideoDatabaseHelper.deleteDownloadItemByUrl(taskItem);
        }
        if (shouldDeleteSourceFile) {
            try {
                VideoStorageUtils.delete(privateFile);
            } catch (Exception e) {
                Log.e(TAG, "发生异常: ", e); 
            }
            try {
                VideoStorageUtils.delete(publicFile);
            } catch (Exception e) {
                Log.e(TAG, "发生异常: ", e); 
            }

            VideoDownloadUtils.deleteFile(VideoDownloadManager.getInstance().mConfig.context, taskItem.getFilePath());


            try {
                if (!TextUtils.isEmpty(taskItem.mM3u8FilePath)) {
                    File m3u8 = new File(taskItem.mM3u8FilePath);
                    VideoStorageUtils.delete(m3u8.getParentFile());
                }
            } catch (Exception e) {
                Log.e(TAG, "发生异常: ", e); 
            }
            VideoDownloadUtils.deleteFile(VideoDownloadManager.getInstance().mConfig.context, taskItem.mCoverPath);

            Log.e(TAG, "asdf===private:" + privateFile.getAbsolutePath());
            Log.e(TAG, "asdf===public:" + publicFile.getAbsolutePath());
            Log.e(TAG, "asdf===filepath:" + taskItem.getFilePath());
            Log.e(TAG, "asdf===cover:" + taskItem.mCoverPath);

        }
        mVideoDownloadTaskMap.remove(taskItem.getUrl());
        mDownloadTaskMap.remove(taskItem.getUrl());
        mVideoItemTaskMap.remove(taskItem.getUrl());
        Log.w(TAG, "============delete");

    }


    public void deleteVideoTask(String videoUrl, boolean shouldDeleteSourceFile) {
        if (mVideoItemTaskMap.containsKey(videoUrl)) {
            VideoTaskItem taskItem = mVideoItemTaskMap.get(videoUrl);
            deleteVideoTask(taskItem, shouldDeleteSourceFile);
            mVideoItemTaskMap.remove(videoUrl);

        }
        if (mDownloadTaskMap.containsKey(videoUrl)) {
            VideoTaskItem taskItem = mDownloadTaskMap.get(videoUrl);
            deleteVideoTask(taskItem, shouldDeleteSourceFile);
            mDownloadTaskMap.remove(videoUrl);
        }
        //从下载的队列里移除
        VideoTaskItem taskItem = mDownloadQueue.getTaskItem(videoUrl);
        if (taskItem != null) {
            mDownloadQueue.remove(taskItem);
        }

    }



    private void removeDownloadQueue(VideoTaskItem taskItem) {
        synchronized (mQueueLock) {
            mVideoDownloadQueue.remove(taskItem);
            mRunningQueue.remove(taskItem);
            mDownloadQueue.remove(taskItem);
            //下载完成，继续下一个下载任务
            synchronized (mQueueLock) {
                while (mRunningQueue.size() < mConfig.concurrentCount) {
                    VideoTaskItem item1 = mDownloadQueue.poll();

                    if (item1 == null) {
                        break;
                    }
                    if (mRunningQueue.contains(item1)) {
                        continue;
                    }
                    startDownload2(item1);
                    Log.e(TAG, "removeDownloadQueue");
                }
            }

        }
    }

    /**
     * 获取当前的下载信息列表：包含正在下载和已完成的,并根据ID排序
     *
     * @return
     */
    public List<VideoTaskItem> getAll() {
        if(mConfig.openDb){
            List<VideoTaskItem> taskItems = mVideoDatabaseHelper.getAll();
            if(taskItems==null||taskItems.isEmpty()){
                return null;
            }
//            for(VideoTaskItem item:taskItems){
//                if(item==null)
//                    continue;
//                mVideoItemTaskMap.put(item.getUrl(), item);
//            }
            return taskItems;

        }
        return null;
    }


    /**
     * 分页获取下载信息
     * @param offset 开始位置
     * @param limit  每页数量
     * @return
     */
    public List<VideoTaskItem> getItemByPage(int offset,int limit) {
        if(mConfig.openDb){
            List<VideoTaskItem> taskItems = mVideoDatabaseHelper.getItemByPage(offset,limit);
            return taskItems;
        }
        return null;
    }

    /**
     * 自定义查询语句，获取下载信息
     * @return
     */
    public List<VideoTaskItem> getItemByQuery(String query) {
        if(mConfig.openDb){
            List<VideoTaskItem> taskItems = mVideoDatabaseHelper.getItemByQuery(query);
            return taskItems;
        }
        return null;
    }


    /**
     * 获取当前的下载信息列表：包含正在下载和已完成的,并根据ID排序
     *
     * @return
     */
    public List<VideoTaskItem> getDownloadingItem() {
        if (mConfig.openDb) {
            List<VideoTaskItem> taskItems = mVideoDatabaseHelper.getDownloadingItem();
            return taskItems;
        }
        return null;
    }

    public VideoTaskItem findVideoTask(String url, String sourceUrl, String quality) {
        if(mConfig.openDb){
            List<VideoTaskItem> taskItems = mVideoDatabaseHelper.getAll( );
            if (taskItems == null) {
                return null;
            }
            for (int i = 0, size = taskItems.size(); i < size; i++) {
                VideoTaskItem item = taskItems.get(i);
                if (item == null) {
                    continue;
                }
                if (!TextUtils.isEmpty(item.sourceUrl) && !TextUtils.isEmpty(item.quality)) {
                    // 根据网页链接和分辨率判断
                    if (item.sourceUrl.equals(sourceUrl) && item.quality.equals(quality)) {
                        if (item.getUrl().contains("?") && url.contains("?")) {
                            // 视频url里面包含参数的判断视频不带参数地址是否相同
                            String a=item.getUrl().substring(0, item.getUrl().indexOf("?"));
                            String b=url.substring(0, url.indexOf("?"));

                            if (a.equals(b)) {
                                return item;
                            } else {
                                item = mVideoDownloadQueue.getTaskItem(url);
                                if(item==null){
                                    continue;
                                }else {
                                    return item;
                                }
                            }
                        }

                    }

                } else {
                    // 根据视频url判断
                    if (item.getUrl().equals(url)) {
                        return taskItems.get(i);
                    }
                }


            }
            return null;
        }

        return null;
    }

    class VideoDownloadHandler extends Handler {

        public VideoDownloadHandler(Looper looper) {
            super(looper);
        }

        @Override
        public void handleMessage(Message msg) {
            super.handleMessage(msg);
            if (msg.what == VideoDownloadConstants.MSG_DELETE_ALL_FILES) {
                if(mConfig.openDb){
                    //删除数据库中所有记录
                    WorkerThreadHandler.submitRunnableTask(() -> mVideoDatabaseHelper.deleteAllDownloadInfos());
                }
            } else {
                dispatchDownloadMessage(msg.what, (VideoTaskItem) msg.obj);
            }
        }


        private void dispatchDownloadMessage(int msg, VideoTaskItem taskItem) {
            switch (msg) {
                case VideoDownloadConstants.MSG_DOWNLOAD_DEFAULT:
                    handleOnDownloadDefault(taskItem);
                    break;
                case VideoDownloadConstants.MSG_DOWNLOAD_PENDING:
                    handleOnDownloadPending(taskItem);
                    break;
                case VideoDownloadConstants.MSG_DOWNLOAD_QUEUING:
                    handleOnDownloadPending(taskItem);
                    break;
                case VideoDownloadConstants.MSG_DOWNLOAD_PREPARE:
                    handleOnDownloadPrepare(taskItem);
                    break;
                case VideoDownloadConstants.MSG_DOWNLOAD_START:
                    handleOnDownloadStart(taskItem);
                    break;
                case VideoDownloadConstants.MSG_DOWNLOAD_PROCESSING:
                    handleOnDownloadProcessing(taskItem);
                    break;
                case VideoDownloadConstants.MSG_DOWNLOAD_PAUSE:
                    handleOnDownloadPause(taskItem);
                    break;
                case VideoDownloadConstants.MSG_DOWNLOAD_ERROR:
                    handleOnDownloadError(taskItem);
                    break;
                case VideoDownloadConstants.MSG_DOWNLOAD_SUCCESS:
                    handleOnDownloadSuccess(taskItem);
                    break;
                case VideoDownloadConstants.MSG_DOWNLOAD_MERGE:
                    handleOnDownloadMerge(taskItem);
                    break;
            }
        }
    }

    private void handleOnDownloadDefault(VideoTaskItem taskItem) {
        if (mGlobalDownloadListener != null) {
            mGlobalDownloadListener.onDownloadDefault(taskItem);
        }


    }

    private void handleOnDownloadPending(VideoTaskItem taskItem) {
        if (mGlobalDownloadListener != null) {
            mGlobalDownloadListener.onDownloadPending(taskItem);
        }

    }

    private void handleOnDownloadPrepare(VideoTaskItem taskItem) {
        if (mGlobalDownloadListener != null) {
            mGlobalDownloadListener.onDownloadPrepare(taskItem);
        }

        markDownloadInfoAddEvent(taskItem);
    }

    private void handleOnDownloadStart(VideoTaskItem taskItem) {
        if (mGlobalDownloadListener != null) {
            mGlobalDownloadListener.onDownloadStart(taskItem);
        }

    }

    private void handleOnDownloadProcessing(VideoTaskItem taskItem) {
        if (mGlobalDownloadListener != null) {
            mGlobalDownloadListener.onDownloadProgress(taskItem);
        }

        markDownloadProgressInfoUpdateEvent(taskItem);
    }

    private void handleOnDownloadPause(VideoTaskItem taskItem) {
        if (mGlobalDownloadListener != null) {
            mGlobalDownloadListener.onDownloadPause(taskItem);
        }

        removeDownloadQueue(taskItem);
    }

    private void handleOnDownloadError(VideoTaskItem taskItem) {
        if (mGlobalDownloadListener != null) {
            mGlobalDownloadListener.onDownloadError(taskItem);
        }

        removeDownloadQueue(taskItem);
    }

    private void handleOnDownloadSuccess(VideoTaskItem taskItem) {
        removeDownloadQueue(taskItem);

        if (mGlobalDownloadListener != null) {
            mGlobalDownloadListener.onDownloadSuccess(taskItem);
        }

        markDownloadFinishEvent(taskItem);
    }

    private void handleOnDownloadMerge(VideoTaskItem taskItem) {

        if (mGlobalDownloadListener != null) {
            mGlobalDownloadListener.onDownloadMerge(taskItem);
        }

    }

    private void markDownloadInfoAddEvent(VideoTaskItem taskItem) {
        if(mConfig.openDb){
            WorkerThreadHandler.submitRunnableTask(() -> mVideoDatabaseHelper.markDownloadInfoAddEvent(taskItem));
        }
    }

    /**
     * 更新数据库
     *
     * @param taskItem
     */
    private void markDownloadProgressInfoUpdateEvent(VideoTaskItem taskItem) {
        if(mConfig.openDb){
            long currentTime = System.currentTimeMillis();
            if (taskItem.getLastUpdateTime() + 1000 < currentTime) {
                taskItem.setLastUpdateTime(currentTime);
                WorkerThreadHandler.submitRunnableTask(() -> mVideoDatabaseHelper.markDownloadProgressInfoUpdateEvent(taskItem));

            }
        }

    }

    /**
     * 更新数据库
     *
     */
    private void markDownloadFinishEvent(VideoTaskItem taskItem) {
        if(mConfig.openDb){
            long currentTime = System.currentTimeMillis();
            taskItem.setLastUpdateTime(currentTime);
            mVideoDatabaseHelper.markDownloadProgressInfoUpdateEvent(taskItem);
        }
    }

    public void updateDateBase(VideoTaskItem taskItem) {
        if(mConfig.openDb){
            taskItem.setLastUpdateTime(System.currentTimeMillis());
            mVideoDatabaseHelper.markDownloadProgressInfoUpdateEvent(taskItem);
        }
    }


    long total=0;
    /**
     * 批量下载
     *
     * @param list
     */
    public void startZipDownload(ArrayList<VideoTaskItem> list) {
        count = 0;

        if (list == null || list.isEmpty())
            return;
        try {
            synchronized (mLock) {

                for (int i = 0, size = list.size(); i < size; i++) {
                    VideoTaskItem item = list.get(i);
                    item.skipM3u8 = true;
                    mDownloadTaskMap.put(item.getUrl(), item);
                    startDownload2(item);
                }
            }

        } catch (Exception e) {
            Log.e(TAG, "未知错误");
        }
    }
}
