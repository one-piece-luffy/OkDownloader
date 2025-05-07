package com.baofu.okdownloaderdemo.ui;

import android.Manifest;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.Log;
import android.view.View;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.NotificationCompat;
import androidx.databinding.DataBindingUtil;

import com.baofu.downloader.common.VideoDownloadConstants;
import com.baofu.downloader.listener.IFFmpegCallback;
import com.baofu.downloader.model.VideoTaskState;
import com.baofu.downloader.rules.VideoDownloadManager;
import com.baofu.downloader.listener.DownloadListener;
import com.baofu.downloader.model.VideoTaskItem;
import com.baofu.downloader.utils.FFmpegUtils;
import com.baofu.downloader.utils.VideoDownloadUtils;
import com.baofu.downloader.utils.notification.NotificationBuilderManager;
import com.baofu.okdownloaderdemo.R;
import com.baofu.okdownloaderdemo.databinding.ActivityMainBinding;
import com.baofu.permissionhelper.PermissionUtil;

import java.io.File;
import java.util.HashMap;
import java.util.Map;

public class MainActivity extends AppCompatActivity {
    Handler handler=new Handler(Looper.getMainLooper());
    ActivityMainBinding dataBinding;
    VideoTaskItem mVideoTaskItem;
//    String link="https://k.sinaimg.cn/n/sinakd20109/243/w749h1094/20240308/f34c-5298fe3ef2a79c143e236cac22d1b819.jpg/w700d1q75cms.jpg";
    String link="https://vip.ffzy-video.com/20250313/13895_b3633b88/index.m3u8";

//    String link2="https://svipsvip.ffzy-online5.com/20241219/36281_d4d2775c/2000k/hls/mixed.m3u8";
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        dataBinding = DataBindingUtil.setContentView(this, R.layout.activity_main);
        dataBinding.delete.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                VideoDownloadManager.getInstance().cancleTask(mVideoTaskItem);
            }
        });
        dataBinding.download.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    PermissionUtil.getInstance().request(MainActivity.this, "请求权限",
                            PermissionUtil.asArray(Manifest.permission.POST_NOTIFICATIONS),
                            (granted, isAlwaysDenied) -> {
                                if (granted) {
                                    startDownload();
                                } else {
                                    if (isAlwaysDenied) {
                                        Toast.makeText(MainActivity.this,"权限申请失败，请设置中修改",Toast.LENGTH_SHORT).show();
                                    } else {
                                        Toast.makeText(MainActivity.this,"权限申请失败",Toast.LENGTH_SHORT).show();
                                    }
                                }
                            });
                } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    startDownload();
                } else {
                    PermissionUtil.getInstance().request(MainActivity.this, "请求权限",
                            PermissionUtil.asArray(Manifest.permission.WRITE_EXTERNAL_STORAGE, Manifest.permission.READ_EXTERNAL_STORAGE),
                            (granted, isAlwaysDenied) -> {
                                if (granted) {
                                    startDownload();
                                } else {
                                    if (isAlwaysDenied) {
                                        Toast.makeText(MainActivity.this,"权限申请失败，请设置中修改",Toast.LENGTH_SHORT).show();
                                    } else {
                                        Toast.makeText(MainActivity.this,"权限申请失败",Toast.LENGTH_SHORT).show();
                                    }
                                }
                            });
                }






            }
        });

        dataBinding.export.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {

                String m3u8Path=null;
                String name="";
                if (mVideoTaskItem == null) {
                    m3u8Path = "/storage/emulated/0/Android/data/app.demo.okdownloader/files/Download/bp/图片1746596901543/图片1746596901543.m3u8";
                    name="图片1746596901543";
                } else {
                    m3u8Path = mVideoTaskItem.getFilePath();
                    name=mVideoTaskItem.mName;
                }
                Log.e("asdf","filepath:"+m3u8Path);
                String mp4Path= VideoDownloadManager.getInstance().mConfig.publicPath + File.separator +name + ".mp4";
                FFmpegUtils.covertM3u8ToMp4(m3u8Path, mp4Path, new IFFmpegCallback() {
                    @Override
                    public void onSuc() {
                        if (isFinishing() || isDestroyed()) {
                            return;
                        }
                        handler.post(new Runnable() {
                            @Override
                            public void run() {
                                Toast.makeText(MainActivity.this,"导出成功",Toast.LENGTH_SHORT).show();
                                //刷新相册
                                VideoDownloadUtils.scanAlbum(MainActivity.this,mp4Path);

                            }
                        });

                    }

                    @Override
                    public void onFail() {
                        handler.post(new Runnable() {
                            @Override
                            public void run() {
                                Toast.makeText(MainActivity.this,"导出失败",Toast.LENGTH_SHORT).show();

                            }
                        });

                    }
                });
            }
        });
        //设置全局下载监听
        VideoDownloadManager.getInstance().setGlobalDownloadListener(mListener);
    }


    private void notifyTest(){
        NotificationManager notificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);

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
        builder.setContentTitle("haha") //设置标题
                .setSmallIcon(getApplicationInfo().icon) //设置小图标
//                    .setLargeIcon(BitmapFactory.decodeResource(getResources(),
//                            getApplicationInfo().icon)) //设置大图标
                .setPriority(NotificationCompat.PRIORITY_MAX) //设置通知的优先级
                .setAutoCancel(false) //设置通知被点击一次不自动取消
                .setSound(null) //设置静音
                .setContentText("0%") //设置内容
                .setProgress(100, 0, false) //设置进度条
                .setContentIntent(NotificationBuilderManager.createIntent(getApplication(), null, 1)); //设置点击事件
        if (builder == null)
            return;
        int ran= (int) (Math.random()*100);
//                builder.setContentIntent(createIntent(context, bundle,item.notificationId)); //设置点击事件
        builder.setContentText( ran+"%");
        builder.setProgress(100,ran, false);
        notificationManager.notify(1, builder.build());
    }

    public void startDownload(){


        VideoTaskItem item = new VideoTaskItem(link);
        item.mName = "图片";
        item.mCoverUrl = link;

        item.setFileName(item.mName);
        item.overwrite = false;
        Map<String,String> header=new HashMap<>();
        header.put("user-agent","Mozilla/5.0 (iPhone; CPU iPhone OS 16_6 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/16.6 Mobile/15E148 Safari/604.1");
//                header.put("Range","bytes=0-14188543");
//        header.put("referer",link);
        item.header= VideoDownloadUtils.mapToJsonString(header);
        item.notify=true;
        item.privateFile=false;
        item.onlyWifi=true;
        //启动前台服务下载
        //设置通知打开链接可以在VideoDownloadManager的下载完成方法onTaskFinished里修改
        VideoDownloadManager.getInstance().startDownload(MainActivity.this,item);
        Toast.makeText(MainActivity.this, "开始下载", Toast.LENGTH_SHORT).show();
    }

    @Override
    protected void onResume() {
        super.onResume();

    }

    private DownloadListener mListener = new DownloadListener() {
        public void onDownloadDefault(VideoTaskItem item) {
            Log.e("asdf",  "onDownloadDefault: "+ item.mName );
//            notifyChanged(item);
        }

        public void onDownloadPending(VideoTaskItem item) {
            Log.e("asdf", "onDownloadPending:"+ item.mName );
//            notifyChanged(item);
        }

        public void onDownloadPrepare(VideoTaskItem item) {
            Log.e("asdf", "onDownloadPrepare: "+ item.mName );
//            notifyChanged(item);
        }

        public void onDownloadStart(VideoTaskItem item) {
            Log.e("asdf", "onDownloadStart: " + item.mName + " " + item.getUrl());
//            notifyChanged(item);
        }

        public void onDownloadProgress(VideoTaskItem item) {
            if (isFinishing()) {
                return;
            }
//            long currentTimeStamp = System.currentTimeMillis();
//            if (currentTimeStamp - mLastProgressTimeStamp > 1000) {
//                LogUtils.w("allDownload", "-==onDownloadProgress: " + item.getPercentString() + ", curTs=" + item.getCurTs() + ", totalTs=" + item.getTotalTs() + ", name=" + item.mName);
//                notifyChanged(item);
//                mLastProgressTimeStamp = currentTimeStamp;
//
//                runOnUiThread(new Runnable() {
//                    @Override
//                    public void run() {
//                        updateStatus(RESUME);
//                    }
//                });
//            }

        }

        public void onDownloadSpeed(VideoTaskItem item) {
            long currentTimeStamp = System.currentTimeMillis();
//            if (currentTimeStamp - mLastSpeedTimeStamp > 1000) {
//                notifyChanged(item);
//                mLastSpeedTimeStamp = currentTimeStamp;
//            }
        }

        public void onDownloadPause(VideoTaskItem item) {
            Log.e("asdf", "onDownloadPause: " + item.mName + " " + item.getUrl());
//            notifyChanged(item);
        }

        public void onDownloadError(VideoTaskItem item) {
            Log.e("asdf", "onDownloadError: " + item.getUrl());
//            notifyChanged(item);
        }

        public void onDownloadSuccess(VideoTaskItem item) {
            Log.e("asdf", "onDownloadSuccess: "+item.mName);
            if (isFinishing()) {
                return;
            }
            if (!item.isDownloadSuc) {
                item.isDownloadSuc = true;
                mVideoTaskItem=item;
                String m3u8Path=mVideoTaskItem.getFilePath();
                Log.e("asdf","filepath:"+m3u8Path);
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        Toast.makeText(MainActivity.this, "下载成功", Toast.LENGTH_SHORT).show();
                    }
                });
            }


        }

        @Override
        public void onDownloadMerge(VideoTaskItem item) {
            super.onDownloadMerge(item);
//            notifyChanged(item);
        }
    };

}