package com.baofu.downloader.service;

import static android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MANIFEST;
import static android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_NONE;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.graphics.BitmapFactory;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

import androidx.core.app.NotificationCompat;

import com.baofu.downloader.common.VideoDownloadConstants;
import com.baofu.downloader.rules.VideoDownloadManager;
import com.baofu.downloader.model.VideoTaskItem;
import com.baofu.downloader.utils.notification.NotificationBuilderManager;

/**
 * 使用 Foreground Service下载，避免app在后台运行时导致下载中断
 */
public class DownloadService extends Service {
    static final String TAG="DownloadService";
    public DownloadService() {
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        Log.e(TAG,"===========service onCreate");
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.e(TAG,"===========service onStartCommand");
        VideoTaskItem item = VideoTaskItem.getItemByIntent(intent);
        if (item == null) {
            stopSelf();
        } else {
            try {
                VideoDownloadManager.getInstance().startDownload2(item);
                NotificationManager notificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
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
                NotificationCompat.Builder builder = new NotificationCompat.Builder(getApplication(), VideoDownloadConstants.CHANNEL_ID);
                builder.setContentTitle(item.mName) //设置标题
                        .setSmallIcon(this.getApplicationInfo().icon) //设置小图标
//                    .setLargeIcon(BitmapFactory.decodeResource(getResources(),
//                            getApplicationInfo().icon)) //设置大图标
                        .setPriority(NotificationCompat.PRIORITY_MAX) //设置通知的优先级
                        .setAutoCancel(false) //设置通知被点击一次不自动取消
                        .setSound(null) //设置静音
                        .setContentText("0%") //设置内容
                        .setProgress(100, 0, false) //设置进度条
                        .setContentIntent(NotificationBuilderManager.createIntent(getApplication(), null, item.notificationId)); //设置点击事件
                NotificationBuilderManager.map.put(item.notificationId, builder);
//

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    startForeground(item.notificationId, builder.build(), FOREGROUND_SERVICE_TYPE_MANIFEST);
                } else {
                    startForeground(item.notificationId, builder.build());// 开始前台服务
                }


            } catch (Exception x) {
                x.printStackTrace();
            }
        }


        return super.onStartCommand(intent, flags, startId);
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        // 停止前台服务--参数：表示是否移除之前的通知
        stopForeground(true);
        Log.e(TAG,"===========service destroy");
    }
}