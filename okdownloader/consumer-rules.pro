#-repackageclasses androidx.core
#-keep class com.baofu.downloader.rules.**{*;}
#-keep class com.baofu.downloader.listener.**{*;}
#-keep class com.baofu.downloader.model.**{*;}
#-keep class com.baofu.downloader.model.**{*;}
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
