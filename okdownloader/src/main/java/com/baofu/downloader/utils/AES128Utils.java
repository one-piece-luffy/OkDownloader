package com.baofu.downloader.utils;

import android.content.Context;
import android.text.TextUtils;
import android.util.Log;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.security.spec.AlgorithmParameterSpec;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * ================================================
 * 作    者：
 * 版    本：
 * 创建日期：2022/01/14
 * 描    述: AES-128 加密解密工具类
 * ================================================
 */
public class AES128Utils {
    private void test(Context context){
        File file= new File(context.getExternalFilesDir("Video"), "Download");
        if (file.exists()) {
            file.mkdir();
        }
        File keyFile = new File(file.getAbsolutePath().toString() + "/encryption.key");
        File ts =new  File(file.getAbsolutePath().toString() + "/a.ts");
        File outfile =new File(file.getAbsolutePath().toString() + "/2.ts");
        byte key[]=readFile(keyFile);
        if(ts.exists()){
            byte[] result= dencryption(readFile(ts),key,null);
            if(result==null)
                return;
            FileOutputStream fos = null;
            try {
                fos = new FileOutputStream(outfile);
                fos.write(result);
            } catch (Exception e) {
                e.printStackTrace();
            }

        }
    }

    /**
     * @param content 待解密的内容
     * @param key 秘钥
     * @param iv 偏移量
     */
    public static byte[] dencryption(byte[] content, byte[] key, String iv) {
        String algorithm = "AES";
        //解密工具初始化

        byte[] ivByte;
        if (TextUtils.isEmpty(iv)) {
            iv = "0000000000000000";
        }
        if (iv.startsWith("0x")) {
            //16进制转二进制
            ivByte = hexStringToByteArray(iv.substring(2));
        } else {
            ivByte = iv.getBytes();
        }
        if (ivByte.length != 16) {
            ivByte = new byte[16];
        }
        //如果m3u8有IV标签，那么IvParameterSpec构造函数就把IV标签后的内容转成字节数组传进去
        AlgorithmParameterSpec ivPs = new IvParameterSpec(ivByte);

        String transformation = "AES/CBC/PKCS5Padding";


        Cipher cipher = null; //解密对象
        try {
            cipher = Cipher.getInstance(transformation);
            SecretKeySpec skey = new SecretKeySpec(key, algorithm);
            cipher.init(Cipher.DECRYPT_MODE, skey, ivPs);
            //解密,输出文件,保存在tempList中,后面合并需要
            if (content != null && content.length % 16 == 0) {
                byte[] result = cipher.doFinal(content);
                return result;
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return null;
    }

    public static byte[] readFile(File file) {
        FileInputStream fis = null;
        BufferedInputStream bis = null;
        try {
            fis = new FileInputStream(file.getAbsoluteFile());
            bis = new BufferedInputStream(fis);
            long fileLength = file.length();

            if (fileLength > Integer.MAX_VALUE) {
                Log.e("a", "OutOfMemoryError");
                return null;
            }
            int remaining = (int) fileLength;
            //自定义缓冲区
            byte[] result = new byte[(int) remaining];
            int offset = 0;
            while (remaining > 0) {
                int read = bis.read(result, offset, remaining);
                if (read < 0) break;
                remaining -= read;
                offset += read;
            }
            return result;
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            //关闭的时候只需要关闭最外层的流就行了
            try {
                if (bis != null) {
                    bis.close();
                }
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
        return null;
    }
    public static byte[] hexStringToByteArray(String s) {
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
}
