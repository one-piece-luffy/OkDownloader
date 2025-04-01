package com.baofu.downloader.factory;

import static com.baofu.downloader.common.VideoDownloadConstants.DOWNLOAD_TYPE_ALL;
import static com.baofu.downloader.common.VideoDownloadConstants.DOWNLOAD_TYPE_RANGE;
import static com.baofu.downloader.common.VideoDownloadConstants.MAX_RETRY_COUNT_503;
import static com.baofu.downloader.utils.OkHttpUtil.URL_INVALID;
import static com.baofu.downloader.utils.VideoDownloadUtils.close;

import android.os.Build;
import android.text.TextUtils;
import android.util.Log;

import com.baofu.downloader.listener.IFactoryListener;
import com.baofu.downloader.model.VideoTaskItem;
import com.baofu.downloader.rules.VideoDownloadManager;
import com.baofu.downloader.utils.HttpUtils;
import com.baofu.downloader.utils.OkHttpUtil;
import com.baofu.downloader.utils.VideoDownloadUtils;
import com.baofu.downloader.utils.VideoStorageUtils;

import org.jetbrains.annotations.NotNull;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileNotFoundException;
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
import java.util.concurrent.atomic.AtomicInteger;

import okhttp3.Response;
import okhttp3.ResponseBody;

public class Android9Factory extends BaseFactory {
    //当前重试次数
    public final String TAG = "Android9Factory: ";

    private File mTmpFile;//临时占位文件
    File mSaveDir;//保存的路径
    WriteFileThread mWriteFileThread;
    RandomAccessFile tmpAccessFile;


    private final AtomicInteger childFinshCount = new AtomicInteger(0);//子线程完成数量


    public Android9Factory(VideoTaskItem taskItem, File savedir, IFactoryListener listener) {
        super(taskItem, listener);
        mSaveDir = savedir;
        mFileBuffersQueue = new LinkedList();
        mWriteFileThread = new WriteFileThread();
    }


    @Override
    public void /**/delete() {
        cleanFile(mTmpFile);
        cleanFile(mCacheFiles);
    }


    /**
     * @param startIndex      开始下载的位置
     * @param endIndex        结束下载的位置
     * @param threadId        线程id
     * @param finalStartIndex 最终开始下载的位置
     * @param response        响应
     * @param downloadtype    下载类型：分段，全部
     */
    @Override
    void handlerResponse(final long startIndex, final long endIndex, final int threadId, final long finalStartIndex,
                                 Response response,  int downloadtype) {
        ResponseBody body = response.body();
        if (body == null) {
            notifyError(new Exception(TAG + "handlerResponse: body is null"));
            return;
        }


        long len = 0;
        String strLen = response.header("Content-Length");
        try {
            len = Long.parseLong(strLen);
//                    Log.i(TAG, "文件大小：" + mFileLength);
        } catch (Exception e) {
            len = response.body().contentLength();
        }
        Log.i(TAG, "thread" + threadId + " 分段总大小:" + len);
        RandomAccessFile cacheAccessFile = null;
        if (mCacheFiles[threadId] != null) {
            try {
                cacheAccessFile = new RandomAccessFile(mCacheFiles[threadId], "rwd");
            } catch (FileNotFoundException e) {
                e.printStackTrace();
            }
        }
        long time = System.currentTimeMillis() / 1000;
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
                    Log.e(TAG, "suspend range" + " " + time);
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
                    notifyProgress(p, mFileLength, true);
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
                notifyProgress(progress, progress, true);
            } else {

                long p = 0;
                for (long l : mProgress) {
                    p += l;
                }
                notifyProgress(p, mFileLength, true);
            }
            childFinshCount.getAndAdd(1);
            Log.e(TAG, "childFinshCount:" + childFinshCount.get() + " " + time);
            //删除临时文件
            close(cacheAccessFile, is, response.body());
            cleanFile(mCacheFiles[threadId]);
        } catch (Exception e) {
            e.printStackTrace();
            retry(startIndex, endIndex, threadId, e, downloadtype, -5);
            Log.e(TAG, "==" + e.getMessage());
        } finally {
            //关闭资源
            close(cacheAccessFile, is, response.body());
        }
    }



    private void notifyFinish(long progress, long total) {
        Log.e(TAG, "notifyFinish");
        mWriteFileThread.isStop = true;
        resetStutus();
        if (mTmpFile != null && fileName != null) {
            //下载完毕后，重命名目标文件名
            mTmpFile.renameTo(new File(mSaveDir, fileName));
            mTaskItem.setFilePath(mTaskItem.getSaveDir() + File.separator + fileName);
        }

        if (listener != null) {
            listener.onProgress(progress, total, true);
        }
//        Log.w(TAG, "finish:"+mTaskItem.getUrl());

    }

    @Override
    void notifyError(Exception e) {
        if (cancel) {
            return;
        }
        e.printStackTrace();
        cancel = true;
        mWriteFileThread.isStop = true;

        close(tmpAccessFile);
        mTaskItem.exception = e;
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

    @Override
    void handlerData(Response response) {
        try {
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
                    handlerResponse(0, 0, 0, 0, response,  DOWNLOAD_TYPE_ALL);
                }
            } else if (supportBreakpoint && mFileLength > 0) {

                mTotalThreadCount = VideoDownloadUtils.getBlockCount(mFileLength);
//                Log.e(TAG,"mTotalThreadCount:"+mTotalThreadCount);
                mProgress = new long[mTotalThreadCount];
                this.mCacheFiles = new File[mTotalThreadCount];
                Log.e(TAG, "asdf 文件大小：" + mFileLength + "分段数量：" + mTotalThreadCount);
                if (mTotalThreadCount == 1) {
                    handlerResponse(0, 0, 0, 0, response,  DOWNLOAD_TYPE_ALL);
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
                        downloadByRange(startIndex, endIndex, threadId);// 开启线程下载
                    }
                    //关闭资源
                    close(response.body());
                }


            } else {
                if (mFileLength > 0) {
                    mTotalThreadCount = 1;
                    mProgress = new long[mTotalThreadCount];
                    this.mCacheFiles = new File[mTotalThreadCount];
                    Log.e(TAG, "mTotalThreadCount:" + mTotalThreadCount);
                    if (response == null) {
                        downloadByAll(0, 0, 0);
                    } else {
                        handlerResponse(0, 0, 0, 0, response,  DOWNLOAD_TYPE_ALL);
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
            notifyError(new Exception(TAG + "saveFile: body is null"));
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
            Exception ex = new Exception(TAG + "saveFile: " + e.getMessage());
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
        if (files == null)
            return;
        for (File file : files) {
            if (file != null) {
                Path path = null;
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    path = Paths.get(file.getAbsolutePath());
                    try {
                        Files.delete(path);
                        Log.e(TAG, "asdf =====delete file suc:" + file.getAbsolutePath());
                    } catch (IOException e) {
                        Log.e(TAG, "asdf =====delete file fail:" + file.getAbsolutePath());
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
//                    Log.e("asdf","childFinishCount:"+childFinshCount.get()+" totalCount:"+mTotalThreadCount);
                } else {
                    if (childFinshCount.get() >= mTotalThreadCount) {
                        //所有分段全部下载完毕
                        notifyFinish(mFileLength, mFileLength);
                    }
                }
                if (isStop) {
//                    Log.e("asdf","thread break");
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
