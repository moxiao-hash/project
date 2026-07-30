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
class LessonPracticeWorkflowTest {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired CourseCatalogImporter importer;

    @Test
    void hidesAnswerUntilSubmissionAndGradesCheckpointDeterministically() throws Exception {
        importer.importCatalog();
        String token = register();

        mockMvc.perform(get("/api/lessons/lesson-rest-controller")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.blocks[3].correctOption").doesNotExist())
                .andExpect(jsonPath("$.content.blocks[3].explanation").doesNotExist());

        mockMvc.perform(post(
                                "/api/lessons/lesson-rest-controller/checkpoints/checkpoint/attempts"
                        )
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"selectedOption":0}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.correct").value(true))
                .andExpect(jsonPath("$.explanation").value(
                        "DTO 明确公共契约，避免前端直接控制持久化字段。"
                ))
                .andExpect(jsonPath("$.progress.checkpointPassed").value(true))
                .andExpect(jsonPath("$.progress.practiceCompleted").value(false));
    }

    private String register() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "email":"lesson-practice-%s@example.com",
                                  "password":"Password123!",
                                  "displayName":"课时练习用户"
                                }
                                """.formatted(UUID.randomUUID())))
                .andExpect(status().isCreated())
                .andReturn();
        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        return body.get("accessToken").asText();
    }
}
