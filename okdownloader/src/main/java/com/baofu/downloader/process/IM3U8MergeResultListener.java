package com.baofu.downloader.process;

import com.baofu.downloader.model.VideoTaskItem;

public interface IM3U8MergeResultListener {

    void onCallback(VideoTaskItem taskItem);
}
