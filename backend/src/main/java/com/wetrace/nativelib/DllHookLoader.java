package com.wetrace.nativelib;

import com.sun.jna.*;
import com.sun.jna.ptr.IntByReference;
import lombok.extern.slf4j.Slf4j;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * wx_key.dll Hook Loader
 *
 * 按照 wx_key.dll 开发指南实现的 JNA 封装
 *
 * 调用流程：
 * 1. load() - 加载 DLL
 * 2. initializeHook(pid) - 注入 Hook
 * 3. startPolling() - 启动后台轮询
 * 4. cleanup() - 清理资源
 */
@Slf4j
public class DllHookLoader {

    private static final int KEY_BUFFER_SIZE = 65;   // aligned with Go (65 bytes, not 128)
    private static final int STATUS_BUFFER_SIZE = 256; // aligned with Go (256 bytes)
    private static final Pattern[] SEQUENCE_PATTERNS = new Pattern[] {
        Pattern.compile("(?:sequenceNumber|seq|sequence)\\s*[=:]\\s*(\\d+)", Pattern.CASE_INSENSITIVE),
        Pattern.compile("#(\\d+)")
    };

    // ==================== JNA 接口定义 ====================
    // 所有导出函数均为 C 风格接口，CallingConvention.Cdecl

    private interface WxKeyLib extends Library {
        // CallingConvention 显式指定，匹配 Windows DLL 标准
        // 使用 STDCALL（对齐 Go syscall 的行为）
        int InitializeHook(int targetPid);
        int PollKeyData(byte[] keyBuf, int size);
        int GetStatusMessage(byte[] msgBuf, int size, IntByReference outLevel);
        int CleanupHook();
        Pointer GetLastErrorMsg();
    }

    // ==================== 成员变量 ====================

    private WxKeyLib dll;
    private Path dllPath;
    private volatile boolean hooked = false;
    private volatile boolean polling = false;

    // 用于去重的序列号
    private int lastSequenceNumber = -1;
    private int targetPid = 0;

    private ExecutorService executor;
    private Future<?> pollingFuture;
    private final AtomicBoolean stopFlag = new AtomicBoolean(false);

    // 回调接口
    private Consumer<String> onKeyFound;
    private Consumer<StatusMessage> onStatusMessage;

    // ==================== 公开 API ====================

    /**
     * 加载 DLL（从资源目录）
     */
    public void load() throws IOException {
        load(null);
    }

    /**
     * 加载 DLL
     * @param dllPath DLL 文件路径，为 null 时从资源目录加载
     */
    public void load(String dllPath) throws IOException {
        if (dllPath != null && !dllPath.isEmpty()) {
            this.dllPath = Paths.get(dllPath);
        } else {
            this.dllPath = extractDllFromResources();
        }

        if (!Files.exists(this.dllPath)) {
            throw new FileNotFoundException("wx_key.dll not found: " + this.dllPath);
        }

        log.info("Loading wx_key.dll: {}", this.dllPath);
        this.dll = Native.load(this.dllPath.toString(), WxKeyLib.class);
        log.info("wx_key.dll loaded successfully");
    }

    /**
     * 初始化 Hook
     * @param targetPid 微信进程 ID
     * @return 成功返回 true
     */
    public boolean initializeHook(int targetPid) {
        if (dll == null) {
            throw new IllegalStateException("DLL not loaded, call load() first");
        }

        // 同一进程重复调用时直接复用，跨进程切换前先释放旧 Hook。
        if (hooked && this.targetPid == targetPid) {
            log.info("Hook already initialized for PID: {}, skip re-inject", targetPid);
            return true;
        }
        if (hooked && this.targetPid != targetPid) {
            log.info("Hook target changed ({} -> {}), cleaning previous hook first", this.targetPid, targetPid);
            cleanup();
        }

        log.info("Initializing hook for PID: {}", targetPid);
        int ret = dll.InitializeHook(targetPid);

        // DLL 返回 bool，JNA 映射为 int：1=true, 0=false
        if (ret == 0) {
            String error = getLastErrorMsg();
            log.error("InitializeHook failed: {}", error);
            return false;
        }

        hooked = true;
        this.targetPid = targetPid;
        this.lastSequenceNumber = -1;
        log.info("Hook initialized successfully");
        return true;
    }

    /**
     * 启动后台轮询
     * 按照开发指南，建议轮询间隔 100ms
     *
     * @param onKeyFound 密钥发现回调
     * @param onStatusMessage 状态消息回调
     */
    public void startPolling(Consumer<String> onKeyFound, Consumer<StatusMessage> onStatusMessage) {
        if (dll == null || !hooked) {
            throw new IllegalStateException("Hook not initialized");
        }

        this.onKeyFound = onKeyFound;
        this.onStatusMessage = onStatusMessage;
        this.stopFlag.set(false);
        this.polling = true;

        if (executor == null || executor.isShutdown()) {
            executor = Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "wx_key-polling");
                t.setDaemon(true);
                return t;
            });
        }

        pollingFuture = executor.submit(this::pollingLoop);
        log.info("Polling started (interval: 100ms)");
    }

    /**
     * 停止轮询
     */
    public void stopPolling() {
        stopFlag.set(true);
        if (pollingFuture != null) {
            pollingFuture.cancel(true);
        }
        polling = false;
        log.info("Polling stopped");
    }

    /**
     * 轮询获取密钥（单次调用）
     * @return 密钥字符串，无数据返回 null
     */
    public String pollKeyData() {
        if (dll == null || !hooked) {
            return null;
        }

        byte[] buffer = new byte[KEY_BUFFER_SIZE];
        int ret = dll.PollKeyData(buffer, buffer.length);

        if (ret == 0) {
            return null;
        }

        String key = decodeCString(buffer);
        return key.isEmpty() ? null : key;
    }

    /**
     * 获取所有待处理的状态消息
     * @return 状态消息列表
     */
    public List<StatusMessage> getStatusMessages() {
        List<StatusMessage> messages = new ArrayList<>();
        if (dll == null) return messages;

        byte[] buffer = new byte[STATUS_BUFFER_SIZE];
        int emptyCount = 0;

        // 每次循环创建新的 IntByReference，确保 level 值与当前消息对应
        while (true) {
            IntByReference levelRef = new IntByReference();
            int ret;
            try {
                ret = dll.GetStatusMessage(buffer, buffer.length, levelRef);
            } catch (Exception e) {
                log.warn("[DLL] GetStatusMessage threw: {}", e.getMessage());
                break;
            }

            if (ret == 0) {
                emptyCount++;
                // 连续 5 次返回 0 认为队列已空
                if (emptyCount >= 5) break;
                continue;
            }
            emptyCount = 0;

            String msg = decodeCString(buffer);
            if (msg.isEmpty()) {
                log.debug("[DLL] GetStatusMessage returned ret=1 but buffer is empty (level={})", levelRef.getValue());
                continue;
            }
            if (isDuplicateStatus(msg)) {
                log.trace("[DLL] Skipping duplicate: {}", msg);
                continue;
            }
            log.info("[DLL][L{}] {}", levelRef.getValue(), msg);
            messages.add(new StatusMessage(msg, levelRef.getValue()));
        }

        if (messages.isEmpty() && emptyCount > 0) {
            log.trace("[DLL] No messages in queue (checked {} empty responses)", emptyCount);
        }
        return messages;
    }

    /**
     * 获取单条状态消息（兼容旧接口）
     * @return 状态结果，无消息返回 null
     */
    public StatusMessage getStatus() {
        if (dll == null) return null;

        byte[] buffer = new byte[STATUS_BUFFER_SIZE];
        IntByReference levelRef = new IntByReference();

        int ret;
        try {
            ret = dll.GetStatusMessage(buffer, buffer.length, levelRef);
        } catch (Exception e) {
            log.warn("[DLL] GetStatusMessage exception: {}", e.getMessage());
            return null;
        }

        if (ret == 0) return null;

        String msg = decodeCString(buffer);
        if (msg.isEmpty()) {
            log.debug("[DLL] getStatus: ret=1 but empty buffer, level={}", levelRef.getValue());
            return null;
        }
        if (isDuplicateStatus(msg)) {
            return null;
        }
        log.info("[DLL][L{}] {}", levelRef.getValue(), msg);
        return new StatusMessage(msg, levelRef.getValue());
    }

    /**
     * 清理资源
     * 程序退出前务必调用，否则残留 Shellcode 可能导致微信崩溃
     */
    public boolean cleanup() {
        stopPolling();

        if (dll == null) return false;

        try {
            log.info("Cleaning up hook...");
            int ret = dll.CleanupHook();
            hooked = false;
            targetPid = 0;
            lastSequenceNumber = -1;

            if (ret == 0) {
                log.warn("CleanupHook returned false");
            } else {
                log.info("Hook cleaned up successfully");
            }
            return ret != 0;
        } catch (Exception e) {
            log.error("CleanupHook exception", e);
            return false;
        }
    }

    /**
     * 卸载 DLL 并清理临时文件
     */
    public void unload() {
        cleanup();

        if (executor != null && !executor.isShutdown()) {
            executor.shutdown();
            try {
                executor.awaitTermination(1, TimeUnit.SECONDS);
            } catch (InterruptedException ignored) {}
        }

        // 清理临时 DLL 文件
        if (dllPath != null && dllPath.startsWith(System.getProperty("java.io.tmpdir"))) {
            try {
                Files.deleteIfExists(dllPath);
                // 尝试删除父目录
                Files.deleteIfExists(dllPath.getParent());
                log.info("Deleted temp DLL: {}", dllPath);
            } catch (IOException ignored) {}
        }

        dll = null;
    }

    /**
     * 获取最后的错误消息
     */
    public String getLastErrorMessage() {
        if (dll == null) return "DLL not loaded";
        return getLastErrorMsg();
    }

    /**
     * 是否已 Hook
     */
    public boolean isHooked() {
        return hooked;
    }

    /**
     * 是否正在轮询
     */
    public boolean isPolling() {
        return polling;
    }

    // ==================== 私有方法 ====================

    /**
     * 轮询循环
     */
    private void pollingLoop() {
        while (!stopFlag.get() && !Thread.currentThread().isInterrupted()) {
            try {
                // 1. 尝试获取密钥
                String key = pollKeyData();
                if (key != null && onKeyFound != null) {
                    log.info("[KEY FOUND] {}", key);
                    onKeyFound.accept(key);
                }

                // 2. 获取所有状态消息
                List<StatusMessage> messages = getStatusMessages();
                for (StatusMessage msg : messages) {
                    log.debug("[DLL Log - L{}] {}", msg.level, msg.message);
                    if (onStatusMessage != null) {
                        onStatusMessage.accept(msg);
                    }
                }

                // 轮询间隔 100ms
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                log.error("Polling error", e);
            }
        }

        polling = false;
    }

    /**
     * 从资源目录提取 DLL
     */
    private Path extractDllFromResources() throws IOException {
        String resourcePath = "/native/wx_key.dll";

        // 生成唯一的目标路径
        Path target = Paths.get(
            System.getProperty("java.io.tmpdir"),
            "wetrace_wx_key",
            "wx_key.dll"
        );

        // 尝试从 classpath 加载
        InputStream is = getClass().getResourceAsStream(resourcePath);
        if (is == null) {
            // 开发环境回退
            Path devPath = Paths.get("src/main/resources/native/wx_key.dll");
            if (Files.exists(devPath)) {
                return devPath.toAbsolutePath();
            }
            throw new FileNotFoundException(
                "wx_key.dll not found in resources. " +
                "Please place the DLL in src/main/resources/native/");
        }

        Files.createDirectories(target.getParent());

        // 检查是否已存在且大小相同
        if (Files.exists(target) && Files.size(target) > 0) {
            is.close();
            return target;
        }

        Files.copy(is, target, StandardCopyOption.REPLACE_EXISTING);
        is.close();

        log.info("Extracted wx_key.dll to: {}", target);
        return target;
    }

    /**
     * 获取错误消息
     */
    private String getLastErrorMsg() {
        try {
            Pointer ptr = dll.GetLastErrorMsg();
            if (ptr == null || Pointer.nativeValue(ptr) == 0) {
                return "Unknown error";
            }

            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            for (long off = 0; ; off++) {
                byte b = ptr.getByte(off);
                if (b == 0) break;
                baos.write(b);
            }
            return baos.toString(StandardCharsets.UTF_8.name());
        } catch (Exception e) {
            return "Error reading last error: " + e.getMessage();
        }
    }

    private String decodeCString(byte[] buffer) {
        int len = 0;
        while (len < buffer.length && buffer[len] != 0) {
            len++;
        }
        return new String(buffer, 0, len, StandardCharsets.UTF_8).trim();
    }

    private boolean isDuplicateStatus(String msg) {
        Integer seq = parseSequence(msg);
        if (seq == null) {
            return false;
        }
        if (seq <= lastSequenceNumber) {
            return true;
        }
        lastSequenceNumber = seq;
        return false;
    }

    private Integer parseSequence(String msg) {
        for (Pattern pattern : SEQUENCE_PATTERNS) {
            Matcher matcher = pattern.matcher(msg);
            if (matcher.find()) {
                try {
                    return Integer.parseInt(matcher.group(1));
                } catch (NumberFormatException ignored) {
                    return null;
                }
            }
        }
        return null;
    }

    // ==================== 内部类 ====================

    /**
     * 状态消息（替代旧的 StatusResult）
     */
    public static class StatusMessage {
        public final String message;
        public final int level; // 0=Info, 1=Success, 2=Error

        public StatusMessage(String message, int level) {
            this.message = message;
            this.level = level;
        }

        public String getLevelName() {
            return switch (level) {
                case 0 -> "INFO";
                case 1 -> "SUCCESS";
                case 2 -> "ERROR";
                default -> "UNKNOWN";
            };
        }

        @Override
        public String toString() {
            return "[" + getLevelName() + "] " + message;
        }
    }

    /**
     * 兼容旧接口的状态结果
     * @deprecated 请使用 StatusMessage
     */
    @Deprecated
    public static class StatusResult extends StatusMessage {
        public StatusResult(String message, int level) {
            super(message, level);
        }
    }
}
