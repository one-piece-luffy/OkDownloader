package com.baofu.okdownloaderdemo.ui;

import android.content.Intent;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AppCompatActivity;
import androidx.databinding.DataBindingUtil;

import com.baofu.downloader.rules.VideoDownloadManager;
import com.baofu.downloader.listener.DownloadListener;
import com.baofu.downloader.model.VideoTaskItem;
import com.baofu.downloader.service.DownloadService;
import com.baofu.downloader.utils.UniqueIdGenerator;
import com.baofu.okdownloaderdemo.R;
import com.baofu.okdownloaderdemo.databinding.ActivityMainBinding;

import java.util.HashMap;
import java.util.Map;

public class MainActivity extends AppCompatActivity {
    ActivityMainBinding dataBinding;
    String link="https://www.tiktok.com/aweme/v1/play/?faid=1988&file_id=1258763e907d4c6f9ca6de626ce9741c&is_play_url=1&item_id=7441887850984934678&line=0&ply_type=2&signaturev3=dmlkZW9faWQ7ZmlsZV9pZDtpdGVtX2lkLmE3Y2M4MjYyNmJjZTk1NDJkYzk4NDE0NTA2YWExYmM5&tk=tt_chain_token&video_id=v24044gl0000ct3eaenog65smrt9flbg";

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
                Map<String,String> header=new HashMap<>();
//                header.put("Range","bytes=0-14188543");
                header.put("referer",link);
                item.header=header;

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