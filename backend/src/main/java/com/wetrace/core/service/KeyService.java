package com.wetrace.core.service;

import com.wetrace.nativelib.DllHookLoader;
import com.wetrace.nativelib.decrypt.SqlCipherDecryptor;
import com.wetrace.nativelib.imagekey.ImageKeyExtractor;
import com.wetrace.nativelib.process.ProcessManager;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Wechat Key Extraction Service
 * 
 * Aligned with Go wetrace implementation:
 * 1. Kill existing Wechat process
 * 2. Launch Wechat fresh
 * 3. Wait for window + 2 seconds for initialization
 * 4. Inject Hook via DLL
 * 5. Poll for key
 */
@Slf4j
@Service
public class KeyService {

    private final ProcessManager processManager = new ProcessManager();
    private final ImageKeyExtractor imageKeyExtractor = new ImageKeyExtractor();
    private final Path workDir;
    private final ExecutorService executor = Executors.newCachedThreadPool();
    private final ReentrantLock hookSessionLock = new ReentrantLock(true);
    private final ReentrantLock imageKeyLock = new ReentrantLock(true);
    private final AtomicInteger hookSessionCounter = new AtomicInteger(1);

    private volatile String lastDbKey;
    private volatile String lastImageKey;
    private volatile String lastXorKey;
    private volatile long lastImageKeyAtMs;
    private volatile int lastImageKeyPid;

    public KeyService() {
        this.workDir = Paths.get("data").toAbsolutePath();
        try {
            Files.createDirectories(workDir);
        } catch (IOException e) {
            throw new RuntimeException("Failed to create work directory", e);
        }
    }

    // ==================== Database Key ====================

    public CompletableFuture<String> getDbKeyAsync(String customWechatPath,
                                                   java.util.function.Consumer<String> onStatus) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return getDbKey(customWechatPath, onStatus);
            } catch (Exception e) {
                log.error("Failed to get database key", e);
                return null;
            }
        }, executor);
    }

    /**
     * Get database key via DLL Hook
     * 
     * Flow:
     * 1. Kill existing Wechat (force logout, clean state)
     * 2. Launch Wechat fresh
     * 3. Wait for login window to appear
     * 4. Inject Hook into login-window process
     * 5. Poll for key (120 seconds timeout)
     */
    public String getDbKey(String customWechatPath, java.util.function.Consumer<String> onStatus) throws Exception {
        hookSessionLock.lockInterruptibly();
        int sessionId = hookSessionCounter.getAndIncrement();
        int pid = 0;
        try {
            // Step 1: Kill existing Wechat for clean state
            onStatus.accept("Step 1: Closing existing Wechat...");
            if (isWeChatRunning()) {
                onStatus.accept("Wechat is running, closing for clean state...");
                int killed = processManager.killWeChatProcesses();
                log.info("[wxhook][session={}] Killed {} Wechat processes", sessionId, killed);
                Thread.sleep(2000); // Wait for graceful shutdown
            }

            // Step 2: Find Wechat installation path
            onStatus.accept("Step 2: Locating Wechat installation...");
            String wechatPath = customWechatPath;
            if (wechatPath == null || wechatPath.isEmpty()) {
                wechatPath = processManager.findWeChatPath();
            }
            if (wechatPath == null || wechatPath.isEmpty()) {
                throw new RuntimeException("Wechat path not found. Please specify in config.");
            }

            // Step 3: Launch Wechat and wait for login window
            onStatus.accept("Step 3: Launching Wechat (please complete login)...");
            processManager.launchWeChat(wechatPath);

            // Wait for window to appear (the login window has "微信" in the title)
            pid = processManager.waitForWeChatWindow(30);
            if (pid == 0) {
                onStatus.accept("Window wait timeout, falling back to process scan...");
                pid = getWeChatPidFallback();
            }
            if (pid == 0) {
                throw new RuntimeException("Cannot find Wechat window. Please ensure Wechat is running.");
            }

            // Align with Go: wait 2 seconds for initialization, then inject immediately
            log.info("[wxhook][session={}] Window found, PID={}, waiting 2s for initialization...", sessionId, pid);
            Thread.sleep(2000);

            // Re-confirm PID is still alive (aligned with Go's FindMainWeChatPid after wait)
            pid = refreshAliveWeChatPid(pid, 5, 300);
            if (pid == 0) {
                throw new RuntimeException("Wechat process exited during initialization wait.");
            }
            log.info("[wxhook][session={}] PID confirmed: {}", sessionId, pid);

            // Step 4: Load DLL and inject Hook
            onStatus.accept("Step 4: Loading Hook DLL...");
            DllHookLoader dllLoader = new DllHookLoader();
            try {
                dllLoader.load();
            } catch (IOException e) {
                throw new RuntimeException("Failed to load wx_key.dll", e);
            }

            try {
                onStatus.accept("Step 5: Installing Hook...");
                int targetPid = tryInitializeHookWithRetry(dllLoader, pid, sessionId, onStatus);
                log.info("[wxhook][session={}] Hook injected to PID: {}", sessionId, targetPid);

                // Step 5: Poll for key
                // 后台线程(dllLoader.pollingLoop)负责消费DLL状态消息，通过onStatusMessage回调推送给前端
                // KeyService轮询只负责：key数据 + 每10秒诊断日志
                onStatus.accept("Step 6: Polling for key (max 5min)...");
                dllLoader.startPolling(
                    (key) -> { /* key callback handled in main loop below */ },
                    (msg) -> { onStatus.accept("[DLL] " + msg.message); }
                );
                int retries = 3000; // 300 seconds (5 minutes)
                long startTime = System.currentTimeMillis();
                while (retries-- > 0) {
                    String key = dllLoader.pollKeyData();
                    if (key != null && key.length() == 64) {
                        this.lastDbKey = key;
                        System.setProperty("WECHAT_DB_KEY", key);
                        persistDbKeyToEnv(key, onStatus);
                        onStatus.accept("Step 7: Key saved, config updated");
                        dllLoader.stopPolling();
                        return key;
                    }

                    // 每 10 秒打一次诊断日志
                    if ((3000 - retries) % 100 == 0) {
                        long elapsed = (System.currentTimeMillis() - startTime) / 1000;
                        log.info("[wxhook][session={}] Still polling... elapsed={}s", sessionId, elapsed);
                    }

                    Thread.sleep(100);
                }

                throw new RuntimeException("Key extraction timeout. Please try again.");
            } finally {
                dllLoader.unload();
            }
        } finally {
            hookSessionLock.unlock();
        }
    }

    // ==================== Image Key ====================

    public ImageKeyExtractor.ImageKeyResult getImageKey(int wechatPid, String dataPath,
                                                        java.util.function.Consumer<String> onStatus) {
        ImageKeyExtractor.ImageKeyResult cached = getCachedImageKeyIfFresh();
        if (cached != null) {
            onStatus.accept("使用最近一次成功获取的图片密钥（缓存）");
            return cached;
        }

        imageKeyLock.lock();
        try {
            // 双重检查，避免并发请求重复执行重扫描
            cached = getCachedImageKeyIfFresh();
            if (cached != null) {
                onStatus.accept("使用最近一次成功获取的图片密钥（缓存）");
                return cached;
            }

            onStatus.accept("Starting image key scan...");
            onStatus.accept("请在微信中打开一张图片预览，保持预览窗口停留几秒...");

            List<Integer> pidPlan = buildImageScanPidPlan(wechatPid);
            if (pidPlan.isEmpty()) {
                return ImageKeyExtractor.ImageKeyResult.failure("未找到可扫描的微信主进程");
            }
            onStatus.accept("扫描 PID 计划: " + pidPlan);

            // 对齐 Go：先快速扫描，未命中则进入持续观察模式（同一 PID 持续重扫）
            final int totalObserveSeconds = 120;
            final long observeIntervalMs = 2000L;
            final int quickRounds = 2;
            String lastError = "内存中未找到 AES 密钥";
            long deadlineMs = System.currentTimeMillis() + totalObserveSeconds * 1000L;
            boolean enteredObserveMode = false;

            for (int pidIndex = 0; pidIndex < pidPlan.size(); pidIndex++) {
                int currentPid = pidPlan.get(pidIndex);
                onStatus.accept("锁定微信进程 PID=" + currentPid + "，开始扫描...");

                int round = 0;
                while (System.currentTimeMillis() < deadlineMs) {
                    round++;
                    if (!isPidAlive(currentPid)) {
                        lastError = "微信进程不可用: PID=" + currentPid;
                        break;
                    }

                    try {
                        onStatus.accept("扫描微信进程 PID=" + currentPid + "（第 " + round + " 轮）...");
                        ImageKeyExtractor.ImageKeyResult result = imageKeyExtractor.extract(currentPid, dataPath);
                        if (result.success) {
                            updateImageKeyState(result.aesKey, result.xorKey, currentPid, onStatus);
                            onStatus.accept("图片密钥获取成功 (PID=" + currentPid + ")");
                            return result;
                        }
                        if (result.error != null && !result.error.isBlank()) {
                            lastError = result.error;
                        }
                    } catch (Exception e) {
                        lastError = e.getMessage() == null ? e.toString() : e.getMessage();
                        log.debug("Image key scan failed for PID={}: {}", currentPid, lastError);
                    }

                    if (round >= quickRounds && !enteredObserveMode) {
                        enteredObserveMode = true;
                        onStatus.accept("初次扫描未找到密钥，进入持续观察模式。请在微信中打开任意图片...");
                    }

                    if (System.currentTimeMillis() >= deadlineMs) {
                        break;
                    }
                    try {
                        Thread.sleep(observeIntervalMs);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return ImageKeyExtractor.ImageKeyResult.failure("图片密钥扫描被中断");
                    }
                }
            }
            if ("内存中未找到 AES 密钥".equals(lastError)) {
                return ImageKeyExtractor.ImageKeyResult.failure("内存中未找到 AES 密钥（请在微信中打开任意图片后重试）");
            }
            return ImageKeyExtractor.ImageKeyResult.failure(lastError);
        } finally {
            imageKeyLock.unlock();
        }
    }

    // ==================== Database Decryption ====================

    public int decryptAll(String dbSrcPath, String dbKey,
                          java.util.function.Consumer<String> onStatus) {
        SqlCipherDecryptor decryptor = new SqlCipherDecryptor(workDir);
        try {
            String resolvedSrcPath = dbSrcPath == null ? null : dbSrcPath.trim();
            String resolvedDbKey = resolveDbKey(dbKey);
            if (resolvedSrcPath == null || resolvedSrcPath.isBlank()) {
                throw new IllegalArgumentException("缺少数据库源路径，请在请求中传入 srcPath");
            }
            if (resolvedDbKey == null || resolvedDbKey.isBlank()) {
                throw new IllegalArgumentException("缺少数据库密钥，请先获取密钥或在 .env 中设置 WECHAT_DB_KEY");
            }
            this.lastDbKey = resolvedDbKey;
            System.setProperty("WECHAT_DB_KEY", resolvedDbKey);
            onStatus.accept("使用数据库路径: " + resolvedSrcPath);
            onStatus.accept("使用数据库密钥: WECHAT_DB_KEY");

            int count = decryptor.decryptAll(Paths.get(resolvedSrcPath), resolvedDbKey, completed -> {
                onStatus.accept("Decrypted " + completed + " files...");
            });
            return count;
        } catch (Exception e) {
            log.error("Decryption failed", e);
            return -1;
        } finally {
            decryptor.close();
        }
    }

    // ==================== Helper Methods ====================

    /** Check if Wechat is running */
    public boolean isWeChatRunning() {
        return processManager.isProcessRunning("WeiXin.exe")
            || processManager.isProcessRunning("WeChatAppEx.exe")
            || processManager.isProcessRunning("WeChat.exe")
            || processManager.isProcessRunning("Weixin.exe");
    }

    /** Get Wechat PID (full detection) */
    public int getWeChatPid() {
        // First: find via main window
        List<Integer> mainWindowPids = processManager.getWeChatMainWindowPids();
        log.info("getWeChatPid: mainWindowPids={}", mainWindowPids);
        for (Integer windowPid : mainWindowPids) {
            if (isWeChatPidAlive(windowPid)) {
                return windowPid;
            }
        }

        // Second: find via any Wechat window
        List<Integer> windowPids = processManager.getWeChatWindowPids();
        log.info("getWeChatPid: windowPids={}", windowPids);
        for (Integer windowPid : windowPids) {
            if (isWeChatPidAlive(windowPid)) {
                return windowPid;
            }
        }

        // Third: fallback to process scan
        return getWeChatPidFallback();
    }
    
    /** Align with Go: fallback to process scan mode when window detection fails */
    public int getWeChatPidFallback() {
        log.info("getWeChatPidFallback: switching to process scan mode...");
        List<Integer> processPids = getFallbackWeChatProcessPids();
        log.info("getWeChatPidFallback: processPids={}", processPids);
        return pickBestFallbackPid(processPids);
    }

    /** Find Wechat installation path */
    public String findWeChatPath() {
        return processManager.findWeChatPath();
    }

    private int tryInitializeHookWithRetry(DllHookLoader dllLoader,
                                           int initialPid,
                                           int sessionId,
                                           java.util.function.Consumer<String> onStatus) {
        int targetPid = initialPid;
        String lastErr = "Unknown Native error";
        List<Integer> candidates = collectCandidatePids(initialPid);
        if (candidates.isEmpty()) {
            candidates.add(initialPid);
        }

        int attempt = 0;
        for (Integer candidate : candidates) {
            attempt++;
            // 验证PID是否存活（只检查进程存在，不强制要求进程名匹配）
            if (!isPidAlive(candidate)) {
                continue;
            }

            log.info("[wxhook][session={}][attempt={}] try PID={}", sessionId, attempt, candidate);
            onStatus.accept("Injecting Hook (attempt " + attempt + "/" + candidates.size() + ", PID=" + candidate + ")...");
            if (dllLoader.initializeHook(candidate)) {
                return candidate;
            }

            String nativeErr = dllLoader.getLastErrorMessage();
            lastErr = (nativeErr == null || nativeErr.isBlank()) ? "Unknown Native error" : nativeErr;
            if (lastErr.contains("GetWechatVersion failed") || lastErr.contains("GetWechatVersion failed")) {
                lastErr = lastErr + " (wx_key.dll may not be compatible with current Wechat version. Please replace with x64 DLL matching current Wechat version.)";
            }
            if (!lastErr.contains("target process may have exited") && !lastErr.contains("GetWechatVersion failed")) {
                break;
            }

            onStatus.accept("Process unstable, trying next Wechat process...");
        }

        throw new RuntimeException("Hook injection failed (PID=" + targetPid + "): " + lastErr + ". Please ensure Wechat is at main screen and try running backend as Administrator.");
    }

    /**
     * Collect candidate PIDs — aligned with Go's FindMainWeChatPid strategy.
     * 
     * Priority: preferredPid (window-found) > other window PIDs > process name PIDs
     * 
     * Go's FindMainWeChatPid: first checks main window → fallback to process scan.
     * We mirror this by putting preferredPid (from waitForWeChatWindow) FIRST,
     * then other window PIDs, then all other known WeChat processes.
     */
    private List<Integer> collectCandidatePids(int preferredPid) {
        LinkedHashSet<Integer> ordered = new LinkedHashSet<>();

        // Step 1: preferredPid (from waitForWeChatWindow) gets absolute priority — same as Go
        if (preferredPid > 0 && isPidAlive(preferredPid)) {
            ordered.add(preferredPid);
        }

        // Step 2: Other WeChat window PIDs (from main window enumeration)
        // These are also legitimate candidates but lower priority than the primary window PID
        Set<Integer> excluded = new HashSet<>(ordered); // exclude already-added PIDs
        for (Integer windowPid : processManager.getWeChatMainWindowPids()) {
            if (!excluded.contains(windowPid) && isPidAlive(windowPid)) {
                ordered.add(windowPid);
            }
        }

        // Step 3: All WeChat process name PIDs as fallback — same fallback logic as Go
        excluded = new HashSet<>(ordered);
        for (String name : List.of("WeiXin.exe", "Weixin.exe", "WeChatAppEx.exe", "WeChat.exe")) {
            for (Integer pid : processManager.getProcessIds(name)) {
                if (!excluded.contains(pid) && isPidAlive(pid)) {
                    ordered.add(pid);
                }
            }
        }

        List<Integer> result = new ArrayList<>(ordered);
        log.info("[wxhook] Candidate PIDs (window-first): {}", result);
        return result;
    }

    private int pickBestFallbackPid(List<Integer> pids) {
        return pids.stream()
            .filter(this::isPidAlive)
            .findFirst()
            .orElse(0);
    }

    private List<Integer> getFallbackWeChatProcessPids() {
        LinkedHashSet<Integer> ordered = new LinkedHashSet<>();
        // 用户偏好：优先只看 Weixin.exe，并按内存降序
        ordered.addAll(getProcessIdsSortedByMemory("Weixin.exe"));
        ordered.addAll(getProcessIdsSortedByMemory("WeiXin.exe"));
        // 其余名称仅作后备
        ordered.addAll(getProcessIdsSortedByMemory("WeChatAppEx.exe"));
        ordered.addAll(getProcessIdsSortedByMemory("WeChat.exe"));
        return new ArrayList<>(ordered);
    }

    private List<Integer> getProcessIdsSortedByMemory(String processName) {
        List<Integer> pids = new ArrayList<>(processManager.getProcessIds(processName));
        Map<Integer, Long> wsMap = new HashMap<>();
        for (Integer pid : pids) {
            wsMap.put(pid, processManager.getProcessWorkingSetBytes(pid));
        }
        pids.sort(
            Comparator
                .comparingLong((Integer pid) -> wsMap.getOrDefault(pid, 0L))
                .reversed()
                .thenComparing(Comparator.reverseOrder())
        );
        return pids;
    }

    /**
     * 主窗口 PID 可用时坚持单 PID 高频扫描；
     * 仅在找不到主窗口 PID 时，降级尝试少量 fallback PID，避免单 PID 误锁。
     */
    private List<Integer> buildImageScanPidPlan(int preferredPid) {
        LinkedHashSet<Integer> ordered = new LinkedHashSet<>();
        // 第一优先级：直接锁定 Weixin.exe 中内存最大的 PID
        List<Integer> weixinByMemory = getProcessIdsSortedByMemory("Weixin.exe");
        if (!weixinByMemory.isEmpty()) {
            Integer topWeixinPid = weixinByMemory.get(0);
            if (isPidAlive(topWeixinPid)) {
                ordered.add(topWeixinPid);
            }
        }

        if (preferredPid > 0 && isPidAlive(preferredPid)) {
            ordered.add(preferredPid);
        }
        if (lastImageKeyPid > 0 && isPidAlive(lastImageKeyPid)) {
            ordered.add(lastImageKeyPid);
        }

        List<Integer> mainWindowPids = processManager.getWeChatMainWindowPids();
        for (Integer pid : mainWindowPids) {
            if (isPidAlive(pid)) {
                ordered.add(pid);
            }
        }

        if (!mainWindowPids.isEmpty()) {
            return new ArrayList<>(ordered);
        }

        int fallbackLimit = 3;
        int added = 0;
        for (Integer pid : getFallbackWeChatProcessPids()) {
            if (!isPidAlive(pid) || ordered.contains(pid)) {
                continue;
            }
            ordered.add(pid);
            added++;
            if (added >= fallbackLimit) {
                break;
            }
        }
        return new ArrayList<>(ordered);
    }

    private List<Integer> collectImageKeyCandidatePids(int preferredPid) {
        LinkedHashSet<Integer> ordered = new LinkedHashSet<>();
        if (lastImageKeyPid > 0 && isPidAlive(lastImageKeyPid)) {
            ordered.add(lastImageKeyPid);
        }
        if (preferredPid > 0 && isPidAlive(preferredPid)) {
            ordered.add(preferredPid);
        }
        for (Integer pid : processManager.getWeChatMainWindowPids()) {
            if (isPidAlive(pid)) ordered.add(pid);
        }
        for (String name : List.of("WeiXin.exe", "Weixin.exe", "WeChatAppEx.exe", "WeChat.exe")) {
            for (Integer pid : processManager.getProcessIds(name)) {
                if (isPidAlive(pid)) ordered.add(pid);
            }
        }
        return new ArrayList<>(ordered);
    }

    private int resolveImageScanPid(int preferredPid) {
        if (preferredPid > 0 && isPidAlive(preferredPid)) {
            return preferredPid;
        }
        if (lastImageKeyPid > 0 && isPidAlive(lastImageKeyPid)) {
            return lastImageKeyPid;
        }
        List<Integer> mainWindowPids = processManager.getWeChatMainWindowPids();
        for (Integer pid : mainWindowPids) {
            if (isPidAlive(pid)) {
                return pid;
            }
        }
        return getWeChatPidFallback();
    }

    private ImageKeyExtractor.ImageKeyResult getCachedImageKeyIfFresh() {
        if ((lastImageKey == null || lastImageKey.isBlank())) {
            String propImageKey = System.getProperty("IMAGE_KEY");
            String propXorKey = System.getProperty("XOR_KEY");
            if (propImageKey != null && !propImageKey.isBlank()) {
                lastImageKey = propImageKey;
            }
            if (propXorKey != null && !propXorKey.isBlank()) {
                lastXorKey = propXorKey;
            }
        }
        if (lastImageKey == null || lastImageKey.isBlank()) {
            return null;
        }
        long ageMs = System.currentTimeMillis() - lastImageKeyAtMs;
        if (ageMs > 5 * 60 * 1000L) { // 5 分钟缓存
            return null;
        }
        int xor = -1;
        try {
            if (lastXorKey != null && !lastXorKey.isBlank()) {
                xor = Integer.parseInt(lastXorKey);
            }
        } catch (NumberFormatException ignored) {
        }
        return ImageKeyExtractor.ImageKeyResult.success(xor, lastImageKey);
    }

    private String formatStatus(DllHookLoader.StatusMessage status) {
        return "[" + status.getLevelName() + "] " + status.message;
    }

    private void updateImageKeyState(String imageKey, int xorKey, int pid, java.util.function.Consumer<String> onStatus) {
        this.lastImageKey = imageKey;
        this.lastXorKey = Integer.toString(xorKey);
        this.lastImageKeyAtMs = System.currentTimeMillis();
        this.lastImageKeyPid = pid;
        System.setProperty("IMAGE_KEY", imageKey);
        System.setProperty("XOR_KEY", this.lastXorKey);
        persistImageKeysToEnv(imageKey, this.lastXorKey, onStatus);
    }

    private void persistImageKeysToEnv(String imageKey, String xorKey, java.util.function.Consumer<String> onStatus) {
        Path envPath = Paths.get(".env").toAbsolutePath();
        try {
            List<String> lines = Files.exists(envPath)
                ? Files.readAllLines(envPath, StandardCharsets.UTF_8)
                : new ArrayList<>();

            boolean imageReplaced = false;
            boolean xorReplaced = false;
            for (int i = 0; i < lines.size(); i++) {
                if (lines.get(i).startsWith("IMAGE_KEY=")) {
                    lines.set(i, "IMAGE_KEY=" + imageKey);
                    imageReplaced = true;
                } else if (lines.get(i).startsWith("XOR_KEY=")) {
                    lines.set(i, "XOR_KEY=" + xorKey);
                    xorReplaced = true;
                }
            }
            if (!imageReplaced) lines.add("IMAGE_KEY=" + imageKey);
            if (!xorReplaced) lines.add("XOR_KEY=" + xorKey);

            Files.write(envPath, lines, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            if (onStatus != null) {
                onStatus.accept("Image keys written to " + envPath);
            }
        } catch (IOException e) {
            log.warn("Failed to write image keys to .env: {}", envPath, e);
            if (onStatus != null) {
                onStatus.accept("Warning: Failed to write image keys to .env. Please save manually.");
            }
        }
    }

    private void persistDbKeyToEnv(String key, java.util.function.Consumer<String> onStatus) {
        Path envPath = Paths.get(".env").toAbsolutePath();
        try {
            List<String> lines = Files.exists(envPath)
                ? Files.readAllLines(envPath, StandardCharsets.UTF_8)
                : new ArrayList<>();

            boolean replaced = false;
            for (int i = 0; i < lines.size(); i++) {
                if (lines.get(i).startsWith("WECHAT_DB_KEY=")) {
                    lines.set(i, "WECHAT_DB_KEY=" + key);
                    replaced = true;
                    break;
                }
            }
            if (!replaced) {
                lines.add("WECHAT_DB_KEY=" + key);
            }

            Files.write(envPath, lines, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            onStatus.accept("Key written to " + envPath);
        } catch (IOException e) {
            log.warn("Failed to write .env: {}", envPath, e);
            onStatus.accept("Warning: Failed to write .env. Please save key manually.");
        }
    }

    /**
     * 验证并刷新PID
     */
    private int refreshAliveWeChatPid(int preferredPid, int rounds, long sleepMs) {
        int pid = preferredPid;
        for (int i = 0; i < rounds; i++) {
            if (isPidAlive(pid)) {
                return pid;
            }
            // 窗口PID优先
            List<Integer> windowPids = processManager.getWeChatMainWindowPids();
            if (!windowPids.isEmpty()) {
                for (Integer wp : windowPids) {
                    if (isPidAlive(wp)) {
                        return wp;
                    }
                }
            }
            try {
                Thread.sleep(sleepMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return 0;
            }
        }
        return 0;
    }

    private boolean isPidAlive(int pid) {
        return pid > 0 && ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false);
    }

    /**
     * @deprecated Use isPidAlive instead. Window PIDs don't need process name verification.
     */
    @Deprecated
    private boolean isWeChatPidAlive(int pid) {
        return isPidAlive(pid);
    }

    private String resolveDbKey(String providedDbKey) {
        if (providedDbKey != null && !providedDbKey.isBlank()) {
            return providedDbKey.trim();
        }
        if (lastDbKey != null && !lastDbKey.isBlank()) {
            return lastDbKey.trim();
        }
        String fromProp = System.getProperty("WECHAT_DB_KEY");
        if (fromProp != null && !fromProp.isBlank()) {
            return fromProp.trim();
        }
        String fromEnv = System.getenv("WECHAT_DB_KEY");
        if (fromEnv != null && !fromEnv.isBlank()) {
            return fromEnv.trim();
        }
        String fromDotEnv = readDotEnvValue("WECHAT_DB_KEY");
        if (fromDotEnv != null && !fromDotEnv.isBlank()) {
            return fromDotEnv.trim();
        }
        return null;
    }

    private String resolveImageKey() {
        if (lastImageKey != null && !lastImageKey.isBlank()) {
            return lastImageKey.trim();
        }
        String fromProp = System.getProperty("IMAGE_KEY");
        if (fromProp != null && !fromProp.isBlank()) {
            return fromProp.trim();
        }
        String fromEnv = System.getenv("IMAGE_KEY");
        if (fromEnv != null && !fromEnv.isBlank()) {
            return fromEnv.trim();
        }
        String fromDotEnv = readDotEnvValue("IMAGE_KEY");
        if (fromDotEnv != null && !fromDotEnv.isBlank()) {
            return fromDotEnv.trim();
        }
        return null;
    }

    private String resolveXorKey() {
        if (lastXorKey != null && !lastXorKey.isBlank()) {
            return lastXorKey.trim();
        }
        String fromProp = System.getProperty("XOR_KEY");
        if (fromProp != null && !fromProp.isBlank()) {
            return fromProp.trim();
        }
        String fromEnv = System.getenv("XOR_KEY");
        if (fromEnv != null && !fromEnv.isBlank()) {
            return fromEnv.trim();
        }
        String fromDotEnv = readDotEnvValue("XOR_KEY");
        if (fromDotEnv != null && !fromDotEnv.isBlank()) {
            return fromDotEnv.trim();
        }
        return null;
    }

    private String readDotEnvValue(String key) {
        Path envPath = Paths.get(".env").toAbsolutePath();
        if (!Files.exists(envPath)) {
            return null;
        }
        try {
            for (String line : Files.readAllLines(envPath, StandardCharsets.UTF_8)) {
                String trimmed = line == null ? "" : line.trim();
                if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                    continue;
                }
                String prefix = key + "=";
                if (trimmed.startsWith(prefix)) {
                    return trimmed.substring(prefix.length()).trim();
                }
            }
        } catch (IOException e) {
            log.warn("Failed to read .env: {}", envPath, e);
        }
        return null;
    }

    /** Get current keys */
    public String getLastDbKey() {
        if (lastDbKey == null || lastDbKey.isBlank()) {
            String resolved = resolveDbKey(null);
            if (resolved != null && !resolved.isBlank()) {
                lastDbKey = resolved;
            }
        }
        return lastDbKey;
    }
    public String getLastImageKey() {
        if (lastImageKey == null || lastImageKey.isBlank()) {
            String resolved = resolveImageKey();
            if (resolved != null && !resolved.isBlank()) {
                lastImageKey = resolved;
            }
        }
        return lastImageKey;
    }
    public String getLastXorKey() {
        if (lastXorKey == null || lastXorKey.isBlank()) {
            String resolved = resolveXorKey();
            if (resolved != null && !resolved.isBlank()) {
                lastXorKey = resolved;
            }
        }
        return lastXorKey;
    }
}
