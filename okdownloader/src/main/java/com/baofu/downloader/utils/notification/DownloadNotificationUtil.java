package com.baofu.downloader.utils.notification;

import android.app.Activity;
import android.app.Application;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.os.Build;
import android.util.Log;

import androidx.core.app.NotificationCompat;

import com.baofu.downloader.model.VideoTaskItem;


public class DownloadNotificationUtil {

    public static final String TAG = "DownloadNotificationUtil";

    /**
     * 初始化通知
     */
    public void createNotification(Context context, VideoTaskItem item) {
        if (context == null || item == null || item.notificationId < 0) {
            return;
        }
        try {

            NotificationManager notificationManager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
            // Android8.0及以后的方式
            if (Build.VERSION.SDK_INT >= 26) {
                // 创建通知渠道
                NotificationChannel notificationChannel = new NotificationChannel("download_channel", "下载",
                        NotificationManager.IMPORTANCE_DEFAULT);
                notificationChannel.enableLights(false); //关闭闪光灯
                notificationChannel.enableVibration(false); //关闭震动
                notificationChannel.setSound(null, null); //设置静音
                notificationManager.createNotificationChannel(notificationChannel);
            }

            NotificationCompat.Builder builder = NotificationBuilderManager.map.get(item.notificationId);
            if (builder == null) {
                builder = new NotificationCompat.Builder(context, "download_channel");
                builder.setContentTitle(item.mName) //设置标题
                        .setSmallIcon(context.getApplicationInfo().icon) //设置小图标
//                    .setLargeIcon(BitmapFactory.decodeResource(activity.getResources(),
//                            activity.getApplicationInfo().icon)) //设置大图标
                        .setPriority(NotificationCompat.PRIORITY_MAX) //设置通知的优先级
                        .setAutoCancel(false) //设置通知被点击一次不自动取消
                        .setSound(null) //设置静音
                        .setContentText("0%") //设置内容
                        .setProgress(100, 0, false) //设置进度条
                        .setContentIntent(NotificationBuilderManager.createIntent(context, null, item.notificationId,item.action)); //设置点击事件
                NotificationBuilderManager.map.put(item.notificationId, builder);
            }
            notificationManager.notify(item.notificationId, builder.build());

        } catch (Exception x) {
            Log.e(TAG, "initNotification error=" + x);
        }
    }


}
