package com.wetrace.nativelib.imagekey;

import com.sun.jna.*;
import com.sun.jna.platform.win32.*;
import com.sun.jna.platform.win32.WinDef.*;
import com.sun.jna.platform.win32.WinNT.HANDLE;
import com.sun.jna.ptr.IntByReference;
import com.wetrace.nativelib.process.ProcessManager;
import lombok.extern.slf4j.Slf4j;

import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 图片密钥提取器
 *
 * 完整复刻 Go 版本 image_key_service.go 的逻辑
 */
@Slf4j
public class ImageKeyExtractor {

    private static final int PID_PROCESS_QUERY_INFORMATION = 0x0400;
    private static final int PID_PROCESS_VM_READ = 0x0010;
    private static final int MEM_COMMIT = 0x1000;
    private static final int MEM_PRIVATE = 0x20000;
    private static final int PAGE_NOACCESS = 0x01;
    private static final int PAGE_GUARD = 0x100;
    private static final byte JPEG_TAIL_1 = (byte) 0xFF;
    private static final byte JPEG_TAIL_2 = (byte) 0xD9;
    private static final byte[] TEMPLATE_SIGNATURE = new byte[]{0x07, 0x08, 0x56, 0x32, 0x08, 0x07};

    public interface Kernel32Ext extends ProcessManager.Kernel32 {
        Kernel32Ext INSTANCE = Native.load("kernel32", Kernel32Ext.class);
        int VirtualQueryEx(HANDLE hProcess, Pointer lpAddress,
                           ProcessManager.MEMORY_BASIC_INFORMATION64 lpBuffer, int dwLength);
    }

    /** 提取图片密钥 */
    public ImageKeyResult extract(int wechatPid, String manualDataPath) throws Exception {
        log.info("开始提取图片密钥 (PID: {})", wechatPid);

        String cacheDir = locateCacheDir(manualDataPath);
        if (cacheDir == null) {
            return ImageKeyResult.failure("未找到微信缓存目录");
        }
        log.info("缓存目录: {}", cacheDir);

        List<String> templates = findTemplateFiles(Paths.get(cacheDir));
        if (templates.isEmpty()) {
            return ImageKeyResult.failure("未找到 _t.dat 模板文件");
        }
        log.info("找到 {} 个模板文件", templates.size());

        int xorKey = calculateXorKey(templates);
        if (xorKey < 0) {
            return ImageKeyResult.failure("无法计算 XOR 密钥");
        }
        log.info("XOR 密钥: 0x{}", Integer.toHexString(xorKey));

        List<byte[]> ciphertexts = extractCiphertexts(templates);
        if (ciphertexts.isEmpty()) {
            return ImageKeyResult.failure("无法提取加密数据");
        }
        log.info("提取到 {} 组模板密文用于校验", ciphertexts.size());

        String aesKey = scanMemoryForAesKey(wechatPid, ciphertexts);
        if (aesKey == null) {
            return ImageKeyResult.failure("内存中未找到 AES 密钥");
        }

        log.info("AES 密钥: {}", aesKey.substring(0, Math.min(16, aesKey.length())));
        return ImageKeyResult.success(xorKey, aesKey);
    }

    private String locateCacheDir(String manualPath) {
        if (manualPath != null && !manualPath.isEmpty()) {
            Path mp = Paths.get(manualPath);
            // 宽松判断：只要 FileStorage 或 db_storage 存在就算有效目录
            if (Files.exists(mp.resolve("FileStorage")) || Files.exists(mp.resolve("db_storage"))) {
                return manualPath;
            }
            // 尝试从父目录扫描账号目录
            String found = scanRootForAccountDir(manualPath);
            if (found != null) return found;
        }
        String home = System.getProperty("user.home");
        String defaultRoot = Paths.get(home, "Documents", "xwechat_files").toString();
        return scanRootForAccountDir(defaultRoot);
    }

    private String scanRootForAccountDir(String root) {
        File rootDir = new File(root);
        if (!rootDir.exists()) return null;
        File[] subdirs = rootDir.listFiles(File::isDirectory);
        if (subdirs == null) return null;
        for (File sub : subdirs) {
            Path sp = sub.toPath();
            if (Files.exists(sp.resolve("FileStorage")) || Files.exists(sp.resolve("db_storage"))) {
                return sub.getAbsolutePath();
            }
        }
        return null;
    }

    private List<String> findTemplateFiles(Path cacheDir) throws IOException {
        List<String> files = new ArrayList<>();
        java.util.regex.Pattern datePattern = java.util.regex.Pattern.compile("(\\d{4}-\\d{2})");
        // 深入遍历 FileStorage/Image/YYYY-MM/ 目录结构，_t.dat 在最深层
        Files.walk(cacheDir, 10)
            .filter(p -> !p.toFile().isDirectory())
            .filter(p -> p.toString().endsWith("_t.dat"))
            .forEach(p -> files.add(p.toString()));
        if (files.isEmpty()) return files;
        files.sort((a, b) -> {
            java.util.regex.Matcher ma = datePattern.matcher(a);
            java.util.regex.Matcher mb = datePattern.matcher(b);
            String da = ma.find() ? ma.group() : "";
            String db = mb.find() ? mb.group() : "";
            return db.compareTo(da);
        });
        return files.size() > 16 ? files.subList(0, 16) : files;
    }

    private int calculateXorKey(List<String> templates) throws IOException {
        Map<String, Integer> lastBytesMap = new HashMap<>();
        for (String fpath : templates) {
            byte[] content = Files.readAllBytes(Paths.get(fpath));
            if (content.length < 2) continue;
            byte last1 = content[content.length - 2];
            byte last2 = content[content.length - 1];
            String key = (last1 & 0xFF) + "_" + (last2 & 0xFF);
            lastBytesMap.put(key, lastBytesMap.getOrDefault(key, 0) + 1);
        }
        if (lastBytesMap.isEmpty()) return -1;
        String mostCommon = lastBytesMap.entrySet().stream()
            .max(Map.Entry.comparingByValue())
            .map(Map.Entry::getKey)
            .orElse(null);
        if (mostCommon != null) {
            String[] parts = mostCommon.split("_");
            int x = Integer.parseInt(parts[0]);
            int y = Integer.parseInt(parts[1]);
            int xorKey = x ^ 0xFF;
            int check = y ^ 0xD9;
            if (xorKey == check) return xorKey;
        }
        return -1;
    }

    private List<byte[]> extractCiphertexts(List<String> templates) throws IOException {
        List<byte[]> ciphertexts = new ArrayList<>();
        Set<String> dedup = new HashSet<>();
        for (String fpath : templates) {
            byte[] content = Files.readAllBytes(Paths.get(fpath));
            if (content.length < 0x1F) continue;

            // 与 Go 版本一致：模板签名和密文直接从原始 _t.dat 提取（不做 XOR）
            if (startsWith(content, TEMPLATE_SIGNATURE)) {
                byte[] ciphertext = new byte[16];
                System.arraycopy(content, 0xF, ciphertext, 0, 16);
                String sig = Base64.getEncoder().encodeToString(ciphertext);
                if (dedup.add(sig)) {
                    ciphertexts.add(ciphertext);
                }
            }
        }
        return ciphertexts;
    }

    private boolean startsWith(byte[] data, byte[] prefix) {
        if (data.length < prefix.length) return false;
        for (int i = 0; i < prefix.length; i++) {
            if (data[i] != prefix[i]) return false;
        }
        return true;
    }

    private String scanMemoryForAesKey(int pid, List<byte[]> ciphertexts) throws Exception {
        HANDLE hProcess = Kernel32Ext.INSTANCE.OpenProcess(
            PID_PROCESS_QUERY_INFORMATION | PID_PROCESS_VM_READ, false, pid);
        if (hProcess == null) {
            throw new RuntimeException("无法打开微信进程 (PID: " + pid + ")");
        }
        try {
            String key = scanMemoryPass(hProcess, ciphertexts);
            if (key != null) return key;
        } finally {
            Kernel32Ext.INSTANCE.CloseHandle(hProcess);
        }
        return null;
    }

    private String scanMemoryPass(HANDLE hProcess, List<byte[]> ciphertexts) throws Exception {
        Pointer base = new Pointer(0);
        int scanned = 0;
        int skipped = 0;
        while (true) {
            ProcessManager.MEMORY_BASIC_INFORMATION64 mbi = new ProcessManager.MEMORY_BASIC_INFORMATION64();
            int ret = Kernel32Ext.INSTANCE.VirtualQueryEx(hProcess, base, mbi, mbi.size());
            if (ret == 0) break;

            boolean committed = mbi.State == MEM_COMMIT;
            boolean privateType = mbi.Type == MEM_PRIVATE;
            boolean readable = (mbi.Protect & PAGE_NOACCESS) == 0 && (mbi.Protect & PAGE_GUARD) == 0;
            boolean sizeOk = mbi.RegionSize > 0 && mbi.RegionSize <= 100L * 1024 * 1024;
            boolean eligible = committed && privateType && readable && sizeOk;

            if (!eligible) {
                skipped++;
                base = new Pointer(Pointer.nativeValue(base) + mbi.RegionSize);
                continue;
            }

            scanned++;
            byte[] data = readProcessMemory(hProcess, base, (int) mbi.RegionSize);
            if (data == null || data.length == 0) {
                base = new Pointer(Pointer.nativeValue(base) + mbi.RegionSize);
                continue;
            }

            String found = findKeyInBuffer(data, ciphertexts);
            if (found != null) {
                return found;
            }
            base = new Pointer(Pointer.nativeValue(base) + mbi.RegionSize);
        }
        log.info("扫描结束(Go模式): 扫描区域={}, 跳过={}", scanned, skipped);
        return null;
    }

    private String findKeyInBuffer(byte[] data, List<byte[]> ciphertexts) {
        // 模式1：与 Go 保持一致 - 32位[a-z0-9]，并且两侧是非[a-z0-9]
        for (int i = 0; i < data.length - 34; i++) {
            if (isAlnumLower(data[i]) || isAlnumLower(data[i + 33])) continue;
            boolean valid = true;
            for (int k = 1; k <= 32; k++) {
                if (!isAlnumLower(data[i + k])) { valid = false; break; }
            }
            if (!valid) continue;

            String candidate32 = new String(data, i + 1, 32, StandardCharsets.US_ASCII);
            if (verifyAny(ciphertexts, candidate32)) {
                return candidate32.substring(0, 16);
            }
        }

        return null;
    }

    private boolean verifyAny(List<byte[]> ciphertexts, String keyStr) {
        for (byte[] ct : ciphertexts) {
            if (verifyKey(ct, keyStr)) {
                return true;
            }
        }
        return false;
    }

    private byte[] readProcessMemory(HANDLE hProcess, Pointer base, int size) {
        byte[] buffer = new byte[size];
        IntByReference read = new IntByReference();
        boolean ok = Kernel32Ext.INSTANCE.ReadProcessMemory(hProcess, base, buffer, size, read);
        if (!ok || read.getValue() == 0) return null;
        if (read.getValue() < size) return Arrays.copyOf(buffer, read.getValue());
        return buffer;
    }

    private boolean isAlnumLower(byte b) {
        return (b >= 'a' && b <= 'z') || (b >= '0' && b <= '9');
    }

    private boolean isAlnumAny(byte b) {
        return (b >= 'a' && b <= 'z')
            || (b >= 'A' && b <= 'Z')
            || (b >= '0' && b <= '9');
    }

    private boolean verifyKey(byte[] ciphertext, String keyStr) {
        if (keyStr == null || keyStr.length() < 16) return false;
        return verifyKey(ciphertext, keyStr.substring(0, 16).getBytes(StandardCharsets.US_ASCII));
    }

    private boolean verifyKey(byte[] ciphertext, byte[] keyBytes) {
        try {
            Cipher cipher = Cipher.getInstance("AES/ECB/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(keyBytes, "AES"));
            byte[] fullDec = cipher.doFinal(ciphertext);
            if (fullDec.length >= 3 && fullDec[0] == (byte) 0xFF
                && fullDec[1] == (byte) 0xD8 && fullDec[2] == (byte) 0xFF) {
                return true;
            }
        } catch (Exception ignored) {}
        return false;
    }

    public static class ImageKeyResult {
        public final int xorKey;
        public final String aesKey;
        public final boolean success;
        public final String error;

        private ImageKeyResult(int xorKey, String aesKey, boolean success, String error) {
            this.xorKey = xorKey;
            this.aesKey = aesKey;
            this.success = success;
            this.error = error;
        }

        public static ImageKeyResult success(int xorKey, String aesKey) {
            return new ImageKeyResult(xorKey, aesKey, true, null);
        }

        public static ImageKeyResult failure(String error) {
            return new ImageKeyResult(-1, null, false, error);
        }
    }
}
