package com.baofu.downloader.database;

import androidx.room.Database;
import androidx.room.Room;
import androidx.room.RoomDatabase;

import com.baofu.downloader.database.dao.VideoTaskItemDao;
import com.baofu.downloader.model.VideoTaskItem;
import com.baofu.downloader.utils.ContextUtils;


@Database(entities = { VideoTaskItem.class },
        version = 1, exportSchema = false)
abstract public class OkDownloaderDatabase extends RoomDatabase {

    private static OkDownloaderDatabase instance;

    public static OkDownloaderDatabase getInstance() {
        if (instance == null) {
            instance = Room.databaseBuilder(ContextUtils.getApplicationContext(),
                            OkDownloaderDatabase.class,
                            "okdownloader.db" //数据库名称
                    )
                    .allowMainThreadQueries()
                    .fallbackToDestructiveMigration()
                    .build();
        }
        return instance;
    }

    public abstract VideoTaskItemDao videoTaskItemDao();



}
