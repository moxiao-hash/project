package com.moxiao.studypilot.roadmap.application;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;

final class RoadmapCatalogChecksum {

    private RoadmapCatalogChecksum() {
    }

    static String sha256(ObjectMapper objectMapper, JsonNode root) {
        try {
            byte[] canonicalBytes = objectMapper.writeValueAsBytes(canonicalize(objectMapper, root));
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonicalBytes));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 不可用", exception);
        }
    }

    private static JsonNode canonicalize(ObjectMapper objectMapper, JsonNode node) {
        if (node.isObject()) {
            ObjectNode sorted = objectMapper.createObjectNode();
            node.properties().stream()
                    .sorted(Map.Entry.comparingByKey())
                    .forEach(entry -> sorted.set(entry.getKey(), canonicalize(objectMapper, entry.getValue())));
            return sorted;
        }
        if (node.isArray()) {
            ArrayNode ordered = objectMapper.createArrayNode();
            node.forEach(element -> ordered.add(canonicalize(objectMapper, element)));
            return ordered;
        }
        return node.deepCopy();
    }
}
