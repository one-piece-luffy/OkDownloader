package com.baofu.downloader.database;

import android.content.Context;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

public class VideoDownloadSQLiteHelper extends SQLiteOpenHelper {

    private static final String DATABASE_NAME = "video_download_info.db";
    private static final int DATABASE_VERSION = 1;

    public static final String TABLE_VIDEO_DOWNLOAD_INFO = "video_download_info";

    public static class Columns {
        public static final String _ID = "_id";
        public static final String VIDEO_URL = "video_url";
        public static final String MIME_TYPE = "mime_type";
        public static final String DOWNLOAD_TIME = "download_time";
        public static final String PERCENT = "percent";
        public static final String TASK_STATE = "task_state";
        public static final String VIDEO_TYPE = "video_type";
        public static final String CACHED_LENGTH = "cached_length";
        public static final String TOTAL_LENGTH = "total_length";
        public static final String CACHED_TS = "cached_ts";
        public static final String TOTAL_TS = "total_ts";
        public static final String COMPLETED = "completed";
        public static final String FILE_NAME = "file_name";
        public static final String FILE_PATH = "file_path";
        public static final String M3U8_FILE_PATH = "m3u8_file_path";
        public static final String COVER_URL = "cover_url";
        public static final String COVER_PATH = "cover_path";
        public static final String NAME = "name";
        public static final String DOWNLOAD_GROUP = "download_group";
        public static final String SORT = "_sort";
        public static final String FILE_HASH = "file_hash";
        public static final String PRIVATE_FILE = "private_file";
        public static final String SOURCE_URL = "source_url";
        public static final String SUFFIX = "suffix";
        public static final String VIDEO_LENGTH = "video_length";
        public static final String NEWFILE = "newfile";
        public static final String HEADER = "header";
        public static final String UPDATE_TIME = "update_time";
        public static final String ESTIMATE_SIZE = "estimate_Size";
        public static final String SPEED = "speed";
        public static final String QUALITY = "quality";
        public static final String GROUP_ID = "group_id";
        public static final String NOTIFICATION_ID = "notification_id";
    }

    public VideoDownloadSQLiteHelper(Context context) {
        super(context, DATABASE_NAME, null, DATABASE_VERSION);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        createVideoDownloadInfoTable(db);
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
//        db.execSQL("ALTER TABLE " + TABLE_VIDEO_DOWNLOAD_INFO + " ADD COLUMN " + Columns.FILE_HASH + " TEXT");
//        db.execSQL("ALTER TABLE " + TABLE_VIDEO_DOWNLOAD_INFO + " ADD COLUMN " + Columns.SORT + " BIGINT");

//        if (oldVersion == 2) {
//            db.execSQL("ALTER TABLE " + TABLE_VIDEO_DOWNLOAD_INFO + " ADD COLUMN " + Columns.NOTIFICATION_ID + " INTEGER");
//        }

    }

    @Override
    public void onDowngrade(SQLiteDatabase db, int oldVersion, int newVersion) {

    }

    private void createVideoDownloadInfoTable(SQLiteDatabase db) {
        db.execSQL("DROP TABLE IF EXISTS " + TABLE_VIDEO_DOWNLOAD_INFO);
        db.execSQL("CREATE TABLE " + TABLE_VIDEO_DOWNLOAD_INFO + "("
                + Columns._ID + " INTEGER PRIMARY KEY AUTOINCREMENT, "
                + Columns.VIDEO_URL + " TEXT, "
                + Columns.MIME_TYPE + " TEXT, "
                + Columns.DOWNLOAD_TIME + " BIGINT, "
                + Columns.PERCENT + " REAL, "
                + Columns.TASK_STATE + " TEXT, "
                + Columns.VIDEO_TYPE + " TINYINT, "
                + Columns.CACHED_LENGTH + " BIGINT, "
                + Columns.TOTAL_LENGTH + " BIGINT, "
                + Columns.CACHED_TS + " INTEGER, "
                + Columns.TOTAL_TS + " INTEGER , "
                + Columns.COMPLETED + " TINYINT, "
                + Columns.FILE_NAME + " TEXT Default 0, "
                + Columns.FILE_PATH + " TEXT, "
                + Columns.M3U8_FILE_PATH + " TEXT, "
                + Columns.COVER_URL + " TEXT, "
                + Columns.NAME + " TEXT, "
                + Columns.DOWNLOAD_GROUP + " TEXT, "
                + Columns.SORT + " BIGINT, "
                + Columns.FILE_HASH + " TEXT, "
                + Columns.PRIVATE_FILE + " TEXT, "
                + Columns.SPEED + " TEXT, "
                + Columns.SUFFIX + " TEXT, "
                + Columns.NEWFILE + " TEXT, "
                + Columns.SOURCE_URL + " TEXT, "
                + Columns.QUALITY + " TEXT, "
                + Columns.HEADER + " TEXT, "
                + Columns.VIDEO_LENGTH + " BIGINT, "
                + Columns.ESTIMATE_SIZE + " BIGINT, "
                + Columns.UPDATE_TIME+ " TEXT, "
                + Columns.GROUP_ID+ " TEXT, "
                + Columns.NOTIFICATION_ID + " INTEGER, "
                + Columns.COVER_PATH + " TEXT);");
    }


}
