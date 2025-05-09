package com.baofu.downloader.database.dao;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;
import androidx.room.RawQuery;
import androidx.room.Update;
import androidx.sqlite.db.SupportSQLiteQuery;


import com.baofu.downloader.model.VideoTaskItem;

import java.util.List;

@Dao
public interface VideoTaskItemDao {
    @Update
    public void update(VideoTaskItem item);

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void insert(VideoTaskItem item);

    @Query("DELETE FROM VideoTaskItem")
    void deleteAll();

    @Query("DELETE  FROM VideoTaskItem where mUrl=:url")
    void  deleteByUrl(String url);

    @Query("select * from VideoTaskItem ORDER BY createTime ")
    List<VideoTaskItem> getAll();

    @Query("select * from VideoTaskItem where mIsCompleted=1  ORDER BY createTime desc  limit :limit offset :offset")
    List<VideoTaskItem> getItemByPage(int offset, int limit);

    @RawQuery
    List<VideoTaskItem> getItemByQuery(SupportSQLiteQuery query);

    @Query("select * from VideoTaskItem where mIsCompleted!=1  ORDER BY createTime desc ")
    List<VideoTaskItem> getDownloadingItem();

    @Query("select * from VideoTaskItem WHERE mUrl=:url")
    VideoTaskItem getItemByUrl(String url);
}
