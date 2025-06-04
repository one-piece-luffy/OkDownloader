package com.baofu.downloader.common;

public class VideoDownloadConstants {

    public static final int READ_TIMEOUT = 1 * 15 * 1000;
    public static final int CONN_TIMEOUT = 1 * 15 * 1000;
    public static final int WRITE_TIMEOUT = 1 * 15 * 1000;
    public static final int CONCURRENT = 3;

    public static final int MSG_DOWNLOAD_DEFAULT = 0;
    public static final int MSG_DOWNLOAD_QUEUING = -1;
    public static final int MSG_DOWNLOAD_PENDING = 1;
    public static final int MSG_DOWNLOAD_PREPARE = 2;
    public static final int MSG_DOWNLOAD_START = 3;
    public static final int MSG_DOWNLOAD_PROCESSING = 4;
    public static final int MSG_DOWNLOAD_PAUSE = 5;
    public static final int MSG_DOWNLOAD_SUCCESS = 6;
    public static final int MSG_DOWNLOAD_ERROR = 7;
    public static final int MSG_DOWNLOAD_MERGE = 8;

    public static final int MSG_FETCH_DOWNLOAD_INFO = 100;
    public static final int MSG_DELETE_ALL_FILES = 101;

    public static final int CPU_COUNT = Runtime.getRuntime().availableProcessors();
    public static final int KB=1024;
    public static final int MB=1024*KB;
    public static final long GB=1024*MB;
    public static final int DOWNLOAD_TYPE_RANGE=0;
    public static final int DOWNLOAD_TYPE_ALL=1;
    public static final int ERROR_SPEED=-1;

    //遇到503的重试次数
    public final static int MAX_RETRY_COUNT_503 = 4;

    public final static String CHANNEL_ID="download_channel";
    public final static String COVER_SUFFIX="_cover.jpg";
}
