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
import java.nio.file.DirectoryNotEmptyException;
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

@RequiresApi(api = Build.VERSION_CODES.Q)
public class Android10FastFactory extends BaseFactory {


    public final String TAG = "Android10FastFactory: ";


    WriteFileThread mWriteFileThread;

    // 存储类型，可选参数 DIRECTORY_PICTURES  ,DIRECTORY_MOVIES  ,DIRECTORY_MUSIC
    String inserType = DIRECTORY_DOWNLOADS;
    ParcelFileDescriptor pdf;
    FileChannel channel;
    BufferedOutputStream bufferedOutputStream = null;
    FileOutputStream fos = null;
    //是否支持读写分离
    boolean mSplitReadWrite;
    //是否使用异步下载
    //分段下载方式：固定线程数
    final int RANGE_TYPE_THREAD = 1;
    //分段下载方式：固定阈值数
    final int RANGE_TYPE_THRESHOLD = 2;
    int mRangeType;
    private final AtomicInteger childFinshCount = new AtomicInteger(0);//子线程完成数量


    public Android10FastFactory(VideoTaskItem taskItem, IFactoryListener listener) {
        super(taskItem, listener);
        mWriteFileThread = new WriteFileThread();
        mRangeType = RANGE_TYPE_THREAD;
    }


    @Override
    public void delete() {
        cleanFile(mCacheFiles);
    }

    @Override
    void handlerData(Response response) {
        try {
            String mimeType = mTaskItem.contentType;
            if (TextUtils.isEmpty(mimeType) && !TextUtils.isEmpty(mTaskItem.suffix)) {
                mimeType = mTaskItem.suffix.replace(".", "");
            } else if (!TextUtils.isEmpty(mimeType) && TextUtils.isEmpty(mTaskItem.suffix)) {
                //MimeType 没有匹配到后缀，如果不置空mimeType,公有目录会默认添加后缀，导致文件路径和数据库存储的不一致
                mimeType = null;
            }
            Uri uri = VideoDownloadUtils.getUri(DIRECTORY_DOWNLOADS, fileName, mimeType);
            if (uri == null) {
                //创建失败，则重命名，重新创建
                Log.e(TAG, "==================重命名");
                fileName = VideoDownloadUtils.getFileName(mTaskItem, System.currentTimeMillis() + "", true);
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
                        Log.e(TAG, "asdf 文件大小：" + mFileLength + "分段数量：" + mTotalThreadCount);
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
                                downloadByRange(startIndex, finalEndIndex, finalThreadId);


                            }
                            //关闭资源
                            close(response.body());
                        }


                    } else {
                        final int threshold = 1024 * 1024 * 5;//每个线程的下载阈值
                        int count = (int) (mFileLength / threshold);// 计算线程的数量.
                        mTotalThreadCount = count;
                        mProgress = new long[count + 1];
                        mCacheFiles = new File[count + 1];
                        long startPos = 0, endPos = 0;
                        for (int i = 0; i < count; i++) {
                            startPos = (long) i * threshold;
                            endPos = startPos + threshold - 1;
                            long finalStartPos = startPos;
                            long finalEndPos = endPos;
                            int finalI = i;
                            downloadByRange(finalStartPos, finalEndPos, finalI);

                        }
                        if (endPos < mFileLength - 1) {
                            long finalEndPos1 = endPos;
                            downloadByRange(finalEndPos1 + 1, mFileLength, mProgress.length - 1);

                        }
                        //关闭资源
                        close(response.body());
                    }


                } else {
                    //没有取到文件大小的或者不支持分段的下载方式
                    mTotalThreadCount = 1;
                    mProgress = new long[1];
                    mCacheFiles = new File[1];
//                        downloadByRange(0, mFileLength, 0);// 开启线程下载
                    if (response == null) {
                        downloadByAll(0, 0, 0);
                    } else {
                        handlerResponse(0, 0, 0, 0, response, DOWNLOAD_TYPE_ALL);
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
     * 全部下载，不分段
     *
     * @param startIndex 开始位置
     * @param endIndex   结束位置
     * @param threadId   线程id
     */
    @Override
    void handlerResponse(final long startIndex, final long endIndex, final int threadId, final long finalStartIndex, Response response, int downloadtype) {
        if (response == null) {
            notifyError(new Exception(TAG + "handlerResponse: response is null"));
            return;
        }
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

        InputStream is = body.byteStream();// 获取流
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
                    Log.e(TAG, "suspend range downlaod");
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
                locatedBuffer.startPosition = startPostion;
                mFileBuffersQueue.offer(locatedBuffer);
//                Log.i(TAG, "thread" + threadId + " write1 本次写入的大小:" + mCurrentLength+" 已下载的大小:"+progress);


            }
            Log.e(TAG, "childFinshCount+1");
            childFinshCount.getAndAdd(1);
            if (mFileLength <= 0) {
                mFileLength = progress;
                //没有获取到文件长度，下载完毕再更新进度
                notifyProgress(progress, progress, true);
            } else {
                long p = 0;
                for (long i : mProgress) {
                    p += i;
                }
                notifyProgress(p, mFileLength, true);

            }
            close(is, response.body());
            //删除临时文件
            cleanFile(mCacheFiles[threadId]);
//            final String name=Thread.currentThread().getName();
//            Log.i(TAG, "download finish "+"thread id:"+threadId+" "+name);
        } catch (Exception e) {
            Log.e(TAG, "" + e.getMessage());
            e.printStackTrace();
            retry(startIndex, endIndex, threadId, e, downloadtype, -3);

        } finally {
            //关闭资源
            close(is, response.body());
        }
    }



    private void notifyFinish(long progress, long total) {

        mWriteFileThread.isStop = true;
        resetStutus();
        close(bufferedOutputStream, fos, pdf, channel);
        mTaskItem.setFilePath(mTaskItem.getSaveDir() + File.separator + fileName);
        if (listener != null) {
            listener.onProgress(progress, total, true);
        }

//        Log.i(TAG, "finish:"+mTaskItem.getUrl());
    }

    @Override
    void notifyError(Exception e) {
        if (cancel) {
            return;
        }
        e.printStackTrace();
        cancel = true;
        mWriteFileThread.isStop = true;
        mTaskItem.exception = e;
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
                        notifyProgress(total, mFileLength, true);
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
                Exception ex = new Exception(TAG + "handPublicDir:" + e.getMessage());
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
     *                    date: 创建时间:2019/12/11
     *                    descripion: 保存图片，视频，音乐到公共地区，此操作需要在子线程，不是我们自己的APP目录下面的
     **/
    private void downInPublicDir(final String downPathUrl) {
        if (VideoDownloadUtils.isIllegalUrl(mTaskItem.getUrl())) {
            notifyError(new Exception(URL_INVALID));
            return;
        }
        try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                OkHttpUtil.getInstance().request(downPathUrl, method, VideoDownloadUtils.getTaskHeader(mTaskItem), new OkHttpUtil.RequestCallback() {
                    @Override
                    public void onFailure(@NonNull Exception e) {
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
                    public void onResponse(@NonNull Response response) {
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
        if (files == null)
            return;
        for (File file : files) {
            if (file == null) {
                continue;
            }
            Path path = Paths.get(file.getAbsolutePath());
            try {
                Files.delete(path);
                Log.e(TAG, "asdf =====delete file suc:" + file.getAbsolutePath());
            } catch (IOException e) {
                Log.e(TAG, "asdf =====delete file fail:" + file.getAbsolutePath());
                System.out.println("文件删除失败: " + e.getMessage());
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
                        Log.e(TAG, "notifyFinish by thread");
                    }
                }
                if (isStop) {
//                    Log.e(TAG,"thread stop");
                    try {
                        close(bufferedOutputStream, fos, pdf, channel);
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                    break;
                }

            }
        }

    }
}
