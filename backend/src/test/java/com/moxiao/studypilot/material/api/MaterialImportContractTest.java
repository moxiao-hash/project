package com.moxiao.studypilot.material.api;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import com.moxiao.studypilot.material.infrastructure.MaterialProcessingJobJpaRepository;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = "studypilot.material.storage-root=target/test-material-storage")
@AutoConfigureMockMvc
class MaterialImportContractTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private MaterialProcessingJobJpaRepository jobRepository;

    @BeforeEach
    void clearPendingJobs() {
        jobRepository.deleteAll();
    }

    @Test
    void importsTextThenWorkerClaimsAndDownloadsCanonicalContent() throws Exception {
        String token = registerUser();

        MvcResult imported = mockMvc.perform(post("/api/materials/text")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "title": "Spring 学习笔记",
                                  "content": "依赖注入用于降低对象之间的耦合。",
                                  "category": "PERSONAL_NOTE",
                                  "privacyLevel": "LOCAL_ONLY"
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.materialType").value("TEXT"))
                .andExpect(jsonPath("$.processingStatus").value("PENDING"))
                .andReturn();
        String materialId = readId(imported);

        MvcResult claimed = mockMvc.perform(post("/internal/material-processing-jobs/claim")
                        .header("X-Internal-Service-Token", "test-internal-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"workerId":"pytest-worker","leaseSeconds":60}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.materialId").value(materialId))
                .andExpect(jsonPath("$.privacyLevel").value("LOCAL_ONLY"))
                .andExpect(jsonPath("$.attemptCount").value(1))
                .andReturn();

        mockMvc.perform(get("/internal/materials/{id}/content", materialId)
                        .header("X-Internal-Service-Token", "test-internal-token"))
                .andExpect(status().isOk())
                .andExpect(content().string("依赖注入用于降低对象之间的耦合。"));

        JsonNode job = objectMapper.readTree(claimed.getResponse().getContentAsString());
        mockMvc.perform(post(
                                "/internal/material-processing-jobs/{id}/heartbeat",
                                job.get("jobId").asText()
                        )
                        .header("X-Internal-Service-Token", "test-internal-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"workerId":"pytest-worker","leaseSeconds":60}
                                """))
                .andExpect(status().isOk());
    }

    @Test
    void importsSupportedMultipartFileAndRejectsUnsupportedFile() throws Exception {
        String token = registerUser();
        MockMultipartFile file = new MockMultipartFile(
                "file",
                "guide.md",
                "text/markdown",
                "# Spring Boot\n自动配置".getBytes()
        );

        mockMvc.perform(multipart("/api/materials/files")
                        .file(file)
                        .param("title", "Spring Boot 指南")
                        .param("category", "LEARNING_MATERIAL")
                        .param("privacyLevel", "NORMAL")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.materialType").value("MARKDOWN"));

        MockMultipartFile archive = new MockMultipartFile(
                "file",
                "unsafe.zip",
                "application/zip",
                new byte[]{1, 2, 3}
        );
        mockMvc.perform(multipart("/api/materials/files")
                        .file(archive)
                        .param("title", "压缩包")
                        .param("category", "REFERENCE")
                        .param("privacyLevel", "NORMAL")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isBadRequest());
    }

    @Test
    void rejectsPrivateNetworkWebMaterial() throws Exception {
        String token = registerUser();

        mockMvc.perform(post("/api/materials/web")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "title": "本机管理页",
                                  "url": "http://127.0.0.1:8080/admin",
                                  "category": "REFERENCE",
                                  "privacyLevel": "NORMAL"
                                }
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    void atomicallyCompletesJobAndPersistsCanonicalChunks() throws Exception {
        String token = registerUser();
        MvcResult imported = mockMvc.perform(post("/api/materials/text")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "title": "Java 笔记",
                                  "content": "Java 使用虚拟机运行字节码。",
                                  "category": "LEARNING_MATERIAL",
                                  "privacyLevel": "NORMAL"
                                }
                                """))
                .andExpect(status().isCreated())
                .andReturn();
        String materialId = readId(imported);
        MvcResult claimed = mockMvc.perform(post("/internal/material-processing-jobs/claim")
                        .header("X-Internal-Service-Token", "test-internal-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"workerId":"worker-1","leaseSeconds":60}
                                """))
                .andExpect(status().isOk())
                .andReturn();
        String jobId = objectMapper.readTree(claimed.getResponse().getContentAsString())
                .get("jobId").asText();

        mockMvc.perform(post("/internal/material-processing-jobs/{id}/complete", jobId)
                        .header("X-Internal-Service-Token", "test-internal-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "workerId": "worker-1",
                                  "summary": "Java 运行机制",
                                  "tags": ["Java"],
                                  "knowledgePoints": ["JVM", "字节码"],
                                  "warnings": [],
                                  "contentReference": "qdrant://materials/%s",
                                  "chunks": [
                                    {
                                      "position": 0,
                                      "text": "Java 使用虚拟机运行字节码。",
                                      "locator": "正文 / chunk 1"
                                    }
                                  ]
                                }
                                """.formatted(materialId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"));

        mockMvc.perform(get("/api/materials/{id}", materialId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.processingStatus").value("READY"))
                .andExpect(jsonPath("$.summary").value("Java 运行机制"));

        mockMvc.perform(get("/internal/materials/{id}/chunks", materialId)
                        .header("X-Internal-Service-Token", "test-internal-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].locator").value("正文 / chunk 1"))
                .andExpect(jsonPath("$[0].text").value("Java 使用虚拟机运行字节码。"));
    }

    private String registerUser() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "email": "material-import-%d@example.com",
                                  "password": "Password123!",
                                  "displayName": "资料导入用户"
                                }
                                """.formatted(System.nanoTime())))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString())
                .get("accessToken").asText();
    }

    private String readId(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString())
                .get("id").asText();
    }
}
