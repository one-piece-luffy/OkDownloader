package com.baofu.downloader.utils;

import android.text.TextUtils;
import android.util.Log;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Arrays;

import javax.crypto.Cipher;
import javax.crypto.CipherInputStream;
import javax.crypto.CipherOutputStream;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * ================================================
 * 作    者：
 * 版    本：
 * 创建日期：2022/01/14
 * 描    述: AES-128 加密解密工具类（流式处理版本）
 * 优化点：
 * 1. 支持流式解密，避免OOM
 * 2. 支持大文件处理
 * 3. 更好的异常处理
 * 4. 资源自动管理
 * ================================================
 */
public class AES128Utils {
    private static final String TAG = "AES128Utils";
    private static final String DEFAULT_TRANSFORMATION = "AES/CBC/PKCS5Padding";
    private static final String DEFAULT_ALGORITHM = "AES";
    private static final int BUFFER_SIZE = 8192; // 8KB缓冲区，可调


    /**
     * 流式解密文件（优化版：使用大缓冲区 + NIO）
     * 返回 false + 删除不完整文件
     */
    public static boolean decryptFileStream(File inputFile, File outputFile, byte[] key, String iv) {
        if (inputFile == null || !inputFile.exists() || key == null) {
            Log.e(TAG, "Invalid parameters for decryption");
            return false;
        }

        if (key.length != 16) {
            Log.e(TAG, "Key length must be 16 bytes, but got " + key.length);
            return false;
        }

        try {
            Cipher cipher = prepareCipher(key, iv, Cipher.DECRYPT_MODE);
            if (cipher == null) {
                return false;
            }

            File parentDir = outputFile.getParentFile();
            if (parentDir != null && !parentDir.exists()) {
                if (!parentDir.mkdirs()) {
                    Log.e(TAG, "Failed to create output directory");
                    return false;
                }
            }

            // 根据文件大小和可用内存动态调整缓冲区
            int bufferSize = getOptimalDecryptBufferSize(inputFile.length());
            //使用try-with-resources避免手动close
            try (FileInputStream fis = new FileInputStream(inputFile);
                 BufferedInputStream bis = new BufferedInputStream(fis, bufferSize);
                 CipherInputStream cis = new CipherInputStream(bis, cipher);
                 FileOutputStream fos = new FileOutputStream(outputFile);
                 BufferedOutputStream bos = new BufferedOutputStream(fos, bufferSize)) {

                byte[] buffer = new byte[bufferSize];
                int bytesRead;
                while ((bytesRead = cis.read(buffer)) != -1) {
                    bos.write(buffer, 0, bytesRead);
                }
                bos.flush();
            }

            return true;

        } catch (Exception e) {
            Log.e(TAG, "Decryption failed", e);
            if (outputFile.exists()) {
                outputFile.delete();
            }
            return false;
        }
    }

    /**
     * 获取最优的解密缓冲区大小
     */
    private static int getOptimalDecryptBufferSize(long fileSize) {
//        Runtime runtime = Runtime.getRuntime();
//        long freeMemory = runtime.maxMemory() - (runtime.totalMemory() - runtime.freeMemory());
//
//        // 基础缓冲区大小
//        int baseSize = 64 * 1024;  // 64KB
//
//        // 对于小文件，使用较小的缓冲区
//        if (fileSize < 1024 * 1024) {  // < 1MB
//            return 16 * 1024;  // 16KB
//        }
//
//        // 根据剩余内存调整
//        if (freeMemory > 100 * 1024 * 1024) {  // 剩余内存 > 100MB
//            return 256 * 1024;  // 256KB - 更高性能
//        } else if (freeMemory > 50 * 1024 * 1024) {  // 剩余内存 > 50MB
//            return 128 * 1024;  // 128KB
//        } else {
//            return baseSize;  // 64KB
//        }
        return 64 * 1024;  // 64KB
    }
    /**
     * 流式解密文件（避免OOM）
     * @param inputFile  待解密的文件
     * @param outputFile 解密后的文件
     * @param key        秘钥（16字节）
     * @param iv         偏移量（16字节字符串，可为null）
     * @return 是否成功
     */
    public static boolean decryptFile(File inputFile, File outputFile, byte[] key, String iv) {
        if (inputFile == null || !inputFile.exists() || key == null) {
            Log.e(TAG, "Invalid parameters for decryption");
            return false;
        }

        if (key.length %16!=0) {
            Log.e(TAG, "Key length must be 16 bytes, but got " + key.length);
            return false;
        }

        FileInputStream fis = null;
        FileOutputStream fos = null;
        BufferedInputStream bis = null;
        BufferedOutputStream bos = null;
        CipherInputStream cis = null;

        try {
            // 1. 准备Cipher
            Cipher cipher = prepareCipher(key, iv, Cipher.DECRYPT_MODE);
            if (cipher == null) {
                return false;
            }

            // 2. 打开文件流
            fis = new FileInputStream(inputFile);
            bis = new BufferedInputStream(fis, BUFFER_SIZE);
            cis = new CipherInputStream(bis, cipher);

            // 3. 确保输出目录存在
            File parentDir = outputFile.getParentFile();
            if (parentDir != null && !parentDir.exists()) {
                if (!parentDir.mkdirs()) {
                    Log.e(TAG, "Failed to create output directory: " + parentDir.getAbsolutePath());
                    return false;
                }
            }

            // 4. 流式解密
            fos = new FileOutputStream(outputFile);
            bos = new BufferedOutputStream(fos, BUFFER_SIZE);

            byte[] buffer = new byte[BUFFER_SIZE];
            int bytesRead;
            long totalBytesRead = 0;

            while ((bytesRead = cis.read(buffer)) != -1) {
                bos.write(buffer, 0, bytesRead);
                totalBytesRead += bytesRead;

            }

            bos.flush();

            Log.i(TAG, "File decrypted successfully: " + inputFile.getName() +
                    " -> " + outputFile.getName() + " (" + totalBytesRead + " bytes)");
            return true;

        } catch (Exception e) {
            Log.e(TAG, "Decryption failed for file: " + inputFile.getName(), e);

            // 删除可能不完整的输出文件
            if (outputFile.exists()) {
                if (!outputFile.delete()) {
                    Log.w(TAG, "Failed to delete incomplete output file: " + outputFile.getName());
                }
            }
            return false;

        } finally {
            // 5. 关闭所有流（注意关闭顺序）
            closeQuietly(cis);
            closeQuietly(bos);
            closeQuietly(fos);
            closeQuietly(bis);
            closeQuietly(fis);
        }
    }
    /**
     * 分块解密（适用于网络流）
     * @param data 待解密的数据块
     * @param key 秘钥
     * @param iv 偏移量
     * @param cipher Cipher实例（需外部维护状态）
     * @return 解密后的数据块
     */
    public static byte[] decryptChunk(byte[] data, byte[] key, String iv, Cipher cipher) {
        if (data == null || data.length == 0 || key == null) {
            return null;
        }

        try {
            // 如果cipher为null，创建新的
            if (cipher == null) {
                cipher = prepareCipher(key, iv, Cipher.DECRYPT_MODE);
            }

            if (cipher == null) {
                return null;
            }

            // 解密当前数据块
            return cipher.update(data);

        } catch (Exception e) {
            Log.e(TAG, "Decrypt chunk failed", e);
            return null;
        }
    }

    /**
     * 解密最后的数据块（完成解密）
     * @param data 最后的数据块
     * @param key 秘钥
     * @param iv 偏移量
     * @param cipher Cipher实例
     * @return 解密后的最后数据
     */
    public static byte[] decryptFinalChunk(byte[] data, byte[] key, String iv, Cipher cipher) {
        if (data == null || key == null) {
            return null;
        }

        try {
            // 如果cipher为null，创建新的
            if (cipher == null) {
                cipher = prepareCipher(key, iv, Cipher.DECRYPT_MODE);
            }

            if (cipher == null) {
                return null;
            }

            // 解密最后的数据块（处理padding）
            return cipher.doFinal(data);

        } catch (Exception e) {
            Log.e(TAG, "Decrypt final chunk failed", e);
            return null;
        }
    }

    /**
     * 准备Cipher实例
     */
    private static Cipher prepareCipher(byte[] key, String iv, int mode) {
        try {
            // 处理IV
            byte[] ivBytes = prepareIV(iv);
            if (ivBytes == null) {
                return null;
            }

            // 创建Cipher
            Cipher cipher = Cipher.getInstance(DEFAULT_TRANSFORMATION);
            SecretKeySpec secretKeySpec = new SecretKeySpec(key, DEFAULT_ALGORITHM);
            IvParameterSpec ivParameterSpec = new IvParameterSpec(ivBytes);

            cipher.init(mode, secretKeySpec, ivParameterSpec);
            return cipher;

        } catch (Exception e) {
            Log.e(TAG, "Failed to prepare cipher", e);
            return null;
        }
    }

    /**
     * 处理IV参数
     */
    private static byte[] prepareIV(String iv) {
        if (TextUtils.isEmpty(iv)) {
            iv = "0000000000000000";
        }

        byte[] ivBytes;
        if (iv.startsWith("0x")) {
            // 16进制字符串转字节数组
            ivBytes = hexStringToByteArray(iv.substring(2));
        } else {
            ivBytes = iv.getBytes();
        }

        // 确保IV为16字节
        if (ivBytes.length != 16) {
            if (ivBytes.length > 16) {
                // 截断
                ivBytes = Arrays.copyOf(ivBytes, 16);
            } else {
                // 填充0
                byte[] padded = new byte[16];
                System.arraycopy(ivBytes, 0, padded, 0, ivBytes.length);
                ivBytes = padded;
            }
        }

        return ivBytes;
    }

    /**
     * 保持向后兼容的方法（但建议使用流式版本）
     * @deprecated 建议使用 {@link #decryptFile(File, File, byte[], String)}
     */
    @Deprecated
    public static byte[] dencryption(byte[] content, byte[] key, String iv) {
        try {
            Cipher cipher = prepareCipher(key, iv, Cipher.DECRYPT_MODE);
            if (cipher == null || content == null) {
                return null;
            }

            if (content.length % 16 == 0) {
                return cipher.doFinal(content);
            } else {
                Log.w(TAG, "Content length not multiple of 16: " + content.length);
                return null;
            }

        } catch (Exception e) {
            Log.e(TAG, "Decryption failed", e);
            return null;
        }
    }

    /**
     * 读取文件（保持向后兼容）
     * @deprecated 对于大文件建议使用流式处理
     */
    @Deprecated
    public static byte[] readFile(File file) {
        if (file == null || !file.exists()) {
            return null;
        }

        // 文件太大时警告
        if (file.length() > 10 * 1024 * 1024) { // 10MB
            Log.w(TAG, "Reading large file into memory: " + file.getName() +
                    " (" + file.length() + " bytes). Consider using stream methods.");
        }

        FileInputStream fis = null;
        BufferedInputStream bis = null;

        try {
            fis = new FileInputStream(file);
            bis = new BufferedInputStream(fis);

            long fileLength = file.length();
            if (fileLength > Integer.MAX_VALUE) {
                Log.e(TAG, "File too large: " + fileLength + " bytes");
                return null;
            }

            byte[] result = new byte[(int) fileLength];
            int offset = 0;
            int remaining = (int) fileLength;

            while (remaining > 0) {
                int read = bis.read(result, offset, remaining);
                if (read < 0) break;
                remaining -= read;
                offset += read;
            }

            return result;

        } catch (Exception e) {
            Log.e(TAG, "Failed to read file: " + file.getName(), e);
            return null;
        } finally {
            closeQuietly(bis);
            closeQuietly(fis);
        }
    }

    /**
     * 十六进制字符串转字节数组
     */
    public static byte[] hexStringToByteArray(String s) {
        if (TextUtils.isEmpty(s)) {
            return new byte[0];
        }

        int len = s.length();
        if ((len & 1) == 1) {
            s = "0" + s;
            len++;
        }

        byte[] data = new byte[len / 2];
        for (int i = 0; i < len; i += 2) {
            data[i / 2] = (byte) ((Character.digit(s.charAt(i), 16) << 4)
                    + Character.digit(s.charAt(i + 1), 16));
        }
        return data;
    }

    /**
     * 安全的关闭流
     */
    private static void closeQuietly(InputStream is) {
        if (is != null) {
            try {
                is.close();
            } catch (IOException e) {
                Log.w(TAG, "Failed to close InputStream", e);
            }
        }
    }

    private static void closeQuietly(OutputStream os) {
        if (os != null) {
            try {
                os.close();
            } catch (IOException e) {
                Log.w(TAG, "Failed to close OutputStream", e);
            }
        }
    }

    /**
     * 加密文件（如需使用）
     */
    public static boolean encryptFile(File inputFile, File outputFile, byte[] key, String iv) {
        if (inputFile == null || !inputFile.exists() || key == null || key.length != 16) {
            return false;
        }

        FileInputStream fis = null;
        FileOutputStream fos = null;
        CipherOutputStream cos = null;

        try {
            Cipher cipher = prepareCipher(key, iv, Cipher.ENCRYPT_MODE);
            if (cipher == null) {
                return false;
            }

            fis = new FileInputStream(inputFile);
            fos = new FileOutputStream(outputFile);
            cos = new CipherOutputStream(fos, cipher);

            byte[] buffer = new byte[BUFFER_SIZE];
            int bytesRead;

            while ((bytesRead = fis.read(buffer)) != -1) {
                cos.write(buffer, 0, bytesRead);
            }

            cos.flush();
            return true;

        } catch (Exception e) {
            Log.e(TAG, "Encryption failed", e);
            return false;
        } finally {
            closeQuietly(cos);
            closeQuietly(fos);
            closeQuietly(fis);
        }
    }

    /**
     * 验证key是否为有效的AES-128 key
     */
    public static boolean validateKey(byte[] key) {
        return key != null && key.length == 16;
    }

    /**
     * 生成随机IV（16字节）
     */
    public static byte[] generateRandomIV() {
        byte[] iv = new byte[16];
        new java.security.SecureRandom().nextBytes(iv);
        return iv;
    }

    /**
     * 字节数组转十六进制字符串
     */
    public static String byteArrayToHexString(byte[] bytes) {
        if (bytes == null) return "";
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }
}