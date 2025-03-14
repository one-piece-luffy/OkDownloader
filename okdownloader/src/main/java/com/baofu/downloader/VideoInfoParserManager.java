package com.baofu.downloader;

import android.text.TextUtils;

import com.baofu.downloader.listener.IVideoInfoListener;
import com.baofu.downloader.listener.IVideoInfoParseListener;
import com.baofu.downloader.m3u8.M3U8;
import com.baofu.downloader.m3u8.M3U8Seg;
import com.baofu.downloader.m3u8.M3U8Utils;
import com.baofu.downloader.model.Video;
import com.baofu.downloader.model.VideoTaskItem;
import com.baofu.downloader.rules.VideoDownloadManager;
import com.baofu.downloader.utils.DownloadExceptionUtils;
import com.baofu.downloader.utils.OkHttpUtil;
import com.baofu.downloader.utils.VideoDownloadUtils;

import java.io.File;
import java.util.Map;

import okhttp3.Response;

public class VideoInfoParserManager {

    private static final String TAG = "VideoInfoParserManager";

    private static volatile VideoInfoParserManager sInstance;

    public static VideoInfoParserManager getInstance() {
        if (sInstance == null) {
            synchronized (VideoInfoParserManager.class) {
                if (sInstance == null) {
                    sInstance = new VideoInfoParserManager();
                }
            }
        }
        return sInstance;

    }



    /**
     * 解析网络m3u8文件
     * @param taskItem
     * @param headers
     * @param listener
     */
    public void parseNetworkM3U8Info(VideoTaskItem taskItem, Map<String, String> headers, IVideoInfoListener listener) {
        try {
            String method=OkHttpUtil.METHOD.GET;
            if(OkHttpUtil.METHOD.POST.equalsIgnoreCase(taskItem.method)){
                method=OkHttpUtil.METHOD.POST;
            }
            M3U8 m3u8 = M3U8Utils.parseNetworkM3U8Info(taskItem.getUrl(), headers, 0,false,method);
            if(m3u8==null){
                listener.onM3U8InfoFailed(new VideoDownloadException("m3u8 is null"));
                return;
            }
            // HLS LIVE video cannot be proxy cached.
            //todo m3u8里面的mp4链接
            if (m3u8.hasEndList()) {
                taskItem.suffix = VideoDownloadUtils.M3U8_SUFFIX;

                String saveName = VideoDownloadUtils.getFileName(taskItem, null, false);
                //todo  如果先下载到公有目录，再下载到私有目录 会导致保存下载进度的cache file 有问题，因为都是一个名字
                //需要用私有目录，不然android10以上没有权限
                File dir = new File(VideoDownloadManager.getInstance().mConfig.privatePath, saveName);
                //同名文件处理
                if (dir.exists()) {
                    if (!taskItem.overwrite) {
                        saveName = VideoDownloadUtils.getFileName(taskItem, System.currentTimeMillis() + "", false);
                        dir = new File(VideoDownloadManager.getInstance().mConfig.privatePath, saveName);
                    }
                }
                if (!dir.exists()) {
                    dir.mkdir();
                }

                M3U8Utils.createRemoteM3U8(dir, m3u8);
                if ( m3u8.getTsList() != null && m3u8.getTsList().size() > 0) {
                    long duration=0;
                    for(int i=0;i<m3u8.getTsList().size();i++){
                        M3U8Seg m3U8Seg=m3u8.getTsList().get(i);
                        duration += m3U8Seg.getDuration();
                    }
                    //视频时长
                    taskItem.videoLength = duration;
                    try {
                        //预估m3u8的大小
                        for (M3U8Seg ts : m3u8.getTsList()) {
                            if (ts == null || TextUtils.isEmpty(ts.getUrl()) || !ts.getUrl().startsWith("http")) {
                                continue;
                            }
                            Response response = OkHttpUtil.getInstance().requestSync(ts.getUrl(),method, headers);
                            String strLen = response.header("Content-Length");
                            long length = Long.parseLong(strLen);
                            taskItem.setTotalSize(length * m3u8.getTsList().size());
                            VideoDownloadUtils.close(response);
                            break;
                        }
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                }
                taskItem.setSaveDir(dir.getAbsolutePath());
                taskItem.setFileHash(saveName);
                taskItem.setVideoType(Video.Type.HLS_TYPE);
                listener.onM3U8InfoSuccess(taskItem, m3u8);
            } else {
                taskItem.setVideoType(Video.Type.HLS_LIVE_TYPE);
                listener.onLiveM3U8Callback(taskItem);
            }
        } catch (Exception e) {
            e.printStackTrace();
            listener.onM3U8InfoFailed(e);
        }
    }


    /**
     * 解析本地m3u8文件
     * @param taskItem
     * @param callback
     */
    public void parseLocalM3U8File(VideoTaskItem taskItem, IVideoInfoParseListener callback) {
        File remoteM3U8File = new File(taskItem.getSaveDir(), VideoDownloadUtils.REMOTE_M3U8);
        if (!remoteM3U8File.exists()) {
            callback.onM3U8FileParseFailed(taskItem, new VideoDownloadException(DownloadExceptionUtils.REMOTE_M3U8_EMPTY));
            return;
        }
        try {
            M3U8 m3u8 = M3U8Utils.parseLocalM3U8File(remoteM3U8File);
            callback.onM3U8FileParseSuccess(taskItem, m3u8);
        } catch (Exception e) {
            e.printStackTrace();
            callback.onM3U8FileParseFailed(taskItem, e);
        }
    }





}
