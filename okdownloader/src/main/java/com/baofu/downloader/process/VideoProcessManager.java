package com.baofu.downloader.process;

import androidx.annotation.NonNull;

public class VideoProcessManager {

    private static final String TAG = "VideoProcessManager";

    public static VideoProcessManager sInstance = null;

    public static VideoProcessManager getInstance() {
        if (sInstance == null) {
            synchronized (VideoProcessManager.class) {
                if (sInstance == null) {
                    sInstance = new VideoProcessManager();
                }
            }
        }
        return sInstance;
    }

    public void mergeTs(String inputPath, String outputPath, @NonNull IM3U8MergeListener listener) {
//        try {
//            int result = FFmpegVideoUtils.transformVideo(inputPath, outputPath);
//            LogUtils.i(TAG, "VideoMerge mergeTs result=" +result);
//            if (result < 0) {
//                listener.onMergeFailed(new Exception("Merge ts failed"));
//            } else {
//                File outputFile = new File(outputPath);
//                if (outputFile.exists()) {
//                    listener.onMergedFinished();
//                } else {
//                    listener.onMergeFailed(new Exception("Merge ts failed, No file"));
//                }
//            }
//        }catch (NoClassDefFoundError e){
//
//        }catch (Exception e){
//            e.printStackTrace();
//        }
        listener.onMergedFinished();

    }

    public void printVideoInfo(String srcPath) {
//        FFmpegVideoUtils.printVideoInfo(srcPath);
    }

}
