package com.wetrace.api.controller;

import com.wetrace.core.model.WeChatMedia;
import com.wetrace.core.repository.WeChatRepository;
import com.wetrace.core.service.MediaPathCacheService;
import com.wetrace.media.MediaDecryptService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

import java.io.*;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/** 媒体 API */
@Slf4j
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class MediaController {

    private final WeChatRepository repo;
    private final MediaDecryptService mediaService;
    private final MediaPathCacheService mediaPathCacheService;
    private static final HttpClient EMOJI_HTTP = HttpClient.newBuilder()
        .followRedirects(HttpClient.Redirect.ALWAYS)
        .build();

    // XOR 密钥（从 KeyService 注入，这里简化为全局保存）
    private static volatile int xorKey = -1;
    public static void setXorKey(int key) { xorKey = key; }

    /**
     * 获取媒体文件（图片/视频/文件）
     * GET /api/v1/media/:type/:key
     */
    @GetMapping("/media/{type}/{key}")
    public ResponseEntity<?> getMedia(@PathVariable String type,
                                      @PathVariable String key,
                                      @RequestParam(required = false) String path,
                                      @RequestParam(required = false, defaultValue = "0") String thumb) {
        try {
            WeChatMedia media = null;
            try {
                media = repo.getMedia(type, key);
            } catch (Exception e) {
                log.debug("从媒体库读取失败: type={} key={}", type, key, e);
            }

            boolean isThumb = "1".equals(thumb);
            Path filePath = resolveMediaFilePath(type, key, media, path, isThumb);
            if (filePath == null || !Files.exists(filePath)) {
                return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(java.util.Map.of("error", "媒体文件未找到"));
            }

            byte[] data = readMediaBytes(filePath, type);
            String contentType = detectContentType(data);
            if ("application/octet-stream".equals(contentType)) {
                String fromPath = Files.probeContentType(filePath);
                if (fromPath != null) contentType = fromPath;
            }

            return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(contentType))
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=" + (media != null ? media.getName() : key))
                .body(data);

        } catch (Exception e) {
            log.error("获取媒体失败: type={} key={}", type, key, e);
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(java.util.Map.of("error", "媒体文件未找到"));
        }
    }

    /** 图片列表 */
    @GetMapping("/media/images")
    public ResponseEntity<?> getImageList(
            @RequestParam(required = false) String sessionId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "50") int size) {
        // 占位：实际从数据库查询消息类型=3 的记录
        return ResponseEntity.ok(java.util.Map.of("total", 0, "list", java.util.List.of()));
    }

    /** 表情包 */
    @GetMapping("/media/emoji")
    public ResponseEntity<?> getEmoji(@RequestParam String url, @RequestParam String key) {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/146.0.0.0 Safari/537.36")
                .header("Accept", "image/avif,image/webp,image/apng,image/svg+xml,image/*,*/*;q=0.8")
                .GET()
                .build();
            HttpResponse<byte[]> upstream = EMOJI_HTTP.send(request, HttpResponse.BodyHandlers.ofByteArray());
            if (upstream.statusCode() < 200 || upstream.statusCode() >= 300) {
                log.warn("获取表情上游失败: status={} url={}", upstream.statusCode(), url);
                return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(java.util.Map.of("error", "上游表情资源请求失败"));
            }
            byte[] raw = upstream.body();
            byte[] data = tryDecryptEmoji(raw, key);
            if (!isImageBytes(data) && isImageBytes(raw)) {
                data = raw;
            }
            if (!isImageBytes(data)) {
                log.warn("表情数据不是图片: url={} rawLen={}", url, raw == null ? 0 : raw.length);
                return ResponseEntity.status(HttpStatus.NOT_FOUND).body(java.util.Map.of("error", "表情资源不可用"));
            }
            String contentType = detectContentType(data);
            return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(contentType))
                .body(data);
        } catch (Exception e) {
            log.warn("获取表情失败: {}", url, e);
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(java.util.Map.of("error", "获取表情失败"));
        }
    }

    /** 语音转文字 */
    @PostMapping("/media/voice/transcribe")
    public ResponseEntity<?> transcribeVoice(@RequestBody java.util.Map<String, String> body) {
        String key = body.get("key");
        // 占位：调用 TTS 模块
        return ResponseEntity.ok(java.util.Map.of("text", ""));
    }

    private Path resolveMediaFilePath(String type, String key, WeChatMedia media, String path, boolean isThumb) {
        List<Path> candidates = new ArrayList<>();
        if (media != null) {
            candidates.addAll(buildMediaCandidatesFromMetadata(type, key, media));
            if (media.getPath() != null && !media.getPath().isBlank()) {
                candidates.addAll(buildCandidatesFromRelativePath(media.getPath(), isThumb));
            }
        }

        if (path != null && !path.isBlank()) {
            candidates.addAll(buildCandidatesFromRelativePath(path, isThumb));
        }
        if ("image".equalsIgnoreCase(type) && key != null && key.matches("(?i)[a-f0-9]{32}")) {
            String cachedPath = mediaPathCacheService.get(key);
            if (cachedPath != null && !cachedPath.isBlank()) {
                candidates.addAll(buildCachedImageCandidates(cachedPath));
            }
        }

        for (Path candidate : candidates) {
            if (candidate != null && Files.exists(candidate) && Files.isRegularFile(candidate)) {
                return candidate.toAbsolutePath().normalize();
            }
        }
        if (path != null && !path.isBlank()) {
            Path searched = findByRelativePath(path, isThumb);
            if (searched != null) {
                return searched;
            }
        }
        Path byKey = findByKey(key, isThumb);
        if (byKey != null) {
            return byKey;
        }
        return null;
    }

    private List<Path> buildCandidatesFromRelativePath(String relativePath, boolean isThumb) {
        List<Path> out = new ArrayList<>();
        if (relativePath == null || relativePath.isBlank()) return out;
        String normalizedPath = relativePath.replace("/", "\\").replace("\\\\", "\\");
        String noExt = normalizedPath;
        int dot = noExt.lastIndexOf('.');
        if (dot > 0) {
            noExt = noExt.substring(0, dot);
        }
        if (noExt.toLowerCase().endsWith("_t")) {
            noExt = noExt.substring(0, noExt.length() - 2);
        }
        String[] suffixes = isThumb
            ? new String[]{noExt + "_t.dat", noExt + ".dat", noExt + "_h.dat", noExt, normalizedPath}
            : new String[]{noExt + ".dat", noExt + "_h.dat", noExt, normalizedPath, noExt + "_t.dat"};
        for (Path root : candidateMediaRoots()) {
            for (String suffix : suffixes) {
                out.add(root.resolve(suffix));
            }
        }
        return out;
    }

    private List<Path> buildCachedImageCandidates(String relativePath) {
        List<Path> out = new ArrayList<>();
        if (relativePath == null || relativePath.isBlank()) return out;
        String normalizedPath = relativePath.replace("/", "\\").replace("\\\\", "\\");
        String noExt = normalizedPath;
        int dot = noExt.lastIndexOf('.');
        if (dot > 0) noExt = noExt.substring(0, dot);
        if (noExt.toLowerCase().endsWith("_t") || noExt.toLowerCase().endsWith("_h")) {
            noExt = noExt.substring(0, noExt.length() - 2);
        }
        String[] suffixes = new String[]{noExt + ".dat", noExt + "_h.dat", noExt + "_t.dat", noExt, normalizedPath};
        for (Path root : candidateMediaRoots()) {
            for (String suffix : suffixes) {
                out.add(root.resolve(suffix));
            }
        }
        return out;
    }

    private List<Path> buildMediaCandidatesFromMetadata(String type, String key, WeChatMedia media) {
        List<Path> candidates = new ArrayList<>();
        String dir1 = media.getDir1() != null ? media.getDir1().trim() : "";
        String dir2 = media.getDir2() != null ? media.getDir2().trim() : "";
        String fileName = (media.getName() != null && !media.getName().isBlank()) ? media.getName().trim() : key;
        if (fileName == null || fileName.isBlank()) {
            return candidates;
        }

        for (Path root : candidateMediaRoots()) {
            if (!dir1.isEmpty()) {
                candidates.add(root.resolve(dir1).resolve(dir2).resolve(fileName));
                candidates.add(root.resolve(dir1).resolve(fileName));
            }
            candidates.add(root.resolve("msg").resolve("attach").resolve(dir1).resolve(dir2).resolve(fileName));
            candidates.add(root.resolve(type).resolve(fileName));
            candidates.add(root.resolve(fileName));
        }
        return candidates;
    }

    private Path findByRelativePath(String path, boolean isThumb) {
        String normalizedPath = path.replace("/", "\\").replace("\\\\", "\\");
        String noExt = normalizedPath;
        int dot = noExt.lastIndexOf('.');
        if (dot > 0) {
            noExt = noExt.substring(0, dot);
        }
        String[] suffixes = new String[]{
            normalizedPath.toLowerCase(),
            (noExt + (isThumb ? "_t.dat" : ".dat")).toLowerCase(),
            (noExt + "_h.dat").toLowerCase(),
            (noExt + "_t.dat").toLowerCase(),
            (noExt + ".dat").toLowerCase()
        };

        for (Path root : candidateMediaRoots()) {
            if (!Files.isDirectory(root)) {
                continue;
            }
            try (java.util.stream.Stream<Path> stream = Files.walk(root, 6)) {
                Path found = stream
                    .filter(Files::isRegularFile)
                    .filter(p -> {
                        String s = p.toString().toLowerCase().replace("/", "\\");
                        for (String suffix : suffixes) {
                            if (s.endsWith(suffix)) return true;
                        }
                        return false;
                    })
                    .findFirst()
                    .orElse(null);
                if (found != null) {
                    return found.toAbsolutePath().normalize();
                }
            } catch (IOException ignored) {
            }
        }
        return null;
    }

    private Path findByKey(String key, boolean isThumb) {
        if (key == null || key.isBlank()) {
            return null;
        }
        String lowerKey = key.toLowerCase();
        for (Path root : candidateMediaRoots()) {
            if (!Files.isDirectory(root)) {
                continue;
            }
            try (java.util.stream.Stream<Path> stream = Files.walk(root, 8)) {
                Path found = stream
                    .filter(Files::isRegularFile)
                    .filter(p -> {
                        String name = p.getFileName().toString().toLowerCase();
                        if (!name.contains(lowerKey)) return false;
                        if (isThumb) {
                            return name.contains("_t.") || name.contains("_t.dat");
                        }
                        return true;
                    })
                    .findFirst()
                    .orElse(null);
                if (found != null) {
                    return found.toAbsolutePath().normalize();
                }
            } catch (IOException ignored) {
            }
        }
        return null;
    }

    private List<Path> candidateMediaRoots() {
        LinkedHashSet<Path> roots = new LinkedHashSet<>();
        roots.add(Paths.get("data"));
        roots.add(Paths.get("data", "cache", "images"));
        roots.add(Paths.get("data", "cache", "images", "msg", "attach"));

        addIfNotBlank(roots, System.getProperty("WECHAT_DB_SRC_PATH"));
        addIfNotBlank(roots, System.getenv("WECHAT_DB_SRC_PATH"));
        addIfNotBlank(roots, System.getProperty("WXKEY_WECHAT_DATA_PATH"));
        addIfNotBlank(roots, System.getenv("WXKEY_WECHAT_DATA_PATH"));
        addIfNotBlank(roots, readDotEnvValue("WECHAT_DB_SRC_PATH"));
        addIfNotBlank(roots, readDotEnvValue("WXKEY_WECHAT_DATA_PATH"));
        roots.add(Paths.get("D:\\xwechat_files"));
        roots.add(Paths.get("C:\\xwechat_files"));
        addIfNotBlank(roots, Paths.get(System.getProperty("user.home", ""), "Documents", "xwechat_files").toString());

        List<Path> existing = new ArrayList<>();
        for (Path p : roots) {
            if (p != null) {
                existing.add(p.toAbsolutePath().normalize());
            }
        }
        return existing;
    }

    private void addIfNotBlank(LinkedHashSet<Path> roots, String value) {
        if (value != null && !value.isBlank()) {
            roots.add(Paths.get(value.trim()));
        }
    }

    private String readDotEnvValue(String key) {
        Path env = Paths.get(".env").toAbsolutePath();
        if (!Files.exists(env)) return null;
        try {
            for (String line : Files.readAllLines(env, StandardCharsets.UTF_8)) {
                String trimmed = line == null ? "" : line.trim();
                if (trimmed.startsWith(key + "=")) {
                    return trimmed.substring((key + "=").length()).trim();
                }
            }
        } catch (IOException ignored) {
        }
        return null;
    }

    private int resolveXorKey() {
        if (xorKey >= 0) return xorKey;
        String[] values = new String[]{
            System.getProperty("XOR_KEY"),
            System.getenv("XOR_KEY"),
            readDotEnvValue("XOR_KEY")
        };
        for (String value : values) {
            if (value == null || value.isBlank()) continue;
            try {
                return Integer.decode(value.trim());
            } catch (Exception ignored) {
            }
        }
        return -1;
    }

    private byte[] tryDecryptEmoji(byte[] raw, String key) {
        if (raw == null) return new byte[0];
        if (isImageBytes(raw)) return raw;
        try {
            return mediaService.decryptAesImage(raw, key);
        } catch (Exception ignored) {
            return raw;
        }
    }

    private byte[] readMediaBytes(Path filePath, String type) throws IOException {
        byte[] raw = Files.readAllBytes(filePath);
        if (!"image".equalsIgnoreCase(type)) {
            return raw;
        }
        int finalXorKey = resolveXorKey();
        if (finalXorKey < 0) {
            return raw;
        }
        String name = filePath.getFileName() == null ? "" : filePath.getFileName().toString().toLowerCase();
        boolean noExt = !name.contains(".");
        boolean datLike = name.endsWith(".dat") || noExt;
        if (!datLike) {
            return raw;
        }
        try {
            byte[] decrypted = mediaService.decryptDatImage(filePath, finalXorKey);
            if (isImageBytes(decrypted)) {
                return decrypted;
            }
        } catch (Exception ignored) {
        }
        return raw;
    }

    private boolean isImageBytes(byte[] data) {
        String ct = detectContentType(data);
        return ct.startsWith("image/");
    }

    private String detectContentType(byte[] data) {
        if (data == null || data.length < 3) return "application/octet-stream";
        if (data[0] == (byte) 0xFF && data[1] == (byte) 0xD8) return "image/jpeg";
        if (data.length >= 4 && data[0] == (byte) 0x89 && data[1] == 0x50 && data[2] == 0x4E && data[3] == 0x47) return "image/png";
        if (data[0] == 'G' && data[1] == 'I' && data[2] == 'F') return "image/gif";
        if (data[0] == 'B' && data[1] == 'M') return "image/bmp";
        return "application/octet-stream";
    }
}
