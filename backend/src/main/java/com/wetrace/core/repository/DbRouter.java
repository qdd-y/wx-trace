package com.wetrace.core.repository;

import com.wetrace.core.service.KeyService;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.FileTime;
import java.time.*;
import java.util.*;
import java.util.regex.*;

/**
 * 数据库路由器
 *
 * 微信数据分散在多个 DB 文件中，按规则路由：
 *
 * Session/Contact: MsgMulti/msg_*.db / MicroMsg.db
 * Message 分片:    MsgMulti/msg_{md5(talker)}.db
 * Media (V4):     hardlink.db
 * Media (V3):     HardlinkImage*.db / Voice/*.db
 *
 * 对应 Go 版本 store/bind/bind.go
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DbRouter {

    private final KeyService keyService;
    private final SqlitePool pool;

    // 微信数据根目录（兼容 data/ 与 data/decrypted/）
    private String baseDir = Paths.get("data", "decrypted").toAbsolutePath().toString();

    public void setBaseDir(String baseDir) {
        this.baseDir = baseDir;
    }

    public String getBaseDir() {
        return baseDir;
    }

    // ==================== 分片路由 ====================

    /**
     * 根据时间和 talker 解析消息目标分片
     */
    public List<RouteResult> resolve(LocalDateTime start, LocalDateTime end, String talker) {
        List<RouteResult> results = new ArrayList<>();
        LinkedHashSet<String> shards = collectMessageShards(talker);
        for (String shard : shards) {
            results.add(new RouteResult(shard, talker, 0));
        }
        return results;
    }

    private LinkedHashSet<String> collectMessageShards(String talker) {
        LinkedHashSet<String> shards = new LinkedHashSet<>();
        for (Path root : candidateRoots()) {
            Path msgMultiDir = root.resolve("MsgMulti");

            if (talker != null && !talker.isEmpty()) {
                String md5 = md5Hex(talker);
                addIfExists(shards, msgMultiDir.resolve("msg_" + md5 + ".db"));
                addIfExists(shards, root.resolve("msg_" + md5 + ".db"));
            }

            // 旧版命名: MsgMulti/msg_*.db 或根目录 msg_*.db
            addByGlob(shards, msgMultiDir, "msg_*.db");
            addByGlob(shards, root, "msg_*.db");

            // 新版命名: message_*.db（对齐 Go 主链路）
            addByGlob(shards, root, "message*.db");
            addByGlob(shards, root.resolve("message"), "message*.db");
            addByGlob(shards, root.resolve("db_storage"), "message*.db");
            addByGlob(shards, root.resolve("db_storage").resolve("message"), "message*.db");

            // V3 命名
            addByGlob(shards, root.resolve("Msg"), "MSG*.db");
            addIfExists(shards, root.resolve("Msg").resolve("MSG.db"));
        }
        return shards;
    }

    private void addIfExists(Set<String> out, Path p) {
        if (p != null && Files.exists(p) && Files.isRegularFile(p)) {
            out.add(p.toAbsolutePath().normalize().toString());
        }
    }

    private void addByGlob(Set<String> out, Path dir, String glob) {
        if (dir == null || !Files.isDirectory(dir)) {
            return;
        }
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir, glob)) {
            for (Path p : stream) {
                if (Files.isRegularFile(p)) {
                    out.add(p.toAbsolutePath().normalize().toString());
                }
            }
        } catch (IOException ignored) {
        }
    }

    // ==================== Session DB ====================

    public String getSessionDBPath() {
        String sessionDb = findFirstExisting(
            "db_storage\\session\\session.db",
            "db_storage\\session.db",
            "session\\session.db",
            "session.db",
            "session\\Session.db",
            "Session.db"
        );
        if (sessionDb == null) {
            sessionDb = Paths.get(baseDir, "session.db").toString();
        }
        log.info("使用 session.db 路径: {}", sessionDb);
        return sessionDb;
    }

    private Path findLatestByGlob(Path dir, String glob) {
        if (dir == null || !Files.isDirectory(dir)) {
            return null;
        }
        Path latest = null;
        FileTime latestTime = null;
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir, glob)) {
            for (Path p : stream) {
                if (!Files.isRegularFile(p)) {
                    continue;
                }
                FileTime modified = Files.getLastModifiedTime(p);
                if (latest == null || modified.compareTo(latestTime) > 0) {
                    latest = p;
                    latestTime = modified;
                }
            }
        } catch (IOException ignored) {
        }
        return latest;
    }

    // ==================== Contact DB ====================

    public String getContactDBPath() {
        String contactDb = findFirstExisting(
            "db_storage\\contact\\contact.db",
            "db_storage\\contact.db",
            "db_storage\\MicroMsg.db",
            "contact\\contact.db",
            "contact.db",
            "contact\\MicroMsg.db",
            "MicroMsg.db",
            "contact\\Contact.db",
            "Contact.db"
        );
        return contactDb != null ? contactDb : Paths.get(baseDir, "contact.db").toString();
    }

    // ==================== Media DB ====================

    public String getMediaDBPath(MediaType type) {
        return switch (type) {
            case IMAGE, VIDEO, FILE -> {
                String hl = findFirstExisting(
                    "db_storage\\hardlink\\hardlink.db",
                    "db_storage\\hardlink.db",
                    "hardlink\\hardlink.db",
                    "hardlink.db",
                    "HardlinkImage.db",
                    "hardlink\\HardlinkImage.db"
                );
                if (hl != null) yield hl;
                yield Paths.get(baseDir, "hardlink.db").toString();
            }
            case VOICE -> {
                // 找第一个 voice 数据库
                for (Path base : candidateRoots()) {
                    Path voiceDir = base.resolve("Voice");
                    Path dbStorageVoiceDir = base.resolve("db_storage").resolve("Voice");
                    for (Path dir : List.of(voiceDir, dbStorageVoiceDir)) {
                        if (!Files.exists(dir)) continue;
                        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir, "*.db")) {
                            for (Path p : stream) {
                                yield p.toString();
                            }
                        } catch (IOException ignored) {}
                    }
                }
                String media = findFirstExisting(
                    "db_storage\\message\\media_0.db",
                    "db_storage\\media_0.db",
                    "db_storage\\Voice0.db",
                    "message\\media_0.db",
                    "media_0.db",
                    "Voice0.db"
                );
                yield media != null ? media : Paths.get(baseDir, "Voice0.db").toString();
            }
        };
    }

    public List<String> getAllDBPaths(MediaType type) {
        List<String> paths = new ArrayList<>();
        String pattern = switch (type) {
            case IMAGE -> "HardlinkImage*.db";
            case VIDEO -> "HardlinkVideo*.db";
            case VOICE -> "Voice*.db";
            case FILE -> "HardlinkFile*.db";
        };

        for (Path dir : candidateRoots()) {
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir, pattern)) {
                for (Path p : stream) {
                    paths.add(p.toString());
                }
            } catch (IOException ignored) {}
        }

        return paths;
    }

    private String findFirstExisting(String... relativePaths) {
        for (Path root : candidateRoots()) {
            for (String rel : relativePaths) {
                Path p = root.resolve(rel).normalize();
                if (Files.exists(p) && Files.isRegularFile(p)) {
                    return p.toString();
                }
            }
        }
        return null;
    }

    private List<Path> candidateRoots() {
        LinkedHashSet<Path> roots = new LinkedHashSet<>();
        roots.add(Paths.get(baseDir).toAbsolutePath().normalize());
        roots.add(Paths.get("data").toAbsolutePath().normalize());
        roots.add(Paths.get("data", "decrypted").toAbsolutePath().normalize());

        List<Path> existing = new ArrayList<>();
        for (Path p : roots) {
            if (Files.isDirectory(p)) {
                existing.add(p);
            }
        }
        return existing.isEmpty() ? new ArrayList<>(roots) : existing;
    }

    // ==================== 工具 ====================

    public String md5Hex(String input) {
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("MD5");
            byte[] digest = md.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) {
                sb.append(String.format("%02x", b & 0xff));
            }
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }

    // ==================== 结果类 ====================

    @Data
    @AllArgsConstructor
    public static class RouteResult {
        private String filePath;   // 数据库文件路径
        private String talker;    // 聊天对象
        private long talkerId;    // V3 数字 ID
    }

    public enum MediaType { IMAGE, VIDEO, VOICE, FILE }
}
