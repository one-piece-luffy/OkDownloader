package com.baofu.downloader.factory;

import static com.baofu.downloader.common.VideoDownloadConstants.DOWNLOAD_TYPE_ALL;
import static com.baofu.downloader.common.VideoDownloadConstants.DOWNLOAD_TYPE_RANGE;
import static com.baofu.downloader.common.VideoDownloadConstants.MAX_RETRY_COUNT_503;
import static com.baofu.downloader.utils.OkHttpUtil.NO_SPACE;
import static com.baofu.downloader.utils.OkHttpUtil.URL_INVALID;
import static com.baofu.downloader.utils.VideoDownloadUtils.close;

import android.os.Build;
import android.text.TextUtils;
import android.util.Log;

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

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import okhttp3.Response;
import okhttp3.ResponseBody;

public class Android9Factory implements IDownloadFactory {
    //当前重试次数
    private int mRetryCount;
    public final String TAG = getClass().getSimpleName()+": ";
    IFactoryListener listener;
    long mFileLength;
    VideoTaskItem mTaskItem;
    private volatile boolean pause;//是否暂停
    private volatile boolean cancel;//是否取消下载
    final int BUFFER_SIZE = 1024 * 1024 ;
    private File mTmpFile;//临时占位文件
    private long[] mProgress;
    File mSaveDir;//保存的路径
    public Queue<LocatedBuffer> mFileBuffersQueue;
    WriteFileThread mWriteFileThread;
    RandomAccessFile tmpAccessFile;
    private File[] mCacheFiles;

    private String eTag;
    private boolean chunked;
    private boolean supportBreakpoint;
    private final AtomicBoolean responseCode206 = new AtomicBoolean(true);//分段请求是否返回206
    private final AtomicBoolean responseCode503 = new AtomicBoolean(true);
    //中断分段下载
    private final AtomicBoolean suspendRange = new AtomicBoolean(false);
    private final AtomicInteger childFinshCount = new AtomicInteger(0);//子线程完成数量
    int mTotalThreadCount;
    String fileName;
    String method;
    public Android9Factory(VideoTaskItem taskItem, File savedir, IFactoryListener listener) {
        this.listener = listener;
        this.mTaskItem = taskItem;
        mSaveDir = savedir;
        mFileBuffersQueue = new LinkedList();
        mWriteFileThread = new WriteFileThread();
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
        cleanFile(mTmpFile);
        cleanFile(mCacheFiles);
    }


    private void initDownloadInfo(String url) {
        if(VideoDownloadUtils.isIllegalUrl(mTaskItem.getUrl())){
            notifyError(new Exception(URL_INVALID));
            return;
        }
//        Log.i(TAG, "初始化，获取下载文件信息...");

        try {
            // 发起请求，从响应头获取文件信息
//            Response response = OkHttpUtil.getInstance().getHeaderSync(url);
            Response response = OkHttpUtil.getInstance().requestSync(url,method,mTaskItem.getHeader());
            int code=response.code();
            if(code>=200&&code<300){
                //            Log.i(TAG, "请求头================\n" + response.headers().toString());
                //header请求才能拿到文件名
//            String fileName = getFileName(response);
//            Log.i(TAG, "获取到文件名：" + fileName);

                // 获取分块传输标志
                String transferEncoding = response.header("Transfer-Encoding");
                this.chunked = "chunked".equals(transferEncoding);
//            Log.i(TAG, "是否分块传输：" + chunked);
                // 没有分块传输才可获取到文件长度
                if (!this.chunked) {
                    String strLen = response.header("Content-Length");
                    try {
                        mFileLength = Long.parseLong(strLen);
//                    Log.i(TAG, "文件大小：" + mFileLength);
                    } catch (Exception e) {
                        mFileLength = response.body().contentLength();
                    }
                }
                long freeSpace=VideoDownloadUtils.getFreeSpaceBytes(VideoDownloadManager.getInstance().mConfig.privatePath);
//            Log.e(TAG,"free space:"+VideoDownloadUtils.getSizeStr(freeSpace));
                if (mFileLength > freeSpace) {
                    //存储空间不足
                    notifyError(new Exception(NO_SPACE));
//                Log.e(TAG,"存储空间不足");
                    return;
                }

                // 是否支持断点续传
                String acceptRanges = response.header("Accept-Ranges");
                this.supportBreakpoint = "bytes".equalsIgnoreCase(acceptRanges);
                this.eTag = response.header("ETag");
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
                fileName = VideoDownloadUtils.getFileName(mTaskItem, null, true);
                File file=new File(mTaskItem.getSaveDir()+ File.separator + fileName);
                if(file.exists()&&!mTaskItem.overwrite){
                    fileName = VideoDownloadUtils.getFileName(mTaskItem, System.currentTimeMillis() + "",true);
                }
                handlerData(response);
            }else {
                notifyError(new Exception(TAG+"initDownloadInfo: code:"+code+" message:"+response.message()));
            }


        } catch (Exception e) {
            e.printStackTrace();
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

    private void downloadByRange(final long startIndex, final long endIndex, final int threadId,boolean retry) throws IOException {
        if(VideoDownloadUtils.isIllegalUrl(mTaskItem.getUrl())){
            notifyError(new Exception(URL_INVALID));
            return;
        }
        Log.e(TAG,"asdf thread"+threadId+" RANGE: "+startIndex+"-"+endIndex+" retry:"+retry);
        long newStartIndex = startIndex;
        // 分段请求网络连接,分段将文件保存到本地.
        // 加载下载位置缓存文件
//        final File cacheFile = new File(mSaveDir, "thread" + threadId + "_" + fileName + ".cache");
        final File cacheFile = new File(VideoStorageUtils.getTempDir(VideoDownloadManager.getInstance().mConfig.context), "thread" + threadId + "_" + fileName + ".cache");
        Log.e(TAG,"asdf fileName: "+cacheFile.getAbsolutePath() );

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
        Map<String,String> taskHeader=mTaskItem.getHeader();
        if(taskHeader!=null){
            header.putAll(taskHeader);
        }
//        for (Map.Entry<String, String> entry : header.entrySet()) {
//            String key = entry.getKey();
//            String value = entry.getValue();
//            Log.e("asdf","trhead:"+threadId+", key: "+key+", value: "+value);
//        }
        try {
            OkHttpUtil.getInstance().request(mTaskItem.getUrl(),method, header, new OkHttpUtil.RequestCallback() {
                @Override
                public void onResponse( @NotNull Response response) throws IOException {
                    int code = response.code();

                    if (!response.isSuccessful()) {
                        if(code==416){
                            notifyError(new Exception("code=416"));
                            return;
                        }
                        // 206：请求部分资源时的成功码,断点下载的成功码
                        retry(startIndex, endIndex, threadId, new Exception("downloadByRange server error:"+code), DOWNLOAD_TYPE_RANGE,code);
                        return;
                    }

                    if (supportBreakpoint && code != 206) {
                        //分段请求状态码不是206，则重新发起【不分段】的请求
                        synchronized (responseCode206) {
                            if (responseCode206.get()) {
                                responseCode206.set(false);
                                downloadByAll(0, 0, 0);
                            }
                        }

                        return;
                    }
                    handlerResponse(startIndex, endIndex, threadId, finalStartIndex, response, cacheFile, cacheAccessFile, DOWNLOAD_TYPE_RANGE);
                }

                @Override
                public void onFailure( @NotNull Exception e) {
                    e.printStackTrace();
                    retry(startIndex, endIndex, threadId, e, DOWNLOAD_TYPE_RANGE,-1);
                }
            });

        } catch (Exception e) {
            e.printStackTrace();
            retry(startIndex, endIndex, threadId, e, DOWNLOAD_TYPE_RANGE,-2);
        }
    }

    private void downloadByAll(final long startIndex, final long endIndex, final int threadId) throws IOException {
        if(VideoDownloadUtils.isIllegalUrl(mTaskItem.getUrl())){
            notifyError(new Exception(URL_INVALID));
            return;
        }
        Log.i(TAG,"download all start");
        Map<String, String> header = new HashMap<>();
        header.put("User-Agent", VideoDownloadUtils.getUserAgent());
        Map<String,String> taskHeader=mTaskItem.getHeader();
        if(taskHeader!=null){
            header.putAll(taskHeader);
        }
        try {
            method = OkHttpUtil.METHOD.GET;
            if (OkHttpUtil.METHOD.POST.equalsIgnoreCase(mTaskItem.method)) {
                method = OkHttpUtil.METHOD.POST;
            }
            OkHttpUtil.getInstance().request(mTaskItem.getUrl(),method, header, new OkHttpUtil.RequestCallback() {
                @Override
                public void onResponse(@NotNull Response response)  {
                    int code = response.code();
                    if (!response.isSuccessful()) {
                        // 206：请求部分资源时的成功码,断点下载的成功码
                        retry(startIndex, endIndex, threadId, new Exception("downloadByAll server error "+code), DOWNLOAD_TYPE_ALL,code);
                        return;
                    }
                    handlerResponse(startIndex, endIndex, threadId, startIndex, response, null, null, DOWNLOAD_TYPE_ALL);
                }

                @Override
                public void onFailure(@NotNull Exception e) {
                    e.printStackTrace();
                    retry(startIndex, endIndex, threadId, e, DOWNLOAD_TYPE_ALL,-3);
                }
            });

        } catch (Exception e) {
            e.printStackTrace();
            Log.e(TAG,"=="+e.getMessage());
            retry(startIndex, endIndex, threadId, e, DOWNLOAD_TYPE_ALL,-4);
        }
    }

    /**
     *
     * @param startIndex 开始下载的位置
     * @param endIndex 结束下载的位置
     * @param threadId 线程id
     * @param finalStartIndex 最终开始下载的位置
     * @param response 响应
     * @param cacheFile  保留下载位置的文件
     * @param cacheAccessFile 保留下载位置的RandomAccessFile
     * @param downloadtype 下载类型：分段，全部
     */
    private void handlerResponse(final long startIndex, final long endIndex, final int threadId, final long finalStartIndex,
                                 Response response, File cacheFile, RandomAccessFile cacheAccessFile, int downloadtype) {
        ResponseBody body=response.body();
        if (body == null) {
            notifyError(new Exception(TAG+"handlerResponse: body is null"));
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

        long time=System.currentTimeMillis()/1000;
        long rangeFileLength = body.contentLength();
//        Log.i(TAG, "rangeFileLength:" + rangeFileLength + " code:" + response.code());
        byte[] data = new byte[1024 << 3];
        int length = -1;
        int progress = 0;// 记录本次下载文件的大小

        InputStream is = body.byteStream();// 获取流
        int bufferSize = BUFFER_SIZE;
        int position = 0;
        int mCurrentLength = 0;
        long startPosition = finalStartIndex;
        byte[] buffer = new byte[bufferSize << 1];
        try {
            while ((length = is.read(data)) > 0) {
                if (suspendRange.get() && downloadtype == DOWNLOAD_TYPE_RANGE) {

                    mWriteFileThread.isStop = false;
                    mWriteFileThread.isStart = false;
                    //关闭资源
                    close(cacheAccessFile, is, response.body());
                    Log.e(TAG,"suspend range"+" "+time);
                    return;
                }
                if (cancel) {
                    mWriteFileThread.isStop = true;
                    mWriteFileThread.isStart = false;
                    //关闭资源
                    close(cacheAccessFile, is, response.body());
                    resetStutus();
//                        VideoDownloadManager.getInstance().deleteVideoTask(mTaskItem.getUrl(), true);
                    return;
                }
                if (pause) {
                    mWriteFileThread.isStop = true;
                    mWriteFileThread.isStart = false;
                    //关闭资源
                    close(cacheAccessFile, is, response.body());
                    //发送暂停消息
                    resetStutus();
                    pause();
                    return;
                }
                mCurrentLength += length;
                System.arraycopy(data, 0, buffer, position, data.length);
                position += length;
                progress += length;
                mProgress[threadId] = finalStartIndex-startIndex+progress;
                if (mCurrentLength >= bufferSize) {
                    if (mWriteFileThread != null && !mWriteFileThread.isStart) {
                        mWriteFileThread.isStart = true;
                        mWriteFileThread.isStop = false;
                        mWriteFileThread.start();
                    }
                    LocatedBuffer locatedBuffer = new LocatedBuffer();
                    locatedBuffer.buffer = buffer;
                    locatedBuffer.length = mCurrentLength;
                    locatedBuffer.startPosition = startPosition;
                    mFileBuffersQueue.offer(locatedBuffer);
//                    Log.i(TAG, "thread" + threadId + " write 本次写入的大小:" + mCurrentLength+" 已下载的大小:"+progress);

                    startPosition += mCurrentLength;
                    //将当前现在到的位置保存到文件中
                    if (cacheAccessFile != null) {
                        cacheAccessFile.seek(0);
                        cacheAccessFile.write((startPosition + "").getBytes(StandardCharsets.UTF_8));
                    }
                    position = 0;
                    mCurrentLength = 0;
                    buffer = new byte[bufferSize << 1];
                }


                //发送进度消息
                if (mFileLength > 0) {
                    long p = 0;
                    for (long l : mProgress) {
                        p += l;
                    }
                    notifyProgress(p, mFileLength,true);
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
                locatedBuffer.startPosition = startPosition;
                mFileBuffersQueue.offer(locatedBuffer);
//                Log.i(TAG, "thread" + threadId + " write 本次写入的大小:" + mCurrentLength+" 已下载的大小:"+progress);

                startPosition += mCurrentLength;
                if (cacheAccessFile != null) {
                    //将当前现在到的位置保存到文件中
                    cacheAccessFile.seek(0);
                    cacheAccessFile.write((startPosition + "").getBytes(StandardCharsets.UTF_8));
                }

            }

            if (mFileLength <= 0) {
                mFileLength = progress;
                //没有获取到文件长度，下载完毕再更新进度
                notifyProgress(progress, progress,true);
            } else {

                long p = 0;
                for (long l : mProgress) {
                    p += l;
                }
                notifyProgress(p, mFileLength,true);
            }
            childFinshCount.getAndAdd(1);
            Log.e(TAG,"childFinshCount:"+childFinshCount.get()+" "+time);
            //删除临时文件
            close(cacheAccessFile, is, response.body());
            cleanFile(cacheFile);
        } catch (Exception e) {
            e.printStackTrace();
            retry(startIndex, endIndex, threadId, e, downloadtype,-5);
            Log.e(TAG,"=="+e.getMessage());
        } finally {
            //关闭资源
            close(cacheAccessFile, is, response.body());
        }
    }

    private void retry(final long startIndex, final long endIndex, final int threadId, Exception e, int retryType,int errCode) {
        Log.e(TAG, "retry");
        if(cancel)
            return;
        mRetryCount++;
        if (errCode == HttpUtils.RESPONSE_503 || errCode == HttpUtils.RESPONSE_429|| errCode == HttpUtils.RESPONSE_509) {
            if (mRetryCount <= MAX_RETRY_COUNT_503) {
                //遇到503，延迟[4,24]秒后再重试，区间间隔不能太小
                int ran = 4000 + (int) (Math.random() * 20000);
                Log.e(TAG, "sleep:" + ran);
                suspendRange.set(true);
                try {
                    Thread.sleep(ran);
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
            }
        } else if (mRetryCount < VideoDownloadManager.getInstance().mConfig.retryCount) {
            try {
                if (retryType == DOWNLOAD_TYPE_RANGE) {
                    downloadByRange(startIndex, endIndex, threadId,true);
                } else {
                    downloadByAll(startIndex, endIndex, threadId);
                }
            } catch (IOException ioException) {
                ioException.printStackTrace();
            }
        } else {
            resetStutus();
            Exception ex=new Exception(TAG+"retry: "+e.getMessage()+" code:"+errCode);
            notifyError(ex);
        }
    }

    private void notifyProgress(long progress, long total,boolean hasFileLength) {

        if (listener != null) {
            listener.onProgress(progress, total,hasFileLength);
        }
    }

    private void notifyFinish(long progress, long total) {
        Log.e(TAG,"notifyFinish");
        mWriteFileThread.isStop = true;
        resetStutus();
        if (mTmpFile != null&&fileName!=null) {
            //下载完毕后，重命名目标文件名
            mTmpFile.renameTo(new File(mSaveDir, fileName));
            mTaskItem.setFilePath(mTaskItem.getSaveDir()+ File.separator + fileName);
        }

        if (listener != null) {
            listener.onProgress(progress, total,true);
        }
//        Log.w(TAG, "finish:"+mTaskItem.getUrl());

    }

    private void notifyError(Exception e) {
        if(cancel){
            return;
        }
        e.printStackTrace();
        cancel = true;
        mWriteFileThread.isStop = true;

        close(tmpAccessFile);
        mTaskItem.exception=e;
        if (listener != null) {
            listener.onError(e);
        }
        Log.e(TAG, "=====notifyError:" + e.getMessage());
    }


    private void createTempFile() {
        mTmpFile = new File(mSaveDir, fileName + ".tmp");
        if (!mTmpFile.getParentFile().exists()) {
            mTmpFile.getParentFile().mkdirs();
        }
        if (!mTmpFile.exists()) {
            try {
                mTmpFile.createNewFile();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    private void handlerData(Response response) {
        try {
            mTaskItem.setTotalSize(mFileLength);
            createTempFile();
            tmpAccessFile = new RandomAccessFile(mTmpFile, "rw");
            if (mFileLength > 0) {
                tmpAccessFile.setLength(mFileLength);
            }
            if (mTaskItem.contentType != null && mTaskItem.contentType.contains("image")) {
                //支持分段下载的下载方式
                //图片不分段
                mTotalThreadCount = 1;
                mProgress = new long[mTotalThreadCount];
                this.mCacheFiles = new File[mTotalThreadCount];
                if (response == null) {
                    downloadByAll(0, 0, 0);
                } else {
                    handlerResponse(0, 0, 0, 0, response, null, null, DOWNLOAD_TYPE_ALL);
                }
            } else if (supportBreakpoint && mFileLength > 0) {

                mTotalThreadCount = VideoDownloadUtils.getBlockCount(mFileLength);
//                Log.e(TAG,"mTotalThreadCount:"+mTotalThreadCount);
                mProgress = new long[mTotalThreadCount];
                this.mCacheFiles = new File[mTotalThreadCount];
                Log.e(TAG,"asdf 文件大小："+mFileLength+"分段数量："+mTotalThreadCount);
                if (mTotalThreadCount == 1) {
                    handlerResponse(0, 0, 0, 0, response, null, null, DOWNLOAD_TYPE_ALL);
                }else {
                    /*将下载任务分配给每个线程*/
                    long blockSize = mFileLength / mTotalThreadCount;// 计算每个线程理论上下载的数量.
                    /*为每个线程配置并分配任务*/
                    for (int threadId = 0; threadId < mTotalThreadCount; threadId++) {
                        long startIndex = threadId * blockSize; // 线程开始下载的位置
                        long endIndex = (threadId + 1) * blockSize - 1; // 线程结束下载的位置
                        if (threadId == (mTotalThreadCount - 1)) { // 如果是最后一个线程,将剩下的文件全部交给这个线程完成
                            endIndex = mFileLength - 1;
                        }
                        downloadByRange(startIndex, endIndex, threadId,false);// 开启线程下载
                    }
                    //关闭资源
                    close(response.body());
                }



            } else {
                if (mFileLength > 0) {
                    mTotalThreadCount = 1;
                    mProgress = new long[mTotalThreadCount];
                    this.mCacheFiles = new File[mTotalThreadCount];
                    Log.e(TAG,"mTotalThreadCount:"+mTotalThreadCount);
                    if (response == null) {
                        downloadByAll(0, 0, 0);
                    } else {
                        handlerResponse(0, 0, 0, 0, response, null, null, DOWNLOAD_TYPE_ALL);
//                        saveFile(response,mTmpFile);
                    }
                } else {
                    saveFile(response, mTmpFile);
                }

            }
        } catch (Exception e) {
            e.printStackTrace();
            resetStutus();
            if (listener != null) {
                listener.onError(e);
            }
        }
    }


    private void saveFile(Response response, File file) {

        ResponseBody body = response.body();
        if (body == null) {
            notifyError(new Exception(TAG+"saveFile: body is null"));
            return;
        }

        InputStream inputStream = body.byteStream();// 获取流

        FileOutputStream fos = null;
        BufferedOutputStream bos = null;
        long totalLength = 0;
        int bufferSize = 1024 * 1024 * 2;
        int position = 0;
        int mCurrentLength = 0;
        try {

            fos = new FileOutputStream(file);
            bos = new BufferedOutputStream(fos);
            int len;
            byte[] data = new byte[1024 << 3];
            byte[] buffer = new byte[bufferSize << 1];

            while ((len = inputStream.read(data)) != -1) {
                totalLength += (long) len;
                mCurrentLength += len;
                System.arraycopy(data, 0, buffer, position, data.length);
                position += len;
                if (mCurrentLength >= bufferSize) {
                    bos.write(buffer, 0, mCurrentLength);
                    position = 0;
                    mCurrentLength = 0;
                    buffer = new byte[bufferSize << 1];
                }
                if (mFileLength > 0) {
                    notifyProgress(totalLength, mFileLength, true);
                } else {
                    //因为文件的总大小不知道，所以这里只返回已下载的
                    notifyProgress(totalLength, totalLength * 100, false);
                }

            }
            if (mCurrentLength > 0) {
                bos.write(buffer, 0, mCurrentLength);
//                bos.flush();
            }
            notifyFinish(totalLength, totalLength);


        } catch (IOException e) {
            if (cancel)
                return;
            resetStutus();
            Exception ex = new Exception(TAG +"saveFile: "+ e.getMessage());
            notifyError(ex);
        } finally {
            VideoDownloadUtils.close(inputStream);
            VideoDownloadUtils.close(fos);
            VideoDownloadUtils.close(bos);
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
                Path path = null;
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    path = Paths.get(file.getAbsolutePath());
                    try {
                        Files.delete(path);
                        Log.e(TAG, "asdf =====delete file suc:"+file.getAbsolutePath());
                    } catch (IOException e) {
                        Log.e(TAG, "asdf =====delete file fail:"+file.getAbsolutePath());
                        System.out.println("文件删除失败: " + e.getMessage());
                    }
                } else {
                    boolean result = file.delete();
                    if (!result) {
                        file.deleteOnExit();
                        Log.e(TAG, "asdf =====delete file fail:" + file.getAbsolutePath());
                    } else {
                        Log.e(TAG, "asdf =====delete file suc:" + file.getAbsolutePath());
                    }
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
                        tmpAccessFile.seek(mCurrentBuffer.startPosition);
                        tmpAccessFile.write(mCurrentBuffer.buffer, 0, mCurrentBuffer.length);
                    } catch (IOException e) {
                        e.printStackTrace();
                    }

//                    Log.e(TAG,"childFinishCount:"+childFinishCount.get()+" totalCount:"+mTotalThreadCount);
                } else {
                    if (childFinshCount.get() >= mTotalThreadCount) {
                        //所有分段全部下载完毕
                        notifyFinish(mFileLength, mFileLength);
                    }
                }
                if (isStop) {
                    Log.e(TAG,"thread break");
                    close(tmpAccessFile);
                    break;
                }

            }
        }

    }
//    public void down() {
//        try {
//            OkHttpUtil.getInstance().getContentLength(mTaskItem.getUrl(), new okhttp3.Callback() {
//                @Override
//                public void onResponse(Call call, Response response) throws IOException {
//                    //                        Log.e(TAG, "start: " + response.code() + "\t isDownloading:" + isDownloading + "\t" + mTaskItem.getUrl());
//                    if (response.code() != 200) {
//
//                        if (mRetryCount < MAX_RETRY_COUNT) {
//                            mRetryCount++;
//                            close(response.body());
//                            resetStutus();
//                            down();
//                        } else {
//                            close(response.body());
//                            resetStutus();
//                            if (listener != null) {
//                                listener.onError(new Exception());
//                            }
//                        }
//                        return;
//                    }
//                    mRetryCount = 0;
//                    MediaType contentType = response.body().contentType();
//                    if (contentType != null) {
//                        mTaskItem.contentType = contentType.toString();
//                        Iterator<Map.Entry<String, String>> it = MimeType.map.entrySet().iterator();
//                        while (it.hasNext()) {
//                            Map.Entry<String, String> entry = it.next();
//                            if (entry.getKey().contains(contentType.toString())) {
//                                mTaskItem.suffix = entry.getValue();
//                                break;
//                            }
//                        }
//                    }
//
//                    // 获取资源大小
//                    mFileLength = response.body().contentLength();
//                    close(response.body());
//
//                    if (mFileLength == 0) {
//                        //resetStutus();
//                        //notifyDownloadError(new Exception("下载文件大小为0"));
//                        try {
//                            downloadNoProgress(mTaskItem.getUrl());
//                        } catch (Exception e) {
//                            e.printStackTrace();
//                        }
//                        return;
//                    }
//
//
//                    handlerData();
//                }
//
//                @Override
//                public void onFailure(Call call, IOException e) {
//                    Log.e(TAG, "start:Exception " + e.getMessage() + "\n" + mTaskItem.getUrl());
//
//                    if (mRetryCount < MAX_RETRY_COUNT) {
//                        mRetryCount++;
//                        resetStutus();
//                        down();
//                    } else {
//                        resetStutus();
//                        if (listener != null) {
//                            listener.onError(e);
//                        }
//                    }
//                }
//            });
//        } catch (IOException e) {
//            e.printStackTrace();
//        }
//    }
}
