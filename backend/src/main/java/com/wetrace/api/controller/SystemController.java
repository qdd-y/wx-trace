package com.wetrace.api.controller;

import com.wetrace.core.service.KeyService;
import com.wetrace.nativelib.imagekey.ImageKeyExtractor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.CompletableFuture;

/**
 * 系统 API — 密钥提取与解密
 * 对应原 Go 版本 web/api/handler_system.go + handler_wxkey.go + handler_decrypt.go
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/system")
@RequiredArgsConstructor
public class SystemController {

    private final KeyService keyService;

    // ==================== 密钥获取 ====================

    /**
     * 获取数据库密钥（通过 SSE 实时推送进度）
     * GET /api/v1/system/wxkey/db
     */
    @GetMapping(value = "/wxkey/db", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter getDbKey(@RequestParam(required = false) String wechatPath) {
        SseEmitter emitter = new SseEmitter(180_000L);

        CompletableFuture.runAsync(() -> {
            try {
                String key = keyService.getDbKey(wechatPath, msg -> {
                    try { emitter.send(SseEmitter.event().name("status").data(msg)); } catch (Exception ignored) {}
                });
                emitter.send(SseEmitter.event().name("key").data(key));
                emitter.complete();
            } catch (Exception e) {
                log.error("获取数据库密钥失败", e);
                try { emitter.send(SseEmitter.event().name("error").data(e.getMessage())); } catch (Exception ignored) {}
                emitter.complete();
            }
        });

        return emitter;
    }

    /**
     * 获取图片密钥（内存扫描）
     * GET /api/v1/system/wxkey/image
     */
    @GetMapping("/wxkey/image")
    public ResponseEntity<?> getImageKey(@RequestParam(required = false) String dataPath) {
        int pid = keyService.getWeChatPid();
        if (pid == 0) {
            return ResponseEntity.badRequest().body(Map.of("error", "微信未运行"));
        }

        ImageKeyExtractor.ImageKeyResult result = keyService.getImageKey(pid, dataPath, msg -> {});

        if (result.success) {
            return ResponseEntity.ok(Map.of(
                "xorKey", String.format("0x%02X", result.xorKey),
                "aesKey", result.aesKey,
                "success", true
            ));
        }
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", result.error));
    }

    // ==================== 解密 ====================

    /**
     * 解密数据库
     * POST /api/v1/system/decrypt
     * Body: { "srcPath": "...", "dbKey": "..." }
     * dbKey 可选：为空时按 WECHAT_DB_KEY（内存/系统属性/.env）回退
     */
    @PostMapping(value = "/decrypt", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter decryptDb(@RequestBody Map<String, String> body) {
        String srcPath = body.get("srcPath");
        if (srcPath != null) {
            srcPath = srcPath.trim();
        }
        String dbKey = body.get("dbKey");

        SseEmitter emitter = new SseEmitter(600_000L);
        if (srcPath == null || srcPath.isBlank()) {
            try {
                emitter.send(SseEmitter.event().name("error").data("srcPath 不能为空，请在界面填写微信数据路径"));
            } catch (Exception ignored) {}
            emitter.complete();
            return emitter;
        }

        String finalSrcPath = srcPath;
        CompletableFuture.runAsync(() -> {
            try {
                emitter.send(SseEmitter.event().name("status").data("开始解密..."));
                int count = keyService.decryptAll(finalSrcPath, dbKey, msg -> {
                    try { emitter.send(SseEmitter.event().name("status").data(msg)); } catch (Exception ignored) {}
                });
                emitter.send(SseEmitter.event().name("done").data("{\"decrypted\":" + count + "}"));
                emitter.complete();
            } catch (Exception e) {
                log.error("解密失败", e);
                try { emitter.send(SseEmitter.event().name("error").data(e.getMessage())); } catch (Exception ignored) {}
                emitter.complete();
            }
        });

        return emitter;
    }

    // ==================== 路径探测 ====================

    @GetMapping("/detect/wechat_path")
    public ResponseEntity<?> detectWeChatPath() {
        String path = keyService.findWeChatPath();
        if (path != null) return ResponseEntity.ok(Map.of("path", path));
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "未找到微信安装路径"));
    }

    @GetMapping("/detect/db_path")
    public ResponseEntity<?> detectDbPath() {
        String home = System.getProperty("user.home", "");
        LinkedHashSet<Path> candidates = new LinkedHashSet<>();

        String envDataPath = System.getenv("WXKEY_WECHAT_DATA_PATH");
        if (envDataPath != null && !envDataPath.isBlank()) {
            candidates.add(Paths.get(envDataPath));
        }
        String propDataPath = System.getProperty("WXKEY_WECHAT_DATA_PATH");
        if (propDataPath != null && !propDataPath.isBlank()) {
            candidates.add(Paths.get(propDataPath));
        }

        candidates.add(Paths.get(home, "Documents", "xwechat_files"));
        candidates.add(Paths.get("D:\\xwechat_files"));
        candidates.add(Paths.get("C:\\xwechat_files"));

        List<String> searched = new ArrayList<>();
        for (Path candidate : candidates) {
            Path root = candidate.toAbsolutePath().normalize();
            searched.add(root.toString());
            if (!java.nio.file.Files.isDirectory(root)) {
                continue;
            }

            // 兼容直接传账号目录（目录下就有 db_storage）
            Path directDbStorage = root.resolve("db_storage");
            if (java.nio.file.Files.isDirectory(directDbStorage)) {
                return ResponseEntity.ok(Map.of("path", root.toString(), "account", root.getFileName().toString()));
            }

            java.io.File[] accounts = root.toFile().listFiles(java.io.File::isDirectory);
            if (accounts == null) {
                continue;
            }
            for (java.io.File acc : accounts) {
                java.io.File dbStorage = new java.io.File(acc, "db_storage");
                if (dbStorage.exists() && dbStorage.isDirectory()) {
                    return ResponseEntity.ok(Map.of("path", acc.getAbsolutePath(), "account", acc.getName()));
                }
            }
        }
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(
            Map.of("error", "未找到微信数据目录", "searched", searched));
    }

    // ==================== 系统状态 ====================

    @GetMapping("/status")
    public ResponseEntity<?> getStatus() {
        return ResponseEntity.ok(Map.of(
            "wechatRunning", keyService.isWeChatRunning(),
            "wechatPid", keyService.getWeChatPid(),
            "dbKeySet", keyService.getLastDbKey() != null,
            "dbKey", keyService.getLastDbKey() != null ? keyService.getLastDbKey() : "",
            "imageKeySet", keyService.getLastImageKey() != null,
            "imageKey", keyService.getLastImageKey() != null ? keyService.getLastImageKey() : "",
            "xorKey", keyService.getLastXorKey() != null ? keyService.getLastXorKey() : "",
            "workDir", "data"
        ));
    }
}
