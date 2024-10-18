package com.baofu.okdownloaderdemo.ui;

import android.content.Intent;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.Log;
import android.view.View;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.databinding.DataBindingUtil;

import com.baofu.downloader.VideoDownloadManager;
import com.baofu.downloader.listener.DownloadListener;
import com.baofu.downloader.model.VideoTaskItem;
import com.baofu.downloader.service.DownloadService;
import com.baofu.downloader.utils.DownloadConstans;
import com.baofu.downloader.utils.LogUtils;
import com.baofu.downloader.utils.UniqueIdGenerator;
import com.baofu.okdownloaderdemo.R;
import com.baofu.okdownloaderdemo.databinding.ActivityMainBinding;

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
                VideoTaskItem item = new VideoTaskItem(link);
                item.mName = "图片";
                item.mCoverUrl = link;

                item.setFileName(item.mName);
                item.overwrite = false;

                //启动前台服务下载
                //设置通知打开链接可以在VideoDownloadManager的下载完成方法onTaskFinished里修改
                item.notificationId= UniqueIdGenerator.generateUniqueId();
                Intent intent = new Intent(MainActivity.this, DownloadService.class);
                item.putExtra(intent);
                startService(intent);
                Toast.makeText(MainActivity.this, "开始下载", Toast.LENGTH_SHORT).show();
            }
        });
        //设置全局下载监听
        VideoDownloadManager.getInstance().setGlobalDownloadListener(mListener);
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