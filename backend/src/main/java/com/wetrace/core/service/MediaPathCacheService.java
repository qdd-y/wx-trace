package com.wetrace.core.service;

import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class MediaPathCacheService {
    private final Map<String, String> md5ToPath = new ConcurrentHashMap<>();

    public void put(String md5, String path) {
        if (md5 == null || md5.isBlank() || path == null || path.isBlank()) {
            return;
        }
        md5ToPath.put(md5.toLowerCase(), path);
    }

    public String get(String md5) {
        if (md5 == null || md5.isBlank()) {
            return null;
        }
        return md5ToPath.get(md5.toLowerCase());
    }
}

