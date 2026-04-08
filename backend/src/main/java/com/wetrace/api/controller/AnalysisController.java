package com.wetrace.api.controller;

import com.wetrace.core.model.HourlyStat;
import com.wetrace.core.model.WeChatSession;
import com.wetrace.core.repository.WeChatRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/** 数据分析 API */
@Slf4j
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class AnalysisController {

    private final WeChatRepository repo;

    /** 概览统计 */
    @GetMapping("/dashboard")
    public ResponseEntity<?> getDashboard() {
        try {
            String myWxid = repo.getCurrentUserWxid();
            List<WeChatSession> sessions = repo.getSessions(null, 100, 0);
            return ResponseEntity.ok(java.util.Map.of(
                "totalSessions", sessions.size(),
                "myWxid", myWxid
            ));
        } catch (Exception e) {
            return ResponseEntity.ok(java.util.Map.of("error", e.getMessage()));
        }
    }

    /** 活跃时段 */
    @GetMapping("/analysis/hourly/{id}")
    public ResponseEntity<?> getHourlyActivity(@PathVariable String id) {
        try {
            List<HourlyStat> stats = repo.getHourlyActivity(id);
            return ResponseEntity.ok(stats);
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(java.util.Map.of("error", e.getMessage()));
        }
    }

    /** 词云 */
    @GetMapping("/analysis/wordcloud/{id}")
    public ResponseEntity<?> getWordCloud(@PathVariable String id) {
        // 占位：需要消息内容分词
        return ResponseEntity.ok(java.util.List.of(
            java.util.Map.of("word", "示例", "frequency", 10)
        ));
    }

    @GetMapping("/analysis/wordcloud/global")
    public ResponseEntity<?> getGlobalWordCloud() {
        return ResponseEntity.ok(java.util.List.of());
    }

    /** 其他分析 */
    @GetMapping("/analysis/personal/top_contacts")
    public ResponseEntity<?> getTopContacts(
            @RequestParam(defaultValue = "10") int limit) {
        try {
            var sessions = repo.getSessions(null, limit, 0);
            var result = sessions.stream()
                .filter(s -> s.getName() != null)
                .map(s -> java.util.Map.of(
                    "talker", s.getUserName() != null ? s.getUserName() : s.getUsername(),
                    "name", s.getName(),
                    "messageCount", s.getMessageCount()
                )).toList();
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(java.util.Map.of("error", e.getMessage()));
        }
    }

    @GetMapping("/analysis/daily/{id}")
    public ResponseEntity<?> getDailyActivity(@PathVariable String id) {
        return ResponseEntity.ok(java.util.List.of());
    }

    @GetMapping("/analysis/weekday/{id}")
    public ResponseEntity<?> getWeekdayActivity(@PathVariable String id) {
        return ResponseEntity.ok(java.util.List.of());
    }

    @GetMapping("/analysis/monthly/{id}")
    public ResponseEntity<?> getMonthlyActivity(@PathVariable String id) {
        return ResponseEntity.ok(java.util.List.of());
    }

    @GetMapping("/analysis/type_distribution/{id}")
    public ResponseEntity<?> getTypeDistribution(@PathVariable String id) {
        // 占位
        return ResponseEntity.ok(java.util.Map.of(
            "text", 100, "image", 20, "voice", 5
        ));
    }

    @GetMapping("/analysis/member_activity/{id}")
    public ResponseEntity<?> getMemberActivity(@PathVariable String id) {
        return ResponseEntity.ok(java.util.List.of());
    }

    @GetMapping("/analysis/repeat/{id}")
    public ResponseEntity<?> getRepeatAnalysis(@PathVariable String id) {
        return ResponseEntity.ok(java.util.List.of());
    }
}
