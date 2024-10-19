package com.baofu.downloader.task;

import static android.os.Environment.DIRECTORY_DOWNLOADS;
import static com.baofu.downloader.common.DownloadConstants.MAX_RETRY_COUNT_503;

import android.content.Context;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Build;
import android.os.ParcelFileDescriptor;
import android.text.TextUtils;
import android.util.Log;

import androidx.annotation.RequiresApi;

import com.baofu.downloader.VideoDownloadException;
import com.baofu.downloader.rules.VideoDownloadManager;
import com.baofu.downloader.listener.IFFmpegCallback;
import com.baofu.downloader.m3u8.M3U8;
import com.baofu.downloader.m3u8.M3U8Constants;
import com.baofu.downloader.m3u8.M3U8Seg;
import com.baofu.downloader.model.VideoTaskItem;
import com.baofu.downloader.utils.AES128Utils;
import com.baofu.downloader.utils.DownloadExceptionUtils;
import com.baofu.downloader.utils.FFmpegUtils;
import com.baofu.downloader.utils.HttpUtils;
import com.baofu.downloader.utils.OkHttpUtil;
import com.baofu.downloader.utils.VideoDownloadUtils;
import com.baofu.downloader.utils.VideoStorageUtils;

import java.io.BufferedOutputStream;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.net.ProtocolException;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.channels.ReadableByteChannel;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import okhttp3.Response;

public class M3U8VideoDownloadTask extends VideoDownloadTask {

    private static final String TAG = "M3U8VideoDownloadTask";
    private final Object mFileLock = new Object();
    private final Object mCreateFileLock = new Object();

    private final M3U8 mM3U8;
    private List<M3U8Seg> mTsList;
    private volatile int mCurTs = 0;
    private int mTotalTs;
    private long mTotalSize;
    private int mErrorTsCont;//ts下载失败的个数
    private Timer netSpeedTimer;//定时任务
    private final AtomicLong mCurrentDownloaddSize = new AtomicLong(0);//当前的下载大小
    AtomicBoolean isRunning = new AtomicBoolean(false);//任务是否正在运行中
    String fileName;

    public M3U8VideoDownloadTask(VideoTaskItem taskItem, M3U8 m3u8) {
        super(taskItem);
        mM3U8 = m3u8;
        mTsList = m3u8.getTsList();
        mTotalTs = mTsList.size();
        mPercent = taskItem.getPercent();
        mTaskItem.header.put("Connection", "close");
        mTaskItem.setTotalTs(mTotalTs);
        mTaskItem.setCurTs(mCurTs);

        if (mTaskItem.estimateSize > 0) {
            //暂时把预估大小设置为文件的总大小，等下载完成后再更新准确的总大小
            mTaskItem.setTotalSize(taskItem.estimateSize);
        }
    }

    private void initM3U8Ts() {
        if (mCurTs == mTotalTs) {
            mTaskItem.setIsCompleted(true);
        }
        mTaskItem.suffix = ".m3u8";
        mCurrentDownloaddSize.set(0);
        mCurTs = 0;
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
//        mDownloadExecutor = new ThreadPoolExecutor(THREAD_COUNT, THREAD_COUNT, 0L,
//                TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>(), Executors.defaultThreadFactory(),
//                new ThreadPoolExecutor.DiscardOldestPolicy());
        //任务过多后，存储任务的一个阻塞队列
        Log.e(TAG, "COUNT:" + THREAD_COUNT);
        mDownloadExecutor = Executors.newFixedThreadPool(THREAD_COUNT);
        isRunning.set(true);
        new Thread() {
            @Override
            public void run() {
                float length = 0;
                for (int index = 0; index < mTotalTs; index++) {
                    final M3U8Seg ts = mTsList.get(index);
                    File tempTsFile = new File(mSaveDir, ts.getIndexName());
                    if (tempTsFile.exists()) {
                        if (tempTsFile.length() > 0) {
                            ts.setTsSize(tempTsFile.length());
                            ts.success = true;
                            mCurrentDownloaddSize.getAndAdd(ts.getTsSize());

                        } else {
                            tempTsFile.delete();
                        }

                    }
                    length += ts.getDuration();
                }
                mTaskItem.videoLength = (long) length;
                Log.e(TAG, "已下载的大小:" + mCurrentDownloaddSize.get());
                for (int index = 0; index < mTotalTs; index++) {
                    final M3U8Seg ts = mTsList.get(index);
                    if (ts.success || ts.failed) {
                        mCurTs++;
                        continue;
                    }
                    try {
                        mDownloadExecutor.execute(() -> {
                            if (ts.hasInitSegment()) {
                                String tsInitSegmentName = ts.getInitSegmentName();
                                File tsInitSegmentFile = new File(mSaveDir, tsInitSegmentName);
                                if (!tsInitSegmentFile.exists()) {
                                    Log.e(TAG, "===================出大事了===============");
                                    Log.e(TAG, "===================出大事了===============");
                                    Log.e(TAG, "===================出大事了===============");
                                    downloadFile(ts, tsInitSegmentFile, ts.getInitSegmentUri());
                                }
                            }
                            File tsFile = new File(mSaveDir, ts.getIndexName());
                            if (!tsFile.exists()) {
                                // ts is network resource, download ts file then rename it to local file.
                                downloadFile(ts, tsFile, ts.getUrl());
                            }

                            //        if (tsFile.exists() && (tsFile.length() == ts.getContentLength())) {
                            //            // rename network ts name to local file name.
                            //            ts.setName(ts.getIndexName());
                            //            ts.setTsSize(tsFile.length());
                            //            notifyDownloadProgress();
                            //        }
                            //下载失败的比例超过30%则不再下载，直接提示下载失败
                            if (mErrorTsCont * 100 / mTotalTs > 30) {
                                Log.e(TAG, "错误的ts超过30%");
                                notifyDownloadError(new VideoDownloadException(DownloadExceptionUtils.VIDEO_REQUEST_FAILED));
                            }
                        });
                    } catch (Exception e) {
                        e.printStackTrace();
                    }


                }
                if (mDownloadExecutor != null) {
                    mDownloadExecutor.shutdown();//下载完成之后要关闭线程池
                }
                while (mDownloadExecutor != null && !mDownloadExecutor.isTerminated()) {

                    try {
                        //等待中
                        Thread.sleep(1500);
                    } catch (Exception e) {
                        e.printStackTrace();
                    }

                    try {
                        ThreadPoolExecutor tpe = ((ThreadPoolExecutor) mDownloadExecutor);
                        int queueSize = tpe.getQueue().size();
                        int activeCount = tpe.getActiveCount();
                        long completedTaskCount = tpe.getCompletedTaskCount();
                        long taskCount = tpe.getTaskCount();
                        Log.i(TAG, "当前排队线程数：" + queueSize + " 当前活动线程数：" + activeCount + " 执行完成线程数：" + completedTaskCount + " 总线程数：" + taskCount);
                    } catch (Exception e) {
                        e.printStackTrace();
                    }


                }
                Log.e(TAG, "开始创建本地文件==============");
                synchronized (mCreateFileLock) {
                    if (isRunning.get()) {
                        isRunning.set(false);
                        try {
                            createLocalM3U8File();
                        } catch (Exception e) {
                            Log.e(TAG, "创建本地文件失败");
                            notifyDownloadError(e);
                            return;
                        }
                        stopTimer();

                        mTotalSize = mCurrentDownloaddSize.get();
                        Log.i(TAG, "下载完成:" + mTotalSize);
                        if (VideoDownloadManager.getInstance().mConfig.mergeM3u8) {
                            mDownloadTaskListener.onTaskM3U8Merge();
                            boolean ffmpeg = true;
                            if (ffmpeg) {
                                doMergeByFFmpeg();
                            } else {
                                doMerge();
                                if (VideoDownloadManager.getInstance().mConfig.saveAsPublic) {
                                    copyToAlbum();
                                }
                                mDownloadTaskListener.onTaskProgressForM3U8(100.0f, mTotalSize, mCurTs, mTotalTs, mSpeed);
                                notifyDownloadFinish();
                                mCurrentCachedSize = VideoStorageUtils.countTotalSize(mSaveDir);
                                Log.i(TAG, "文件目录大小:" + VideoDownloadUtils.getSizeStr(mCurrentCachedSize));
                            }

                        } else {
                            mDownloadTaskListener.onTaskProgressForM3U8(100.0f, mTotalSize, mCurTs, mTotalTs, mSpeed);
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

        FFmpegUtils.covertM3u8ToMp4(localM3U8File.getAbsolutePath(), mergeFile.getAbsolutePath(), new IFFmpegCallback() {
            @Override
            public void onSuc() {
                if (VideoDownloadManager.getInstance().mConfig.saveAsPublic) {
                    copyToAlbum();
                }
                mDownloadTaskListener.onTaskProgressForM3U8(100.0f, mTotalSize, mCurTs, mTotalTs, mSpeed);
                notifyDownloadFinish();

            }

            @Override
            public void onFail() {
                notifyDownloadError(new Exception("m3u8合并失败"));
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


        mTaskItem.suffix = ".mp4";
        fileName = VideoDownloadUtils.getFileNameWithSuffix(mTaskItem);
        byte[] mMp4Header = null;
        File mergeFile = new File(mSaveDir, fileName);
        FileOutputStream fileOutputStream = null;
        try {
            fileOutputStream = new FileOutputStream(mergeFile);
        } catch (FileNotFoundException e) {
            e.printStackTrace();
        }
        if (fileOutputStream == null) {
            notifyDownloadError(new Exception());
            return;
        }
        int i = 0;
        for (M3U8Seg ts : mTsList) {
            if (ts.failed) {
                Log.e(TAG, "ts fail");
                continue;
            }
            String tsInitSegmentName = ts.getInitSegmentName();
            File tsInitSegmentFile = new File(mSaveDir, tsInitSegmentName);
            File tsFile = new File(mSaveDir, ts.getIndexName());

            if (ts.hasInitSegment() && mMp4Header == null) {
                mMp4Header = AES128Utils.readFile(tsInitSegmentFile);
            }
//            byte[] encryptionKey = ts.encryptionKey == null ? mM3U8.encryptionKey : ts.encryptionKey;
//            String iv = ts.encryptionKey == null ? mM3U8.encryptionIV : ts.getKeyIV();
//            if (encryptionKey != null) {
//                String key=encryptionKey.toString();
//
//                try {
//                    byte[] result = AES128Utils.dencryption(AES128Utils.readFile(tsFile), encryptionKey, iv);
//                    if (result != null) {
//                        if (mMp4Header != null) {
//                            fileOutputStream.write(mMp4Header);
//                        }
//                        fileOutputStream.write(result);
//                    }
//                } catch (Exception e) {
//                    e.printStackTrace();
//                }
//            } else {
//                try {
//                    if (mMp4Header != null) {
//                        fileOutputStream.write(mMp4Header);
//                    }
//                    fileOutputStream.write(AES128Utils.readFile(tsFile));
//                    Log.i(TAG, "count:" + i++);
//                } catch (Exception e) {
//                    e.printStackTrace();
//                }
//
//            }

            //下载的时候已经解密了，合并的时候无需再解密
            try {
                if (mMp4Header != null) {
                    fileOutputStream.write(mMp4Header);
                }
                fileOutputStream.write(AES128Utils.readFile(tsFile));
                Log.i(TAG, "count:" + i++);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
        try {
            fileOutputStream.close();
        } catch (IOException e) {
            e.printStackTrace();
        }

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

        //适配android10公共目录下载后android10及以上可以不用刷新媒体库
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.Q) {
            try {
                if (toFile.exists()) {
                    MediaScannerConnection.scanFile(VideoDownloadManager.getInstance().mConfig.context, new String[]{toFile.getAbsolutePath()}
                            , null, null);
                }

            } catch (Exception e) {
                e.printStackTrace();
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

                ParcelFileDescriptor pdf = context.getContentResolver().openFileDescriptor(insertUri, "rw");
                ost = new FileOutputStream(pdf.getFileDescriptor());
            }
            if (ost != null) {
                byte[] buffer = new byte[1024 * 1024];
                int byteCount = 0;
                while ((byteCount = ist.read(buffer)) != -1) {  // 循环从输入流读取 buffer字节
                    ost.write(buffer, 0, byteCount);        // 将读取的输入流写入到输出流
                }
            }
        } catch (IOException e) {
            e.printStackTrace();
        } finally {
            try {
                if (ist != null) {
                    ist.close();
                }
                if (ost != null) {
                    ost.close();
                }
            } catch (IOException e) {
                e.printStackTrace();
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
            e.printStackTrace();
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
                e.printStackTrace();
            }
        }
    }


    @Override
    public void resumeDownload() {
        startDownload();
    }

    @Override
    public void pauseDownload() {
        Log.i(TAG, "pauseDownload");

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
                        e.printStackTrace();
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
                    e.printStackTrace();
                }

            }
        }.start();

    }

    @Override
    public void delete() {

    }

    @Override
    public void initSaveDir() {
        Log.e("asdf","initSaveDir");

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
            mCurTs = mTotalTs;
            mDownloadTaskListener.onTaskProgressForM3U8(100.0f, mCurrentDownloaddSize.get(), mCurTs, mTotalTs, mSpeed);
            mPercent = 100.0f;
            mTotalSize = mCurrentDownloaddSize.get();
            notifyDownloadFinish();
            return;
        }
        if (mCurTs >= mTotalTs) {
            mCurTs = mTotalTs;
        }
        float percent = mCurTs * 1.0f * 100 / mTotalTs;
        if (!VideoDownloadUtils.isFloatEqual(percent, mPercent) && mCurrentDownloaddSize.get() > mLastCachedSize) {
            long nowTime = System.currentTimeMillis();
            mSpeed = (mCurrentDownloaddSize.get() - mLastCachedSize) * 1000 * 1.0f / (nowTime - mLastInvokeTime);
            mDownloadTaskListener.onTaskProgressForM3U8(percent, mCurrentDownloaddSize.get(), mCurTs, mTotalTs, mSpeed);
            mPercent = percent;
            mLastCachedSize = mCurrentDownloaddSize.get();
            mLastInvokeTime = nowTime;
            Log.i(TAG, "m3u8  cur:" + mCurTs + " error count:" + mErrorTsCont + " mTotalTs:" + mTotalTs);
        }
    }


    private void notifyDownloadFinish() {
        stopTimer();
        if (VideoDownloadManager.getInstance().mConfig.mergeM3u8) {
            if (VideoDownloadManager.getInstance().mConfig.saveAsPublic) {
                mTaskItem.setFilePath(VideoDownloadManager.getInstance().mConfig.publicPath + File.separator + fileName);
            } else {
                mTaskItem.setFilePath(mTaskItem.getSaveDir() + File.separator + fileName);
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
        try {
            String method = OkHttpUtil.METHOD.GET;
            if (OkHttpUtil.METHOD.POST.equalsIgnoreCase(mTaskItem.method)) {
                method = OkHttpUtil.METHOD.POST;
            }
            response = OkHttpUtil.getInstance().requestSync(videoUrl,method, mTaskItem.header);
            if(response==null){
                ts.failed = true;
                mErrorTsCont++;
                return;
            }
            int responseCode = response.code();
            if (responseCode == HttpUtils.RESPONSE_200 || responseCode == HttpUtils.RESPONSE_206) {
                ts.setRetryCount(0);
                inputStream = response.body().byteStream();
                long contentLength = response.body().contentLength();

                byte[] encryptionKey = ts.encryptionKey == null ? mM3U8.encryptionKey : ts.encryptionKey;
                String iv = ts.encryptionKey == null ? mM3U8.encryptionIV : ts.getKeyIV();
                if (VideoDownloadManager.getInstance().mConfig.decryptM3u8 && encryptionKey != null) {
                    String tsInitSegmentName = ts.getInitSegmentName() + ".temp";
                    File tsInitSegmentFile = new File(mSaveDir, tsInitSegmentName);

                    rbc = Channels.newChannel(inputStream);
                    fos = new FileOutputStream(tsInitSegmentFile);
                    foutc = fos.getChannel();
                    foutc.transferFrom(rbc, 0, Long.MAX_VALUE);

                    FileOutputStream fileOutputStream = null;
                    try {
                        byte[] result = AES128Utils.dencryption(AES128Utils.readFile(tsInitSegmentFile), encryptionKey, iv);
                        if (result != null) {
                            fileOutputStream = new FileOutputStream(file);
                            fileOutputStream.write(result);
                        }
                    } catch (Exception e) {
                        e.printStackTrace();
                    } finally {
                        if (fileOutputStream != null) {
                            fileOutputStream.close();
                            tsInitSegmentFile.delete();
                        }
                    }
                } else {
                    rbc = Channels.newChannel(inputStream);
                    fos = new FileOutputStream(file);
                    foutc = fos.getChannel();
                    foutc.transferFrom(rbc, 0, Long.MAX_VALUE);
                }

                if (contentLength <= 0) {
                    contentLength = file.length();
                }
                ts.setContentLength(contentLength);
                mCurrentDownloaddSize.getAndAdd(contentLength);
                mCurTs++;
                ts.success = true;
            } else {
                ts.setRetryCount(ts.getRetryCount() + 1);
                if (responseCode == HttpUtils.RESPONSE_503 || responseCode == HttpUtils.RESPONSE_429) {
                    if (ts.getRetryCount() <= MAX_RETRY_COUNT_503) {
                        //遇到503，延迟[4,24]秒后再重试，区间间隔不能太小
                        int ran = 4000 + (int) (Math.random() * 20000);
                        Thread.sleep(ran);
                        Log.e(TAG, "sleep:" + ran);
                        downloadFile(ts, file, videoUrl);
                    }
                } else if (ts.getRetryCount() <= VideoDownloadManager.getInstance().mConfig.retryCount) {
                    Log.e(TAG, "====retry1   responseCode=" + responseCode + "  ts:" + ts.getUrl());

                    downloadFile(ts, file, videoUrl);
                } else {
                    Log.e(TAG, "====error   responseCode=" + responseCode + "  ts:" + ts.getUrl());
                    ts.failed = true;
                    mErrorTsCont++;
                }
            }


        } catch (InterruptedIOException e) {
            //被中断了，使用stop时会抛出这个，不需要处理
            Log.e(TAG, "InterruptedIOException");
            return;
        } catch (Exception e) {
            e.printStackTrace();
            ts.setRetryCount(ts.getRetryCount() + 1);
            if (ts.getRetryCount() <= VideoDownloadManager.getInstance().mConfig.retryCount) {
                Log.e(TAG, "====retry, exception=" + e.getMessage());
                downloadFile(ts, file, videoUrl);
            } else {
                ts.failed = true;
                mErrorTsCont++;
            }
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
                    e.printStackTrace();
                }
            }
            if (foutc != null) {
                try {
                    foutc.close();
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        }


    }

    private void saveFile(InputStream inputStream, File file, long contentLength, M3U8Seg ts, String videoUrl) {
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
            }
            if (mCurrentLength > 0) {
                bos.write(buffer, 0, mCurrentLength);
//                bos.flush();
            }
            ts.setContentLength(totalLength);
            ts.setTsSize(totalLength);


        } catch (InterruptedIOException e) {
            //被中断了，使用stop时会抛出这个，不需要处理
            return;
        } catch (IOException e) {
            if (file.exists() && ((contentLength > 0 && contentLength == file.length()) || (contentLength == -1 && totalLength == file.length()))) {
                //这时候也能说明ts已经下载好了
            } else {
                if ((e instanceof ProtocolException &&
                        !TextUtils.isEmpty(e.getMessage()) &&
                        e.getMessage().contains(DownloadExceptionUtils.PROTOCOL_UNEXPECTED_END_OF_STREAM)) &&
                        (contentLength > totalLength && totalLength == file.length())) {
                    if (file.length() == 0) {
                        ts.setRetryCount(ts.getRetryCount() + 1);
                        if (ts.getRetryCount() < HttpUtils.MAX_RETRY_COUNT) {
                            downloadFile(ts, file, videoUrl);
                        } else {
                            Log.e(TAG, file.getAbsolutePath() + ", length=" + file.length() + " contentLength=" + contentLength + ", saveFile failed1, exception=" + e);
                            if (file.exists()) {
                                file.delete();
                            }
                            ts.failed = true;
                            mErrorTsCont++;
                        }
                    } else {
                        ts.setContentLength(totalLength);
                    }
                } else {
                    Log.e(TAG, file.getAbsolutePath() + ", length=" + file.length() + " contentLength=" + contentLength + ", saveFile failed2, exception=" + e);
                    if (file.exists()) {
                        file.delete();
                    }
                    ts.failed = true;
                    mErrorTsCont++;
                }
            }
        } finally {
            VideoDownloadUtils.close(inputStream);
            VideoDownloadUtils.close(fos);
            VideoDownloadUtils.close(bos);
        }
    }

    /**
     * 创建本地m3u8文件，可用于离线播放
     */
    private void createLocalM3U8File() throws IOException {
        synchronized (mFileLock) {
            File tempM3U8File = new File(mSaveDir, "temp.m3u8");
            if (tempM3U8File.exists()) {
                tempM3U8File.delete();
            }
            Log.i(TAG, "createLocalM3U8File");

            BufferedWriter bfw = new BufferedWriter(new FileWriter(tempM3U8File, false));
            bfw.write(M3U8Constants.PLAYLIST_HEADER + "\n");
            bfw.write(M3U8Constants.TAG_VERSION + ":" + mM3U8.getVersion() + "\n");
            bfw.write(M3U8Constants.TAG_MEDIA_SEQUENCE + ":" + mM3U8.getInitSequence() + "\n");

            bfw.write(M3U8Constants.TAG_TARGET_DURATION + ":" + mM3U8.getTargetDuration() + "\n");

            for (M3U8Seg m3u8Ts : mTsList) {
                if (m3u8Ts.failed) {
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
                            if (!m3u8Ts.isMessyKey() && keyFile.exists()) {
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
                localM3U8File.delete();
            }
            tempM3U8File.renameTo(localM3U8File);
        }
    }
}

