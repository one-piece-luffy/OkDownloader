package com.baofu.downloader.listener;

import com.baofu.downloader.model.VideoTaskItem;

import java.util.List;

public interface IDownloadInfosCallback {

    void onDownloadInfos(List<VideoTaskItem> items);
}
