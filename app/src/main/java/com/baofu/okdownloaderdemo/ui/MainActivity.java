package com.baofu.okdownloaderdemo.ui;

import android.Manifest;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.Log;
import android.view.View;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.NotificationCompat;
import androidx.databinding.DataBindingUtil;

import com.allfootball.news.imageloader.ImageLoader;
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
    String cover="https://img2.baidu.com/it/u=1853150649,4204942553&fm=253&app=138&f=JPEG?w=800&h=1422";
//    String link="https://k.sinaimg.cn/n/sinakd20109/243/w749h1094/20240308/f34c-5298fe3ef2a79c143e236cac22d1b819.jpg/w700d1q75cms.jpg";
    String link="https://wwzycdn.10cong.com/20250719/3cWYkrFI/index.m3u8";//短剧
//    String link="https://bfikuncdn.com/20250608/GFr5gwxA/index.m3u8";//微短剧

    String msj="https://v.cdnlz22.com/20250720/20060_354bcdcf/index.m3u8";//牧神记
    String fanren="https://p.b8bf.com/video/fanrenxiuxianchuan/%E7%AC%AC152%E9%9B%86/index.m3u8";//凡人修仙
    String jinxiu="https://vodcnd011.myrqsb.com/20250704/nQ7ckJjH/index.m3u8";//锦绣芳华

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
                PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
                if (!pm.isIgnoringBatteryOptimizations(getPackageName())) {
                    AlertDialog.Builder builder = new AlertDialog.Builder(MainActivity.this);

                    // 设置对话框标题和消息
                    builder.setTitle("提示")
                            .setMessage("请允许应用在后台运行，避免下载中断")
                            .setPositiveButton("确认", (dialog, which) -> {
                                // 点击确认按钮时显示Toast
                                try {
                                    Intent intent = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
                                    intent.setData(Uri.parse("package:" + getPackageName()));
                                    startActivity(intent);
                                } catch (Exception e) {
                                    e.printStackTrace();
                                }
                            })
                            .setNegativeButton("取消", (dialog, which) -> {
                                // 点击取消按钮时关闭对话框
                                dialog.dismiss();
                            });

                    // 创建并显示对话框
                    AlertDialog dialog = builder.create();
                    dialog.show();

                }
                preDownload("锦绣芳华",link);
//                preDownload("牧神记",msj);


            }
        });
        dataBinding.fix.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                mVideoTaskItem.setIsCompleted(false);
                VideoDownloadManager.getInstance().startDownload2(mVideoTaskItem);
            }
        });

        dataBinding.pause.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                VideoDownloadManager.getInstance().pauseAllDownloadTasks();
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
                FFmpegUtils.covertM3u8ToMp4(m3u8Path, mp4Path, null,new IFFmpegCallback() {
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
    }


    public void preDownload(String name, String url) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            PermissionUtil.getInstance().request(MainActivity.this, "请求权限",
                    PermissionUtil.asArray(Manifest.permission.POST_NOTIFICATIONS),
                    (granted, isAlwaysDenied) -> {
                        if (granted) {
                            startDownload(name, url);
                        } else {
                            if (isAlwaysDenied) {
                                Toast.makeText(MainActivity.this, "权限申请失败，请设置中修改", Toast.LENGTH_SHORT).show();
                            } else {
                                Toast.makeText(MainActivity.this, "权限申请失败", Toast.LENGTH_SHORT).show();
                            }
                        }
                    });
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startDownload(name, url);
        } else {
            PermissionUtil.getInstance().request(MainActivity.this, "请求权限",
                    PermissionUtil.asArray(Manifest.permission.WRITE_EXTERNAL_STORAGE, Manifest.permission.READ_EXTERNAL_STORAGE),
                    (granted, isAlwaysDenied) -> {
                        if (granted) {
                            startDownload(name, url);
                        } else {
                            if (isAlwaysDenied) {
                                Toast.makeText(MainActivity.this, "权限申请失败，请设置中修改", Toast.LENGTH_SHORT).show();
                            } else {
                                Toast.makeText(MainActivity.this, "权限申请失败", Toast.LENGTH_SHORT).show();
                            }
                        }
                    });
        }
    }

    public void startDownload(String name,String url){


        VideoTaskItem item = new VideoTaskItem(url);
        item.mName = name;
        item.mCoverUrl = cover;

        item.setFileName(item.mName);
        Map<String,String> header=new HashMap<>();
        header.put("user-agent","Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/138.0.0.0 Mobile Safari/537.36");
//                header.put("Range","bytes=0-14188543");
//        header.put("referer",link);
        item.header= VideoDownloadUtils.mapToJsonString(header);
        item.notify=true;
        item.privateFile=false;
        item.onlyWifi=true;
        item.overwrite=true;
        //启动前台服务下载
        //设置通知打开链接可以在VideoDownloadManager的下载完成方法onTaskFinished里修改
        VideoDownloadManager.getInstance().startDownload(MainActivity.this,item);
        Toast.makeText(MainActivity.this, "开始下载", Toast.LENGTH_SHORT).show();
    }

    @Override
    protected void onResume() {
        super.onResume();
        VideoDownloadManager.getInstance().setGlobalDownloadListener(mListener);
    }

    private DownloadListener mListener = new DownloadListener() {
        public void onDownloadDefault(VideoTaskItem item) {
//            notifyChanged(item);
        }

        public void onDownloadPending(VideoTaskItem item) {
//            notifyChanged(item);
        }

        public void onDownloadPrepare(VideoTaskItem item) {
//            notifyChanged(item);
        }

        public void onDownloadStart(VideoTaskItem item) {
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

                        ImageLoader.getInstance()
                                .imageView(dataBinding.cover)
                                .url(mVideoTaskItem.mCoverPath)
                                .loadImage(MainActivity.this);
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