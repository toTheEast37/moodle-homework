package cn.example.security;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Arrays;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Component;

@Component
public class CredentialPasswordCipher {
    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int NONCE_BYTES = 12;
    private static final int TAG_BITS = 128;
    private final SecureRandom random = new SecureRandom();

    public EncryptedPassword encrypt(long ownerUserId, String siteCode, byte[] keyBytes, char[] password) {
        requireKey(keyBytes);
        byte[] nonce = new byte[NONCE_BYTES];
        random.nextBytes(nonce);
        byte[] clear = new String(password).getBytes(StandardCharsets.UTF_8);
        try {
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(keyBytes, "AES"), new GCMParameterSpec(TAG_BITS, nonce));
            cipher.updateAAD(aad(ownerUserId, siteCode));
            return new EncryptedPassword(nonce, cipher.doFinal(clear));
        } catch (Exception e) {
            throw new IllegalStateException("加密作业网站密码失败。", e);
        } finally {
            Arrays.fill(clear, (byte) 0);
        }
    }

    public char[] decrypt(long ownerUserId, String siteCode, byte[] keyBytes, byte[] nonce, byte[] ciphertext) {
        requireKey(keyBytes);
        byte[] clear = null;
        try {
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(keyBytes, "AES"), new GCMParameterSpec(TAG_BITS, nonce));
            cipher.updateAAD(aad(ownerUserId, siteCode));
            clear = cipher.doFinal(ciphertext);
            return new String(clear, StandardCharsets.UTF_8).toCharArray();
        } catch (Exception e) {
            throw new IllegalStateException("无法解密该作业网站密码，请检查数据库中的账号密钥和密码密文。", e);
        } finally {
            if (clear != null) Arrays.fill(clear, (byte) 0);
        }
    }

    private static void requireKey(byte[] keyBytes) {
        if (keyBytes == null || keyBytes.length != 32) {
            throw new IllegalStateException("当前提取器账号的密码加密密钥缺失或无效。");
        }
    }

    private static byte[] aad(long ownerUserId, String siteCode) {
        return ("extractor-user:" + ownerUserId + ":site:" + siteCode).getBytes(StandardCharsets.UTF_8);
    }

    public record EncryptedPassword(byte[] nonce, byte[] ciphertext) {}
}
