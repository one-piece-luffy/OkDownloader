package com.baofu.downloader.database;

import android.annotation.SuppressLint;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.text.TextUtils;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.TypeReference;
import com.baofu.downloader.model.VideoTaskItem;
import com.baofu.downloader.utils.LogUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

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
                insertVideoDownloadInfo(db, item);
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
            updateDownloadProgressInfo(db, item);
        } else {
            insertVideoDownloadInfo(db, item);
        }
    }

    public void markDownloadInfoPauseEvent(VideoTaskItem item) {
        SQLiteDatabase db = mSQLiteHelper.getWritableDatabase();
        if (db == null) {
            return;
        }
        if (item.isInDatabase() || isTaskInfoExistInTable(db, item)) {
            updateDownloadProgressInfo(db, item);
        } else {
            insertVideoDownloadInfo(db, item);
        }
    }

    public void markDownloadInfoErrorEvent(VideoTaskItem item) {
        SQLiteDatabase db = mSQLiteHelper.getWritableDatabase();
        if (db == null) {
            return;
        }
        if (item.isInDatabase() || isTaskInfoExistInTable(db, item)) {
            updateDownloadProgressInfo(db, item);
        } else {
            insertVideoDownloadInfo(db, item);
        }
    }

    public void updateDownloadProgressInfo(SQLiteDatabase db, VideoTaskItem item) {
        if (db == null) {
            return;
        }
        db.beginTransaction();
        try {
            ContentValues values = new ContentValues();
            values.put(VideoDownloadSQLiteHelper.Columns.MIME_TYPE, item.getMimeType());
            values.put(VideoDownloadSQLiteHelper.Columns.TASK_STATE, item.getTaskState());
            values.put(VideoDownloadSQLiteHelper.Columns.VIDEO_TYPE, item.getVideoType());
            values.put(VideoDownloadSQLiteHelper.Columns.PERCENT, item.getPercent());
            values.put(VideoDownloadSQLiteHelper.Columns.CACHED_LENGTH, item.getDownloadSize());
            values.put(VideoDownloadSQLiteHelper.Columns.TOTAL_LENGTH, item.getTotalSize());
            values.put(VideoDownloadSQLiteHelper.Columns.ESTIMATE_SIZE, item.estimateSize);
            values.put(VideoDownloadSQLiteHelper.Columns.CACHED_TS, item.getCurTs());
            values.put(VideoDownloadSQLiteHelper.Columns.TOTAL_TS, item.getTotalTs());
            values.put(VideoDownloadSQLiteHelper.Columns.COMPLETED, item.isCompleted());
            values.put(VideoDownloadSQLiteHelper.Columns.FILE_NAME, item.getFileName());
            values.put(VideoDownloadSQLiteHelper.Columns.FILE_PATH, item.getFilePath());
            values.put(VideoDownloadSQLiteHelper.Columns.M3U8_FILE_PATH, item.mM3u8FilePath);
            values.put(VideoDownloadSQLiteHelper.Columns.COVER_URL, item.getCoverUrl());
            values.put(VideoDownloadSQLiteHelper.Columns.COVER_PATH, item.getCoverPath());
            values.put(VideoDownloadSQLiteHelper.Columns.NAME, item.mName);
            values.put(VideoDownloadSQLiteHelper.Columns.DOWNLOAD_GROUP, item.downloadGroup);
            values.put(VideoDownloadSQLiteHelper.Columns.FILE_HASH, item.mFileHash);
            values.put(VideoDownloadSQLiteHelper.Columns.SORT, item.sort);
            values.put(VideoDownloadSQLiteHelper.Columns.PRIVATE_FILE, item.privateFile+"");
            values.put(VideoDownloadSQLiteHelper.Columns.SPEED, item.getSpeed());
            values.put(VideoDownloadSQLiteHelper.Columns.SOURCE_URL, item.sourceUrl);
            values.put(VideoDownloadSQLiteHelper.Columns.SUFFIX, item.suffix);
            values.put(VideoDownloadSQLiteHelper.Columns.VIDEO_LENGTH, item.videoLength);
            values.put(VideoDownloadSQLiteHelper.Columns.NEWFILE, item.newFile);
            values.put(VideoDownloadSQLiteHelper.Columns.HEADER, JSON.toJSONString(item.header));
            values.put(VideoDownloadSQLiteHelper.Columns.UPDATE_TIME, item.getLastUpdateTime()+"");
            values.put(VideoDownloadSQLiteHelper.Columns.GROUP_ID, item.groupId);
            values.put(VideoDownloadSQLiteHelper.Columns.NOTIFICATION_ID, item.notificationId);
            String whereClause = VideoDownloadSQLiteHelper.Columns.VIDEO_URL + " = ?";
            String[] whereArgs = {item.getUrl()};
            db.update(VideoDownloadSQLiteHelper.TABLE_VIDEO_DOWNLOAD_INFO, values, whereClause, whereArgs);
            db.setTransactionSuccessful();
        } catch (Exception e) {
            LogUtils.w(TAG, "updateVideoDownloadInfo failed, exception = " + e.getMessage());
        } finally {
            db.endTransaction();
        }
    }

    private void insertVideoDownloadInfo(SQLiteDatabase db, VideoTaskItem item) {
        if (db == null) {
            return;
        }
        db.beginTransaction();
        try {
            ContentValues values = new ContentValues();
            values.put(VideoDownloadSQLiteHelper.Columns.VIDEO_URL, item.getUrl());
            values.put(VideoDownloadSQLiteHelper.Columns.MIME_TYPE, item.getMimeType());
            values.put(VideoDownloadSQLiteHelper.Columns.FILE_NAME, item.getFileName());
            values.put(VideoDownloadSQLiteHelper.Columns.DOWNLOAD_TIME, item.getDownloadCreateTime());
            values.put(VideoDownloadSQLiteHelper.Columns.PERCENT, item.getPercent());
            values.put(VideoDownloadSQLiteHelper.Columns.TASK_STATE, item.getTaskState());
            values.put(VideoDownloadSQLiteHelper.Columns.VIDEO_TYPE, item.getVideoType());
            values.put(VideoDownloadSQLiteHelper.Columns.CACHED_LENGTH, item.getDownloadSize());
            values.put(VideoDownloadSQLiteHelper.Columns.TOTAL_LENGTH, item.getTotalSize());
            values.put(VideoDownloadSQLiteHelper.Columns.ESTIMATE_SIZE, item.estimateSize);
            values.put(VideoDownloadSQLiteHelper.Columns.CACHED_TS, item.getCurTs());
            values.put(VideoDownloadSQLiteHelper.Columns.TOTAL_TS, item.getTotalTs());
            values.put(VideoDownloadSQLiteHelper.Columns.COMPLETED, item.isCompleted());
            values.put(VideoDownloadSQLiteHelper.Columns.COVER_URL, item.getCoverUrl());
            values.put(VideoDownloadSQLiteHelper.Columns.COVER_PATH, item.getCoverPath());
            values.put(VideoDownloadSQLiteHelper.Columns.NAME, item.mName);
            values.put(VideoDownloadSQLiteHelper.Columns.DOWNLOAD_GROUP, item.downloadGroup);
            values.put(VideoDownloadSQLiteHelper.Columns.SORT, item.sort);
            values.put(VideoDownloadSQLiteHelper.Columns.FILE_HASH, item.mFileHash);
            values.put(VideoDownloadSQLiteHelper.Columns.PRIVATE_FILE, item.privateFile+"");
            values.put(VideoDownloadSQLiteHelper.Columns.SPEED, item.getSpeed());
            values.put(VideoDownloadSQLiteHelper.Columns.SOURCE_URL, item.sourceUrl);
            values.put(VideoDownloadSQLiteHelper.Columns.QUALITY, item.quality);
            values.put(VideoDownloadSQLiteHelper.Columns.SUFFIX, item.suffix);
            values.put(VideoDownloadSQLiteHelper.Columns.VIDEO_LENGTH, item.videoLength);
            values.put(VideoDownloadSQLiteHelper.Columns.NEWFILE, item.newFile);
            values.put(VideoDownloadSQLiteHelper.Columns.HEADER, JSON.toJSONString(item.header));
            values.put(VideoDownloadSQLiteHelper.Columns.UPDATE_TIME, item.getLastUpdateTime()+"");
            values.put(VideoDownloadSQLiteHelper.Columns.GROUP_ID, item.groupId);
            values.put(VideoDownloadSQLiteHelper.Columns.NOTIFICATION_ID, item.notificationId);
            long id=db.insert(VideoDownloadSQLiteHelper.TABLE_VIDEO_DOWNLOAD_INFO, null, values);
            try {
                item.id = (int) id;
            } catch (Exception e) {
                e.printStackTrace();
            }
            db.setTransactionSuccessful();
        } catch (Exception e) {
            LogUtils.w(TAG, "insertVideoDownloadInfo failed, exception = " + e.getMessage());
        } finally {
            db.endTransaction();
            item.setIsInDatabase(true);
        }
    }

    private boolean isTaskInfoExistInTable(SQLiteDatabase db,
                                           VideoTaskItem item) {
        if (db == null)
            return false;
        Cursor cursor = null;
        try {
            String selection = VideoDownloadSQLiteHelper.Columns.VIDEO_URL + " = ?";
            String[] selectionArgs = {item.getUrl() + ""};
            cursor = db.query(VideoDownloadSQLiteHelper.TABLE_VIDEO_DOWNLOAD_INFO, null,
                            selection, selectionArgs, null, null, null, null);
            if (cursor != null && cursor.moveToFirst() && cursor.getLong(0) > 0) {
                return true;
            } else {
                return false;
            }
        } catch (Exception e) {
            LogUtils.w(TAG, "isTaskInfoExistInTable query failed, exception = " + e.getMessage());
            return false;
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }
    }

    /**
     * 分页获取下载数据
     *
     * @param selection 条件  eg:VideoDownloadSQLiteHelper.Columns.COMPLETED+"=?"
     * @param selectionArgs 条件值 [1]
     * @param orderby 排序  eg: VideoDownloadSQLiteHelper.Columns._ID + " DESC "
     * @param offset 从第几条开始
     * @param limit 每页几条数据，-1表示全部
     */
    @SuppressLint("Range")
    public List<VideoTaskItem> getDownloadInfos(String selection, String[] selectionArgs, String orderby, int offset, int limit) {
        SQLiteDatabase db =null;
        try{
            db = mSQLiteHelper.getReadableDatabase();
        }catch (Exception e){
            e.printStackTrace();
        }
        if (db == null) {
            return null;
        }
        List<VideoTaskItem> items = new ArrayList<>();
        if (TextUtils.isEmpty(orderby)) {
            orderby = VideoDownloadSQLiteHelper.Columns._ID + " DESC ";
        }
        String limitStr = null;
        if (limit > -1) {
            limitStr = offset + "," + limit;
        }
        Cursor cursor = null;
        try {
            cursor = db.query(VideoDownloadSQLiteHelper.TABLE_VIDEO_DOWNLOAD_INFO,
                    null, selection, selectionArgs, null, null, orderby, limitStr);
            if (cursor != null && cursor.moveToFirst()) {
                do {
                    String url = cursor.getString(cursor.getColumnIndex(VideoDownloadSQLiteHelper.Columns.VIDEO_URL));

                    VideoTaskItem item = new VideoTaskItem(url);
                    item.groupId=cursor.getString(cursor.getColumnIndex(VideoDownloadSQLiteHelper.Columns.GROUP_ID));
                    item.id = cursor.getInt(cursor.getColumnIndex(VideoDownloadSQLiteHelper.Columns._ID));
                    item.notificationId = cursor.getInt(cursor.getColumnIndex(VideoDownloadSQLiteHelper.Columns.NOTIFICATION_ID));
                    item.setMimeType(cursor.getString(cursor.getColumnIndex(VideoDownloadSQLiteHelper.Columns.MIME_TYPE)));
                    item.setDownloadCreateTime(cursor.getLong(cursor.getColumnIndex(VideoDownloadSQLiteHelper.Columns.DOWNLOAD_TIME)));
                    item.setPercent(cursor.getFloat(cursor.getColumnIndex(VideoDownloadSQLiteHelper.Columns.PERCENT)));
                    item.setTaskState(cursor.getInt(cursor.getColumnIndex(VideoDownloadSQLiteHelper.Columns.TASK_STATE)));
                    item.setVideoType(cursor.getInt(cursor.getColumnIndex(VideoDownloadSQLiteHelper.Columns.VIDEO_TYPE)));
                    item.setDownloadSize(cursor.getLong(cursor.getColumnIndex(VideoDownloadSQLiteHelper.Columns.CACHED_LENGTH)));
                    item.setTotalSize(cursor.getLong(cursor.getColumnIndex(VideoDownloadSQLiteHelper.Columns.TOTAL_LENGTH)));
                    item.setCurTs(cursor.getInt(cursor.getColumnIndex(VideoDownloadSQLiteHelper.Columns.CACHED_TS)));
                    item.setTotalTs(cursor.getInt(cursor.getColumnIndex(VideoDownloadSQLiteHelper.Columns.TOTAL_TS)));
                    item.setIsCompleted(cursor.getInt(cursor.getColumnIndex(VideoDownloadSQLiteHelper.Columns.COMPLETED)) == 1 ? true : false);
                    item.setFileName(cursor.getString(cursor.getColumnIndex(VideoDownloadSQLiteHelper.Columns.FILE_NAME)));
                    item.setFilePath(cursor.getString(cursor.getColumnIndex(VideoDownloadSQLiteHelper.Columns.FILE_PATH)));
                    item.mM3u8FilePath=cursor.getString(cursor.getColumnIndex(VideoDownloadSQLiteHelper.Columns.M3U8_FILE_PATH));
                    item.setCoverUrl(cursor.getString(cursor.getColumnIndex(VideoDownloadSQLiteHelper.Columns.COVER_URL)));
                    item.setCoverPath(cursor.getString(cursor.getColumnIndex(VideoDownloadSQLiteHelper.Columns.COVER_PATH)));
                    item.mName=cursor.getString(cursor.getColumnIndex(VideoDownloadSQLiteHelper.Columns.NAME));
                    item.downloadGroup=cursor.getString(cursor.getColumnIndex(VideoDownloadSQLiteHelper.Columns.DOWNLOAD_GROUP));
                    item.sort=cursor.getInt(cursor.getColumnIndex(VideoDownloadSQLiteHelper.Columns.SORT));
                    item.mFileHash=cursor.getString(cursor.getColumnIndex(VideoDownloadSQLiteHelper.Columns.FILE_HASH));
                    String pf=cursor.getString(cursor.getColumnIndex(VideoDownloadSQLiteHelper.Columns.PRIVATE_FILE));
                    item.privateFile= "true".equals(pf);
                    item.sourceUrl=cursor.getString(cursor.getColumnIndex(VideoDownloadSQLiteHelper.Columns.SOURCE_URL));
                    item.quality=cursor.getString(cursor.getColumnIndex(VideoDownloadSQLiteHelper.Columns.QUALITY));
                    item.suffix=cursor.getString(cursor.getColumnIndex(VideoDownloadSQLiteHelper.Columns.SUFFIX));
                    item.videoLength=cursor.getLong(cursor.getColumnIndex(VideoDownloadSQLiteHelper.Columns.VIDEO_LENGTH));
                    item.newFile=cursor.getInt(cursor.getColumnIndex(VideoDownloadSQLiteHelper.Columns.NEWFILE));
                    item.estimateSize=(cursor.getLong(cursor.getColumnIndex(VideoDownloadSQLiteHelper.Columns.ESTIMATE_SIZE)));
                    String speed = cursor.getString(cursor.getColumnIndex(VideoDownloadSQLiteHelper.Columns.SPEED));
                    try {
                        item.setSpeed(Float.parseFloat(speed));
                    }catch (Exception e){
                        e.printStackTrace();
                    }
                    String time=cursor.getString(cursor.getColumnIndex(VideoDownloadSQLiteHelper.Columns.UPDATE_TIME));
                    try {
                        item.setLastUpdateTime(Long.parseLong(time));
                    }catch (Exception e){
                        e.printStackTrace();
                    }

                    String header=cursor.getString(cursor.getColumnIndex(VideoDownloadSQLiteHelper.Columns.HEADER));

                    Map<String,String> result=null;
                    if(!TextUtils.isEmpty(header)){
                        try {
                            result = JSON.parseObject(header, new TypeReference<Map<String,String>>() {
                            });
                            item.header=result;
                        } catch (Exception e) {
                        }
                    }

//                    if (item.isRunningTask() && Math.abs(item.getSpeed()) < 0.0001f) {
//                        item.setTaskState(VideoTaskState.PAUSE);
//                    }
                    items.add(item);
                } while (cursor.moveToNext());
            }
        } catch (Exception e) {
            LogUtils.w(TAG, "getDownloadInfos failed, exception = " + e.getMessage());
            return null;
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }
        return items;
    }


    public void deleteAllDownloadInfos() {
        SQLiteDatabase db = mSQLiteHelper.getWritableDatabase();
        if (db == null) {
            return;
        }
        db.beginTransaction();
        try {
            db.delete(VideoDownloadSQLiteHelper.TABLE_VIDEO_DOWNLOAD_INFO, null, null);
            db.setTransactionSuccessful();
        } catch (Exception e) {
            LogUtils.w(TAG, "deleteAllDownloadInfos failed, exception = " + e.getMessage());
        } finally {
            db.endTransaction();
        }
    }

    public void deleteDownloadItemByUrl(VideoTaskItem item) {
        SQLiteDatabase db = mSQLiteHelper.getWritableDatabase();
        if (db == null) {
            return;
        }
        db.beginTransaction();
        try {
            String whereClause = VideoDownloadSQLiteHelper.Columns.VIDEO_URL + " = ? ";
            String whereArgs[] = {item.getUrl()};
            db.delete(VideoDownloadSQLiteHelper.TABLE_VIDEO_DOWNLOAD_INFO, whereClause, whereArgs);
            db.setTransactionSuccessful();
        } catch (Exception e) {
            LogUtils.w(TAG, "deleteDownloadItemByUrl failed, exception = " + e.getMessage());
        } finally {
            db.endTransaction();
        }
    }

}
