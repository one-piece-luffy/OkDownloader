package com.baofu.downloader.task;

import static android.os.Environment.DIRECTORY_DOWNLOADS;
import static com.baofu.downloader.common.VideoDownloadConstants.MAX_RETRY_COUNT_503;

import android.content.Context;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Build;
import android.os.ParcelFileDescriptor;
import android.text.TextUtils;
import android.util.Log;

import androidx.annotation.RequiresApi;

import com.baofu.downloader.VideoDownloadException;
import com.baofu.downloader.common.VideoDownloadConstants;
import com.baofu.downloader.listener.IFFmpegCallback;
import com.baofu.downloader.m3u8.M3U8;
import com.baofu.downloader.m3u8.M3U8Constants;
import com.baofu.downloader.m3u8.M3U8Seg;
import com.baofu.downloader.model.VideoTaskItem;
import com.baofu.downloader.rules.VideoDownloadManager;
import com.baofu.downloader.utils.AES128Utils;
import com.baofu.downloader.utils.DownloadExecutor;
import com.baofu.downloader.utils.FFmpegUtils;
import com.baofu.downloader.utils.HttpUtils;
import com.baofu.downloader.utils.OkHttpUtil;
import com.baofu.downloader.utils.VideoDownloadUtils;
import com.baofu.downloader.utils.VideoStorageUtils;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.channels.ReadableByteChannel;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import okhttp3.Response;

public class M3U8VideoDownloadTask extends VideoDownloadTask {

    private static final String TAG = "M3U8VideoDownloadTask";
    private final Object mFileLock = new Object();
    private final Object mCreateFileLock = new Object();

    private final M3U8 mM3U8;
    private final List<M3U8Seg> mTsList;
    private final AtomicInteger mCurTs = new AtomicInteger(0);
    private final int mTotalTs;
    private long mTotalSize;
    //ts下载失败的个数
    private final AtomicInteger mErrorTsCont = new AtomicInteger(0);
    private Timer netSpeedTimer;//定时任务
    private final AtomicLong mCurrentDownloaddSize = new AtomicLong(0);//当前的下载大小
    AtomicBoolean isRunning = new AtomicBoolean(false);//任务是否正在运行中
    String fileName;
    //存储下载失败的错误信息
    Map<String,String> errMsgMap=new ConcurrentHashMap<>();
    final int MAX_ERR_MAP_COUNT = 3;
    ExecutorService executorService;
    //5kb
    final long SIZE_THRESHOLD = 5 * 1024;

    public M3U8VideoDownloadTask(VideoTaskItem taskItem, M3U8 m3u8) {
        super(taskItem);
        mM3U8 = m3u8;
        mTsList = m3u8.getTsList();
        mTotalTs = mTsList.size();
        mPercent = taskItem.getPercent();
        Map<String,String> header=VideoDownloadUtils.getTaskHeader(taskItem);
        if(header!=null){
            header.put("Connection", "close");
            mTaskItem.header=VideoDownloadUtils.mapToJsonString(header);
        }
        mTaskItem.setTotalTs(mTotalTs);
        mTaskItem.setCurTs(mCurTs.get());

        if (mTaskItem.estimateSize > 0) {
            //暂时把预估大小设置为文件的总大小，等下载完成后再更新准确的总大小
            mTaskItem.setTotalSize(taskItem.estimateSize);
        }
    }

    private void initM3U8Ts() {
        if (mCurTs.get() == mTotalTs) {
            mTaskItem.setIsCompleted(true);
        }
        mTaskItem.suffix = ".m3u8";
        mCurrentDownloaddSize.set(0);
        mCurTs.set(0);
        fileName = VideoDownloadUtils.getFileNameWithSuffix(mTaskItem);
    }


    @Override
    public void startDownload() {
        mDownloadTaskListener.onTaskStart(mTaskItem.getUrl());

        initM3U8Ts();
        begin();
    }

    private void begin() {
        if (mTaskItem.isCompleted()) {
            Log.i(TAG, "M3U8VideoDownloadTask local file.");
            notifyDownloadFinish();
            return;
        }
        if (isRunning.get())
            return;
        netSpeedTimer = new Timer();
        netSpeedTimer.schedule(new TimerTask() {
            @Override
            public void run() {
                notifyProgress();
            }
        }, 0, 1000);

        if (mDownloadExecutor != null) {
            mDownloadExecutor.shutdownNow();
        }
        mDownloadExecutor = null;
        mDownloadExecutor = new ThreadPoolExecutor(THREAD_COUNT, THREAD_COUNT, 0L,
                TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>(), Executors.defaultThreadFactory(),
                new ThreadPoolExecutor.DiscardOldestPolicy());
        //任务过多后，存储任务的一个阻塞队列
//        mDownloadExecutor = Executors.newFixedThreadPool(THREAD_COUNT);
        isRunning.set(true);
        new Thread() {
            @Override
            public void run() {
                float length = 0;
                String[] failArr=null;

                //检查是否有下载失败的ts，有就重新下载
                Map<String, String> myHeader = VideoDownloadUtils.getTaskHeader(mTaskItem);
                if (myHeader != null && myHeader.containsKey(VideoDownloadConstants.HEADER_FAIL_TS)) {
                    String s= myHeader.get(VideoDownloadConstants.HEADER_FAIL_TS);
                    if (s != null) {
                        failArr =s.split(",");
                    }
                }
                for (int index = 0; index < mTotalTs; index++) {
                    final M3U8Seg ts = mTsList.get(index);

                    boolean isFail = false;
                    if (failArr != null) {
                        for (String s : failArr) {
                            int position = Integer.parseInt(s);
                            if (index == position) {
                                isFail = true;
                                break;

                            }
                        }
                    }
                    if (isFail) {
                        Log.e("asdfg",mTaskItem.mName+" fail ts:"+index);
                        continue;
                    }




                    File tempTsFile = new File(mSaveDir, ts.getIndexName());
                    if (tempTsFile.exists()) {
                        if (tempTsFile.length() > 0) {
                            ts.setTsSize(tempTsFile.length());
                            ts.setContentLength(tempTsFile.length());
                            ts.success = true;
                            mCurrentDownloaddSize.getAndAdd(ts.getTsSize());

                        } else {
                            VideoStorageUtils.deleteFile2(tempTsFile);
                        }

                    }
                    length += ts.getDuration();
                }
                mTaskItem.videoLength = (long) length;
                Log.e(TAG, "已下载的大小:" + mCurrentDownloaddSize.get());


                File coverFile = new File(mSaveDir, mTaskItem.mName+ VideoDownloadConstants.COVER_SUFFIX);
                if (VideoDownloadManager.getInstance().mConfig.saveCover && (!coverFile.exists() || coverFile.length() == 0)) {
                    //下载封面
                    DownloadExecutor.execute(() -> {
                        try {
                            downloadCover(coverFile, mTaskItem.mCoverUrl);
                        } catch (Exception e) {
                            Log.e("","",e);
                        }
                    });
                }


                for (int index = 0; index < mTotalTs; index++) {
                    final M3U8Seg ts = mTsList.get(index);
                    if(ts.success){
                        File tempTsFile = new File(mSaveDir, ts.getIndexName());
                        if (tempTsFile.exists()&&tempTsFile.length() > 0) {
                            mCurTs.incrementAndGet();
                            continue;
                        }
                    }


                    mDownloadExecutor.execute(() -> {
                        if (ts.hasInitSegment()) {
                            String tsInitSegmentName = ts.getInitSegmentName();
                            File tsInitSegmentFile = new File(mSaveDir, tsInitSegmentName);
                            if (!tsInitSegmentFile.exists() || tsInitSegmentFile.length() == 0) {
                                Log.e(TAG, "===================出大事了===============");
                                Log.e(TAG, "===================出大事了===============");
                                Log.e(TAG, "===================出大事了===============");
                                try {
                                    downloadFile(ts, tsInitSegmentFile, ts.getInitSegmentUri());
                                } catch (Exception e) {
                                    Log.e(TAG, "出错了", e);
                                }

                            }
                        }
                        File tsFile = new File(mSaveDir, ts.getIndexName());
                        if (!tsFile.exists() || tsFile.length() == 0) {
                            // ts is network resource, download ts file then rename it to local file.
                            try {
                                downloadFile(ts, tsFile, ts.getUrl());
                            } catch (Exception e) {
                                Log.e(TAG, "出错了", e);
                            }
                        }

                        //下载失败的比例超过30%则不再下载，直接提示下载失败
                        if (mErrorTsCont.get() * 100 / mTotalTs > 25) {
                            StringBuilder err = new StringBuilder();

                            Set<String> keySet = errMsgMap.keySet();
                            int i = 0;
                            for (String key : keySet) {
                                i++;
                                err.append("errNum ").append(i).append(":").append(key).append("  ");
                            }
                            Log.e(TAG, "错误的ts超过30%: " + err);
                            if (isRunning.get()) {
                                notifyDownloadError(new VideoDownloadException("m3u8:" + err));
                            }

                        }
                    });


                }
                if (mDownloadExecutor != null) {
                    mDownloadExecutor.shutdown();//下载完成之后要关闭线程池
                }
                while (mDownloadExecutor != null && !mDownloadExecutor.isTerminated()) {

                    try {
                        //等待中
                        Thread.sleep(1500);
                    } catch (Exception e) {
                        Log.e(TAG, "发生异常: ", e);
                    }

                    try {
                        ThreadPoolExecutor tpe =  mDownloadExecutor;
                        int queueSize = tpe.getQueue().size();
                        int activeCount = tpe.getActiveCount();
                        long completedTaskCount = tpe.getCompletedTaskCount();
                        long taskCount = tpe.getTaskCount();
                        Log.e(TAG, mTaskItem.mName+" 当前排队线程数：" + queueSize + " 当前活动线程数：" + activeCount + " 执行完成线程数：" + completedTaskCount + " 总线程数：" + taskCount);
                    } catch (Exception e) {
                        Log.e(TAG, "发生异常: ", e);
                    }

                }

                //检查失败的数量并重新下载一次
                if(isRunning.get()){
                    Log.e(TAG, "下载失败的ts数量: " + mErrorTsCont.get());
                    if (mErrorTsCont.get() * 100 / mTotalTs > 25) {
                        return;
                    }
                    mErrorTsCont.set(0);
                    if (executorService != null) {
                        executorService.shutdownNow();
                    }
                    executorService = null;
                    //限制并发量
                    executorService = Executors.newFixedThreadPool(3);
                    for (int index = 0; index < mTotalTs; index++) {
                        final M3U8Seg ts = mTsList.get(index);
                        if(ts.success){
                            File tempTsFile = new File(mSaveDir, ts.getIndexName());
                            if (tempTsFile.exists() && ts.getContentLength() > 0 && tempTsFile.length() > 0 && sizeSimilar(tempTsFile.length(), ts.getContentLength())) {
                                continue;
                            }
                        }
                        Log.e("asdf","====="+ts.getIndexName()+"  err retry");
                        mErrorTsCont.incrementAndGet();

                        //设置重试次数为最大值，这样下载失败不再重试
                        ts.setRetryCount(VideoDownloadManager.getInstance().mConfig.retryCount*10);

                        executorService.execute(() -> {
                            if (ts.hasInitSegment()) {
                                String tsInitSegmentName = ts.getInitSegmentName();
                                File tsInitSegmentFile = new File(mSaveDir, tsInitSegmentName);
                                if (!tsInitSegmentFile.exists() || tsInitSegmentFile.length() == 0) {
                                    try {
                                        downloadFile(ts, tsInitSegmentFile, ts.getInitSegmentUri());
                                    } catch (Exception e) {
                                        Log.e(TAG, "出错了", e);
                                    }

                                }
                            }
                            File tsFile = new File(mSaveDir, ts.getIndexName());
                            // ts is network resource, download ts file then rename it to local file.
                            try {
                                downloadFile(ts, tsFile, ts.getUrl());
                            } catch (Exception e) {
                                Log.e(TAG, "出错了", e);
                            }
                        });
                    }
                    if (executorService != null) {
                        executorService.shutdown();//下载完成之后要关闭线程池
                    }
                    while (executorService != null && !executorService.isTerminated()) {

                        try {
                            //等待中
                            Thread.sleep(1500);
                        } catch (Exception e) {
                            Log.e(TAG, "发生异常: ", e);
                        }

                        try {
                            ThreadPoolExecutor tpe = ((ThreadPoolExecutor) executorService);
                            int queueSize = tpe.getQueue().size();
                            int activeCount = tpe.getActiveCount();
                            long completedTaskCount = tpe.getCompletedTaskCount();
                            long taskCount = tpe.getTaskCount();
                            Log.e(TAG, mTaskItem.mName+" 待修复当前排队线程数：" + queueSize + " 待修复当前活动线程数：" + activeCount + " 待修复执行完成线程数：" + completedTaskCount + " 待修复总线程数：" + taskCount);
                        } catch (Exception e) {
                            Log.e(TAG, "发生异常: ", e);
                        }

                    }
                }
                StringBuilder builder=new StringBuilder();
                for (int index = 0; index < mTotalTs; index++) {
                    final M3U8Seg ts = mTsList.get(index);
                    if(ts.failed){
                        builder.append(index);
                        builder.append(",");
                    }
                }
                String failTs=builder.toString();
                if(!TextUtils.isEmpty(failTs)){
                    Map<String, String> header = VideoDownloadUtils.getTaskHeader(mTaskItem);
                    if (header == null) {
                        header = new HashMap<>();
                    }
                    header.put(VideoDownloadConstants.HEADER_FAIL_TS, failTs);
                    mTaskItem.header = VideoDownloadUtils.mapToJsonString(header);
                    Log.e("asdfg",mTaskItem.mName+" fail ts:"+failTs);
                }


                Log.e(TAG, "开始创建本地文件==============");
                synchronized (mCreateFileLock) {
                    if (isRunning.get()) {
                        isRunning.set(false);
                        try {
                            createLocalM3U8File();
                            createContentLengthFile();
                        } catch (Exception e) {
                            Log.e(TAG, "创建本地文件失败");
                            notifyDownloadError(new VideoDownloadException("m3u8:创建本地文件失败"));
                            return;
                        }
                        try {
                            createContentLengthFile();
                        } catch (Exception e) {
                            Log.e(TAG, "",e);
                        }
                        stopTimer();

                        mTotalSize = mCurrentDownloaddSize.get();
                        Log.i(TAG, "下载完成:" + mTotalSize);
                        if (VideoDownloadManager.getInstance().mConfig.mergeM3u8) {
                            mDownloadTaskListener.onTaskM3U8Merge();
                            mTaskItem.mM3u8FilePath = mTaskItem.getSaveDir() + File.separator + fileName;
                            boolean ffmpeg = true;
                            if (ffmpeg) {
                                doMergeByFFmpeg();
                            } else {
                                doMerge();

                            }

                        } else {
                            mDownloadTaskListener.onTaskProgressForM3U8(100.0f, mTotalSize, mCurTs.get(), mTotalTs, mSpeed);
                            notifyDownloadFinish();


                            mCurrentCachedSize = VideoStorageUtils.countTotalSize(mSaveDir);
                            Log.i(TAG, "文件目录大小:" + VideoDownloadUtils.getSizeStr(mCurrentCachedSize));

                        }


                    }
                }

            }
        }.start();
    }

    /**
     * 利用ffmpeg将m3u8合成mp4
     */
    private void doMergeByFFmpeg(){

        File localM3U8File = new File(mSaveDir, fileName);

        mTaskItem.suffix = ".mp4";
        fileName = VideoDownloadUtils.getFileNameWithSuffix(mTaskItem);
        File  mergeFile = new File(mSaveDir, fileName);
//        if (VideoDownloadManager.getInstance().mConfig.saveAsPublic) {
//            mergeFile = new File(VideoDownloadManager.getInstance().mConfig.publicPath + File.separator + fileName);
//        } else {
//            mergeFile = new File(mSaveDir, fileName);
//        }

        FFmpegUtils.covertM3u8ToMp4(localM3U8File.getAbsolutePath(), mergeFile.getAbsolutePath(), VideoDownloadUtils.getTaskHeader(mTaskItem),new IFFmpegCallback() {
            @Override
            public void onSuc() {
                if (!mTaskItem.privateFile) {
                    copyToAlbum();
                }
                mDownloadTaskListener.onTaskProgressForM3U8(100.0f, mTotalSize, mCurTs.get(), mTotalTs, mSpeed);
                notifyDownloadFinish();

            }

            @Override
            public void onFail() {
                notifyDownloadError(new Exception("m3u8:合并失败"));
//                doMerge();
//                if (VideoDownloadManager.getInstance().mConfig.saveAsPublic) {
//                    copyToAlbum();
//                }
//                mDownloadTaskListener.onTaskProgressForM3U8(100.0f, mTotalSize, mCurTs, mTotalTs, mSpeed);
//                notifyDownloadFinish();
            }
        });

    }
    /**
     * 将m3u8合成mp4
     * 直接将ts拼接成mp4
     */
    private void doMerge() {

        File localM3U8File = new File(mSaveDir, fileName);
        mTaskItem.suffix = ".mp4";
        fileName = VideoDownloadUtils.getFileNameWithSuffix(mTaskItem);
        File mergeFile = new File(mSaveDir, fileName);



        FFmpegUtils.doMerge(localM3U8File.getAbsolutePath(), mergeFile.getAbsolutePath(),VideoDownloadUtils.getTaskHeader(mTaskItem), new IFFmpegCallback() {
            @Override
            public void onSuc() {
                if (!mTaskItem.privateFile) {
                    copyToAlbum();
                }
                mDownloadTaskListener.onTaskProgressForM3U8(100.0f, mTotalSize, mCurTs.get(), mTotalTs, mSpeed);
                notifyDownloadFinish();
                mCurrentCachedSize = VideoStorageUtils.countTotalSize(mSaveDir);
                Log.i(TAG, "文件目录大小:" + VideoDownloadUtils.getSizeStr(mCurrentCachedSize));
            }

            @Override
            public void onFail() {
                notifyDownloadError(new Exception("m3u8:合并失败"));
            }
        });

    }

    /**
     * 将mp4拷贝到相册
     */
    public void copyToAlbum() {
        mTaskItem.suffix = ".mp4";
        fileName = VideoDownloadUtils.getFileNameWithSuffix(mTaskItem);
        File mergeFile = new File(mSaveDir, fileName);
        File toFile = new File(VideoDownloadManager.getInstance().mConfig.publicPath + File.separator + fileName);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            copyPrivateToDownload(VideoDownloadManager.getInstance().mConfig.context, mergeFile, fileName);
        } else {
            fileCopyWithFileChannel(mergeFile, toFile);
        }
        try {
            //删除旧文件
            VideoStorageUtils.delete(mergeFile);
        } catch (IOException e) {
            Log.e(TAG, "发生异常: ", e);
        }
        //适配android10公共目录下载后android10及以上可以不用刷新媒体库
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.Q) {
            try {
                if (toFile.exists()) {
                    MediaScannerConnection.scanFile(VideoDownloadManager.getInstance().mConfig.context, new String[]{toFile.getAbsolutePath()}
                            , null, null);
                }

            } catch (Exception e) {
                Log.e(TAG, "发生异常: ", e);
            }
        }
    }

    /**
     * 复制私有目录的文件到公有Download目录
     *
     * @param context  上下文
     * @param orgFile  私有目录的文件路径
     * @param filename 复制后文件要显示的文件名称带后缀（如xx.txt）
     *                 return 公有目录的uri，为空则代表复制失败
     */
    @RequiresApi(Build.VERSION_CODES.Q)
    public void copyPrivateToDownload(Context context, File orgFile, String filename) {
        String mimeType = "mp4";
        if (TextUtils.isEmpty(mimeType) && !TextUtils.isEmpty(mTaskItem.suffix)) {
            mimeType = mTaskItem.suffix.replace(".", "");
        }
        Uri insertUri = VideoDownloadUtils.getUri(DIRECTORY_DOWNLOADS, filename, mimeType);
        if (insertUri == null) {
            //创建失败，则重命名
            mSaveName = VideoDownloadUtils.computeMD5(mTaskItem.getUrl() + System.currentTimeMillis());
            mTaskItem.setFileHash(mSaveName);
            filename = VideoDownloadUtils.getFileNameWithSuffix(mTaskItem);
            insertUri = VideoDownloadUtils.getUri(DIRECTORY_DOWNLOADS, filename, mimeType);
        }
        InputStream ist = null;
        OutputStream ost = null;
        try {
            ist = Files.newInputStream(orgFile.toPath());
            if (insertUri != null) {

                try (  ParcelFileDescriptor pdf = context.getContentResolver().openFileDescriptor(insertUri, "rw")) {
                    // 使用 pfd 进行操作，无需手动关闭
                    if(pdf!=null){
                        ost = new FileOutputStream(pdf.getFileDescriptor());
                    }

                } catch (IOException e) {
                    // 处理异常
                }


            }
            if (ost != null) {
                byte[] buffer = new byte[1024 * 1024];
                int byteCount ;
                while ((byteCount = ist.read(buffer)) != -1) {  // 循环从输入流读取 buffer字节
                    ost.write(buffer, 0, byteCount);        // 将读取的输入流写入到输出流
                }
            }
        } catch (IOException e) {
            Log.e(TAG, "发生异常: ", e); 
        } finally {
            try {
                if (ist != null) {
                    ist.close();
                }
                if (ost != null) {
                    ost.close();
                }
            } catch (IOException e) {
                Log.e(TAG, "发生异常: ", e); 
            }
        }
    }

    /**
     * 用filechannel进行文件复制
     *
     * @param fromFile 源文件
     * @param toFile   目标文件
     */
    public static void fileCopyWithFileChannel(File fromFile, File toFile) {
        FileInputStream fileInputStream = null;
        FileOutputStream fileOutputStream = null;
        FileChannel fileChannelInput = null;
        FileChannel fileChannelOutput = null;
        try {
            fileInputStream = new FileInputStream(fromFile);
            fileOutputStream = new FileOutputStream(toFile);
            //得到fileInputStream的文件通道
            fileChannelInput = fileInputStream.getChannel();
            //得到fileOutputStream的文件通道
            fileChannelOutput = fileOutputStream.getChannel();
            //将fileChannelInput通道的数据，写入到fileChannelOutput通道
            fileChannelInput.transferTo(0, fileChannelInput.size(), fileChannelOutput);
        } catch (IOException e) {
            Log.e(TAG, "发生异常: ", e); 
        } finally {
            try {
                if (fileInputStream != null) {
                    fileInputStream.close();
                }
                if (fileChannelInput != null) {
                    fileChannelInput.close();
                }
                if (fileOutputStream != null) {
                    fileOutputStream.close();
                }
                if (fileChannelOutput != null) {
                    fileChannelOutput.close();
                }
            } catch (IOException e) {
                Log.e(TAG, "发生异常: ", e); 
            }
        }
    }


    @Override
    public void resumeDownload() {
        startDownload();
    }

    @Override
    public void pauseDownload() {
        Log.e(TAG, "=============pauseDownload");

        new Thread() {
            @Override
            public void run() {
                stopTimer();
                isRunning.set(false);
                notifyOnTaskPaused();
                if (mDownloadExecutor != null) {
                    Log.i(TAG, "mDownloadExecutor shutdownNow");
                    try {
                        mDownloadExecutor.shutdownNow();
                    } catch (Exception e) {
                        Log.e(TAG, "发生异常: ", e); 
                    }

                }
                if (executorService != null) {
                    Log.i(TAG, "mDownloadExecutor shutdownNow");
                    try {
                        executorService.shutdownNow();
                    } catch (Exception e) {
                        Log.e(TAG, "发生异常: ", e);
                    }

                }

            }
        }.start();

    }

    private void stopTimer() {
        if (netSpeedTimer != null) {
            netSpeedTimer.cancel();
            netSpeedTimer = null;
        }
    }

    @Override
    public void cancle() {
        new Thread() {
            @Override
            public void run() {
                Log.i(TAG, "cancle");
                isRunning.set(false);
                stopTimer();
                try {
                    if (mDownloadExecutor != null) {
                        mDownloadExecutor.shutdownNow();
                    }
                } catch (Exception e) {
                    Log.e(TAG, "发生异常: ", e); 
                }
                if (executorService != null) {
                    Log.i(TAG, "mDownloadExecutor shutdownNow");
                    try {
                        executorService.shutdownNow();
                    } catch (Exception e) {
                        Log.e(TAG, "发生异常: ", e);
                    }

                }

            }
        }.start();

    }

    @Override
    public void delete() {

    }

    @Override
    public void initSaveDir() {

        if (TextUtils.isEmpty(mTaskItem.getSaveDir())) {
            mSaveName = VideoDownloadUtils.getFileName(mTaskItem, null, false);
            mSaveDir = new File(VideoDownloadManager.getInstance().mConfig.privatePath, mSaveName);

            if (mSaveDir.exists()) {
                if (!mTaskItem.overwrite) {
                    mSaveName = VideoDownloadUtils.getFileName(mTaskItem, System.currentTimeMillis() + "", false);
                    mSaveDir = new File(VideoDownloadManager.getInstance().mConfig.privatePath, mSaveName);
                }
            }
            if (!mSaveDir.exists()) {
                mSaveDir.mkdir();
            }
        } else {
            mSaveDir = new File(mTaskItem.getSaveDir());
            mSaveName = mTaskItem.getFileHash();
        }

        mTaskItem.setFileHash(mSaveName);
        mTaskItem.setSaveDir(mSaveDir.getAbsolutePath());
    }

    private void notifyProgress() {
        //未获得下载大小前不更新进度
        if (mCurrentDownloaddSize.get() == 0) {
            return;
        }
        if (mTaskItem.isCompleted()) {
            mCurTs.set(mTotalTs);
            mDownloadTaskListener.onTaskProgressForM3U8(100.0f, mCurrentDownloaddSize.get(), mCurTs.get(), mTotalTs, mSpeed);
            mPercent = 100.0f;
            mTotalSize = mCurrentDownloaddSize.get();
            notifyDownloadFinish();
            return;
        }
        if (mCurTs.get() >= mTotalTs) {
            mCurTs.set(mTotalTs);
        }
        float percent = mCurTs.get() * 1.0f * 100 / mTotalTs;
        if (!VideoDownloadUtils.isFloatEqual(percent, mPercent) && mCurrentDownloaddSize.get() > mLastCachedSize) {
            long nowTime = System.currentTimeMillis();
            mSpeed = (mCurrentDownloaddSize.get() - mLastCachedSize)   / ((nowTime - mLastInvokeTime)/1000f);
            mDownloadTaskListener.onTaskProgressForM3U8(percent, mCurrentDownloaddSize.get(), mCurTs.get(), mTotalTs, mSpeed);
            mPercent = percent;

            mLastCachedSize = mCurrentDownloaddSize.get();
            mLastInvokeTime = nowTime;
            Log.i(TAG, mTaskItem.mName+" m3u8  cur:" + mCurTs + " error count:" + mErrorTsCont + " mTotalTs:" + mTotalTs);

        }
    }


    private void notifyDownloadFinish() {
        stopTimer();
        if (VideoDownloadManager.getInstance().mConfig.mergeM3u8) {
            if (mTaskItem.privateFile) {
                mTaskItem.setFilePath(mTaskItem.getSaveDir() + File.separator + fileName);
            } else {
                mTaskItem.setFilePath(VideoDownloadManager.getInstance().mConfig.publicPath + File.separator + fileName);
            }
        } else {
            mTaskItem.setFilePath(mTaskItem.getSaveDir() + File.separator + fileName);
        }
        mDownloadTaskListener.onTaskFinished(mTotalSize);
    }

    private void notifyDownloadError(Exception e) {
        Log.e(TAG, "notifyDownloadError:" + e.getMessage());
        stopTimer();
        cancle();
        notifyOnTaskFailed(e);
    }

    public void downloadFile(M3U8Seg ts, File file, String videoUrl) {
        Log.e("asdf","开始下载:"+ file.getName());
        if (VideoDownloadManager.getInstance().mDownloadReplace != null) {
            for (Map.Entry<String, String> entry : (Iterable<Map.Entry<String, String>>) VideoDownloadManager.getInstance().mDownloadReplace.entrySet()) {
                if (videoUrl.contains(entry.getKey())) {
                    videoUrl = videoUrl.replaceAll(entry.getKey(), entry.getValue());
                    break;
                }
            }
        }
        InputStream inputStream = null;

        ReadableByteChannel rbc = null;
        FileOutputStream fos = null;
        FileChannel foutc = null;
        Response response = null;
        int responseCode = -1;
        try {
            String method = OkHttpUtil.METHOD.GET;
            if (OkHttpUtil.METHOD.POST.equalsIgnoreCase(mTaskItem.method)) {
                method = OkHttpUtil.METHOD.POST;
            }
            response = OkHttpUtil.getInstance().requestSync(videoUrl,method, VideoDownloadUtils.getTaskHeader(mTaskItem));

            if (response != null) {
                responseCode = response.code();
            }
            if (response!=null&& response.isSuccessful()) {
                ts.setRetryCount(0);
                inputStream = response.body().byteStream();
                long contentLength = response.body().contentLength();
                long fileLength = 0;

                if (contentLength <= 0) {
                    String strLen = response.header("Content-Length");
                    try {
                        contentLength = Long.parseLong(strLen);
                    } catch (Exception e) {
                        Log.e(TAG, "", e);
                    }
                }
                ts.origonContentLength = contentLength;
                if (contentLength <= 0 && ts.getRetryCount() == 0) {
                    onDownloadFileErr(ts, file, videoUrl, responseCode, new Exception("content length:" + contentLength));
                    return;
                }
                byte[] encryptionKey = ts.encryptionKey == null ? mM3U8.encryptionKey : ts.encryptionKey;
                String iv = ts.encryptionKey == null ? mM3U8.encryptionIV : ts.getKeyIV();
                if (VideoDownloadManager.getInstance().mConfig.decryptM3u8 && encryptionKey != null) {
                    String tsInitSegmentName = ts.getInitSegmentName() + ".temp";
                    File tsInitSegmentFile = new File(mSaveDir, tsInitSegmentName);

                    rbc = Channels.newChannel(inputStream);
                    fos = new FileOutputStream(tsInitSegmentFile);
                    foutc = fos.getChannel();
                    foutc.transferFrom(rbc, 0, Long.MAX_VALUE);

                    if (contentLength <= 0) {
                        contentLength = tsInitSegmentFile.length();
                    } else if (!sizeSimilar(contentLength, tsInitSegmentFile.length())) {
                        String log=file.getName() + " file length:" + file.length() + " content length:" + contentLength;
                        Log.e(TAG, log);
                        onDownloadFileErr(ts, file, videoUrl, responseCode, new Exception(log));
                        return;

                    }

                    FileOutputStream fileOutputStream = null;
                    try {
                        byte[] result = AES128Utils.dencryption(AES128Utils.readFile(tsInitSegmentFile), encryptionKey, iv);
                        if (result == null) {
                            // aes解密失败,这里的失败不用重试，重试也是失败
                            ts.failed = true;
                            ts.setRetryCount(ts.getRetryCount()+1);
                            mErrorTsCont.incrementAndGet();
                            String err = "aes dencryption  fail";
                            if (errMsgMap.size() < MAX_ERR_MAP_COUNT) {
                                errMsgMap.put(err, err);
                            }
                        } else {
                            fileOutputStream = new FileOutputStream(file);
                            fileOutputStream.write(result);
                            //解密后文件的大小和content-length不一致，所以直接赋值为文件大小
                            fileLength = contentLength = file.length();
                        }
                    } catch (Exception e) {
                        Log.e(TAG, "发生异常: ", e); 
                        ts.setRetryCount(ts.getRetryCount() + 1);
                        if (ts.getRetryCount() <= VideoDownloadManager.getInstance().mConfig.retryCount) {
                            Log.e(TAG, "retry, exception=" + e.getMessage());
                            downloadFile(ts, file, videoUrl);
                        } else {
                            ts.failed = true;
                            mErrorTsCont.incrementAndGet();
                            if (errMsgMap.size() < MAX_ERR_MAP_COUNT) {
                                errMsgMap.put(e.getMessage(), e.getMessage());
                            }
                        }
                        return;
                    } finally {
                        if (fileOutputStream != null) {
                            fileOutputStream.close();
                            VideoStorageUtils.delete(tsInitSegmentFile);
                        }
                    }
                } else {
                    rbc = Channels.newChannel(inputStream);
                    fos = new FileOutputStream(file);
                    foutc = fos.getChannel();
                    foutc.transferFrom(rbc, 0, Long.MAX_VALUE);
                    fileLength = file.length();
                    if (contentLength <= 0) {
                        contentLength = file.length();
                    } else if (!sizeSimilar(contentLength, fileLength)) {
                        String log = file.getName() + " file length:" + file.length() + " content length:" + contentLength;
                        Log.e(TAG, log);
                        onDownloadFileErr(ts, file, videoUrl, responseCode, new Exception(log));
                        return;

                    }
                }


                if (contentLength <= 0) {
                    onDownloadFileErr(ts, file, videoUrl, responseCode, new Exception("file length = 0 or code=" + responseCode));
                } else {
                    ts.setContentLength(contentLength);
                    ts.setTsSize(fileLength);
//                    Log.e("asdf","content length:"+contentLength+"  str:"+VideoDownloadUtils.getSizeStr((contentLength)));
                    mCurrentDownloaddSize.getAndAdd(contentLength);
                    mCurTs.incrementAndGet();
                    ts.success = true;
                }

            } else {
                onDownloadFileErr(ts, file, videoUrl, responseCode, new Exception("response is null or code=" + responseCode));
            }

            if (VideoDownloadManager.getInstance().mConfig.threadSchedule) {
                //线程调度
                long nowTime = System.currentTimeMillis();
                maxSpeed = Math.max(mSpeed, maxSpeed);
                minSpeed = Math.min(mSpeed, minSpeed == 0 ? mSpeed : minSpeed);
//                Log.e("==asdf", "speed:" + VideoDownloadUtils.getSizeStr((long) this.mSpeed) + "/s" + " time:" + ((nowTime - mLastInvokeTime) / 1000f) + " size:" + ((mCurrentDownloaddSize.get() - mLastCachedSize) / 1024f));
                float midPoint = (maxSpeed + minSpeed) / 2;
                if (mSpeed >= midPoint) {
                    if (mDownloadExecutor != null) {
                        mDownloadExecutor.setCorePoolSize(THREAD_COUNT);
                        mDownloadExecutor.setMaximumPoolSize(THREAD_COUNT);
//                        Log.e(TAG, "=======大于平均速度 speed:" + VideoDownloadUtils.getSizeStr((long) mSpeed) + " mid:" + VideoDownloadUtils.getSizeStr((long) midPoint));
                    }

                } else if (mSpeed < midPoint) {
                    if (mDownloadExecutor != null) {
                        mDownloadExecutor.setCorePoolSize(8);
                        mDownloadExecutor.setMaximumPoolSize(8);
//                        Log.e(TAG, "========小于平均速度 speed:" + VideoDownloadUtils.getSizeStr((long) mSpeed) + " mid:" + VideoDownloadUtils.getSizeStr((long) midPoint));
                    }
                }
            }
        } catch (InterruptedIOException e) {
            //被中断了，使用stop时会抛出这个，不需要处理
            Log.e(TAG, "InterruptedIOException");
        } catch (Exception e) {
            onDownloadFileErr(ts,file,videoUrl,responseCode,e);
        } finally {
            VideoDownloadUtils.close(inputStream);
            VideoDownloadUtils.close(fos);
            if (response != null) {
                VideoDownloadUtils.close(response.body());
            }
            if (rbc != null) {
                try {
                    rbc.close();
                } catch (IOException e) {
                    Log.e(TAG, "发生异常: ", e); 

                }
            }
            if (foutc != null) {
                try {
                    foutc.close();
                } catch (IOException e) {
                    Log.e(TAG, "发生异常: ", e); 
                }
            }
        }

    }

    private void onDownloadFileErr(M3U8Seg ts, File file, String videoUrl,int responseCode,Exception exception){
        ts.setRetryCount(ts.getRetryCount() + 1);
        if (responseCode == HttpUtils.RESPONSE_503 || responseCode == HttpUtils.RESPONSE_429) {
            if (ts.getRetryCount() <= MAX_RETRY_COUNT_503) {
                //遇到503，延迟[4,24]秒后再重试，区间间隔不能太小
                int ran = 4000 + (int) (Math.random() * 20000);
                try {
                    Thread.sleep(ran);
                } catch (InterruptedException e) {
                    throw new RuntimeException(e);
                }
                downloadFile(ts, file, videoUrl);
            }
        } else if (ts.getRetryCount() <= VideoDownloadManager.getInstance().mConfig.retryCount) {
            Log.e(TAG, "retry1   responseCode=" + responseCode + " msg:"+ exception.getMessage()+ "  ts:" + ts.getUrl()+" "+file.getName()+" count:"+ts.getRetryCount());

            downloadFile(ts, file, videoUrl);
        } else {
            Log.e(TAG, "error   responseCode=" + responseCode+ " msg:"+ exception.getMessage() + "  ts:" + ts.getUrl()+" "+file.getName()+" count:"+ts.getRetryCount());
            ts.failed = true;
            mErrorTsCont.incrementAndGet();
            String err= exception.getMessage();
            if (errMsgMap.size() < MAX_ERR_MAP_COUNT) {
                errMsgMap.put(err,err);
            }
        }
    }


    /**
     * 判断两个文件大小是否近似
     * 允许有5kb误差
     */
    private boolean sizeSimilar(long size1,long size2){

        long difference = Math.abs(size1 - size2);

        if (difference <= SIZE_THRESHOLD) {
//            System.out.println("两个文件大小相差在10KB以内");

            return true;
        } else {
//            System.out.println("两个文件大小相差超过10KB");
//            Log.e(TAG,"两个文件大小相差:"+difference);
            return false;
        }
    }
    /**
     * 创建本地m3u8文件，可用于离线播放
     */
    private void createLocalM3U8File() throws IOException {
        synchronized (mFileLock) {
            File tempM3U8File = new File(mSaveDir, "temp.m3u8");
            if (tempM3U8File.exists()) {
                VideoStorageUtils.deleteFile2(tempM3U8File);
            }
            Log.i(TAG, "createLocalM3U8File");

            BufferedWriter bfw = new BufferedWriter(new FileWriter(tempM3U8File, false));
            bfw.write(M3U8Constants.PLAYLIST_HEADER + "\n");
            bfw.write(M3U8Constants.TAG_VERSION + ":" + mM3U8.getVersion() + "\n");
            bfw.write(M3U8Constants.TAG_MEDIA_SEQUENCE + ":" + mM3U8.getInitSequence() + "\n");

            bfw.write(M3U8Constants.TAG_TARGET_DURATION + ":" + mM3U8.getTargetDuration() + "\n");

            for (M3U8Seg m3u8Ts : mTsList) {
                if (m3u8Ts.failed || m3u8Ts.getTsSize() == 0) {
                    continue;
                }
                if (m3u8Ts.hasInitSegment()) {
                    String initSegmentInfo;
                    String initSegmentFilePath = mSaveDir.getAbsolutePath() + File.separator + m3u8Ts.getInitSegmentName();
                    if (m3u8Ts.getSegmentByteRange() != null) {
                        initSegmentInfo = "URI=\"" + initSegmentFilePath + "\"" + ",BYTERANGE=\"" + m3u8Ts.getSegmentByteRange() + "\"";
                    } else {
                        initSegmentInfo = "URI=\"" + initSegmentFilePath + "\"";
                    }
                    bfw.write(M3U8Constants.TAG_INIT_SEGMENT + ":" + initSegmentInfo + "\n");
                }
                if (m3u8Ts.hasKey() && !VideoDownloadManager.getInstance().mConfig.decryptM3u8) {
                    if (m3u8Ts.getMethod() != null) {
                        String key = "METHOD=" + m3u8Ts.getMethod();
                        if (m3u8Ts.getKeyUri() != null) {
                            File keyFile = new File(mSaveDir, m3u8Ts.getLocalKeyUri());
                            if (!m3u8Ts.isMessyKey() && keyFile.exists() && keyFile.length() > 0) {
                                key += ",URI=\"" + keyFile.getAbsolutePath() + "\"";
                            } else {
                                key += ",URI=\"" + m3u8Ts.getKeyUri() + "\"";
                            }
                        }
                        if (m3u8Ts.getKeyIV() != null) {
                            key += ",IV=" + m3u8Ts.getKeyIV();
                        }
                        bfw.write(M3U8Constants.TAG_KEY + ":" + key + "\n");
                    }
                }
                if (m3u8Ts.hasDiscontinuity()) {
                    bfw.write(M3U8Constants.TAG_DISCONTINUITY + "\n");
                }
                bfw.write(M3U8Constants.TAG_MEDIA_DURATION + ":" + m3u8Ts.getDuration() + ",\n");
                bfw.write(mSaveDir.getAbsolutePath() + File.separator + m3u8Ts.getIndexName());
                bfw.newLine();
            }
            bfw.write(M3U8Constants.TAG_ENDLIST);
            bfw.flush();
            bfw.close();

//            File localM3U8File = new File(mSaveDir, mSaveName + "_" + VideoDownloadUtils.LOCAL_M3U8);
            File localM3U8File = new File(mSaveDir, fileName);
            if (localM3U8File.exists()) {
                VideoStorageUtils.deleteFile2(localM3U8File);

            }
            tempM3U8File.renameTo(localM3U8File);
        }
    }

    /**
     * 保存ts的contentLength
     */
    private void createContentLengthFile() {
        synchronized (mFileLock) {
            BufferedWriter bfw = null;
            try {
                File tempM3U8File = new File(mSaveDir, "length.m3u8");
                if (tempM3U8File.exists()) {
                    VideoStorageUtils.deleteFile2(tempM3U8File);
                }
                Log.i(TAG, "createContentLengthFile");

                bfw = new BufferedWriter(new FileWriter(tempM3U8File, false));
//                bfw.write(M3U8Constants.PLAYLIST_HEADER + "\n");
//                bfw.write(M3U8Constants.TAG_VERSION + ":" + mM3U8.getVersion() + "\n");
//                bfw.write(M3U8Constants.TAG_MEDIA_SEQUENCE + ":" + mM3U8.getInitSequence() + "\n");
//                bfw.write(M3U8Constants.TAG_TARGET_DURATION + ":" + mM3U8.getTargetDuration() + "\n");

                for (M3U8Seg m3u8Ts : mTsList) {
                    bfw.write(M3U8Constants.TAG_MEDIA_DURATION + ":" + m3u8Ts.getContentLength() + "|" + m3u8Ts.getTsSize()
                            + "|" + m3u8Ts.origonContentLength + "|" + m3u8Ts.getRetryCount() + ",\n");
                    bfw.write(mSaveDir.getAbsolutePath() + File.separator + m3u8Ts.getIndexName());
                    bfw.newLine();
                }
                bfw.write(M3U8Constants.TAG_ENDLIST);
                bfw.flush();
                bfw.close();

            } catch (Exception e) {
                Log.e("", "", e);
            } finally {
                if (bfw != null) {
                    try {
                        bfw.close();
                    } catch (IOException e) {
                        Log.e("", "", e);
                    }
                }
            }

        }
    }


}

