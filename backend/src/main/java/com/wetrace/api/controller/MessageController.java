package com.wetrace.api.controller;

import com.wetrace.core.model.WeChatMessage;
import com.wetrace.core.repository.WeChatRepository;
import com.wetrace.core.service.MediaPathCacheService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.HashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 消息 API */
@Slf4j
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class MessageController {

    private final WeChatRepository repo;
    private final MediaPathCacheService mediaPathCacheService;

    /**
     * GET /api/v1/messages
     * @param sessionId  聊天对象 wxid 或群 id
     * @param msgType   消息类型过滤（可选）
     * @param sender    发送者过滤（可选）
     * @param before    Unix 秒时间戳，查询此时间之前的消息（可选）
     * @param after     Unix 秒时间戳，查询此时间之后的消息（可选）
     * @param limit     每页数量，默认 50
     * @param offset    偏移量
     */
    @GetMapping("/messages")
    public ResponseEntity<?> getMessages(
            @RequestParam(required = false) String sessionId,
            @RequestParam(name = "talker_id", required = false) String talkerId,
            @RequestParam(required = false, defaultValue = "0") int msgType,
            @RequestParam(required = false) String sender,
            @RequestParam(required = false) Long before,
            @RequestParam(required = false) Long after,
            @RequestParam(required = false) String time,
            @RequestParam(defaultValue = "50") int limit,
            @RequestParam(defaultValue = "0") int offset,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size,
            @RequestParam(required = false) Integer bottom,
            @RequestParam(required = false) String format) {

        try {
            String talker = (sessionId != null && !sessionId.isBlank()) ? sessionId : talkerId;
            if (talker == null || talker.isBlank()) {
                return ResponseEntity.badRequest().body(java.util.Map.of("error", "sessionId 或 talker_id 不能为空"));
            }

            LocalDateTime start;
            LocalDateTime end;
            if (time != null && !time.isBlank()) {
                LocalDateTime[] range = parseTimeRange(time);
                start = range[0];
                end = range[1];
            } else {
                start = after != null
                    ? LocalDateTime.ofEpochSecond(after, 0, java.time.ZoneOffset.ofHours(8))
                    : (before != null ? LocalDateTime.of(1970, 1, 1, 0, 0) : null);
                end = before != null
                    ? LocalDateTime.ofEpochSecond(before, 0, java.time.ZoneOffset.ofHours(8))
                    : null;
            }

            int finalLimit = limit;
            int finalOffset = offset;
            if (size != null && size > 0) {
                finalLimit = size;
                int pageNo = (page == null || page < 1) ? 1 : page;
                finalOffset = (pageNo - 1) * finalLimit;
            }

            List<WeChatMessage> messages = repo.getMessages(talker, msgType, sender, start, end, finalLimit, finalOffset);
            warmImagePathCache(messages);
            List<Map<String, Object>> data = messages.stream().map(this::toGoMessage).toList();
            return ResponseEntity.ok(java.util.Map.of(
                "success", true,
                "data", data,
                "total", data.size()
            ));
        } catch (Exception e) {
            log.error("获取消息失败: sessionId={}, talker_id={}", sessionId, talkerId, e);
            return ResponseEntity.internalServerError().body(
                java.util.Map.of("error", "获取消息失败: " + e.getMessage()));
        }
    }

    /**
     * GET /api/v1/messages/replay  — 回放模式消息
     */
    @GetMapping("/messages/replay")
    public ResponseEntity<?> getReplayMessages(
            @RequestParam String sessionId,
            @RequestParam(required = false) Long start,
            @RequestParam(required = false) Long end) {

        LocalDateTime startTime = start != null
            ? LocalDateTime.ofEpochSecond(start, 0, java.time.ZoneOffset.ofHours(8))
            : LocalDateTime.of(1970, 1, 1, 0, 0);
        LocalDateTime endTime = end != null
            ? LocalDateTime.ofEpochSecond(end, 0, java.time.ZoneOffset.ofHours(8))
            : LocalDateTime.now();

        try {
            List<WeChatMessage> messages = repo.getMessages(sessionId, 0, null, startTime, endTime, 0, 0);
            warmImagePathCache(messages);
            List<Map<String, Object>> data = messages.stream().map(this::toGoMessage).toList();
            return ResponseEntity.ok(java.util.Map.of("success", true, "data", data, "total", data.size()));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(
                java.util.Map.of("error", e.getMessage()));
        }
    }

    private LocalDateTime[] parseTimeRange(String timeRange) {
        try {
            DateTimeFormatter dateFmt = DateTimeFormatter.ofPattern("yyyy-MM-dd");
            if (timeRange.contains("~")) {
                String[] parts = timeRange.split("~", 2);
                LocalDate startDate = LocalDate.parse(parts[0].trim(), dateFmt);
                LocalDate endDate = LocalDate.parse(parts[1].trim(), dateFmt);
                return new LocalDateTime[]{
                    startDate.atStartOfDay(),
                    endDate.atTime(LocalTime.MAX)
                };
            }
            LocalDate day = LocalDate.parse(timeRange.trim(), dateFmt);
            return new LocalDateTime[]{day.atStartOfDay(), day.atTime(LocalTime.MAX)};
        } catch (Exception e) {
            return new LocalDateTime[]{LocalDateTime.of(1970, 1, 1, 0, 0), LocalDateTime.now()};
        }
    }

    private Map<String, Object> toGoMessage(WeChatMessage m) {
        LinkedHashMap<String, Object> out = new LinkedHashMap<>();
        long seq = m.getSortSeq() > 0 ? m.getSortSeq() : 0L;
        if (seq <= 0) seq = m.getSeq();
        if (seq <= 0 || seq < 1_000_000_000_000L) seq = normalizeUnixTimestamp(m.getTimestamp()) * 1000;
        out.put("seq", seq);
        out.put("time", toIsoTimeString(m.getTimestamp()));
        out.put("talker", nullToEmpty(m.getTalker()));
        out.put("talkerName", nullToEmpty(m.getTalkerName()));
        out.put("isChatRoom", m.isChatRoom());
        out.put("sender", nullToEmpty(m.getSender()));
        out.put("senderName", nullToEmpty(m.getSenderName()));
        out.put("isSelf", m.isSelf());
        out.put("type", m.getType());
        out.put("subType", m.getSubType());
        out.put("content", nullToEmpty(m.getContent()));
        out.put("bigHeadURL", nullToEmpty(m.getBigHeadURL()));
        out.put("smallHeadURL", nullToEmpty(m.getSmallHeadURL()));
        Map<String, Object> normalizedContents = normalizeMessageContents(m);
        if (!normalizedContents.isEmpty()) {
            out.put("contents", normalizedContents);
        }
        if (m.getThumb() != null && !m.getThumb().isBlank()) {
            out.put("thumb", m.getThumb());
        }
        out.put("serverId", m.getServerId());
        out.put("status", m.getStatus());
        return out;
    }

    private String toIsoTimeString(long ts) {
        long sec = normalizeUnixTimestamp(ts);
        if (sec <= 0) return "";
        return OffsetDateTime.ofInstant(java.time.Instant.ofEpochSecond(sec), ZoneId.systemDefault())
            .format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
    }

    private long normalizeUnixTimestamp(long ts) {
        if (ts <= 0) return 0;
        return ts > 9_999_999_999L ? ts / 1000 : ts;
    }

    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private Map<String, Object> normalizeMessageContents(WeChatMessage m) {
        Map<String, Object> out = new HashMap<>();
        if (m.getContents() != null) {
            out.putAll(m.getContents());
        }
        if (m.getType() != 47) {
            return out;
        }

        String cdnurl = firstNonBlank(asString(out.get("cdnurl")), asString(out.get("url")));
        String aeskey = firstNonBlank(asString(out.get("aeskey")), asString(out.get("key")));
        if (cdnurl.isBlank() || aeskey.isBlank()) {
            String merged = mergeAscii(m.getContent(), bytesToAscii(m.getCompressContent()), bytesToAscii(m.getPackedInfoData()));
            if (cdnurl.isBlank()) {
                cdnurl = extractUrl(merged);
            }
            if (aeskey.isBlank()) {
                aeskey = extractAesKey(merged);
            }
        }
        if (cdnurl.isBlank() || aeskey.isBlank()) {
            Map<String, String> proto = extractEmojiMetaFromProto(m.getPackedInfoData());
            if (cdnurl.isBlank()) {
                cdnurl = firstNonBlank(proto.get("cdnurl"), proto.get("url"));
            }
            if (aeskey.isBlank()) {
                aeskey = firstNonBlank(proto.get("aeskey"), proto.get("key"));
            }
        }
        if (!cdnurl.isBlank()) {
            out.put("cdnurl", cdnurl);
            out.put("url", cdnurl);
        }
        if (!aeskey.isBlank()) {
            out.put("aeskey", aeskey);
            out.put("key", aeskey);
        }
        return out;
    }

    private String asString(Object value) {
        return value instanceof String s ? s : "";
    }

    private String mergeAscii(String... parts) {
        StringBuilder sb = new StringBuilder();
        for (String p : parts) {
            if (p != null && !p.isBlank()) {
                if (sb.length() > 0) sb.append(' ');
                sb.append(p.replace("\\u0026", "&").replace("&amp;", "&"));
            }
        }
        return sb.toString();
    }

    private String bytesToAscii(byte[] bytes) {
        if (bytes == null || bytes.length == 0) return "";
        StringBuilder sb = new StringBuilder(bytes.length);
        for (byte b : bytes) {
            int c = b & 0xFF;
            if (c >= 32 && c <= 126) {
                sb.append((char) c);
            } else {
                sb.append(' ');
            }
        }
        return sb.toString();
    }

    private String extractUrl(String text) {
        if (text == null || text.isBlank()) return "";
        Matcher m = Pattern.compile("(?i)(https?://[^\\s\"'<>]+)").matcher(text);
        return m.find() ? m.group(1) : "";
    }

    private String extractAesKey(String text) {
        if (text == null || text.isBlank()) return "";
        Matcher m = Pattern.compile("(?i)(?:aeskey|cdnthumbaeskey)[\"'=:\\s]+([a-f0-9]{32})").matcher(text);
        if (m.find()) return m.group(1).toLowerCase(Locale.ROOT);
        Matcher any = Pattern.compile("(?i)\\b([a-f0-9]{32})\\b").matcher(text);
        return any.find() ? any.group(1).toLowerCase(Locale.ROOT) : "";
    }

    private String firstNonBlank(String... values) {
        for (String v : values) {
            if (v != null && !v.isBlank()) return v;
        }
        return "";
    }

    private Map<String, String> extractEmojiMetaFromProto(byte[] data) {
        Map<String, String> out = new HashMap<>();
        if (data == null || data.length == 0) return out;
        parseProtoChunks(data, 0, out);
        return out;
    }

    private void parseProtoChunks(byte[] data, int depth, Map<String, String> out) {
        if (data == null || data.length == 0 || depth > 3) return;
        int i = 0;
        while (i < data.length) {
            long[] keyRead = readVarint(data, i);
            if (keyRead[1] <= 0) break;
            long key = keyRead[0];
            i += (int) keyRead[1];
            int wireType = (int) (key & 0x07);

            try {
                switch (wireType) {
                    case 0 -> { // varint
                        long[] v = readVarint(data, i);
                        if (v[1] <= 0) return;
                        i += (int) v[1];
                    }
                    case 1 -> i += 8; // 64-bit
                    case 2 -> { // length-delimited
                        long[] lenRead = readVarint(data, i);
                        if (lenRead[1] <= 0) return;
                        i += (int) lenRead[1];
                        int len = (int) lenRead[0];
                        if (len < 0 || i + len > data.length) return;
                        byte[] chunk = java.util.Arrays.copyOfRange(data, i, i + len);
                        i += len;

                        String ascii = bytesToAscii(chunk).replace("  ", " ").trim();
                        if (!out.containsKey("cdnurl")) {
                            String url = extractUrl(ascii);
                            if (!url.isBlank()) {
                                out.put("cdnurl", url);
                                out.put("url", url);
                            }
                        }
                        if (!out.containsKey("aeskey")) {
                            String k = extractAesKey(ascii);
                            if (k.isBlank()) {
                                if (chunk.length == 16) {
                                    k = bytesToHex(chunk);
                                } else if (chunk.length == 32) {
                                    String s = new String(chunk, java.nio.charset.StandardCharsets.UTF_8);
                                    if (s.matches("(?i)[a-f0-9]{32}")) {
                                        k = s.toLowerCase(Locale.ROOT);
                                    }
                                }
                            }
                            if (!k.isBlank()) {
                                out.put("aeskey", k);
                                out.put("key", k);
                            }
                        }

                        // 递归解析嵌套 message
                        parseProtoChunks(chunk, depth + 1, out);
                    }
                    case 5 -> i += 4; // 32-bit
                    default -> { return; }
                }
            } catch (Exception ignored) {
                return;
            }
        }
    }

    private long[] readVarint(byte[] data, int start) {
        long value = 0;
        int shift = 0;
        int i = start;
        while (i < data.length && shift < 64) {
            int b = data[i] & 0xFF;
            value |= (long) (b & 0x7F) << shift;
            i++;
            if ((b & 0x80) == 0) {
                return new long[]{value, i - start};
            }
            shift += 7;
        }
        return new long[]{0, 0};
    }

    private String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(String.format("%02x", b & 0xFF));
        }
        return sb.toString();
    }

    private void warmImagePathCache(List<WeChatMessage> messages) {
        if (messages == null || messages.isEmpty()) {
            return;
        }
        for (WeChatMessage m : messages) {
            if (m == null || m.getType() != 3 || m.getContents() == null) {
                continue;
            }
            String md5 = firstNonBlank(
                m.getThumb(),
                asString(m.getContents().get("md5"))
            );
            String path = asString(m.getContents().get("path"));
            if (md5 != null && md5.matches("(?i)[a-f0-9]{32}") && path != null && !path.isBlank()) {
                mediaPathCacheService.put(md5, path);
            }
        }
    }
}
