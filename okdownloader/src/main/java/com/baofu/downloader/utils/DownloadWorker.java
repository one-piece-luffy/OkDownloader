package com.baofu.downloader.utils;

import static android.app.Notification.FOREGROUND_SERVICE_DEFAULT;
import static android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC;
import static android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MANIFEST;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.os.Build;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.core.app.NotificationCompat;
import androidx.work.Data;
import androidx.work.ForegroundInfo;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import com.baofu.downloader.common.VideoDownloadConstants;
import com.baofu.downloader.model.VideoTaskItem;
import com.baofu.downloader.rules.VideoDownloadManager;
import com.baofu.downloader.utils.notification.NotificationBuilderManager;

public class DownloadWorker extends Worker {
    public DownloadWorker(@NonNull Context context, @NonNull WorkerParameters workerParams) {
        super(context, workerParams);
    }

    @NonNull
    @Override
    public Result doWork() {
        // 在这里执行你的任务
        Data inputData = getInputData();
        VideoTaskItem item = VideoTaskItem.getItemByWorkerData(inputData);
        if (item == null) {
            return Result.failure();
        }
        try {

            VideoDownloadManager.getInstance().startDownload2(item);

            NotificationManager notificationManager = (NotificationManager) getApplicationContext().getSystemService(Context.NOTIFICATION_SERVICE);
            // Android8.0及以后的方式
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                NotificationChannel mNotificationChannel = notificationManager.getNotificationChannel(VideoDownloadConstants.CHANNEL_ID);
                if (mNotificationChannel == null) {
                    // 创建通知渠道
                    NotificationChannel notificationChannel = new NotificationChannel(VideoDownloadConstants.CHANNEL_ID, "download",
                            NotificationManager.IMPORTANCE_DEFAULT);
                    notificationChannel.enableLights(false); //关闭闪光灯
                    notificationChannel.enableVibration(false); //关闭震动
                    notificationChannel.setSound(null, null); //设置静音
                    notificationManager.createNotificationChannel(notificationChannel);
                }
            }
            NotificationCompat.Builder builder = new NotificationCompat.Builder(getApplicationContext(), VideoDownloadConstants.CHANNEL_ID);
            builder.setContentTitle(item.mName) //设置标题
                    .setSmallIcon(this.getApplicationContext().getApplicationInfo().icon) //设置小图标
//                    .setLargeIcon(BitmapFactory.decodeResource(getResources(),
//                            getApplicationInfo().icon)) //设置大图标
                    .setPriority(NotificationCompat.PRIORITY_MAX) //设置通知的优先级
                    .setAutoCancel(false) //设置通知被点击一次不自动取消
                    .setSound(null) //设置静音
                    .setContentText("0%") //设置内容
                    .setProgress(100, 0, false) //设置进度条
                    .setContentIntent(NotificationBuilderManager.createIntent(getApplicationContext(), null, item.notificationId,item.scheme)); //设置点击事件
            NotificationBuilderManager.map.put(item.notificationId, builder);



        } catch (Exception x) {
            x.printStackTrace();
        }
        return Result.success();
    }

}
