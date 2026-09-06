package com.moxiao.studypilot.agent.runner;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import com.moxiao.studypilot.agent.tool.AgentToolRiskLevel;

@Component
public class RunnerSecurityService {

    private static final String HMAC_SHA256 = "HmacSHA256";
    private static final String PROTOCOL_VERSION = "v1";
    private static final Duration DEFAULT_EXPIRY = Duration.ofMinutes(10);
    private static final String DOCUMENTED_DEFAULT_SECRET =
            "studypilot-runner-default-secret-key-32b";

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
            String ownerId,
            String workspaceId,
            RunnerTemplateType templateType,
            AgentToolRiskLevel riskLevel,
            String workspacePath,
            List<String> commandTokens,
            RunnerIsolationMode isolationMode,
            boolean networkDisabled,
            String memoryLimit,
            String cpuLimit,
            int timeoutSeconds,
            Instant confirmedAt
    ) {
        Instant now = Instant.now();
        Instant expiresAt = now.plus(DEFAULT_EXPIRY);
        String nonce = generateNonce();

        String payloadToSign = buildSignaturePayload(
                PROTOCOL_VERSION, executionId, ownerId, workspaceId, templateType,
                riskLevel, workspacePath, commandTokens,
                isolationMode, networkDisabled, memoryLimit, cpuLimit,
                timeoutSeconds, now, confirmedAt, expiresAt, nonce
        );

        String signature = sign(payloadToSign);

        return new RunnerSignedEnvelope(
                PROTOCOL_VERSION,
                executionId,
                ownerId,
                workspaceId,
                templateType,
                riskLevel,
                workspacePath,
                commandTokens,
                isolationMode,
                networkDisabled,
                memoryLimit,
                cpuLimit,
                timeoutSeconds,
                now,
                confirmedAt,
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

        if (envelope.issuedAt() == null || envelope.expiresAt() == null
                || now.isAfter(envelope.expiresAt())
                || envelope.issuedAt().isAfter(now.plusSeconds(30))
                || envelope.expiresAt().isAfter(envelope.issuedAt().plus(DEFAULT_EXPIRY))) {
            return false;
        }

        String expectedPayload = buildSignaturePayload(
                envelope.protocolVersion(),
                envelope.executionId(),
                envelope.ownerId(),
                envelope.workspaceId(),
                envelope.templateType(),
                envelope.riskLevel(),
                envelope.workspacePath(),
                envelope.commandTokens(),
                envelope.isolationMode(),
                envelope.networkDisabled(),
                envelope.memoryLimit(),
                envelope.cpuLimit(),
                envelope.timeoutSeconds(),
                envelope.issuedAt(),
                envelope.confirmedAt(),
                envelope.expiresAt(),
                envelope.nonce()
        );

        String expectedSignature = sign(expectedPayload);
        if (!MessageDigest.isEqual(
                expectedSignature.getBytes(StandardCharsets.US_ASCII),
                envelope.signature().toLowerCase().getBytes(StandardCharsets.US_ASCII))) {
            return false;
        }
        return usedNonces.add(envelope.nonce());
    }

    private String generateNonce() {
        byte[] bytes = new byte[16];
        secureRandom.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private String buildSignaturePayload(
            String protocolVersion,
            String executionId,
            String ownerId,
            String workspaceId,
            RunnerTemplateType templateType,
            AgentToolRiskLevel riskLevel,
            String workspacePath,
            List<String> commandTokens,
            RunnerIsolationMode isolationMode,
            boolean networkDisabled,
            String memoryLimit,
            String cpuLimit,
            int timeoutSeconds,
            Instant issuedAt,
            Instant confirmedAt,
            Instant expiresAt,
            String nonce
    ) {
        StringBuilder payload = new StringBuilder();
        append(payload, protocolVersion);
        append(payload, executionId);
        append(payload, ownerId);
        append(payload, workspaceId);
        append(payload, templateType == null ? null : templateType.name());
        append(payload, riskLevel == null ? null : riskLevel.name());
        append(payload, workspacePath);
        List<String> tokens = commandTokens == null ? List.of() : commandTokens;
        append(payload, Integer.toString(tokens.size()));
        tokens.forEach(token -> append(payload, token));
        append(payload, isolationMode == null ? null : isolationMode.name());
        append(payload, Boolean.toString(networkDisabled));
        append(payload, memoryLimit);
        append(payload, cpuLimit);
        append(payload, Integer.toString(timeoutSeconds));
        append(payload, epochMillis(issuedAt));
        append(payload, epochMillis(confirmedAt));
        append(payload, epochMillis(expiresAt));
        append(payload, nonce);
        return payload.toString();
    }

    private void append(StringBuilder target, String value) {
        String safe = value == null ? "" : value;
        target.append(safe.length()).append('#').append(safe);
    }

    private String epochMillis(Instant value) {
        return value == null ? "" : Long.toString(value.toEpochMilli());
    }

    private String sign(String data) {
        if (DOCUMENTED_DEFAULT_SECRET.equals(signingSecret)
                || signingSecret.getBytes(StandardCharsets.UTF_8).length < 32) {
            throw new IllegalStateException("必须配置独立且不少于 32 字节的 Runner 签名密钥");
        }
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
