package com.baofu.downloader.utils;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * 生成一个int类型的唯一ID
 */
public class UniqueIdGenerator {
    private static final AtomicInteger uniqueId = new AtomicInteger(1);

    public static int generateUniqueId() {
        uniqueId.incrementAndGet();
        return uniqueId.get();
    }
}
