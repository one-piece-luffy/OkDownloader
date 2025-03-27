package com.baofu.downloader.factory;

import static com.baofu.downloader.common.VideoDownloadConstants.MAX_RETRY_COUNT_503;
import static com.baofu.downloader.utils.OkHttpUtil.NO_SPACE;
import static com.baofu.downloader.utils.OkHttpUtil.URL_INVALID;

import android.util.Log;

import com.baofu.downloader.listener.IFactoryListener;
import com.baofu.downloader.model.VideoTaskItem;
import com.baofu.downloader.rules.VideoDownloadManager;
import com.baofu.downloader.utils.DownloadExecutor;
import com.baofu.downloader.utils.HttpUtils;
import com.baofu.downloader.utils.MimeType;
import com.baofu.downloader.utils.OkHttpUtil;
import com.baofu.downloader.utils.VideoDownloadUtils;

import java.io.File;
import java.util.LinkedList;
import java.util.Map;
import java.util.Queue;

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
                    for (Map.Entry<String, String> entry : MimeType.map.entrySet()) {
                        if (entry.getKey().contains(contentType)) {
                            mTaskItem.suffix = entry.getValue();
                            break;
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



}
