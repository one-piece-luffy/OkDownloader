package com.baofu.downloader.utils;

import android.os.Handler;
import android.os.Looper;

import java.util.concurrent.Callable;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;

/**
 * 工作线程处理器
 * 优化后使用统一的线程池管理器
 */
public class WorkerThreadHandler {

    private static final String TAG = "WorkerThreadHandler";

    // 主线程Handler
    private static Handler sMainHandler = new Handler(Looper.getMainLooper());

    public static Handler getMainHandler() {
        return sMainHandler;
    }

    public static void runOnUiThread(Runnable r) {
        runOnUiThread(r, 0);
    }

    public static void runOnUiThread(Runnable r, int delayTime) {
        if (delayTime > 0) {
            sMainHandler.postDelayed(r, delayTime);
        } else if (runningOnUiThread()) {
            r.run();
        } else {
            sMainHandler.post(r);
        }
    }

    private static boolean runningOnUiThread() {
        return sMainHandler.getLooper() == Looper.myLooper();
    }

    /**
     * 提交后台任务（使用统一线程池）
     * 返回Future用于跟踪任务状态
     */
    public static Future<?> submitRunnableTask(Runnable task) {
        FutureTask<Void> futureTask = new FutureTask<Void>(task, null);
        ThreadPoolManager.getInstance().executeCpu(futureTask);
        return futureTask;
    }

    /**
     * 提交带返回值的后台任务
     */
    public static <T> Future<T> submitCallbackTask(Callable<T> task) {
        FutureTask<T> futureTask = new FutureTask<T>(task);
        ThreadPoolManager.getInstance().executeCpu(futureTask);
        return futureTask;
    }
}
