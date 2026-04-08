package com.wetrace.api.controller;

import com.wetrace.core.model.WeChatSession;
import com.wetrace.core.repository.WeChatRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 会话 API */
@Slf4j
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class SessionController {

    private final WeChatRepository repo;

    @GetMapping("/sessions")
    public ResponseEntity<?> getSessions(
             @RequestParam(required = false) String keyword,
             @RequestParam(defaultValue = "50") int limit,
             @RequestParam(defaultValue = "0") int offset,
             @RequestParam(required = false) String format,
             @RequestParam(name = "_t", required = false) String t) {
        try {
            List<WeChatSession> sessions = repo.getSessions(keyword, limit, offset);
            List<Map<String, Object>> data = sessions.stream().map(this::toGoSession).toList();
            return ResponseEntity.ok(Map.of(
                "success", true,
                "data", data
            ));
        } catch (Exception e) {
            log.error("获取会话列表失败", e);
            return ResponseEntity.internalServerError().body(
                new ErrorResp("获取会话列表失败: " + e.getMessage()));
        }
    }

    @DeleteMapping("/sessions/{username}")
    public ResponseEntity<?> deleteSession(@PathVariable String username) {
        try {
            repo.deleteSession(username);
            return ResponseEntity.ok(java.util.Map.of("ok", true));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(
                new ErrorResp("删除会话失败: " + e.getMessage()));
        }
    }

    public record ErrorResp(String error) {}

    private Map<String, Object> toGoSession(WeChatSession s) {
        LinkedHashMap<String, Object> out = new LinkedHashMap<>();
        String userName = firstNonBlank(s.getUserName(), s.getUsername());
        long nOrder = s.getOrder() > 0 ? s.getOrder() : normalizeUnixTimestamp(s.getLastTime());
        if (nOrder <= 0) {
            nOrder = normalizeUnixTimestamp(s.getLastTimestamp());
        }
        out.put("userName", userName);
        out.put("nOrder", nOrder);
        out.put("nickName", firstNonBlank(s.getNickName(), s.getName(), userName));
        out.put("content", firstNonBlank(s.getContent(), s.getLastMessage(), ""));
        out.put("nTime", toIsoTimeString(s.getNTime() > 0 ? s.getNTime() : s.getLastTime()));
        out.put("smallHeadURL", emptyToBlank(s.getSmallHeadURL()));
        out.put("bigHeadURL", emptyToBlank(s.getBigHeadURL()));
        return out;
    }

    private String toIsoTimeString(long ts) {
        long sec = normalizeUnixTimestamp(ts);
        if (sec <= 0) return "";
        return OffsetDateTime.ofInstant(Instant.ofEpochSecond(sec), ZoneId.systemDefault())
            .format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
    }

    private long normalizeUnixTimestamp(long ts) {
        if (ts <= 0) return 0;
        return ts > 9_999_999_999L ? ts / 1000 : ts;
    }

    private String firstNonBlank(String... values) {
        for (String v : values) {
            if (v != null && !v.isBlank()) return v;
        }
        return "";
    }

    private String emptyToBlank(String value) {
        return value == null ? "" : value;
    }
}
