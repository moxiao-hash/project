package com.moxiao.studypilot.aicredential.application;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DefaultCredentialStatusClientTest {

    private static final HttpServer SERVER = createServer();

    @BeforeAll
    static void start() {
        SERVER.createContext("/internal/model/default-credentials", exchange -> {
            assertEquals(
                    "internal-test",
                    exchange.getRequestHeaders().getFirst("X-Internal-Service-Token")
            );
            byte[] response = """
                    {"deepseek":{"configured":true,"maskedSuffix":"-key"},
                     "tavily":{"configured":false,"maskedSuffix":null}}
                    """.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        SERVER.start();
    }

    @AfterAll
    static void stop() {
        SERVER.stop(0);
    }

    @Test
    void readsOnlySafeDefaultMetadata() {
        DefaultCredentialStatusClient client = new DefaultCredentialStatusClient(
                "http://127.0.0.1:" + SERVER.getAddress().getPort(),
                "internal-test",
                new ObjectMapper()
        );

        DefaultCredentialStatusClient.DefaultStatuses statuses = client.fetch();

        assertTrue(statuses.available());
        assertTrue(statuses.deepseek().configured());
        assertEquals("-key", statuses.deepseek().maskedSuffix());
        assertFalse(statuses.tavily().configured());
    }

    private static HttpServer createServer() {
        try {
            return HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }
}
