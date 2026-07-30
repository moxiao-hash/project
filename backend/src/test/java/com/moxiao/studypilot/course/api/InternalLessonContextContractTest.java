package com.moxiao.studypilot.course.api;

import com.moxiao.studypilot.course.application.CourseCatalogImporter;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class InternalLessonContextContractTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private CourseCatalogImporter importer;

    @Test
    void returnsOwnerScopedLessonContextWithoutCheckpointAnswers() throws Exception {
        importer.importCatalog();
        Registration user = registerUser();

        mockMvc.perform(get("/internal/teaching/lessons/{lessonId}/context",
                        "lesson-rest-controller")
                        .header("X-Internal-Service-Token", "test-internal-token")
                        .param("ownerId", user.id()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lesson.id").value("lesson-rest-controller"))
                .andExpect(jsonPath("$.lesson.progress.status").value("NOT_STARTED"))
                .andExpect(jsonPath("$.lesson.sources[0].bvid").value("BV14z4y1N7pg"))
                .andExpect(jsonPath("$.lesson.content.blocks[3].correctOption").doesNotExist());
    }

    @Test
    void rejectsAnInvalidInternalToken() throws Exception {
        mockMvc.perform(get("/internal/teaching/lessons/lesson-rest-controller/context")
                        .header("X-Internal-Service-Token", "wrong-token")
                        .param("ownerId", UUID.randomUUID().toString()))
                .andExpect(status().isUnauthorized());
    }

    private Registration registerUser() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "email": "lesson-context-%s@example.com",
                                  "password": "Password123!",
                                  "displayName": "课内导师用户"
                                }
                                """.formatted(UUID.randomUUID())))
                .andExpect(status().isCreated())
                .andReturn();
        JsonNode response = objectMapper.readTree(result.getResponse().getContentAsString());
        return new Registration(response.get("user").get("id").asText());
    }

    private record Registration(String id) {
    }
}
