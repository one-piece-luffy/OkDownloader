package com.baofu.downloader.database.dao;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;
import androidx.room.Update;


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

    @Query("select * from VideoTaskItem ORDER BY id ")
    List<VideoTaskItem> getAll();

    @Query("select * from VideoTaskItem WHERE mUrl=:url")
    public VideoTaskItem getItemByUrl(String url);
}
