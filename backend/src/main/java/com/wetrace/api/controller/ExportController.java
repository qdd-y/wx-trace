package com.wetrace.api.controller;

import com.wetrace.core.model.WeChatMessage;
import com.wetrace.core.repository.WeChatRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;

/** 数据导出 API */
@Slf4j
@RestController
@RequestMapping("/api/v1/export")
@RequiredArgsConstructor
public class ExportController {

    private final WeChatRepository repo;
    private static final DateTimeFormatter DTF = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /** 导出聊天记录 */
    @GetMapping(value = "/chat", produces = "text/html;charset=utf-8")
    public ResponseEntity<?> exportChat(
            @RequestParam String sessionId,
            @RequestParam(defaultValue = "html") String format,
            @RequestParam(required = false) String start,
            @RequestParam(required = false) String end) {

        try {
            List<WeChatMessage> messages = repo.getMessages(sessionId, 0, null,
                null, null, 0, 0);

            String filename = sessionId + "_" + System.currentTimeMillis();

            return switch (format) {
                case "html" -> exportHtml(sessionId, messages);
                case "txt" -> exportTxt(sessionId, messages);
                case "csv" -> exportCsv(sessionId, messages);
                default -> exportHtml(sessionId, messages);
            };

        } catch (Exception e) {
            log.error("导出失败", e);
            return ResponseEntity.internalServerError()
                .body(java.util.Map.of("error", e.getMessage()));
        }
    }

    /** 取证格式导出 */
    @GetMapping(value = "/forensic", produces = "application/json")
    public ResponseEntity<?> exportForensic(@RequestParam String sessionId) {
        try {
            List<WeChatMessage> messages = repo.getMessages(sessionId, 0, null, null, null, 0, 0);
            String myWxid = repo.getCurrentUserWxid();

            // 生成取证报告 JSON
            var report = java.util.Map.of(
                "sessionId", sessionId,
                "exportTime", java.time.LocalDateTime.now().toString(),
                "totalMessages", messages.size(),
                "myWxid", myWxid,
                "messages", messages.stream()
                    .limit(1000)
                    .map(m -> java.util.Map.of(
                        "seq", m.getSeq(),
                        "timestamp", m.getTimestamp(),
                        "sender", m.getSender() != null ? m.getSender() : "",
                        "type", m.getType(),
                        "content", m.getContent() != null ? m.getContent() : "",
                        "talker", m.getTalker() != null ? m.getTalker() : ""
                    )).toList()
            );

            return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                    "attachment; filename=forensic_" + sessionId + ".json")
                .body(report);

        } catch (Exception e) {
            return ResponseEntity.internalServerError()
                .body(java.util.Map.of("error", e.getMessage()));
        }
    }

    // ==================== 内部导出方法 ====================

    private ResponseEntity<byte[]> exportHtml(String sessionId, List<WeChatMessage> messages) {
        StringBuilder html = new StringBuilder();
        html.append("""
            <!DOCTYPE html>
            <html>
            <head>
            <meta charset="UTF-8">
            <title>聊天记录导出</title>
            <style>
            body { font-family: -apple-system, sans-serif; max-width: 800px; margin: 0 auto; padding: 20px; }
            .msg { margin: 8px 0; padding: 8px 12px; border-radius: 8px; max-width: 70%%; }
            .self { background: #95ec69; margin-left: auto; }
            .other { background: #fff; border: 1px solid #eee; }
            .time { font-size: 11px; color: #999; margin-top: 2px; }
            .name { font-size: 12px; font-weight: bold; margin-bottom: 2px; }
            </style>
            </head>
            <body>
            <h2>聊天记录</h2>
            """);

        for (var msg : messages) {
            String cls = msg.isSelf() ? "self" : "other";
            String name = msg.getSenderName() != null ? msg.getSenderName() : (msg.getSender() != null ? msg.getSender() : "");
            String content = msg.getContent() != null ? escapeHtml(msg.getContent()) : "[" + msg.getTypeDesc() + "]";
            String time = msg.getTime() != null ? msg.getTime().format(DTF) : "";

            html.append(String.format("""
                <div class="msg %s">
                  <div class="name">%s</div>
                  <div>%s</div>
                  <div class="time">%s</div>
                </div>
                """, cls, name, content, time));
        }

        html.append("</body></html>");

        return ResponseEntity.ok()
            .contentType(MediaType.TEXT_HTML)
            .header(HttpHeaders.CONTENT_DISPOSITION,
                "attachment; filename=chat_" + sessionId + ".html; charset=utf-8")
            .body(html.toString().getBytes(StandardCharsets.UTF_8));
    }

    private ResponseEntity<byte[]> exportTxt(String sessionId, List<WeChatMessage> messages) {
        StringBuilder txt = new StringBuilder();
        txt.append("聊天记录导出\n");
        txt.append("=".repeat(50)).append("\n\n");

        for (var msg : messages) {
            String sender = msg.getSenderName() != null ? msg.getSenderName() : (msg.getSender() != null ? msg.getSender() : "未知");
            String content = msg.getContent() != null ? msg.getContent() : "[" + msg.getTypeDesc() + "]";
            String time = msg.getTime() != null ? msg.getTime().format(DTF) : "";
            txt.append(String.format("[%s] %s: %s\n", time, sender, content));
        }

        return ResponseEntity.ok()
            .contentType(MediaType.parseMediaType("text/plain; charset=utf-8"))
            .header(HttpHeaders.CONTENT_DISPOSITION,
                "attachment; filename=chat_" + sessionId + ".txt; charset=utf-8")
            .body(txt.toString().getBytes(StandardCharsets.UTF_8));
    }

    private ResponseEntity<byte[]> exportCsv(String sessionId, List<WeChatMessage> messages) {
        StringBuilder csv = new StringBuilder();
        csv.append("\uFEFF"); // BOM for Excel UTF-8
        csv.append("序号,时间,发送者,类型,内容\n");

        for (var msg : messages) {
            csv.append(msg.getSeq()).append(",")
                .append(msg.getTime() != null ? msg.getTime().format(DTF) : "").append(",")
                .append(escapeCsv(msg.getSenderName())).append(",")
                .append(msg.getTypeDesc()).append(",")
                .append(escapeCsv(msg.getContent())).append("\n");
        }

        return ResponseEntity.ok()
            .header(HttpHeaders.CONTENT_DISPOSITION,
                "attachment; filename=chat_" + sessionId + ".csv; charset=utf-8")
            .body(csv.toString().getBytes(StandardCharsets.UTF_8));
    }

    private String escapeHtml(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private String escapeCsv(String s) {
        if (s == null) return "";
        if (s.contains(",") || s.contains("\"") || s.contains("\n")) {
            return "\"" + s.replace("\"", "\"\"") + "\"";
        }
        return s;
    }
}
