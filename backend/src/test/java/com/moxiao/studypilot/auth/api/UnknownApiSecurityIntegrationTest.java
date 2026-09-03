package com.moxiao.studypilot.auth.api;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class UnknownApiSecurityIntegrationTest {

    @LocalServerPort
    int port;

    @Autowired
    ObjectMapper objectMapper;

    @Test
    void authenticatedUnknownApiReturnsNotFoundInsteadOfExpiringLogin() throws Exception {
        HttpClient client = HttpClient.newHttpClient();
        HttpResponse<String> registration = client.send(
                HttpRequest.newBuilder(uri("/api/auth/register"))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString("""
                                {
                                  "email": "missing-api-%d@example.com",
                                  "password": "Password123!",
                                  "displayName": "接口兼容测试"
                                }
                                """.formatted(System.nanoTime())))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(registration.statusCode()).isEqualTo(201);
        String token = objectMapper.readTree(registration.body()).get("accessToken").asText();

        HttpResponse<String> missing = client.send(
                HttpRequest.newBuilder(uri("/api/not-present-in-this-backend-version"))
                        .header("Authorization", "Bearer " + token)
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.ofString());

        assertThat(missing.statusCode()).isEqualTo(404);
    }

    private URI uri(String path) {
        return URI.create("http://127.0.0.1:" + port + path);
    }
}
