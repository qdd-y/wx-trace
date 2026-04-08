package com.wetrace.api.controller;

import com.wetrace.core.model.WeChatContact;
import com.wetrace.core.repository.WeChatRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 联系人 API */
@Slf4j
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class ContactController {

    private final WeChatRepository repo;

    @GetMapping("/contacts")
    public ResponseEntity<?> getContacts(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false, defaultValue = "0") int type,
            @RequestParam(defaultValue = "200") int limit,
            @RequestParam(defaultValue = "0") int offset,
            @RequestParam(required = false) String format,
            @RequestParam(name = "_t", required = false) String t) {
        try {
            List<WeChatContact> contacts = repo.getContacts(keyword, limit, offset);
            if (type > 0) {
                contacts = contacts.stream().filter(c -> resolveContactType(c) == type).toList();
            }
            List<Map<String, Object>> data = contacts.stream().map(this::toGoContact).toList();
            return ResponseEntity.ok(java.util.Map.of(
                "success", true,
                "data", data,
                "total", data.size()
            ));
        } catch (Exception e) {
            log.error("获取联系人失败", e);
            return ResponseEntity.internalServerError().body(
                java.util.Map.of("error", "获取联系人失败: " + e.getMessage()));
        }
    }

    @GetMapping("/contacts/{wxid}")
    public ResponseEntity<?> getContact(@PathVariable String wxid) {
        try {
            var contacts = repo.getContacts(wxid, 100, 0);
            for (WeChatContact c : contacts) {
                String id = firstNonBlank(c.getUserName(), c.getUsername());
                if (wxid.equals(id)) {
                    return ResponseEntity.ok(java.util.Map.of("success", true, "data", toGoContact(c)));
                }
            }
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(java.util.Map.of("error", "未找到"));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(java.util.Map.of("error", e.getMessage()));
        }
    }

    /** 需要联系人的会话列表 */
    @GetMapping("/contacts/need-contact")
    public ResponseEntity<?> getNeedContactList() {
        // 预留接口
        return ResponseEntity.ok(java.util.List.of());
    }

    /** 导出联系人 */
    @GetMapping(value = "/contacts/export", produces = "text/csv")
    public ResponseEntity<?> exportContacts() {
        try {
            List<WeChatContact> contacts = repo.getContacts(null, 0, 0);
            StringBuilder csv = new StringBuilder();
            csv.append("username,remark,nickName,alias,type\n");
            for (var c : contacts) {
                csv.append(escapeCsv(c.getUsername())).append(",")
                    .append(escapeCsv(c.getRemark())).append(",")
                    .append(escapeCsv(c.getNickName())).append(",")
                    .append(escapeCsv(c.getAlias())).append(",")
                    .append(c.getLocalType()).append("\n");
            }
            return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=contacts.csv")
                .body(csv.toString());
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(java.util.Map.of("error", e.getMessage()));
        }
    }

    private String escapeCsv(String s) {
        if (s == null) return "";
        if (s.contains(",") || s.contains("\"") || s.contains("\n")) {
            return "\"" + s.replace("\"", "\"\"") + "\"";
        }
        return s;
    }

    private Map<String, Object> toGoContact(WeChatContact c) {
        LinkedHashMap<String, Object> out = new LinkedHashMap<>();
        out.put("userName", firstNonBlank(c.getUserName(), c.getUsername()));
        out.put("alias", emptyToBlank(c.getAlias()));
        out.put("remark", emptyToBlank(c.getRemark()));
        out.put("nickName", firstNonBlank(c.getNickName(), c.getName(), firstNonBlank(c.getUserName(), c.getUsername())));
        out.put("isFriend", true);
        out.put("smallHeadImgUrl", emptyToBlank(c.getSmallHeadURL()));
        if (c.getBigHeadURL() != null && !c.getBigHeadURL().isBlank()) {
            out.put("bigHeadImgUrl", c.getBigHeadURL());
        }
        return out;
    }

    private int resolveContactType(WeChatContact c) {
        if (c.getLocalType() > 0) {
            return c.getLocalType();
        }
        try {
            return Integer.parseInt(c.getReserved1() == null ? "0" : c.getReserved1().trim());
        } catch (Exception ignored) {
            return 0;
        }
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
