package com.moxiao.studypilot.aicredential.application;

import com.moxiao.studypilot.aicredential.domain.AiProvider;
import org.junit.jupiter.api.Test;

import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AiCredentialCipherTest {

    private static final String MASTER_KEY = Base64.getEncoder()
            .encodeToString(new byte[32]);

    private final AiCredentialCipher cipher = new AiCredentialCipher(MASTER_KEY);

    @Test
    void encryptsWithRandomIvAndDecryptsForMatchingOwnerAndProvider() {
        AiCredentialCipher.EncryptedValue first =
                cipher.encrypt("owner-1", AiProvider.DEEPSEEK, "secret-key");
        AiCredentialCipher.EncryptedValue second =
                cipher.encrypt("owner-1", AiProvider.DEEPSEEK, "secret-key");

        assertNotEquals(first.iv(), second.iv());
        assertNotEquals(first.ciphertext(), second.ciphertext());
        assertEquals(
                "secret-key",
                cipher.decrypt(
                        "owner-1",
                        AiProvider.DEEPSEEK,
                        first.ciphertext(),
                        first.iv()
                )
        );
    }

    @Test
    void rejectsWrongAadAndTamperedCiphertext() {
        AiCredentialCipher.EncryptedValue encrypted =
                cipher.encrypt("owner-1", AiProvider.DEEPSEEK, "secret-key");

        assertThrows(
                AiCredentialSecurityException.class,
                () -> cipher.decrypt(
                        "owner-2",
                        AiProvider.DEEPSEEK,
                        encrypted.ciphertext(),
                        encrypted.iv()
                )
        );
        assertThrows(
                AiCredentialSecurityException.class,
                () -> cipher.decrypt(
                        "owner-1",
                        AiProvider.TAVILY,
                        encrypted.ciphertext(),
                        encrypted.iv()
                )
        );

        byte[] tampered = Base64.getDecoder().decode(encrypted.ciphertext());
        tampered[0] ^= 1;
        assertThrows(
                AiCredentialSecurityException.class,
                () -> cipher.decrypt(
                        "owner-1",
                        AiProvider.DEEPSEEK,
                        Base64.getEncoder().encodeToString(tampered),
                        encrypted.iv()
                )
        );
    }

    @Test
    void rejectsMissingOrInvalidMasterKey() {
        assertThrows(AiCredentialSecurityException.class, () -> new AiCredentialCipher(""));
        assertThrows(AiCredentialSecurityException.class, () -> new AiCredentialCipher("not-base64"));
        assertThrows(
                AiCredentialSecurityException.class,
                () -> new AiCredentialCipher(Base64.getEncoder().encodeToString(new byte[16]))
        );
    }
}
