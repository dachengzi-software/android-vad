package com.konovalov.vad.example.utils;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class GlobalExecutor {
    // 固定 4 线程，可根据需求调整
    public static final ExecutorService EXECUTOR = Executors.newCachedThreadPool();
}
