package com.baofu.downloader.utils.notification;

import android.app.Activity;
import android.app.Application;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.text.TextUtils;

import androidx.core.app.NotificationCompat;

import com.baofu.downloader.model.VideoTaskItem;
import com.baofu.downloader.model.VideoTaskState;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class NotificationBuilderManager {
    private static NotificationBuilderManager instance;
    public static final Map<Integer, NotificationCompat.Builder> map = new ConcurrentHashMap<>();

    public static NotificationBuilderManager getInstance() {
        if (instance == null) {
            instance = new NotificationBuilderManager();
        }
        return instance;
    }


    /**
     * 刷新通知
     * @param context 这里要用application，用activity还是有后台被限制的情况
     */
    public void updateNotification(Context context, Bundle bundle, VideoTaskItem item) {
        if (context==null||item == null || item.notificationId < 0) {
            return;
        }
        try {
            NotificationManager notificationManager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
            NotificationCompat.Builder builder = map.get(item.notificationId);
            if (builder == null)
                return;
            builder.setContentIntent(createIntent(context, bundle,item.notificationId)); //设置点击事件
            if (item.getPercent() >= 0) {
                builder.setContentText((int)item.getPercent() + "%");
                builder.setProgress(100, (int) item.getPercent(), false);
            }
            if (item.getPercent() == 100) {
//                builder.setContentText("下载完成");
                if (TextUtils.isEmpty(item.message)) {
                    builder.setContentText((int) item.getPercent() + "%");
                } else {
                    builder.setContentText(item.message);
                }
//                if (item.getTaskState() == VideoTaskState.SUCCESS) {
//                    //取消进度条
//                    builder.setProgress(0, 0, false);
//                }
                builder.setProgress(0, 0, false);
                builder.setAutoCancel(true);
            }
            if(item.getTaskState()== VideoTaskState.ERROR){
                if (TextUtils.isEmpty(item.message)) {
                    builder.setContentText((int) item.getPercent() + "%");
                } else {
                    builder.setContentText(item.message);
                }
                //取消进度条
//                builder.setProgress(0, 0, false);
            }
            notificationManager.notify(item.notificationId, builder.build());
        } catch (Exception e) {
            e.printStackTrace();
        }

    }

    /**
     * 设置通知点击事件
     *
     * @return 点击事件
     */
    public static PendingIntent createIntent(Context context, Bundle bundle, int notificationId) {
        Intent intent = new Intent("main");
        intent.setPackage(context.getPackageName());
        if (bundle != null) {
            intent.putExtras(bundle);
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        return PendingIntent.getActivity(context, notificationId, intent,  PendingIntent.FLAG_UPDATE_CURRENT| PendingIntent.FLAG_IMMUTABLE);

    }
}
