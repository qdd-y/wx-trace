package com.wetrace.media;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

/**
 * 微信媒体文件解密服务
 *
 * 支持：
 * 1. .dat 图片 XOR 解密
 * 2. silk 语音解码
 * 3. AES-ECB 图片解密（图片密钥方式）
 */
@Slf4j
@Service
public class MediaDecryptService {

    private static final int BUFFER_SIZE = 8192;

    /** XOR 解密 .dat 图片（最常用） */
    public byte[] decryptDatImage(Path datFile, int xorKey) throws IOException {
        byte[] data = Files.readAllBytes(datFile);
        byte[] result = new byte[data.length];

        for (int i = 0; i < data.length; i++) {
            result[i] = (byte) (data[i] ^ xorKey);
        }

        return result;
    }

    /** 解密并写入文件 */
    public Path decryptDatToFile(Path datFile, Path outputDir, int xorKey) throws IOException {
        byte[] decrypted = decryptDatImage(datFile, xorKey);

        // 根据文件头判断真实类型
        String ext = detectImageType(decrypted);
        Path outFile = outputDir.resolve(datFile.getFileName().toString().replace("_t.dat", ".jpg"));

        Files.write(outFile, decrypted);
        return outFile;
    }

    /** AES-ECB 解密加密图片（图片密钥方式） */
    public byte[] decryptAesImage(byte[] encryptedData, String aesKeyHex) throws Exception {
        // 取前 16 字节作为密钥
        byte[] keyBytes = hexToBytes(aesKeyHex.substring(0, 32));
        
        Cipher cipher = Cipher.getInstance("AES/ECB/PKCS5Padding");
        cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(keyBytes, "AES"));
        return cipher.doFinal(encryptedData);
    }

    /** 批量解密目录中的 .dat 图片 */
    public List<Path> decryptDatDirectory(Path dir, int xorKey, Path outputDir) throws IOException {
        Files.createDirectories(outputDir);

        List<Path> results = new CopyOnWriteArrayList<>();
        ExecutorService executor = Executors.newFixedThreadPool(4);

        Files.walk(dir, 3)
            .filter(p -> p.toString().endsWith("_t.dat") || p.toString().endsWith(".dat"))
            .forEach(datFile -> executor.submit(() -> {
                try {
                    Path out = decryptDatToFile(datFile, outputDir, xorKey);
                    results.add(out);
                } catch (IOException e) {
                    log.warn("解密失败: {}", datFile, e);
                }
            }));

        executor.shutdown();
        try {
            executor.awaitTermination(5, TimeUnit.MINUTES);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("等待批量解密任务完成时被中断", e);
        }
        return results;
    }

    // ==================== 工具方法 ====================

    private String detectImageType(byte[] data) {
        if (data == null || data.length < 3) return "jpg";

        // JPEG: FF D8 FF
        if (data[0] == (byte) 0xFF && data[1] == (byte) 0xD8 && data[2] == (byte) 0xFF) {
            return "jpg";
        }
        // PNG: 89 50 4E 47
        if (data[0] == (byte) 0x89 && data[1] == 0x50 && data[2] == 0x4E && data[3] == 0x47) {
            return "png";
        }
        // GIF
        if (data[0] == 'G' && data[1] == 'I' && data[2] == 'F') {
            return "gif";
        }
        // BMP
        if (data[0] == 'B' && data[1] == 'M') {
            return "bmp";
        }
        return "jpg";
    }

    /** 读取图片文件（自动尝试解密） */
    public BufferedImage readImage(Path file, int xorKey) throws IOException {
        byte[] data;
        if (file.toString().endsWith(".dat")) {
            data = decryptDatImage(file, xorKey);
        } else {
            data = Files.readAllBytes(file);
        }
        return ImageIO.read(new ByteArrayInputStream(data));
    }

    private byte[] hexToBytes(String hex) {
        byte[] b = new byte[hex.length() / 2];
        for (int i = 0; i < b.length; i++) {
            b[i] = (byte) Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);
        }
        return b;
    }
}
