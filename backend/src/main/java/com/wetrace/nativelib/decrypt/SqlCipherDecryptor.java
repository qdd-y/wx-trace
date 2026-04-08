package com.wetrace.nativelib.decrypt;

import lombok.extern.slf4j.Slf4j;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

/**
 * SQLCipher Decryptor (Pure Java Implementation)
 *
 * Algorithm parameters (matching Go version):
 * - Page size: 4096 bytes
 * - Salt: first 16 bytes of page
 * - Key derivation: PBKDF2-HMAC-SHA512, 256000 iterations
 * - Encryption: AES-256-CBC
 * - Integrity: HMAC-SHA512
 * - Reserve area: 80 bytes (16 IV + 64 HMAC)
 */
@Slf4j
public class SqlCipherDecryptor {

    private static final int PAGE_SIZE = 4096;
    private static final int SALT_SIZE = 16;
    private static final int IV_SIZE = 16;
    private static final int HMAC_SIZE = 64;
    private static final int ITER_COUNT = 256000;
    private static final int KEY_SIZE = 32;
    private static final int RESERVE_SIZE = IV_SIZE + HMAC_SIZE; // 80
    private static final int AESBlockSize = 16;
    private static final String SQLITE_HEADER = "SQLite format 3\u0000";

    private final Path workDir;
    private final ExecutorService executor;

    public SqlCipherDecryptor(Path workDir) {
        this.workDir = workDir;
        this.executor = Executors.newFixedThreadPool(8);
    }

    public int decryptAll(Path srcPath, String dbKeyHex, ProgressCallback onProgress) throws Exception {
        byte[] key = decodeHexKey(dbKeyHex);
        AtomicInteger totalDecrypted = new AtomicInteger(0);

        log.info("Starting decryption for source path: {}", srcPath);
        log.info("Database key: {}", dbKeyHex.substring(0, 16) + "...");

        // First try db_storage subdirectory
        Path dbStorageDir = srcPath.resolve("db_storage");
        List<Path> dbFiles = new ArrayList<>();

        log.info("Checking db_storage directory: {}", dbStorageDir);
        if (Files.exists(dbStorageDir)) {
            log.info("db_storage exists, searching for .db files...");
            dbFiles = findDatabaseFiles(dbStorageDir);
            log.info("Found {} files in db_storage", dbFiles.size());
        } else {
            log.info("db_storage directory does not exist");
        }

        // If no files found in db_storage, try the main directory
        if (dbFiles.isEmpty()) {
            log.info("Trying main directory: {}", srcPath);
            dbFiles = findDatabaseFiles(srcPath);
            log.info("Found {} files in main directory", dbFiles.size());
        }

        if (dbFiles.isEmpty()) {
            throw new Exception("未找到任何微信数据库文件(.db)，请检查路径是否正确。已搜索: " + srcPath);
        }

        log.info("Total database files found: {}", dbFiles.size());

        List<CompletableFuture<Void>> futures = new ArrayList<>();
        for (Path dbFile : dbFiles) {
            futures.add(CompletableFuture.runAsync(() -> {
                try {
                    log.info("Processing file: {} (size: {} bytes)", dbFile, Files.size(dbFile));
                    decryptSingleFile(dbFile, key, srcPath);
                    int done = totalDecrypted.incrementAndGet();
                    log.info("Successfully decrypted: {}", dbFile.getFileName());
                    if (onProgress != null) {
                        onProgress.onProgress(done);
                    }
                } catch (Exception e) {
                    log.error("Failed to decrypt {}: {}", dbFile.getFileName(), e.getMessage());
                }
            }, executor));
        }

        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
        return totalDecrypted.get();
    }

    private List<Path> findDatabaseFiles(Path directory) throws IOException {
        List<Path> dbFiles = new ArrayList<>();
        log.info("Scanning directory: {}", directory);

        try (Stream<Path> files = Files.walk(directory)) {
            List<Path> allFiles = files.toList();
            log.info("Total files found in directory: {}", allFiles.size());

            dbFiles = allFiles.stream()
                .filter(p -> {
                    String name = p.getFileName().toString().toLowerCase();
                    boolean isDb = name.endsWith(".db");
                    boolean isFts = name.contains("fts");
                    if (isDb && !isFts) {
                        log.debug("Including: {}", p);
                    } else if (isDb && isFts) {
                        log.debug("Excluding (FTS): {}", p);
                    }
                    return isDb && !isFts;
                })
                .toList();

            log.info("Database files after filtering: {}", dbFiles.size());
        }

        return dbFiles;
    }

    public void decryptSingleFile(Path srcFile, byte[] key, Path sourceRoot) throws Exception {
        // Check if already decrypted (read first 16 bytes)
        byte[] headerCheck = new byte[16];
        try (RandomAccessFile raf = new RandomAccessFile(srcFile.toFile(), "r")) {
            raf.readFully(headerCheck);
        }
        if ("SQLite format 3".equals(new String(headerCheck, 0, 15, StandardCharsets.UTF_8))) {
            log.debug("Already decrypted, copying: {}", srcFile.getFileName());
            Path relative = sourceRoot.toAbsolutePath().normalize().relativize(srcFile.toAbsolutePath().normalize());
            Path outFile = workDir.resolve(relative);
            Files.createDirectories(outFile.getParent());
            Files.copy(srcFile, outFile, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            return;
        }

        // Seek back to read salt
        try (RandomAccessFile raf = new RandomAccessFile(srcFile.toFile(), "r")) {
            raf.seek(0);
            byte[] page1 = new byte[PAGE_SIZE];
            raf.readFully(page1);

            byte[] salt = Arrays.copyOfRange(page1, 0, SALT_SIZE);
            byte[] encKey = deriveKey(key, salt);
            byte[] hmacKey = deriveHmacKey(encKey, salt);

            // Prepare output file: write SQLite header first
            Path relative = sourceRoot.toAbsolutePath().normalize().relativize(srcFile.toAbsolutePath().normalize());
            Path outFile = workDir.resolve(relative);
            Files.createDirectories(outFile.getParent());
            try (FileOutputStream fos = new FileOutputStream(outFile.toFile())) {
                fos.write(SQLITE_HEADER.getBytes(StandardCharsets.UTF_8));

                long fileSize = Files.size(srcFile);
                int totalPages = (int) ((fileSize + PAGE_SIZE - 1) / PAGE_SIZE);

                // Decrypt first page
                byte[] decPage1 = decryptPage(page1, encKey, hmacKey, 1);

                // Reset and write header + decrypted first page body (like Go version)
                fos.getChannel().position(0);
                fos.getChannel().truncate(0);
                fos.write(SQLITE_HEADER.getBytes(StandardCharsets.UTF_8));
                fos.write(decPage1);

                // Decrypt remaining pages
                byte[] buf = new byte[PAGE_SIZE];
                for (int i = 1; i < totalPages; i++) {
                    int n = raf.read(buf);
                    if (n <= 0) break;

                    if (isAllZero(buf, n)) {
                        fos.write(buf, 0, n);
                        continue;
                    }

                    byte[] decPage = decryptPage(buf, encKey, hmacKey, i + 1);
                    fos.write(decPage);
                }
            }
            log.info("Decrypted: {} -> {}", srcFile.getFileName(), outFile);
        }
    }

    private byte[] decryptPage(byte[] page, byte[] encKey, byte[] hmacKey, int pageNum) throws Exception {
        // Page 1 has salt (16 bytes) at the beginning, others don't
        int offset = (pageNum == 1) ? SALT_SIZE : 0;
        int dataEnd = PAGE_SIZE - RESERVE_SIZE + IV_SIZE;  // 4096 - 80 + 16 = 4032

        // HMAC = HMAC-SHA512(hmacKey, page[offset:dataEnd] + little-endian(pageNum))
        Mac mac = Mac.getInstance("HmacSHA512");
        mac.init(new javax.crypto.spec.SecretKeySpec(hmacKey, "HmacSHA512"));
        mac.update(Arrays.copyOfRange(page, offset, dataEnd));
        // Append 4-byte little-endian page number
        byte[] pageNoBytes = new byte[] {
            (byte) (pageNum & 0xff),
            (byte) ((pageNum >> 8) & 0xff),
            (byte) ((pageNum >> 16) & 0xff),
            (byte) ((pageNum >> 24) & 0xff)
        };
        mac.update(pageNoBytes);
        byte[] calculatedMac = mac.doFinal();

        byte[] storedMac = Arrays.copyOfRange(page, dataEnd, dataEnd + HMAC_SIZE);
        if (!MessageDigest.isEqual(calculatedMac, storedMac)) {
            throw new Exception("HMAC verification failed for page " + pageNum);
        }

        // IV = last 16 bytes of page (before HMAC)
        byte[] iv = Arrays.copyOfRange(page, PAGE_SIZE - RESERVE_SIZE, PAGE_SIZE - RESERVE_SIZE + IV_SIZE);
        // Encrypted data: from offset to PAGE_SIZE - RESERVE_SIZE (same as Go version)
        byte[] encrypted = Arrays.copyOfRange(page, offset, PAGE_SIZE - RESERVE_SIZE);

        // Check if encrypted data length is multiple of AES block size
        if (encrypted.length % AESBlockSize != 0) {
            throw new Exception("ciphertext length is not a multiple of the block size");
        }

        Cipher cipher = Cipher.getInstance("AES/CBC/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(encKey, "AES"), new IvParameterSpec(iv));
        byte[] decrypted = cipher.doFinal(encrypted);

        // Append reserved area (IV + HMAC) unchanged, matching Go version
        byte[] result = new byte[decrypted.length + RESERVE_SIZE];
        System.arraycopy(decrypted, 0, result, 0, decrypted.length);
        System.arraycopy(page, PAGE_SIZE - RESERVE_SIZE, result, decrypted.length, RESERVE_SIZE);
        return result;
    }

    private boolean isAllZero(byte[] buf, int len) {
        for (int i = 0; i < len; i++) {
            if (buf[i] != 0) return false;
        }
        return true;
    }

    private byte[] deriveKey(byte[] key, byte[] salt) throws Exception {
        return pbkdf2HmacSha512(key, salt, ITER_COUNT, KEY_SIZE);
    }

    private byte[] deriveHmacKey(byte[] encKey, byte[] salt) throws Exception {
        // macSalt = salt XOR 0x3a, then PBKDF2(encKey, macSalt, 2, 32)
        byte[] macSalt = new byte[salt.length];
        for (int i = 0; i < salt.length; i++) {
            macSalt[i] = (byte) (salt[i] ^ 0x3a);
        }
        return pbkdf2HmacSha512(encKey, macSalt, 2, KEY_SIZE);
    }


    private byte[] decodeHexKey(String hex) {
        String normalized = hex == null ? "" : hex.trim();
        if (normalized.startsWith("0x") || normalized.startsWith("0X")) {
            normalized = normalized.substring(2);
        }
        if (normalized.length() != 64) {
            throw new IllegalArgumentException("Invalid key length: expected 64 hex chars, got " + normalized.length());
        }
        byte[] key = new byte[normalized.length() / 2];
        for (int i = 0; i < key.length; i++) {
            key[i] = (byte) Integer.parseInt(normalized.substring(i * 2, i * 2 + 2), 16);
        }
        return key;
    }

    private byte[] pbkdf2HmacSha512(byte[] password, byte[] salt, int iterations, int dkLen) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA512");
        mac.init(new SecretKeySpec(password, "HmacSHA512"));

        int hLen = mac.getMacLength();
        int l = (int) Math.ceil((double) dkLen / hLen);
        byte[] output = new byte[l * hLen];

        for (int block = 1; block <= l; block++) {
            byte[] blockSalt = new byte[salt.length + 4];
            System.arraycopy(salt, 0, blockSalt, 0, salt.length);
            blockSalt[salt.length] = (byte) (block >>> 24);
            blockSalt[salt.length + 1] = (byte) (block >>> 16);
            blockSalt[salt.length + 2] = (byte) (block >>> 8);
            blockSalt[salt.length + 3] = (byte) block;

            byte[] u = mac.doFinal(blockSalt);
            byte[] t = Arrays.copyOf(u, u.length);
            for (int i = 1; i < iterations; i++) {
                u = mac.doFinal(u);
                for (int j = 0; j < t.length; j++) {
                    t[j] ^= u[j];
                }
            }

            System.arraycopy(t, 0, output, (block - 1) * hLen, hLen);
        }

        return Arrays.copyOf(output, dkLen);
    }

    public void close() {
        executor.shutdown();
    }

    @FunctionalInterface
    public interface ProgressCallback {
        void onProgress(int completed);
    }
}
