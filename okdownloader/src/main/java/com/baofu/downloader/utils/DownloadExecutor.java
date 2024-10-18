package com.baofu.downloader.utils;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 下载线程池
 */
public class DownloadExecutor {

    private static final int CPU_COUNT = Runtime.getRuntime().availableProcessors();
    private static final int CORE_POOL_SIZE = 1;
    private static final int MAXIMUM_POOL_SIZE = CPU_COUNT * 4 + 5;
    private static final int KEEP_ALIVE = 1;
    private static final BlockingQueue<Runnable> sPoolWorkQueue =
            new LinkedBlockingQueue<>();

    private static final ThreadFactory sThreadFactory = new ThreadFactory() {
        private final AtomicInteger mCount = new AtomicInteger(0);

        public Thread newThread(Runnable r) {
            return new Thread(r, "DownloadExecutor #" + mCount.getAndIncrement());
        }
    };

    private static final ExecutorService mExecutorService = new ThreadPoolExecutor(MAXIMUM_POOL_SIZE, MAXIMUM_POOL_SIZE, KEEP_ALIVE,
                                                                                TimeUnit.SECONDS, sPoolWorkQueue, sThreadFactory);
//    private static final ExecutorService mExecutorService = new ThreadPoolExecutor(MAXIMUM_POOL_SIZE, MAXIMUM_POOL_SIZE, 3L, TimeUnit.SECONDS, new ArrayBlockingQueue(MAXIMUM_POOL_SIZE),
//            sThreadFactory, new ThreadPoolExecutor.CallerRunsPolicy());
//    private static final ExecutorService mExecutorService = new ThreadPoolExecutor(MAXIMUM_POOL_SIZE, MAXIMUM_POOL_SIZE, 60L, TimeUnit.SECONDS, new ArrayBlockingQueue(MAXIMUM_POOL_SIZE),
//            sThreadFactory);
//    private static final ExecutorService mExecutorService = new ThreadPoolExecutor(20, Integer.MAX_VALUE,
//        60L, TimeUnit.SECONDS,
//        new SynchronousQueue<Runnable>());

    public static void execute(Runnable runnable) {
        mExecutorService.execute(runnable);
    }

    public static ExecutorService getExecutorService() {
        return mExecutorService;
    }
}
