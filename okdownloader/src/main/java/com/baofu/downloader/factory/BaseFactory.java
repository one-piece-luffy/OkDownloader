package com.baofu.downloader.factory;

import static com.baofu.downloader.common.VideoDownloadConstants.DOWNLOAD_TYPE_ALL;
import static com.baofu.downloader.common.VideoDownloadConstants.DOWNLOAD_TYPE_RANGE;
import static com.baofu.downloader.common.VideoDownloadConstants.MAX_RETRY_COUNT_503;
import static com.baofu.downloader.utils.OkHttpUtil.NO_SPACE;
import static com.baofu.downloader.utils.OkHttpUtil.URL_INVALID;

import android.text.TextUtils;
import android.util.Log;

import com.baofu.downloader.listener.IFactoryListener;
import com.baofu.downloader.m3u8.M3U8Utils;
import com.baofu.downloader.model.VideoTaskItem;
import com.baofu.downloader.rules.VideoDownloadManager;
import com.baofu.downloader.utils.DownloadExecutor;
import com.baofu.downloader.utils.HttpUtils;
import com.baofu.downloader.utils.MimeType;
import com.baofu.downloader.utils.OkHttpUtil;
import com.baofu.downloader.utils.VideoDownloadUtils;
import com.baofu.downloader.utils.VideoStorageUtils;

import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.atomic.AtomicBoolean;

import okhttp3.Response;

public abstract class BaseFactory implements IDownloadFactory {

    final String TAG = "BaseFactory";
    final int BUFFER_SIZE = 1024 * 1024  ;
    IFactoryListener listener;
    VideoTaskItem mTaskItem;
    String method;
    // 初始等待时间（毫秒）
    static final int INITIAL_DELAY = 3000;
    // 重试的指数因子
    static final int FACTOR = 2;
    //当前重试次数
    int mRetryCount;
    //文件大小
    long mFileLength;
    String eTag;
    boolean chunked;
    boolean supportBreakpoint;
    volatile boolean pause;//是否暂停
    volatile boolean cancel;//是否取消下载
    String fileName;

    Queue<LocatedBuffer> mFileBuffersQueue;

    final AtomicBoolean responseCode206 = new AtomicBoolean(true);//分段请求是否返回206
    final AtomicBoolean responseCode503 = new AtomicBoolean(true);
    //中断分段下载
    final AtomicBoolean suspendRange = new AtomicBoolean(false);
    int mTotalThreadCount;
    long[] mProgress;
    File[] mCacheFiles;

    class LocatedBuffer {
        public byte[] buffer;
        public long startPosition;
        public int length;

        public LocatedBuffer() {
            startPosition = 0;
            buffer = new byte[BUFFER_SIZE << 1];
        }
    }

    public BaseFactory(VideoTaskItem taskItem, IFactoryListener listener) {
        this.listener = listener;
        this.mTaskItem = taskItem;
        mFileBuffersQueue = new LinkedList();

    }


    private void initDownloadInfo(String url) {
        if (VideoDownloadUtils.isIllegalUrl(mTaskItem.getUrl())) {
            notifyError(new Exception(URL_INVALID));
            return;
        }
//        Log.i(TAG, "初始化，获取下载文件信息...");

        try {
//            Log.i(TAG,"线程 start:"+ Thread.currentThread().getName());
            method = OkHttpUtil.METHOD.GET;
            if (OkHttpUtil.METHOD.POST.equalsIgnoreCase(mTaskItem.method)) {
                method = OkHttpUtil.METHOD.POST;
            }
            // 发起请求，从响应头获取文件信息
//            Response response = OkHttpUtil.getInstance().getHeaderSync(url);
            Response response = OkHttpUtil.getInstance().requestSync(url, method, VideoDownloadUtils.getTaskHeader(mTaskItem));
            int code = response.code();
            if (code >= 200 && code < 300) {
                //            Log.i(TAG, "请求头================\n" + response.headers().toString());
                //header请求才能拿到文件名
//            String fileName = getFileName(response);
//            Log.i(TAG, "获取到文件名：" + fileName);


                // 获取分块传输标志
                String transferEncoding = response.header("Transfer-Encoding");
                chunked = "chunked".equals(transferEncoding);
//            Log.i(TAG, "是否分块传输：" + chunked);
                // 没有分块传输才可获取到文件长度
                if (!chunked) {
                    String strLen = response.header("Content-Length");
                    try {
                        mFileLength = Long.parseLong(strLen);
//                    Log.i(TAG, "文件大小：" + mFileLength);
                    } catch (Exception e) {
                        mFileLength = response.body().contentLength();
                    }
                }
                long freeSpace = VideoDownloadUtils.getFreeSpaceBytes(VideoDownloadManager.getInstance().mConfig.privatePath);
//            Log.e(TAG,"free space:"+VideoDownloadUtils.getSizeStr(freeSpace));
                if (mFileLength > freeSpace) {
                    //存储空间不足
                    notifyError(new Exception(NO_SPACE));
//                Log.e(TAG,"存储空间不足");
                    return;
                }

                // 是否支持断点续传
                String acceptRanges = response.header("Accept-Ranges");
                supportBreakpoint = "bytes".equalsIgnoreCase(acceptRanges);
                eTag = response.header("ETag");
//            Log.i(TAG, "是否支持断点续传：" + supportBreakpoint);
//            Log.i(TAG, "ETag：" + eTag);
                String contentType = response.header("Content-Type");
//            Log.i(TAG, "content-type：" + contentType);
                if (contentType != null) {
                    mTaskItem.contentType = contentType;
                    if(MimeType.STREAM.equals(contentType)){
                        String filename = M3U8Utils.getFileName(response, url);
                        if (filename != null) {
                            int index = filename.lastIndexOf(".");
                            if (index >= 0) {
                                mTaskItem.suffix = filename.substring(index);
                            }

                        }

                    } else {
                        for (Map.Entry<String, String> entry : MimeType.map.entrySet()) {
                            if (entry.getKey().contains(contentType)) {
                                mTaskItem.suffix = entry.getValue();
                                break;
                            }
                        }
                    }

                }
                mTaskItem.setTotalSize(mFileLength);
                fileName = VideoDownloadUtils.getFileName(mTaskItem, null, true);
                File file = new File(mTaskItem.getSaveDir() + File.separator + fileName);
                if (file.exists() && !mTaskItem.overwrite) {
                    fileName = VideoDownloadUtils.getFileName(mTaskItem, System.currentTimeMillis() + "", true);
                }

                handlerData(response);
            } else {
                retryInit(url, code, response.message());
            }


        } catch (Exception e) {
            e.printStackTrace();
            retryInit(url, -1, "initDownloadInfo: message:" + e.getMessage());
        }

    }

    private void retryInit(String url, int code, String message) {
        mRetryCount++;
        if (code == HttpUtils.RESPONSE_503 || code == HttpUtils.RESPONSE_429 || code == HttpUtils.RESPONSE_509) {

            if (mRetryCount <= MAX_RETRY_COUNT_503) {
                //遇到503，延迟后再重试，区间间隔不能太小
                //指数退避算法
                long delay = (long) (INITIAL_DELAY * Math.pow(FACTOR, mRetryCount));
                Log.e("asdf", "delay:" + delay);
                try {
                    Thread.sleep(delay);
                } catch (Exception ex) {
                    ex.printStackTrace();
                }
                resetStutus();
                initDownloadInfo(url);
            } else {
                notifyError(new Exception(TAG + "retryInit1: code:" + code + " message:" + message));
            }

        } else if (mRetryCount < VideoDownloadManager.getInstance().mConfig.retryCount) {
            resetStutus();
            initDownloadInfo(url);
        } else {
            notifyError(new Exception(TAG + "retryInit2: code:" + code + " message:" + message));
        }
    }

    void notifyProgress(long progress, long total,boolean hasFileLength) {

        if (listener != null) {
            listener.onProgress(progress, total,hasFileLength);
        }
    }


    /**
     * 分段下载
     *
     * @param startIndex 开始位置
     * @param endIndex   结束位置
     * @param threadId   线程id
     */
    void downloadByRange(final long startIndex, final long endIndex, final int threadId)  {
        if (VideoDownloadUtils.isIllegalUrl(mTaskItem.getUrl())) {
            notifyError(new Exception(URL_INVALID));
            return;
        }

        Log.e(TAG, "asdf thread" + threadId + " RANGE: " + startIndex + "-" + endIndex );
        long newStartIndex = startIndex;
        // 分段请求网络连接,分段将文件保存到本地.
        // 加载下载位置缓存文件
//        final File cacheFile = new File(mSaveDir, "thread" + threadId + "_" + fileName + ".cache");
        final File cacheFile = new File(VideoStorageUtils.getTempDir(VideoDownloadManager.getInstance().mConfig.context), "thread" + threadId + "_" + fileName + ".cache");
        Log.e(TAG, "asdf fileName: " + cacheFile.getAbsolutePath());

        mCacheFiles[threadId] = cacheFile;
        RandomAccessFile cacheAccessFile = null;
        try {
            cacheAccessFile = new RandomAccessFile(cacheFile, "rwd");
        } catch (FileNotFoundException e) {
            e.printStackTrace();
        }
        if (cacheAccessFile != null && cacheFile.exists()) {
            try {
                String startIndexStr = cacheAccessFile.readLine();
                if (!TextUtils.isEmpty(startIndexStr)) {
                    newStartIndex = Long.parseLong(startIndexStr);//重新设置下载起点
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
            //解决http 416问题
            if (newStartIndex > endIndex) {
                newStartIndex = startIndex;
            }
        }
        final long finalStartIndex = newStartIndex;
        Map<String, String> header = new HashMap<>();
        header.put("User-Agent", VideoDownloadUtils.getUserAgent());
        if (supportBreakpoint) {
            header.put("RANGE", "bytes=" + newStartIndex + "-" + endIndex);
            if (!TextUtils.isEmpty(eTag)) {
                header.put("ETag", eTag);
            }
        }
        Map<String, String> taskHeader = VideoDownloadUtils.getTaskHeader(mTaskItem);
        if (taskHeader != null) {
            header.putAll(taskHeader);
        }
//        for (Map.Entry<String, String> entry : header.entrySet()) {
//            String key = entry.getKey();
//            String value = entry.getValue();
//            Log.e("asdf","trhead:"+threadId+", key: "+key+", value: "+value);
//        }
        try {
            OkHttpUtil.getInstance().request(mTaskItem.getUrl(), method, header, new OkHttpUtil.RequestCallback() {
                @Override
                public void onResponse(@NotNull Response response) throws IOException {
                    int code = response.code();

                    if (!response.isSuccessful()) {
                        if (code == 416) {
                            //Range Not Satisfiable（请求范围不满足)
                            notifyError(new Exception("code=416"));
                            return;
                        }
                        // 206：请求部分资源时的成功码,断点下载的成功码
                        retry(startIndex, endIndex, threadId, new Exception("downloadByRange server error:" + code), DOWNLOAD_TYPE_RANGE, code);
                        return;
                    }

                    if (supportBreakpoint && code != 206) {
                        //分段请求状态码不是206，则重新发起【不分段】的请求
                        synchronized (responseCode206) {
                            if (responseCode206.get()) {
                                responseCode206.set(false);
                                mTotalThreadCount = 1;
                                mProgress = new long[1];
                                mCacheFiles = new File[1];
                                downloadByAll(0, 0, 0);
                            }
                        }

                        return;
                    }
                    handlerResponse(startIndex, endIndex, threadId, finalStartIndex, response,  DOWNLOAD_TYPE_RANGE);
                }

                @Override
                public void onFailure(@NotNull Exception e) {
                    e.printStackTrace();
                    retry(startIndex, endIndex, threadId, e, DOWNLOAD_TYPE_RANGE, -1);
                }
            });

        } catch (Exception e) {
            e.printStackTrace();
            retry(startIndex, endIndex, threadId, e, DOWNLOAD_TYPE_RANGE, -2);
        }

    }

    /**
     * 全部下载，不分段
     *
     * @param startIndex 开始位置
     * @param endIndex   结束位置
     * @param threadId   线程id
     */
    void downloadByAll(final long startIndex, final long endIndex, final int threadId) throws IOException {
        if (VideoDownloadUtils.isIllegalUrl(mTaskItem.getUrl())) {
            notifyError(new Exception(URL_INVALID));
            return;
        }
        Log.i(TAG, "download all start");
        Map<String, String> header = new HashMap<>();
        header.put("User-Agent", VideoDownloadUtils.getUserAgent());
        Map<String, String> taskHeader = VideoDownloadUtils.getTaskHeader(mTaskItem);
        if (taskHeader != null) {
            header.putAll(taskHeader);
        }
        try {
            method = OkHttpUtil.METHOD.GET;
            if (OkHttpUtil.METHOD.POST.equalsIgnoreCase(mTaskItem.method)) {
                method = OkHttpUtil.METHOD.POST;
            }
            OkHttpUtil.getInstance().request(mTaskItem.getUrl(), method, header, new OkHttpUtil.RequestCallback() {
                @Override
                public void onResponse(@NotNull Response response) {
                    int code = response.code();
                    if (!response.isSuccessful()) {
                        // 206：请求部分资源时的成功码,断点下载的成功码
                        retry(startIndex, endIndex, threadId, new Exception("downloadByAll server error " + code), DOWNLOAD_TYPE_ALL, code);
                        return;
                    }
                    handlerResponse(startIndex, endIndex, threadId, startIndex, response, DOWNLOAD_TYPE_ALL);
                }

                @Override
                public void onFailure(@NotNull Exception e) {
                    e.printStackTrace();
                    retry(startIndex, endIndex, threadId, e, DOWNLOAD_TYPE_ALL, -3);
                }
            });

        } catch (Exception e) {
            e.printStackTrace();
            Log.e(TAG, "==" + e.getMessage());
            retry(startIndex, endIndex, threadId, e, DOWNLOAD_TYPE_ALL, -4);
        }
    }

    void retry(final long startIndex, final long endIndex, final int threadId, Exception e, int retryType, int errCode) {
        Log.e(TAG, "retry");
        if (cancel)
            return;
        mRetryCount++;
        if (errCode == HttpUtils.RESPONSE_503 || errCode == HttpUtils.RESPONSE_429 || errCode == HttpUtils.RESPONSE_509) {
            if (mRetryCount <= MAX_RETRY_COUNT_503) {
                //遇到503，延迟后再重试，区间间隔不能太小
                //指数退避算法
                long delay = (long) (INITIAL_DELAY * Math.pow(FACTOR, mRetryCount));
                Log.e(TAG, "sleep:" + delay);
                suspendRange.set(true);
                try {
                    Thread.sleep(delay);
                    synchronized (responseCode503) {
                        if (responseCode503.get()) {
                            responseCode503.set(false);
                            mTotalThreadCount = 1;
                            mProgress = new long[mTotalThreadCount];
                            this.mCacheFiles = new File[mTotalThreadCount];

                            downloadByAll(0, 0, 0);
                        }
                    }

                } catch (Exception ex) {
                    e.printStackTrace();
                }
            } else {
                resetStutus();
                Exception ex = new Exception(TAG + "retry1: " + e.getMessage() + " code:" + errCode);
                notifyError(ex);
            }
        } else if (mRetryCount < VideoDownloadManager.getInstance().mConfig.retryCount) {
            try {
                if (retryType == DOWNLOAD_TYPE_RANGE) {
                    downloadByRange(startIndex, endIndex, threadId);
                } else {
                    downloadByAll(startIndex, endIndex, threadId);
                }
            } catch (IOException ioException) {
                ioException.printStackTrace();
            }
        } else {
            resetStutus();
            Exception ex = new Exception(TAG + "retry2: " + e.getMessage() + " code:" + errCode);
            notifyError(ex);
        }
    }

    @Override
    public void download() {
        DownloadExecutor.execute(() -> {
            pause = false;
            cancel = false;

            try {
                initDownloadInfo(mTaskItem.getUrl());
            } catch (Exception e) {
                e.printStackTrace();
            }
        });

    }

    @Override
    public void resetStutus() {
        pause = false;
        cancel = false;
        if (listener != null) {
            listener.onReset();
        }
    }

    @Override
    public void cancel() {
        cancel = true;
        resetStutus();
    }

    @Override
    public void pause() {
        pause = true;
    }

    @Override
    public void delete() {

    }


    abstract void notifyError(Exception e);

    abstract void handlerData(Response response);
    abstract void handlerResponse(final long startIndex, final long endIndex, final int threadId, final long finalStartIndex, Response response, int downloadtype);


}
