package com.baofu.downloader.utils;

public class DownloadConstans {
    //获取不到文件长度时，默认设置为2
    public static final int ERROR_DEFAULT_LENGTH = 2;
    //最大的重试次数
    public static int MAX_RETRY_COUNT = 1;
    public static int MAX_FILENAME_LENGTH = 50;
    public static final String DEFAULT_UA = "Mozilla/5.0 (Linux; Android 9; MI 6 Build/PKQ1.190118.001; wv) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/96.0.4664.104 Mobile Safari/537.36";

    // 1 connection: [0, 2MB)
    public static final long ONE_CONNECTION_UPPER_LIMIT = 2 * 1024 * 1024; // 2MiB
    // 2 connection: [2MB, 10MB)
    public static final long TWO_CONNECTION_UPPER_LIMIT = 10 * 1024 * 1024; // 10MiB
    // 3 connection: [10MB, 50MB)
    public static final long THREE_CONNECTION_UPPER_LIMIT = 50 * 1024 * 1024; // 50MiB
    // 4 connection: [50MB, 100MB)
    public static final long FOUR_CONNECTION_UPPER_LIMIT = 100 * 1024 * 1024; // 100MiB

}
