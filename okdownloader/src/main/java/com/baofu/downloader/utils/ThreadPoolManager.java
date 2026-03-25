package com.baofu.downloader.utils;


import android.util.Log;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 统一线程池管理器
 * 优化点：
 * 1. 统一管理所有线程池，避免资源浪费
 * 2. 支持任务分类执行
 * 3. 支持动态调整线程数
 * 4. 支持优雅关闭
 */
public class ThreadPoolManager {
    private static final String TAG = "ThreadPoolManager";

    // 单例
    private static volatile ThreadPoolManager sInstance;

    // CPU核心数
    private static final int CPU_COUNT = Runtime.getRuntime().availableProcessors();

    // 网络IO密集型线程池核心线程数（适用于下载任务）
    private static final int NETWORK_IO_CORE_SIZE = Math.max(4, CPU_COUNT * 2);
    private static final int NETWORK_IO_MAX_SIZE = Math.max(8, CPU_COUNT * 4);

    // 文件IO密集型线程池核心线程数（适用于写入、解密任务）
    private static final int DISK_IO_CORE_SIZE = Math.max(2, CPU_COUNT);
    private static final int DISK_IO_MAX_SIZE = Math.max(4, CPU_COUNT * 2);

    // CPU密集型线程池核心线程数（适用于解析、合并任务）
    private static final int CPU_CORE_SIZE = Math.max(2, CPU_COUNT);

    // 线程存活时间
    private static final long KEEP_ALIVE_TIME = 60L;
    private static final TimeUnit KEEP_ALIVE_UNIT = TimeUnit.SECONDS;

    // 任务队列大小
    private static final int NETWORK_QUEUE_SIZE = 128;
    private static final int DISK_QUEUE_SIZE = 64;
    private static final int CPU_QUEUE_SIZE = 32;

    // 线程池实例
    private ThreadPoolExecutor mNetworkExecutor;   // 网络下载线程池
    private ThreadPoolExecutor mDiskExecutor;      // 磁盘IO线程池
    private ThreadPoolExecutor mCpuExecutor;       // CPU计算线程池
    private ScheduledExecutorService mScheduledExecutor; // 定时任务线程池

    // 任务计数器
    private final AtomicInteger mActiveTaskCount = new AtomicInteger(0);
    private final ConcurrentHashMap<Runnable, Long> mTaskMap = new ConcurrentHashMap<>();

    // 监听器
    private OnTaskCountChangeListener mTaskCountListener;

    private ThreadPoolManager() {
        init();
    }

    public static ThreadPoolManager getInstance() {
        if (sInstance == null) {
            synchronized (ThreadPoolManager.class) {
                if (sInstance == null) {
                    sInstance = new ThreadPoolManager();
                }
            }
        }
        return sInstance;
    }

    private void init() {
        // 1. 网络IO线程池（用于下载请求）
        mNetworkExecutor = new ThreadPoolExecutor(
                NETWORK_IO_CORE_SIZE,
                NETWORK_IO_MAX_SIZE,
                KEEP_ALIVE_TIME,
                KEEP_ALIVE_UNIT,
                new LinkedBlockingQueue<>(NETWORK_QUEUE_SIZE),
                new DownloadThreadFactory("network-download"),
                new ThreadPoolExecutor.CallerRunsPolicy() // 队列满时由调用线程执行，保证任务不丢失
        );

        // 2. 磁盘IO线程池（用于文件写入、解密、合并）
        mDiskExecutor = new ThreadPoolExecutor(
                DISK_IO_CORE_SIZE,
                DISK_IO_MAX_SIZE,
                KEEP_ALIVE_TIME,
                KEEP_ALIVE_UNIT,
                new LinkedBlockingQueue<>(DISK_QUEUE_SIZE),
                new DownloadThreadFactory("disk-io"),
                new ThreadPoolExecutor.CallerRunsPolicy()
        );

        // 3. CPU密集型线程池（用于解析、加密解密等）
        mCpuExecutor = new ThreadPoolExecutor(
                CPU_CORE_SIZE,
                CPU_CORE_SIZE,
                KEEP_ALIVE_TIME,
                KEEP_ALIVE_UNIT,
                new LinkedBlockingQueue<>(CPU_QUEUE_SIZE),
                new DownloadThreadFactory("cpu-compute"),
                new ThreadPoolExecutor.CallerRunsPolicy()
        );

        // 4. 定时任务线程池（用于速度计算、进度更新等）
        mScheduledExecutor = Executors.newScheduledThreadPool(1, new DownloadThreadFactory("scheduler"));

        Log.i(TAG, "ThreadPoolManager initialized: network=" + NETWORK_IO_CORE_SIZE + "/" + NETWORK_IO_MAX_SIZE +
                ", disk=" + DISK_IO_CORE_SIZE + "/" + DISK_IO_MAX_SIZE +
                ", cpu=" + CPU_CORE_SIZE);
    }

    /**
     * 执行网络下载任务
     */
    public void executeNetwork(Runnable task) {
        executeWithTracking(mNetworkExecutor, task);
    }

    /**
     * 执行磁盘IO任务
     */
    public void executeDisk(Runnable task) {
        executeWithTracking(mDiskExecutor, task);
    }

    /**
     * 执行CPU计算任务
     */
    public void executeCpu(Runnable task) {
        executeWithTracking(mCpuExecutor, task);
    }

    /**
     * 延迟执行任务
     */
    public void schedule(Runnable task, long delay, TimeUnit unit) {
        mScheduledExecutor.schedule(() -> executeWithTracking(mNetworkExecutor, task), delay, unit);
    }

    /**
     * 周期性执行任务
     */
    public void scheduleAtFixedRate(Runnable task, long initialDelay, long period, TimeUnit unit) {
        mScheduledExecutor.scheduleAtFixedRate(() -> {
            try {
                task.run();
            } catch (Exception e) {
                Log.e(TAG, "Scheduled task failed", e);
            }
        }, initialDelay, period, unit);
    }

    private void executeWithTracking(ExecutorService executor, Runnable task) {
        if (executor == null || executor.isShutdown()) {
            Log.e(TAG, "Executor is shutdown, cannot execute task");
            return;
        }

        Runnable wrappedTask = () -> {
            mActiveTaskCount.incrementAndGet();
            mTaskMap.put(task, System.currentTimeMillis());

            try {
                task.run();
            } catch (Exception e) {
                Log.e(TAG, "Task execution failed", e);
            } finally {
                mActiveTaskCount.decrementAndGet();
                mTaskMap.remove(task);

                if (mTaskCountListener != null) {
                    mTaskCountListener.onTaskCountChange(mActiveTaskCount.get());
                }
            }
        };

        executor.execute(wrappedTask);
    }

    /**
     * 获取当前活跃任务数
     */
    public int getActiveTaskCount() {
        return mActiveTaskCount.get();
    }

    /**
     * 获取网络线程池队列大小
     */
    public int getNetworkQueueSize() {
        return mNetworkExecutor.getQueue().size();
    }

    /**
     * 获取磁盘线程池队列大小
     */
    public int getDiskQueueSize() {
        return mDiskExecutor.getQueue().size();
    }

    /**
     * 动态调整网络线程池大小
     */
    public void adjustNetworkPoolSize(int coreSize, int maxSize) {
        if (mNetworkExecutor != null) {
            mNetworkExecutor.setCorePoolSize(coreSize);
            mNetworkExecutor.setMaximumPoolSize(maxSize);
            Log.i(TAG, "Network pool size adjusted: core=" + coreSize + ", max=" + maxSize);
        }
    }

    /**
     * 设置任务数量变化监听
     */
    public void setOnTaskCountChangeListener(OnTaskCountChangeListener listener) {
        mTaskCountListener = listener;
    }

    /**
     * 优雅关闭所有线程池
     */
    public void shutdown() {
        Log.i(TAG, "Shutting down thread pools...");

        shutdownExecutor(mNetworkExecutor, "network");
        shutdownExecutor(mDiskExecutor, "disk");
        shutdownExecutor(mCpuExecutor, "cpu");

        if (mScheduledExecutor != null) {
            mScheduledExecutor.shutdown();
            try {
                if (!mScheduledExecutor.awaitTermination(10, TimeUnit.SECONDS)) {
                    mScheduledExecutor.shutdownNow();
                }
            } catch (InterruptedException e) {
                mScheduledExecutor.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }

        Log.i(TAG, "Thread pools shutdown completed");
    }

    private void shutdownExecutor(ExecutorService executor, String name) {
        if (executor == null) return;

        executor.shutdown();
        try {
            if (!executor.awaitTermination(20, TimeUnit.SECONDS)) {
                Log.w(TAG, "Executor " + name + " did not terminate, forcing shutdown");
                executor.shutdownNow();
                if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                    Log.e(TAG, "Executor " + name + " did not terminate after force");
                }
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
    private void shutdownExecutorNow(ExecutorService executor, String name) {
        if (executor == null) return;
        try {
            Log.w(TAG, "Executor " + name + " did not terminate, forcing shutdown");
            executor.shutdownNow();
        } catch (Exception e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    /**
     * 线程工厂
     */
    private static class DownloadThreadFactory implements ThreadFactory {
        private final AtomicInteger threadNumber = new AtomicInteger(1);
        private final String namePrefix;

        DownloadThreadFactory(String poolName) {
            this.namePrefix = "download-" + poolName + "-thread-";
        }

        @Override
        public Thread newThread(Runnable r) {
            Thread t = new Thread(r, namePrefix + threadNumber.getAndIncrement());
            t.setDaemon(false);
            t.setPriority(Thread.NORM_PRIORITY);
            return t;
        }
    }

    /**
     * 创建一个临时的网络线程池，用于单个下载任务
     * 使用完后需要调用 shutdown()
     *
     * @param name 线程池名称，用于调试
     * @return 临时线程池
     */
    public ExecutorService createNetworkExecutor(String name) {
        int coreSize = Math.max(4, CPU_COUNT * 2);
        int maxSize = Math.max(8, CPU_COUNT * 4);

        return new ThreadPoolExecutor(
                coreSize,
                maxSize,
                60L,
                TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(),
                new DownloadThreadFactory(name),
                new ThreadPoolExecutor.CallerRunsPolicy()
        );
    }

    /**
     * 任务数量变化监听器
     */
    public interface OnTaskCountChangeListener {
        void onTaskCountChange(int activeCount);
    }
}
