package com.baofu.downloader.factory;

import static android.os.Environment.DIRECTORY_DOWNLOADS;
import static com.baofu.downloader.common.VideoDownloadConstants.DOWNLOAD_TYPE_ALL;
import static com.baofu.downloader.common.VideoDownloadConstants.DOWNLOAD_TYPE_RANGE;
import static com.baofu.downloader.common.VideoDownloadConstants.MAX_RETRY_COUNT_503;
import static com.baofu.downloader.utils.OkHttpUtil.NO_SPACE;
import static com.baofu.downloader.utils.OkHttpUtil.URL_INVALID;
import static com.baofu.downloader.utils.VideoDownloadUtils.close;

import android.net.Uri;
import android.os.Build;
import android.os.ParcelFileDescriptor;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import android.text.TextUtils;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.RequiresApi;

import com.baofu.downloader.rules.VideoDownloadManager;
import com.baofu.downloader.listener.IFactoryListener;
import com.baofu.downloader.model.VideoTaskItem;
import com.baofu.downloader.utils.DownloadExecutor;
import com.baofu.downloader.utils.HttpUtils;
import com.baofu.downloader.utils.MimeType;
import com.baofu.downloader.utils.OkHttpUtil;
import com.baofu.downloader.utils.VideoDownloadUtils;
import com.baofu.downloader.utils.VideoStorageUtils;

import org.jetbrains.annotations.NotNull;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import okhttp3.Response;
import okhttp3.ResponseBody;

@RequiresApi(api = Build.VERSION_CODES.Q)
public class Android10FastFactory implements IDownloadFactory {

    //当前重试次数
    private int mRetryCount;
    public final String TAG = getClass().getName()+": ";
    IFactoryListener listener;
    //文件大小
    long mFileLength;
    VideoTaskItem mTaskItem;
    private volatile boolean pause;//是否暂停
    private volatile boolean cancel;//是否取消下载
    final int BUFFER_SIZE = 1024 * 1024 * 2;
    private long[] mProgress;
    public Queue<LocatedBuffer> mFileBuffersQueue;
    WriteFileThread mWriteFileThread;
    private File[] mCacheFiles;

    private String eTag;
    //断点续传
    private boolean supportBreakpoint;
    // 存储类型，可选参数 DIRECTORY_PICTURES  ,DIRECTORY_MOVIES  ,DIRECTORY_MUSIC
    String inserType = DIRECTORY_DOWNLOADS;
    ParcelFileDescriptor pdf;
    FileChannel channel;
    BufferedOutputStream bufferedOutputStream = null;
    FileOutputStream fos = null;
    //是否支持读写分离
    boolean mSplitReadWrite;
    //读写分离总开关
    final boolean ENABLE_SPLIT_READ_WRITE = false;
    //是否使用异步下载
    final boolean asyncDownload = true;
    //分段下载方式：固定线程数
    final int RANGE_TYPE_THREAD = 1;
    //分段下载方式：固定阈值数
    final int RANGE_TYPE_THRESHOLD = 2;
    int mRangeType;
    private final AtomicBoolean responseCode206 = new AtomicBoolean(true);//分段请求是否返回206
    private final AtomicBoolean responseCode503 = new AtomicBoolean(true);
    //中断分段下载
    private final AtomicBoolean suspendRange = new AtomicBoolean(false);
    private final AtomicInteger childFinshCount = new AtomicInteger(0);//子线程完成数量
    int mTotalThreadCount;
    String fileName;
    String method;

    public Android10FastFactory(VideoTaskItem taskItem, IFactoryListener listener) {
        this.listener = listener;
        this.mTaskItem = taskItem;


        mFileBuffersQueue = new LinkedList<>();
        mWriteFileThread = new WriteFileThread();
        mRangeType = RANGE_TYPE_THREAD;
    }

    @Override
    public void download() {
        DownloadExecutor.execute(() -> {
            pause = false;
            cancel = false;
            try {
                Log.i(TAG,"download 线程 :"+ Thread.currentThread().getName());
                initDownloadInfo(mTaskItem.getUrl());
            } catch (Exception e) {
                e.printStackTrace();
            }
        });

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
    public void resetStutus() {
        pause = false;
        cancel = false;
        if (listener != null) {
            listener.onReset();
        }
    }

    @Override
    public void delete() {
        cleanFile(mCacheFiles);
    }


    private void initDownloadInfo(String url)  {
        if(VideoDownloadUtils.isIllegalUrl(mTaskItem.getUrl())){
            notifyError(new Exception(URL_INVALID));
            return;
        }
//        Log.i(TAG, "初始化，获取下载文件信息...");

        try {
//            long start=System.currentTimeMillis();
            // 发起请求，从响应头获取文件信息
//            Response response = OkHttpUtil.getInstance().getHeaderSync(url);
//            Log.i(TAG,"线程 start:"+ Thread.currentThread().getName());
            method = OkHttpUtil.METHOD.GET;
            if (OkHttpUtil.METHOD.POST.equalsIgnoreCase(mTaskItem.method)) {
                method = OkHttpUtil.METHOD.POST;
            }
            Response response = OkHttpUtil.getInstance().requestSync(url,method,mTaskItem.header);
            int code=response.code();
            if(code>=200&&code<300) {
//            long dif=System.currentTimeMillis()-start;
//            Log.i(TAG,"耗时:"+dif+" "+ Thread.currentThread().getName());
//            Log.i(TAG, "请求头================\n" + response.headers().toString());


                // 获取分块传输标志
                String transferEncoding = response.header("Transfer-Encoding");
                //分段传输标志，有这个标志不能获取到文件大小，就不能断点续传
                boolean chunked = "chunked".equals(transferEncoding);
//            Log.i(TAG, "是否分块传输：" + chunked);
                // 没有分块传输才可获取到文件长度
                if (!chunked) {
                    String strLen = response.header("Content-Length");
                    try {
                        mFileLength = Long.parseLong(strLen);
                    } catch (Exception e) {
                        mFileLength = response.body().contentLength();
                    }

//                Log.e(TAG, "文件大小：" + VideoDownloadUtils.getSizeStr(mFileLength));
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
                handlerData(response);
            }else {
                notifyError(new Exception(TAG+"code:"+code+" message:"+response.message()));
            }

        } catch (Exception e) {
            Log.e(TAG, "start:Exception "+Thread.currentThread().getName() + "\n"  + e.getMessage());

            if (mRetryCount < VideoDownloadManager.getInstance().mConfig.retryCount) {
                mRetryCount++;
                resetStutus();
                initDownloadInfo(url);
            } else {
                resetStutus();
                try {
                    handlerData(null);
                } catch (Exception exception) {
                    exception.printStackTrace();
                }
            }
        }

    }


    private void handlerData(Response response) {
        try {
            mTaskItem.setTotalSize(mFileLength);
            fileName = VideoDownloadUtils.getFileName(mTaskItem, null,true);
            File file=new File(mTaskItem.getSaveDir()+ File.separator + fileName);
            if(file.exists()&&!mTaskItem.overwrite){
                fileName = VideoDownloadUtils.getFileName(mTaskItem, System.currentTimeMillis() + "",true);
            }
            String mimeType = mTaskItem.contentType;
            if (TextUtils.isEmpty(mimeType) && !TextUtils.isEmpty(mTaskItem.suffix)) {
                mimeType = mTaskItem.suffix.replace(".", "");
            }
            Uri uri = VideoDownloadUtils.getUri(DIRECTORY_DOWNLOADS, fileName, mimeType);
            if (uri == null) {
                //创建失败，则重命名，重新创建
                Log.e(TAG,"==================重命名");
                fileName = VideoDownloadUtils.getFileName(mTaskItem, System.currentTimeMillis() + "",true);
                uri = VideoDownloadUtils.getUri(DIRECTORY_DOWNLOADS, fileName, mimeType);
            }
            //重建后还是没有uri，提示失败
            if (uri == null) {
                //文件创建失败
                if (listener != null) {
                    listener.onError(new NullPointerException("Uri insert fail, Please change the file name"));
                }
                return;
            }
            pdf = VideoDownloadManager.getInstance().mConfig.context.getContentResolver().openFileDescriptor(uri, "rw");
            fos = new FileOutputStream(pdf.getFileDescriptor());
            channel = fos.getChannel();

            bufferedOutputStream = new BufferedOutputStream(fos);
            if (mFileLength > 0) {
                try {
                    Os.posix_fallocate(pdf.getFileDescriptor(), 0, mFileLength);
                    mSplitReadWrite = true;
                } catch (Throwable e) {

                    if (e instanceof ErrnoException) {
                        if (((ErrnoException) e).errno == OsConstants.ENOSYS
                                || ((ErrnoException) e).errno == OsConstants.ENOTSUP) {
                            try {
                                Os.ftruncate(pdf.getFileDescriptor(), mFileLength);
                                mSplitReadWrite = true;
                            } catch (Throwable e1) {
                                e1.printStackTrace();
                                mSplitReadWrite = false;
                            }
                        } else {
                            mSplitReadWrite = false;

                        }
                    }
                }
            } else {
                mSplitReadWrite = false;
            }
//            Log.w(TAG, "读写分离:" + mSplitReadWrite+" "+"分段："+supportBreakpoint+" "+Thread.currentThread().getName());


            //todo
//            mSplitReadWrite=false;

            if (mSplitReadWrite) {
                if (mTaskItem.contentType != null && mTaskItem.contentType.contains("image")) {
                    //支持分段下载的下载方式
                    //图片不分段
                    mTotalThreadCount = 1;
                    mProgress = new long[1];
                    mCacheFiles = new File[1];
//                    downloadByAll(0, 0, 0);
                    if (response == null) {
                        downloadByAll(0, 0, 0);
                    } else {
                        handlerResponse(0, 0, 0, 0, response, DOWNLOAD_TYPE_ALL);
                    }
                } else if (supportBreakpoint && mFileLength > 0) {
                    if (mRangeType == RANGE_TYPE_THREAD) {
                        mTotalThreadCount = VideoDownloadUtils.getBlockCount(mFileLength);
                        mProgress = new long[mTotalThreadCount];
                        mCacheFiles = new File[mTotalThreadCount];
//                        Log.e(TAG,"文件大小："+mFileLength+"分段数量："+mTotalThreadCount);
                        if (mTotalThreadCount == 1) {
                            //只有一段，直接下载
                            handlerResponse(0, 0, 0, 0, response, DOWNLOAD_TYPE_ALL);
                        } else {
                            /*将下载任务分配给每个线程*/
                            long blockSize = mFileLength / mTotalThreadCount;// 计算每个线程理论上下载的数量.

                            /*为每个线程配置并分配任务*/
                            for (int threadId = 0; threadId < mTotalThreadCount; threadId++) {
                                long startIndex = threadId * blockSize; // 线程开始下载的位置
                                long endIndex = (threadId + 1) * blockSize - 1; // 线程结束下载的位置
                                if (threadId == (mTotalThreadCount - 1)) { // 如果是最后一个线程,将剩下的文件全部交给这个线程完成
                                    endIndex = mFileLength - 1;
                                }
                                long finalEndIndex = endIndex;
                                int finalThreadId = threadId;
                                if (asyncDownload) {
//                                    if(threadId==0){
//                                        new Thread(){
//                                            @Override
//                                            public void run() {
//                                                super.run();
//                                                Looper.prepare();
//                                                try {
//                                                    Thread.sleep(2000);
//                                                    downloadByRange(startIndex, finalEndIndex, finalThreadId);
//                                                } catch (Exception e) {
//                                                    e.printStackTrace();
//                                                }
//                                                Looper.loop();
//                                            }
//                                        }.start();
//
//
//                                    }else {
                                        downloadByRange(startIndex, finalEndIndex, finalThreadId);
//                                    }


                                } else {
                                    DownloadExecutor.execute(new Runnable() {
                                        @Override
                                        public void run() {
                                            try {
                                                downloadByRangeSync(startIndex, finalEndIndex, finalThreadId);
                                            } catch (IOException e) {
                                                e.printStackTrace();
                                            }
                                        }
                                    });
                                }

                            }
                            //关闭资源
                            close( response.body());
                        }


                    } else {
                        final int threshold = 1024 * 1024 * 5;//每个线程的下载阈值
                        int count = (int) (mFileLength / threshold);// 计算线程的数量.
                        mTotalThreadCount = count;
                        mProgress = new long[count + 1];
                        mCacheFiles = new File[count+1];
                        long startPos = 0, endPos = 0;
                        for (int i = 0; i < count; i++) {
                            startPos = (long) i * threshold;
                            endPos = startPos + threshold - 1;
                            long finalStartPos = startPos;
                            long finalEndPos = endPos;
                            int finalI = i;
                            if (asyncDownload) {
                                downloadByRange(finalStartPos, finalEndPos, finalI);
                            } else {
                                DownloadExecutor.execute(() -> {
                                    try {
                                        downloadByRangeSync(finalStartPos, finalEndPos, finalI);
                                    } catch (IOException e) {
                                        e.printStackTrace();
                                    }
                                });
                            }

                        }
                        if (endPos < mFileLength - 1) {
                            long finalEndPos1 = endPos;
                            if (asyncDownload) {
                                downloadByRange(finalEndPos1 + 1, mFileLength, mProgress.length - 1);
                            } else {
                                DownloadExecutor.execute(() -> {
                                    try {
                                        downloadByRangeSync(finalEndPos1 + 1, mFileLength, mProgress.length - 1);
                                    } catch (IOException e) {
                                        e.printStackTrace();
                                    }
                                });
                            }

                        }
                        //关闭资源
                        close( response.body());
                    }


                } else {
                    //没有取到文件大小的或者不支持分段的下载方式
                    mTotalThreadCount = 1;
                    mProgress = new long[1];
                    mCacheFiles = new File[1];
                    if (asyncDownload) {
//                        downloadByRange(0, mFileLength, 0);// 开启线程下载
                        if (response == null) {
                            downloadByAll(0, 0, 0);
                        } else {
                            handlerResponse(0, 0, 0, 0, response, DOWNLOAD_TYPE_ALL);
                        }
                    } else {
                        DownloadExecutor.execute(() -> {
                            try {
                                if (response == null) {
                                    downloadByRangeSync(0, mFileLength, 0);// 开启线程下载
                                } else {
                                    handlerResponse(0, 0, 0, 0, response, DOWNLOAD_TYPE_ALL);
                                }
                            } catch (IOException e) {
                                e.printStackTrace();
                            }
                        });
                    }
                }
            } else {
                //不支持读写分离
                mTotalThreadCount = 1;
                if (response == null) {
                    downInPublicDir(mTaskItem.getUrl());
                } else {
                    handPublicDir(response);
                }
            }

        } catch (IOException e) {
            e.printStackTrace();
            resetStutus();
            if (listener != null) {
                listener.onError(e);
            }
        }
    }


    /**
     * 同步下载
     *
     * @param startIndex 开始位置
     * @param endIndex 结束位置
     * @param threadId 线程id
     */
    private void downloadByRangeSync(final long startIndex, final long endIndex, final int threadId) throws IOException {
        if(VideoDownloadUtils.isIllegalUrl(mTaskItem.getUrl())){
            notifyError(new Exception(URL_INVALID));
            return;
        }
        Map<String, String> header = new HashMap<>();
        header.put("RANGE", "bytes=" + startIndex + "-" + endIndex);
        header.put("User-Agent", VideoDownloadUtils.getUserAgent());
        if (!TextUtils.isEmpty(eTag)) {
            header.put("ETag", eTag);
        }
        if(mTaskItem.header!=null){
            header.putAll(mTaskItem.header);
        }
        try {
            Response response = OkHttpUtil.getInstance().requestSync(mTaskItem.getUrl(),method, header);
            int code = response.code();
            if (!response.isSuccessful()) {
                // 206：请求部分资源时的成功码,断点下载的成功码
                if (mRetryCount < VideoDownloadManager.getInstance().mConfig.retryCount) {
                    mRetryCount++;
                    try {
                        downloadByRangeSync(startIndex, endIndex, threadId);
                    } catch (IOException ioException) {
                        ioException.printStackTrace();
                    }
                } else {
                    resetStutus();
                    notifyError(new Exception(TAG+"server error:"+code));
                }
                return;
            }
            ResponseBody body=response.body();
            if (body == null) {
                notifyError(new Exception(TAG+"response body is null"));
                return;
            }
            InputStream is =body.byteStream();// 获取流
//                final RandomAccessFile tmpAccessFile = new RandomAccessFile(mTmpFile, "rw");// 获取前面已创建的文件.
//                tmpAccessFile.seek(finalStartIndex);// 文件写入的开始位置.
            /*  将网络流中的文件写入本地*/
            byte[] data = new byte[1024 << 3];
            int length ;
            int progress = 0;// 记录本次下载文件的大小

            long rangeFileLength = body.contentLength();
//            Log.e(TAG, "rangeFileLength:" + rangeFileLength);
            int bufferSize = BUFFER_SIZE;
            int position = 0;
            int mCurrentLength = 0;
            long startPostion = startIndex;
            byte[] buffer = new byte[bufferSize << 1];
            try {
                while ((length = is.read(data)) > 0) {
                    if (cancel) {
                        //关闭资源
                        mWriteFileThread.isStop = true;
                        close(is, response.body());
                        resetStutus();

//                        VideoDownloadManager.getInstance().deleteVideoTask(mTaskItem.getUrl(), true);
                        return;
                    }
                    if (pause) {
                        mWriteFileThread.isStop = true;
                        //关闭资源
                        close(is, response.body());
                        //发送暂停消息
                        resetStutus();
                        pause();
                        return;
                    }
                    mCurrentLength += length;
                    System.arraycopy(data, 0, buffer, position, data.length);
                    position += length;

                    if (mCurrentLength >= bufferSize) {
                        if (mWriteFileThread != null && !mWriteFileThread.isStart) {
                            mWriteFileThread.isStart = true;
                            mWriteFileThread.start();
                        }
                        LocatedBuffer locatedBuffer = new LocatedBuffer();
                        locatedBuffer.buffer = buffer;
                        locatedBuffer.length = mCurrentLength;
                        locatedBuffer.startPosition = startPostion;
                        mFileBuffersQueue.offer(locatedBuffer);
                        startPostion += mCurrentLength;
                        position = 0;
                        mCurrentLength = 0;
                        buffer = new byte[bufferSize << 1];
                    }


                    progress += length;

                    mProgress[threadId] = progress;
                    if (progress >= rangeFileLength && rangeFileLength > 0) {
                        if (mCurrentLength > 0) {
                            if (mWriteFileThread != null && !mWriteFileThread.isStart) {
                                mWriteFileThread.isStart = true;
                                mWriteFileThread.start();
                            }
                            LocatedBuffer locatedBuffer = new LocatedBuffer();
                            locatedBuffer.buffer = buffer;
                            locatedBuffer.length = mCurrentLength;
                            locatedBuffer.startPosition = startPostion;
                            mFileBuffersQueue.offer(locatedBuffer);
                            startPostion += mCurrentLength;
                            mCurrentLength = 0;
                            long p = 0;
                            for (long i : mProgress) {
                                p += i;
                            }

                            notifyProgress(p, mFileLength);
                        }
                    }
                    //发送进度消息
                    if (mFileLength > 0) {
                        long p = 0;
                        for (long i : mProgress) {
                            p += i;
                        }
                        notifyProgress(p, mFileLength);
                    }

//                    ThreadPoolExecutor tpe = ((ThreadPoolExecutor) DownloadExecutor.getExecutorService());
//                    int queueSize = tpe.getQueue().size();
//                    int activeCount = tpe.getActiveCount();
//                    long completedTaskCount = tpe.getCompletedTaskCount();
//                    long taskCount = tpe.getTaskCount();
//                    Log.i(TAG, "当前排队线程数：" + queueSize + " 当前活动线程数：" + activeCount + " 执行完成线程数：" + completedTaskCount + " 总线程数：" + taskCount);

                }
                if (mFileLength <= 0) {
                    //没有获取到文件长度，下载完毕再更新进度
                    notifyProgress(progress, progress);
                }

                //关闭资源
                close(is, response.body());
            } catch (Exception e) {
                e.printStackTrace();
                if (mRetryCount < VideoDownloadManager.getInstance().mConfig.retryCount) {
                    mRetryCount++;
                    try {
                        downloadByRangeSync(startIndex, endIndex, threadId);
                    } catch (IOException ioException) {
                        ioException.printStackTrace();
                    }
                } else {
                    resetStutus();
                    Exception ex=new Exception(TAG+e.getMessage());
                    notifyError(ex);
                }

            }

        } catch (Exception e) {
            e.printStackTrace();
            if (mRetryCount < VideoDownloadManager.getInstance().mConfig.retryCount) {
                mRetryCount++;
                try {
                    downloadByRangeSync(startIndex, endIndex, threadId);
                } catch (IOException ioException) {
                    ioException.printStackTrace();
                }
            } else {
                resetStutus();
                Exception ex=new Exception(TAG+e.getMessage());
                notifyError(ex);
            }
        }
    }

    /**
     * 分段下载
     *
     * @param startIndex 开始位置
     * @param endIndex 结束位置
     * @param threadId 线程id
     */
    private void downloadByRange(final long startIndex, final long endIndex, final int threadId) throws IOException {
        if(VideoDownloadUtils.isIllegalUrl(mTaskItem.getUrl())){
            notifyError(new Exception(URL_INVALID));
            return;
        }
        long newStartIndex = startIndex;
        final File cacheFile = new File(VideoStorageUtils.getTempDir(VideoDownloadManager.getInstance().mConfig.context), "thread" + threadId + "_" + fileName + ".cache");
        mCacheFiles[threadId] = cacheFile;
        final RandomAccessFile cacheAccessFile = new RandomAccessFile(cacheFile, "rwd");
        if (cacheFile.exists()) {
            try {
                String startIndexStr = cacheAccessFile.readLine();
                if (!TextUtils.isEmpty(startIndexStr)) {
                    newStartIndex = Long.parseLong(startIndexStr);//重新设置下载起点
                }
            } catch (NumberFormatException e) {
                e.printStackTrace();
            }

        }

        final long finalStartIndex = newStartIndex;

        Map<String, String> header = new HashMap<>();
        if (supportBreakpoint ) {
            header.put("RANGE", "bytes=" + newStartIndex + "-" + endIndex);
        }
        header.put("User-Agent", VideoDownloadUtils.getUserAgent());
//        Log.i(TAG, "range :" + startIndex + "-" + endIndex);
//        Log.i(TAG, "thread" + threadId + "newStart:" + newStartIndex);
        if (!TextUtils.isEmpty(eTag)) {
            header.put("ETag", eTag);
        }
        if(mTaskItem.header!=null){
            header.putAll(mTaskItem.header);
        }
        OkHttpUtil.getInstance().request(mTaskItem.getUrl(),method, header, new OkHttpUtil.RequestCallback() {
            @Override
            public void onResponse(Response response) throws IOException {
                int code = response.code();
                if (!response.isSuccessful()) {
                    // 206：请求部分资源时的成功码,断点下载的成功码
                    retry(startIndex, endIndex, threadId, new Exception("server error not 206:"+ code), DOWNLOAD_TYPE_RANGE,code);
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
//                Log.i(TAG, "请求头================\n" + response.headers().toString());
                handlerResponse(startIndex, endIndex, threadId, finalStartIndex, response, DOWNLOAD_TYPE_RANGE);
            }

            @Override
            public void onFailure(Exception e) {
                e.printStackTrace();
                retry(startIndex, endIndex, threadId, e, DOWNLOAD_TYPE_RANGE,-1);
            }
        });

    }

    /**
     * 全部下载，不分段
     *
     * @param startIndex 开始位置
     * @param endIndex 结束位置
     * @param threadId 线程id
     */
    private void downloadByAll(final long startIndex, final long endIndex, final int threadId) throws IOException {
//        Log.i(TAG, "downloadByAll start "+name);
        if(VideoDownloadUtils.isIllegalUrl(mTaskItem.getUrl())){
            notifyError(new Exception(URL_INVALID));
            return;
        }
        Log.i(TAG,"download all start");
        OkHttpUtil.getInstance().request(mTaskItem.getUrl(),method, mTaskItem.header, new OkHttpUtil.RequestCallback() {
            @Override
            public void onResponse(@NotNull Response response)  {
                int code = response.code();
                if (!response.isSuccessful()) {
                    // 206：请求部分资源时的成功码,断点下载的成功码
                    retry(startIndex, endIndex, threadId, new Exception("server error:"+code), DOWNLOAD_TYPE_ALL,code);
                    return;
                }
//                Log.i(TAG, "downloadByAll response "+name);
                handlerResponse(startIndex, endIndex, threadId, startIndex, response, DOWNLOAD_TYPE_ALL);
            }

            @Override
            public void onFailure(@NotNull Exception e) {
                e.printStackTrace();
                retry(startIndex, endIndex, threadId, e, DOWNLOAD_TYPE_ALL,-1);
            }
        });

    }

    private void handlerResponse(final long startIndex, final long endIndex, final int threadId, final long finalStartIndex, Response response, int downloadtype) {
        if (response == null) {
            notifyError(new Exception(TAG+"response is null"));
            return;
        }
        ResponseBody body=response.body();
        if (body == null) {
            notifyError(new Exception(TAG+"body is null"));
            return;
        }
        long len=0;
        String strLen = response.header("Content-Length");
        try {
            len = Long.parseLong(strLen);
//                    Log.i(TAG, "文件大小：" + mFileLength);
        } catch (Exception e) {
            len = response.body().contentLength();
        }
        Log.i(TAG, "thread" + threadId + " 分段总大小:" + len);

        RandomAccessFile cacheAccessFile=null;
        if(mCacheFiles[threadId]!=null){
            try {
                cacheAccessFile = new RandomAccessFile(mCacheFiles[threadId], "rwd");
            } catch (FileNotFoundException e) {
                e.printStackTrace();
            }
        }

        InputStream is =body.byteStream();// 获取流
//                final RandomAccessFile tmpAccessFile = new RandomAccessFile(mTmpFile, "rw");// 获取前面已创建的文件.
//                tmpAccessFile.seek(finalStartIndex);// 文件写入的开始位置.
        /*  将网络流中的文件写入本地*/
        byte[] data = new byte[1024 << 3];
        int length = -1;
        int progress = 0;// 记录本次下载文件的大小

//        Log.i(TAG, "thread" + threadId + " rangeFileLength:" + rangeFileLength + " code:" + response.code());
//        if (rangeFileLength == -1) {
//            Log.i(TAG, "-1 url:" + mTaskItem.getUrl());
//        }
        int bufferSize = BUFFER_SIZE;
        int position = 0;
        int mCurrentLength = 0;
        long startPostion = finalStartIndex;
        byte[] buffer = new byte[bufferSize << 1];
        try {
            while ((length = is.read(data)) > 0) {
                if (suspendRange.get() && downloadtype == DOWNLOAD_TYPE_RANGE) {
                    mWriteFileThread.isStop = false;
                    mWriteFileThread.isStart = false;
                    //关闭资源
                    close(cacheAccessFile, is, response.body());
                    Log.e(TAG,"suspend range downlaod");
                    return;
                }
                if (cancel) {
                    //关闭资源
                    mWriteFileThread.isStop = true;
                    mWriteFileThread.isStart = false;
                    close(is, response.body());
                    resetStutus();

//                        VideoDownloadManager.getInstance().deleteVideoTask(mTaskItem.getUrl(), true);
                    return;
                }
                if (pause) {
                    mWriteFileThread.isStop = true;
                    mWriteFileThread.isStart = false;
                    //关闭资源
                    close(is, response.body());
                    //发送暂停消息
                    resetStutus();
                    pause();
                    return;
                }
                mCurrentLength += length;
                System.arraycopy(data, 0, buffer, position, data.length);
                position += length;
                progress += length;
                mProgress[threadId] = finalStartIndex - startIndex + progress;

                if (mCurrentLength >= bufferSize) {
                    if (mWriteFileThread != null && !mWriteFileThread.isStart) {
                        mWriteFileThread.isStart = true;
                        mWriteFileThread.isStop = false;
                        mWriteFileThread.start();
                    }
                    LocatedBuffer locatedBuffer = new LocatedBuffer();
                    locatedBuffer.buffer = buffer;
                    locatedBuffer.length = mCurrentLength;
                    locatedBuffer.startPosition = startPostion;
                    mFileBuffersQueue.offer(locatedBuffer);
//                    Log.i(TAG, "thread" + threadId + " write 本次写入的大小:" + mCurrentLength+" 已下载的大小:"+progress);
                    //将当前现在到的位置保存到文件中
                    startPostion += mCurrentLength;
                    if (cacheAccessFile != null) {
                        cacheAccessFile.seek(0);
                        cacheAccessFile.write((startPostion + "").getBytes(StandardCharsets.UTF_8));
                    }
                    position = 0;
                    mCurrentLength = 0;
                    buffer = new byte[bufferSize << 1];
                }


                //发送进度消息
                if (mFileLength > 0) {
                    long p = 0;
                    for (long i : mProgress) {
                        p += i;
                    }
                    notifyProgress(p, mFileLength);
                }


            }
            if (mCurrentLength > 0) {
                if (mWriteFileThread != null && !mWriteFileThread.isStart) {
                    mWriteFileThread.isStart = true;
                    mWriteFileThread.start();
                }
                LocatedBuffer locatedBuffer = new LocatedBuffer();
                locatedBuffer.buffer = buffer;
                locatedBuffer.length = mCurrentLength;
                locatedBuffer.startPosition = startPostion;
                mFileBuffersQueue.offer(locatedBuffer);
//                Log.i(TAG, "thread" + threadId + " write1 本次写入的大小:" + mCurrentLength+" 已下载的大小:"+progress);


            }
            Log.e(TAG,"childFinshCount+1");
            childFinshCount.getAndAdd(1);
            if (mFileLength <= 0) {
                mFileLength = progress;
                //没有获取到文件长度，下载完毕再更新进度
                notifyProgress(progress, progress);
            } else {
                long p = 0;
                for (long i : mProgress) {
                    p += i;
                }
                notifyProgress(p, mFileLength);

            }
            //删除临时文件
            cleanFile(mCacheFiles[threadId]);
//            final String name=Thread.currentThread().getName();
//            Log.i(TAG, "download finish "+"thread id:"+threadId+" "+name);
        } catch (Exception e) {
            Log.e(TAG,""+e.getMessage());
            e.printStackTrace();
            retry(startIndex, endIndex, threadId, e, downloadtype,-1);

        } finally {
            //关闭资源
            close(is, response.body());
        }
    }

    private void retry(final long startIndex, final long endIndex, final int threadId, Exception e, int downloadType,int errCode) {
//        Log.i(TAG, "threadid:"+threadId+"retry type:"+ downloadType+"   \n"+e.getMessage());
        if(cancel||pause)
            return;
        mRetryCount++;
        if (errCode == HttpUtils.RESPONSE_503 || errCode == HttpUtils.RESPONSE_429|| errCode == HttpUtils.RESPONSE_509) {
            if (mRetryCount <= MAX_RETRY_COUNT_503) {
                //遇到503，延迟[4,20]秒后再重试，区间间隔不能太小
                int ran = 4000 + (int) (Math.random() * 16000);
                Log.e(TAG, "sleep:" + ran);
                suspendRange.set(true);
                try {
                    Thread.sleep(ran);
                    synchronized (responseCode503) {
                        if (responseCode503.get()) {
                            responseCode503.set(false);
                            mTotalThreadCount = 1;
                            mProgress = new long[1];
                            mCacheFiles = new File[1];
                            downloadByAll(0, 0, 0);
                        }
                    }
                } catch (Exception ex) {
                    e.printStackTrace();
                }
            }
        } else if (mRetryCount < VideoDownloadManager.getInstance().mConfig.retryCount) {
            try {
                if (downloadType == DOWNLOAD_TYPE_RANGE) {
                    downloadByRange(startIndex, endIndex, threadId);
                } else {
                    downloadByAll(startIndex, endIndex, threadId);
                }
            } catch (IOException ioException) {
                ioException.printStackTrace();
            }
        } else {
            resetStutus();
            Exception ex=new Exception(TAG+e.getMessage());
            notifyError(ex);
        }
    }

    private void notifyProgress(long progress, long total) {

        if (listener != null) {
            listener.onProgress(progress, total,true);
        }
    }

    private void notifyFinish(long progress, long total) {

        mWriteFileThread.isStop = true;
        resetStutus();
        close(bufferedOutputStream, fos, pdf, channel);
        mTaskItem.setFilePath(mTaskItem.getSaveDir()+ File.separator + fileName);
        if (listener != null) {
            listener.onProgress(progress, total,true);
        }

//        Log.i(TAG, "finish:"+mTaskItem.getUrl());
    }

    private void notifyError(Exception e) {
        e.printStackTrace();
        cancel = true;
        mWriteFileThread.isStop = true;
        mTaskItem.exception=e;
//        notifyFinish(mFileLength,mFileLength);
        if (listener != null) {
            listener.onError(e);
        }
//        Log.e(TAG, "=====notifyError:" + mTaskItem.getUrl());
    }

    private void handPublicDir(Response response) {
//        Log.i(TAG, "handPublicDir");
        InputStream is = null;
        BufferedInputStream inputStream = null;
        try {
            ResponseBody body = response.body();
            if (body == null) {
                if (listener != null) {
                    listener.onError(new Exception("response body is null"));
                }
                return;
            }
            mFileLength = body.contentLength();
            is = body.byteStream();// 获取流
            inputStream = new BufferedInputStream(is);

            int total = 0;
            if (bufferedOutputStream != null) {

                byte[] data = new byte[1024 << 3];
                int length;

                while ((length = inputStream.read(data)) != -1) {
                    if (cancel) {
                        //关闭资源
                        resetStutus();
                        close(inputStream);
                        close(is);
                        close(bufferedOutputStream);
                        return;
                    }
                    if (pause) {
                        //发送暂停消息
                        resetStutus();
                        pause();
                        close(inputStream);
                        close(is);
                        close(bufferedOutputStream);
                        return;
                    }
                    bufferedOutputStream.write(data, 0, length);
                    total += length;

                    if (mFileLength > 0) {
                        notifyProgress(total, mFileLength);
                    }
                }
                bufferedOutputStream.flush();
                if (pdf != null && pdf.getFileDescriptor() != null) {
                    pdf.getFileDescriptor().sync();
                }
                resetStutus();
                close(bufferedOutputStream, fos, pdf);
                notifyFinish(total, total);
            }
        } catch (Exception e) {
            e.printStackTrace();
            if (mRetryCount < VideoDownloadManager.getInstance().mConfig.retryCount) {
                mRetryCount++;
                try {
                    downInPublicDir(mTaskItem.getUrl());
                } catch (Exception ioException) {
                    ioException.printStackTrace();
                }
            } else {
                Exception ex=new Exception(TAG+e.getMessage());
                notifyError(ex);
            }
        } finally {
            close(response.body());
            close(inputStream);
            close(is);
            close(bufferedOutputStream, fos, pdf);

        }
    }

    /**
     * 不支持读写分离的用这个方法下载
     *
     * @param downPathUrl 下载文件的路径，需要包含后缀
     * date: 创建时间:2019/12/11
     * descripion: 保存图片，视频，音乐到公共地区，此操作需要在子线程，不是我们自己的APP目录下面的
     **/
    private void downInPublicDir(final String downPathUrl) {
        if(VideoDownloadUtils.isIllegalUrl(mTaskItem.getUrl())){
            notifyError(new Exception(URL_INVALID));
            return;
        }
        try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                OkHttpUtil.getInstance().request(downPathUrl,method,mTaskItem.header, new OkHttpUtil.RequestCallback() {
                    @Override
                    public void onFailure( @NonNull Exception e) {
                        if (mRetryCount < VideoDownloadManager.getInstance().mConfig.retryCount) {
                            mRetryCount++;
                            try {
                                downInPublicDir(downPathUrl);
                            } catch (Exception ioException) {
                                ioException.printStackTrace();
                            }
                        } else {
                            if (listener != null) {
                                listener.onError(e);
                            }
                        }

                    }

                    @Override
                    public void onResponse( @NonNull Response response)  {
                        if (response.code() != 200) {

                            if (mRetryCount < VideoDownloadManager.getInstance().mConfig.retryCount) {
                                mRetryCount++;
                                try {
                                    downInPublicDir(downPathUrl);
                                } catch (Exception ioException) {
                                    ioException.printStackTrace();
                                }
                            } else {
                                if (listener != null) {
                                    listener.onError(new Exception("unknow"));
                                }
                            }
                            return;


                        }


//                        Log.i(TAG, "download by public");
                        handPublicDir(response);

                    }
                });
            }

        } catch (IOException e) {
            e.printStackTrace();
        }
    }


    /**
     * 删除临时文件
     */
    private void cleanFile(File... files) {
        if(files==null)
            return;
        for (File file : files) {
            if (file != null) {
                boolean result = file.delete();
                if (result) {
                    Log.i(TAG, "delete fail");
                }
            }
        }
    }



    class LocatedBuffer {
        public byte[] buffer;
        public long startPosition;
        public int length;

        public LocatedBuffer() {
            startPosition = 0;
            buffer = new byte[BUFFER_SIZE << 1];
        }
    }

    public class WriteFileThread extends Thread {
        public boolean isStart = false;
        public boolean isStop = false;

        @Override
        public void run() {
            while (true) {
                if (mFileBuffersQueue.peek() != null) {
                    LocatedBuffer mCurrentBuffer = mFileBuffersQueue.poll();
                    if (mCurrentBuffer == null) {
                        continue;
                    }
                    try {
                        if (channel != null) {

                            channel.position(mCurrentBuffer.startPosition);
//                            Log.d(TAG,"seek:"+mCurrentBuffer.startPosition);
                        }
                        if (bufferedOutputStream != null) {
                            bufferedOutputStream.write(mCurrentBuffer.buffer, 0, mCurrentBuffer.length);
                            bufferedOutputStream.flush();
//                            Log.d(TAG,"write buffer:"+mCurrentBuffer.length);

                        }
                        if (pdf != null && pdf.getFileDescriptor() != null) {
                            pdf.getFileDescriptor().sync();
                        }

//                        Log.d(TAG,"childFinshCount:"+childFinshCount.get()+" totalCount:"+mTotalThreadCount);
//                        if (childFinshCount.get() == mTotalThreadCount && mFileBuffersQueue.peek() == null) {
//                            //所有分段全部下载完毕
//                            notifyFinish(mFileLength, mFileLength);
//                        }

                    } catch (IOException e) {
                        e.printStackTrace();
                    }

                } else {
                    if (childFinshCount.get() >= mTotalThreadCount) {
                        //所有分段全部下载完毕
                        notifyFinish(mFileLength, mFileLength);
                        Log.e(TAG,"notifyFinish by thread");
                    }
                }
                if (isStop) {
//                    Log.e(TAG,"thread stop");
                    try {
                        close(bufferedOutputStream,fos,pdf,channel);
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                    break;
                }

            }
        }

    }
}
