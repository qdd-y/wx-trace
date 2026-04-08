package com.wetrace.core.repository;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.*;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;

/**
 * SQLite 连接池
 *
 * 每个数据库路径一个连接，懒加载复用
 * 对应 Go 版本 store/pool.go
 */
@Slf4j
@Component
public class SqlitePool {

    private final Map<String, Connection> connections = new ConcurrentHashMap<>();
    private final Map<String, Long> lastAccess = new ConcurrentHashMap<>();
    private final ExecutorService executor = Executors.newCachedThreadPool();

    // 最大连接数
    private static final int MAX_CONNECTIONS = 20;

    /** 获取连接（懒加载） */
    public Connection getConnection(String dbPath) throws SQLException {
        Path path = normalizePath(dbPath);
        if (!Files.exists(path)) {
            throw new SQLException("数据库文件不存在: " + path);
        }

        String canonicalPath = path.toString();
        return connections.compute(canonicalPath, (k, existing) -> {
            if (existing != null) {
                try {
                    if (!existing.isClosed()) {
                        lastAccess.put(k, System.currentTimeMillis());
                        return existing;
                    }
                } catch (SQLException ignored) {}
            }
            try {
                String url = "jdbc:sqlite:" + path.toAbsolutePath();
                Connection conn = DriverManager.getConnection(url);
                conn.setAutoCommit(true);
                lastAccess.put(k, System.currentTimeMillis());
                log.debug("打开数据库连接: {}", dbPath);
                return conn;
            } catch (SQLException e) {
                throw new RuntimeException(e);
            }
        });
    }

    /** 检查表是否存在 */
    public boolean isTableExist(String dbPath, String table) {
        String sql = "SELECT name FROM sqlite_master WHERE type='table' AND name = ?";
        try {
            Connection conn = getConnection(dbPath);
            try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, table);
                try (ResultSet rs = stmt.executeQuery()) {
                    return rs.next();
                }
            }
        } catch (SQLException e) {
            return false;
        }
    }

    private Path normalizePath(String dbPath) {
        String raw = dbPath == null ? "" : dbPath.trim();
        if (raw.startsWith("jdbc:sqlite:")) {
            raw = raw.substring("jdbc:sqlite:".length());
        }
        return Paths.get(raw).toAbsolutePath().normalize();
    }

    /** 执行查询（仅内部用） */
    ResultSet query(Connection conn, String sql, Object... args) throws SQLException {
        PreparedStatement ps = conn.prepareStatement(sql);
        for (int i = 0; i < args.length; i++) {
            ps.setObject(i + 1, args[i]);
        }
        return ps.executeQuery();
    }

    /** 执行更新（仅内部用） */
    int update(Connection conn, String sql, Object... args) throws SQLException {
        PreparedStatement ps = conn.prepareStatement(sql);
        for (int i = 0; i < args.length; i++) {
            ps.setObject(i + 1, args[i]);
        }
        return ps.executeUpdate();
    }

    /** 关闭所有连接（应用关闭时调用） */
    public void closeAll() {
        connections.forEach((path, conn) -> {
            try {
                if (conn != null && !conn.isClosed()) {
                    conn.close();
                    log.debug("关闭数据库连接: {}", path);
                }
            } catch (SQLException ignored) {}
        });
        connections.clear();
        executor.shutdown();
    }
}
