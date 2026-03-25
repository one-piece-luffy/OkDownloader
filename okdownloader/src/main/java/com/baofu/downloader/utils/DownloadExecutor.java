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


    /**
     * 执行网络下载任务
     * @deprecated 请使用 {@link ThreadPoolManager#executeNetwork(Runnable)}
     */
    @Deprecated
    public static void execute(Runnable runnable) {
        ThreadPoolManager.getInstance().executeNetwork(runnable);
    }

    /**
     * 执行网络下载任务（推荐使用）
     */
    public static void executeNetwork(Runnable runnable) {
        ThreadPoolManager.getInstance().executeNetwork(runnable);
    }

    /**
     * 执行磁盘IO任务
     */
    public static void executeDisk(Runnable runnable) {
        ThreadPoolManager.getInstance().executeDisk(runnable);
    }

    /**
     * 执行CPU计算任务
     */
    public static void executeCpu(Runnable runnable) {
        ThreadPoolManager.getInstance().executeCpu(runnable);
    }

    /**
     * 获取线程池管理器实例
     */
    public static ThreadPoolManager getThreadPoolManager() {
        return ThreadPoolManager.getInstance();
    }
}
