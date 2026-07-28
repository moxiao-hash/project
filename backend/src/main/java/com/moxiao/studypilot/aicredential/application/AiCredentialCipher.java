package com.moxiao.studypilot.aicredential.application;

import com.moxiao.studypilot.aicredential.domain.AiProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.AEADBadTagException;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * 使用 AES-256-GCM 加密用户 API Key。
 *
 * <p>每次写入都生成新的 96 位 IV。ownerId 与 provider 作为 AAD 参与认证，
 * 因而数据库中的密文不能被复制到另一用户或另一服务商后继续解密。</p>
 */
@Component
public class AiCredentialCipher {

    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;
    private final SecretKeySpec key;
    private final SecureRandom secureRandom = new SecureRandom();

    public AiCredentialCipher(
            @Value("${AI_CREDENTIAL_MASTER_KEY:}") String encodedMasterKey
    ) {
        try {
            byte[] decoded = Base64.getDecoder().decode(encodedMasterKey);
            if (decoded.length != 32) {
                throw new AiCredentialSecurityException("AI 凭据主密钥配置无效");
            }
            this.key = new SecretKeySpec(decoded, "AES");
        } catch (IllegalArgumentException exception) {
            throw new AiCredentialSecurityException("AI 凭据主密钥配置无效");
        }
    }

    public EncryptedValue encrypt(String ownerId, AiProvider provider, String plaintext) {
        byte[] iv = new byte[IV_BYTES];
        secureRandom.nextBytes(iv);
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            cipher.updateAAD(aad(ownerId, provider));
            byte[] encrypted = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            return new EncryptedValue(
                    Base64.getEncoder().encodeToString(encrypted),
                    Base64.getEncoder().encodeToString(iv)
            );
        } catch (GeneralSecurityException exception) {
            throw new AiCredentialSecurityException("AI 凭据加密失败", exception);
        }
    }

    public String decrypt(
            String ownerId,
            AiProvider provider,
            String ciphertext,
            String encodedIv
    ) {
        try {
            byte[] iv = Base64.getDecoder().decode(encodedIv);
            byte[] encrypted = Base64.getDecoder().decode(ciphertext);
            if (iv.length != IV_BYTES) {
                throw new AiCredentialSecurityException("AI 凭据解密失败");
            }
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            cipher.updateAAD(aad(ownerId, provider));
            return new String(cipher.doFinal(encrypted), StandardCharsets.UTF_8);
        } catch (AEADBadTagException exception) {
            throw new AiCredentialSecurityException("AI 凭据解密失败");
        } catch (GeneralSecurityException | IllegalArgumentException exception) {
            throw new AiCredentialSecurityException("AI 凭据解密失败", exception);
        }
    }

    private byte[] aad(String ownerId, AiProvider provider) {
        return (ownerId + ":" + provider.name()).getBytes(StandardCharsets.UTF_8);
    }

    public record EncryptedValue(String ciphertext, String iv) {
    }
}
