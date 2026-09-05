package com.moxiao.studypilot.agent.runner;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class RunnerSecurityService {

    private static final String HMAC_SHA256 = "HmacSHA256";
    private static final String PROTOCOL_VERSION = "v1";
    private static final Duration DEFAULT_EXPIRY = Duration.ofMinutes(10);

    private final String signingSecret;
    private final SecureRandom secureRandom = new SecureRandom();
    private final Set<String> usedNonces = ConcurrentHashMap.newKeySet();

    public RunnerSecurityService(
            @Value("${studypilot.runner.signing-secret:studypilot-runner-default-secret-key-32b}") String signingSecret
    ) {
        this.signingSecret = signingSecret;
    }

    public RunnerSignedEnvelope createEnvelope(
            String executionId,
            String workspacePath,
            List<String> commandTokens,
            RunnerIsolationMode isolationMode,
            boolean networkDisabled,
            String memoryLimit,
            String cpuLimit,
            int timeoutSeconds
    ) {
        Instant now = Instant.now();
        Instant expiresAt = now.plus(DEFAULT_EXPIRY);
        String nonce = generateNonce();

        String payloadToSign = buildSignaturePayload(
                PROTOCOL_VERSION, executionId, workspacePath, commandTokens,
                isolationMode, networkDisabled, memoryLimit, cpuLimit,
                timeoutSeconds, expiresAt, nonce
        );

        String signature = sign(payloadToSign);

        return new RunnerSignedEnvelope(
                PROTOCOL_VERSION,
                executionId,
                workspacePath,
                commandTokens,
                isolationMode,
                networkDisabled,
                memoryLimit,
                cpuLimit,
                timeoutSeconds,
                expiresAt,
                nonce,
                signature
        );
    }

    public boolean verifyEnvelope(RunnerSignedEnvelope envelope, Instant now) {
        if (envelope == null || envelope.signature() == null || envelope.nonce() == null) {
            return false;
        }

        if (!PROTOCOL_VERSION.equals(envelope.protocolVersion())) {
            return false;
        }

        if (envelope.expiresAt() == null || now.isAfter(envelope.expiresAt())) {
            return false;
        }

        if (!usedNonces.add(envelope.nonce())) {
            return false;
        }

        String expectedPayload = buildSignaturePayload(
                envelope.protocolVersion(),
                envelope.executionId(),
                envelope.workspacePath(),
                envelope.commandTokens(),
                envelope.isolationMode(),
                envelope.networkDisabled(),
                envelope.memoryLimit(),
                envelope.cpuLimit(),
                envelope.timeoutSeconds(),
                envelope.expiresAt(),
                envelope.nonce()
        );

        String expectedSignature = sign(expectedPayload);
        return expectedSignature.equalsIgnoreCase(envelope.signature());
    }

    private String generateNonce() {
        byte[] bytes = new byte[16];
        secureRandom.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private String buildSignaturePayload(
            String protocolVersion,
            String executionId,
            String workspacePath,
            List<String> commandTokens,
            RunnerIsolationMode isolationMode,
            boolean networkDisabled,
            String memoryLimit,
            String cpuLimit,
            int timeoutSeconds,
            Instant expiresAt,
            String nonce
    ) {
        return String.join("|",
                protocolVersion,
                executionId != null ? executionId : "",
                workspacePath != null ? workspacePath : "",
                commandTokens != null ? String.join(" ", commandTokens) : "",
                isolationMode != null ? isolationMode.name() : "",
                String.valueOf(networkDisabled),
                memoryLimit != null ? memoryLimit : "",
                cpuLimit != null ? cpuLimit : "",
                String.valueOf(timeoutSeconds),
                String.valueOf(expiresAt.toEpochMilli()),
                nonce
        );
    }

    private String sign(String data) {
        try {
            Mac mac = Mac.getInstance(HMAC_SHA256);
            SecretKeySpec keySpec = new SecretKeySpec(
                    signingSecret.getBytes(StandardCharsets.UTF_8), HMAC_SHA256);
            mac.init(keySpec);
            byte[] hash = mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to compute HMAC signature", e);
        }
    }
}
