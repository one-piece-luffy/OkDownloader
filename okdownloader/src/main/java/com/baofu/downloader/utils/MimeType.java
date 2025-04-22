package com.baofu.downloader.utils;

import java.util.HashMap;

public class MimeType {
    public static final String STREAM="application/octet-stream";
    public static HashMap<String, String> map = new HashMap<String, String>() {
        {
            put("video/3gpp", ".3gp");
            put("application/vnd.android.package-archive", ".apk");
            put("video/x-ms-asf", ".asf");
            put("video/x-msvideo", ".avi");
//            put(STREAM, ".bin");
            put("image/bmp", ".bmp");
            put("application/msword", ".doc");
            put("application/vnd.openxmlformats-officedocument.wordprocessingml.document", ".docx");
            put("application/vnd.ms-excel", ".xls");
            put("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", ".xlsx");
            put("image/gif", ".gif");
            put("application/x-gtar", ".gtar");
            put("application/x-gzip", ".gz");
//            put("text/plain", ".h");
            put("text/html", ".html");
            put("application/java-archive", ".jar");
//            put("text/plain", ".java");
//            put(".jpeg", "image/jpeg");
            put("image/jpeg", ".jpg");
            put("image/webp", ".webp");
            put("application/x-javascript", ".js");
//            put("text/plain", ".log");

            put("audio/mp4a-latm", ".m4a");
            put("video/vnd.mpegurl", ".m4u");
            put("video/x-m4v", ".m4v");
            put("video/quicktime", ".mov");
            put("audio/x-mpeg", ".mp3");
            put("video/mp4", ".mp4");
            put("application/vnd.mpohun.certificate", ".mpc");
            put("video/mpeg", ".mpeg");
            put("audio/mpeg", ".mp3");
            put("application/vnd.ms-outlook", ".msg");
            put("audio/ogg", ".ogg");
            put("application/pdf", ".pdf");
            put("image/png", ".png");
            put("application/vnd.ms-powerpoint", ".ppt");
            put("application/vnd.openxmlformats-officedocument.presentationml.presentation", ".pptx");
//            put("text/plain", ".prop");
//            put("text/plain", ".rc");
            put("audio/x-pn-realaudio", ".rmvb");
            put("application/rtf", ".rtf");
//            put("text/plain", ".sh");
            put("application/x-tar", ".tar");
            put("application/x-compressed", ".tgz");
            put("text/plain", ".txt");
            put("audio/x-wav", ".wav");
            put("audio/x-ms-wma", ".wma");
            put("audio/x-ms-wmv", ".wmv");
            put("application/vnd.ms-works", ".wps");
//            put("text/plain", ".xml");
            put("application/x-compress", ".zip");
            put("application/x-zip-compressed", ".zip");
            put("application/vnd.apple.mpegURL", ".m3u8");
            put("vnd.apple.mpegURL", ".m3u8");
            put("audio/x-mpegurl", ".m3u8");
            put("application/x-mpegURL", ".m3u8");
            put("applicationnd.apple.mpegurl", ".m3u8");
            put("*/*", "");
        }
    };

}
