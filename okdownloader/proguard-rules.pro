# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# If your project uses WebView with JS, uncomment the following
# and specify the fully qualified class name to the JavaScript interface
# class:
#-keepclassmembers class fqcn.of.javascript.interface.for.webview {
#   public *;
#}

# Uncomment this to preserve the line number information for
# debugging stack traces.
#-keepattributes SourceFile,LineNumberTable

# If you keep the line number information, uncomment this to
# hide the original source file name.
#-renamesourcefileattribute SourceFile
-keep class com.baofu.downloader.rules.**{*;}
-keep class com.baofu.downloader.listener.**{*;}
-keep class com.baofu.downloader.model.**{*;}
-keep class com.baofu.downloader.utils.**{*;}
-keep class com.baofu.downloader.database.**{*;}
-keep class com.baofu.downloader.m3u8.**{*;}
-keep class com.baofu.downloader.common.DownloadMode{*;}
-keep class com.baofu.downloader.common.VideoDownloadConstants{*;}
-keep class com.arthenica.ffmpegkit.FFmpegKitConfig {
    native <methods>;
    void log(long, int, byte[]);
    void statistics(long, int, float, float, long , double, double, double);
    int safOpen(int);
    int safClose(int);
}

-keep class com.arthenica.ffmpegkit.AbiDetect {
    native <methods>;
}


