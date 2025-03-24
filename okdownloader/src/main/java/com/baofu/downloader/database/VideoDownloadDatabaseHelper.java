package com.baofu.downloader.database;

import android.annotation.SuppressLint;
import android.content.Context;
import android.database.sqlite.SQLiteDatabase;

import androidx.sqlite.db.SimpleSQLiteQuery;
import androidx.sqlite.db.SupportSQLiteQuery;

import com.baofu.downloader.model.VideoTaskItem;

import java.util.List;

public class VideoDownloadDatabaseHelper {

    private static final String TAG = "VideoDownloadDatabaseHelper";
    private VideoDownloadSQLiteHelper mSQLiteHelper;

    public VideoDownloadDatabaseHelper(Context context) {
        if (context == null) {
            return;
        }
        mSQLiteHelper = new VideoDownloadSQLiteHelper(context);
    }

    public void markDownloadInfoAddEvent(VideoTaskItem item) {
        SQLiteDatabase db = mSQLiteHelper.getWritableDatabase();
        if (db == null) {
            return;
        }
        synchronized (this) {
            if (item.isInDatabase() || isTaskInfoExistInTable(db, item)) {
            } else {
                insertVideoDownloadInfo( item);
            }
        }
    }

    /**
     * 更新数据库
     * @param item
     */
    public void markDownloadProgressInfoUpdateEvent(VideoTaskItem item) {
        SQLiteDatabase db = mSQLiteHelper.getWritableDatabase();
        if (db == null) {
            return;
        }
        if (item.isInDatabase() || isTaskInfoExistInTable(db, item)) {
            updateDownloadProgressInfo(item);
        } else {
            insertVideoDownloadInfo( item);
        }
    }


    public void updateDownloadProgressInfo( VideoTaskItem item) {
        OkDownloaderDatabase.getInstance().videoTaskItemDao().update(item);
    }

    private void insertVideoDownloadInfo( VideoTaskItem item) {
        OkDownloaderDatabase.getInstance().videoTaskItemDao().insert(item);
    }

    private boolean isTaskInfoExistInTable(SQLiteDatabase db,
                                           VideoTaskItem item) {
        VideoTaskItem videoTaskItem = OkDownloaderDatabase.getInstance().videoTaskItemDao().getItemByUrl(item.mUrl);
        if (videoTaskItem == null) {
            return false;
        } else {
            return true;
        }

    }

    /**
     * 分页获取下载数据
     *
     * @param offset 从第几条开始
     * @param limit 每页几条数据，
     */
    @SuppressLint("Range")
    public List<VideoTaskItem> getItemByPage(int offset, int limit) {
        List<VideoTaskItem> items = OkDownloaderDatabase.getInstance().videoTaskItemDao().getItemByPage(offset,limit);
        return items;
    }

    @SuppressLint("Range")
    public List<VideoTaskItem> getItemByQuery( String queryString) {
        SupportSQLiteQuery query = new SimpleSQLiteQuery(queryString);
        List<VideoTaskItem> items = OkDownloaderDatabase.getInstance().videoTaskItemDao().getItemByQuery(query);
        return items;
    }

    public List<VideoTaskItem> getAll( ) {
        List<VideoTaskItem> items = OkDownloaderDatabase.getInstance().videoTaskItemDao().getAll();
        return items;
    }
    public List<VideoTaskItem> getDownloadingItem( ) {
        List<VideoTaskItem> items = OkDownloaderDatabase.getInstance().videoTaskItemDao().getDownloadingItem();
        return items;
    }


    public void deleteAllDownloadInfos() {
       OkDownloaderDatabase.getInstance().videoTaskItemDao().deleteAll();
    }

    public void deleteDownloadItemByUrl(VideoTaskItem item) {
        OkDownloaderDatabase.getInstance().videoTaskItemDao().deleteByUrl(item.mUrl);
    }

}
