package com.moxiao.studypilot.course.api;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class CourseLearningWorkflowTest {

    private static final String COURSE_ID = "course-java-ai";
    private static final String COURSE_SLUG = "studypilot-java-ai";
    private static final String MODULE_ID = "module-spring-rest";
    private static final String LESSON_ID = "lesson-rest-controller";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void seedCourse() {
        Timestamp now = Timestamp.from(Instant.now());
        jdbcTemplate.update("""
                INSERT INTO courses (
                    id, slug, title, description, tech_stack,
                    publication_status, version, created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                COURSE_ID,
                COURSE_SLUG,
                "StudyPilot Java + AI 智能应用开发",
                "通过开发真实项目学习完整技术栈",
                "Java,Spring Boot,Python,FastAPI,Vue",
                "PUBLISHED",
                1,
                now,
                now
        );
        jdbcTemplate.update("""
                INSERT INTO course_modules (
                    id, course_id, module_order, title, description
                ) VALUES (?, ?, ?, ?, ?)
                """,
                MODULE_ID,
                COURSE_ID,
                1,
                "Spring Boot 3 与 REST API",
                "理解浏览器请求如何进入 Java 业务层"
        );
        jdbcTemplate.update("""
                INSERT INTO lessons (
                    id, module_id, lesson_order, slug, title, summary,
                    estimated_minutes, content_json, published
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                LESSON_ID,
                MODULE_ID,
                1,
                "controller-rest-api-validation",
                "Controller、REST API 与参数校验",
                "从注册接口理解 Controller、DTO 和校验",
                90,
                "{\"blocks\":[]}",
                true
        );
    }

    @Test
    void listsPublishedCoursesAndReturnsTheCurrentUsersProgress() throws Exception {
        String token = registerUser("course-list");

        mockMvc.perform(get("/api/courses")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].slug").value(COURSE_SLUG))
                .andExpect(jsonPath("$[0].moduleCount").value(1))
                .andExpect(jsonPath("$[0].lessonCount").value(1))
                .andExpect(jsonPath("$[0].completedLessonCount").value(0))
                .andExpect(jsonPath("$[0].progressPercent").value(0));
    }

    @Test
    void lessonProgressIsIsolatedAndCannotBeCompletedWithoutPractice() throws Exception {
        String ownerOne = registerUser("course-owner-one");
        String ownerTwo = registerUser("course-owner-two");

        mockMvc.perform(put("/api/lessons/{lessonId}/progress", LESSON_ID)
                        .header("Authorization", "Bearer " + ownerOne)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "videoCompleted": true,
                                  "readingCompleted": true,
                                  "lastSectionKey": "summary"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.progress.status").value("IN_PROGRESS"))
                .andExpect(jsonPath("$.progress.videoCompleted").value(true))
                .andExpect(jsonPath("$.progress.readingCompleted").value(true))
                .andExpect(jsonPath("$.progress.practiceCompleted").value(false));

        mockMvc.perform(get("/api/lessons/{lessonId}", LESSON_ID)
                        .header("Authorization", "Bearer " + ownerTwo))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.progress.status").value("NOT_STARTED"))
                .andExpect(jsonPath("$.progress.videoCompleted").value(false))
                .andExpect(jsonPath("$.progress.readingCompleted").value(false))
                .andExpect(jsonPath("$.progress.practiceCompleted").value(false));
    }

    @Test
    void continueLearningReturnsTheFirstPublishedIncompleteLesson() throws Exception {
        String token = registerUser("course-continue");

        mockMvc.perform(get("/api/courses/continue")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(LESSON_ID))
                .andExpect(jsonPath("$.progress.status").value("NOT_STARTED"));
    }

    private String registerUser(String prefix) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "email": "%s-%s@example.com",
                                  "password": "Password123!",
                                  "displayName": "课程用户"
                                }
                                """.formatted(prefix, UUID.randomUUID())))
                .andExpect(status().isCreated())
                .andReturn();
        JsonNode response = objectMapper.readTree(result.getResponse().getContentAsString());
        return response.get("accessToken").asText();
    }
}
