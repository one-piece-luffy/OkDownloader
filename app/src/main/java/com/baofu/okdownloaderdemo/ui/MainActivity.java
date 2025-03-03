package com.baofu.okdownloaderdemo.ui;

import android.Manifest;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.util.Log;
import android.view.View;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AppCompatActivity;
import androidx.databinding.DataBindingUtil;

import com.baofu.downloader.rules.VideoDownloadManager;
import com.baofu.downloader.listener.DownloadListener;
import com.baofu.downloader.model.VideoTaskItem;
import com.baofu.okdownloaderdemo.R;
import com.baofu.okdownloaderdemo.databinding.ActivityMainBinding;
import com.baofu.permissionhelper.PermissionUtil;

import java.io.File;
import java.util.HashMap;
import java.util.Map;

public class MainActivity extends AppCompatActivity {
    ActivityMainBinding dataBinding;
    String link="https://k.sinaimg.cn/n/sinakd20109/243/w749h1094/20240308/f34c-5298fe3ef2a79c143e236cac22d1b819.jpg/w700d1q75cms.jpg";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        dataBinding = DataBindingUtil.setContentView(this, R.layout.activity_main);
        dataBinding.download.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
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
        //设置全局下载监听
        VideoDownloadManager.getInstance().setGlobalDownloadListener(mListener);
    }

    public void startDownload(){
        VideoTaskItem item = new VideoTaskItem(link);
        item.mName = "图片";
        item.mCoverUrl = link;

        item.setFileName(item.mName);
        item.overwrite = false;
        Map<String,String> header=new HashMap<>();
//                header.put("Range","bytes=0-14188543");
        header.put("referer",link);
        item.header=header;
        item.notify=true;

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