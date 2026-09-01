package com.moxiao.studypilot.roadmap.api;

import com.moxiao.studypilot.course.application.CourseCatalogImporter;
import com.moxiao.studypilot.roadmap.application.RoadmapCatalogImporter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = "studypilot.roadmap.catalog-import-enabled=false")
@AutoConfigureMockMvc
class RoadmapScheduleWorkflowTest {
    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired CourseCatalogImporter courseCatalogImporter;
    @Autowired RoadmapCatalogImporter roadmapCatalogImporter;

    @BeforeEach
    void importCatalog() {
        courseCatalogImporter.importCatalog();
        roadmapCatalogImporter.importCatalog();
    }

    @Test
    void createsASevenDayShanghaiScheduleWithinDailyCapacityAndPrerequisites() throws Exception {
        Registration owner = register("schedule-default");
        enrollV2(owner.token());

        MvcResult result = mockMvc.perform(get("/api/roadmaps/current/schedule")
                        .header("Authorization", bearer(owner.token())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.timeZone").value("Asia/Shanghai"))
                .andExpect(jsonPath("$.dailyCapacityMinutes").value(60))
                .andExpect(jsonPath("$.weekendsEnabled").value(true))
                .andExpect(jsonPath("$.days.length()").value(7))
                .andReturn();
        JsonNode schedule = objectMapper.readTree(result.getResponse().getContentAsString());
        schedule.get("days").forEach(day ->
                assertThat(day.get("plannedMinutes").asInt()).isLessThanOrEqualTo(60));
        assertThat(schedule.at("/days/0/items/0/nodeCode").asText())
                .isEqualTo("java-environment-first-program");
        assertThat(flattenNodeCodes(schedule)).containsSubsequence(
                "java-environment-first-program", "variables-types-conversion");
    }

    @Test
    void enforcesInclusiveSevenDayBoundsAndOwnerIsolation() throws Exception {
        Registration owner = register("schedule-boundary");
        Registration foreign = register("schedule-foreign");
        enrollV2(owner.token());
        enrollV2(foreign.token());
        LocalDate from = LocalDate.of(2026, 8, 17);

        mockMvc.perform(get("/api/roadmaps/current/schedule")
                        .param("from", from.toString()).param("to", from.plusDays(7).toString())
                        .header("Authorization", bearer(owner.token())))
                .andExpect(status().isBadRequest());
        MvcResult own = schedule(owner.token(), from, from.plusDays(6));
        MvcResult other = schedule(foreign.token(), from, from.plusDays(6));
        assertThat(objectMapper.readTree(own.getResponse().getContentAsString()).get("scheduleId").asText())
                .isNotEqualTo(objectMapper.readTree(other.getResponse().getContentAsString())
                        .get("scheduleId").asText());
    }

    @Test
    void refreshRollsOverduePlansButPreservesStartedAndCompletedDatesIdempotently() throws Exception {
        Registration owner = register("schedule-rollover");
        enrollV2(owner.token());
        LocalDate today = LocalDate.of(2026, 8, 17);
        JsonNode initial = objectMapper.readTree(
                schedule(owner.token(), today, today.plusDays(6)).getResponse().getContentAsString());
        var itemIds = new java.util.ArrayList<String>();
        initial.get("days").forEach(day -> day.get("items").forEach(item -> itemIds.add(item.get("id").asText())));
        assertThat(itemIds).hasSizeGreaterThanOrEqualTo(3);
        jdbcTemplate.update("UPDATE roadmap_schedule_items SET scheduled_date = ?, status = 'PLANNED' WHERE id = ?",
                today.minusDays(1), itemIds.get(0));
        jdbcTemplate.update("UPDATE roadmap_schedule_items SET scheduled_date = ?, status = 'STARTED' WHERE id = ?",
                today.plusDays(4), itemIds.get(1));
        jdbcTemplate.update("UPDATE roadmap_schedule_items SET scheduled_date = ?, status = 'COMPLETED' WHERE id = ?",
                today.minusDays(2), itemIds.get(2));

        JsonNode first = refresh(owner.token(), today);
        JsonNode second = refresh(owner.token(), today);

        assertThat(jdbcTemplate.queryForObject("SELECT scheduled_date FROM roadmap_schedule_items WHERE id = ?",
                LocalDate.class, itemIds.get(0))).isBetween(today, today.plusDays(6));
        assertThat(jdbcTemplate.queryForObject("SELECT scheduled_date FROM roadmap_schedule_items WHERE id = ?",
                LocalDate.class, itemIds.get(1))).isEqualTo(today.plusDays(4));
        assertThat(jdbcTemplate.queryForObject("SELECT scheduled_date FROM roadmap_schedule_items WHERE id = ?",
                LocalDate.class, itemIds.get(2))).isEqualTo(today.minusDays(2));
        assertThat(flattenItemIds(first)).containsExactlyElementsOf(flattenItemIds(second));
    }

    @Test
    void failedQuizAndSettingsChangesRequestDebouncedRefresh() throws Exception {
        Registration owner = register("schedule-trigger");
        enrollV2(owner.token());
        schedule(owner.token(), LocalDate.of(2026, 8, 17), LocalDate.of(2026, 8, 23));
        var refreshedAt = jdbcTemplate.queryForObject(
                "SELECT refreshed_at FROM roadmap_schedule_states WHERE owner_id = ?",
                java.time.OffsetDateTime.class, owner.userId());

        mockMvc.perform(put("/api/user-settings")
                        .header("Authorization", bearer(owner.token()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"timeZone":"Asia/Shanghai","dailyStudyLimitMinutes":90,
                                 "weekendPreference":"SAME","defaultPrivacyLevel":"NORMAL",
                                 "weeklyAvailability":[]}
                                """))
                .andExpect(status().isOk());
        var requestedOnce = jdbcTemplate.queryForObject(
                "SELECT refresh_requested_at FROM roadmap_schedule_states WHERE owner_id = ?",
                java.time.OffsetDateTime.class, owner.userId());
        mockMvc.perform(put("/api/user-settings")
                        .header("Authorization", bearer(owner.token()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"timeZone":"Asia/Shanghai","dailyStudyLimitMinutes":90,
                                 "weekendPreference":"SAME","defaultPrivacyLevel":"NORMAL",
                                 "weeklyAvailability":[]}
                                """))
                .andExpect(status().isOk());
        var requestedTwice = jdbcTemplate.queryForObject(
                "SELECT refresh_requested_at FROM roadmap_schedule_states WHERE owner_id = ?",
                java.time.OffsetDateTime.class, owner.userId());
        assertThat(requestedOnce).isAfter(refreshedAt);
        assertThat(requestedTwice).isEqualTo(requestedOnce);
    }

    private MvcResult schedule(String token, LocalDate from, LocalDate to) throws Exception {
        return mockMvc.perform(get("/api/roadmaps/current/schedule")
                        .param("from", from.toString()).param("to", to.toString())
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk()).andReturn();
    }

    private JsonNode refresh(String token, LocalDate from) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/roadmaps/current/schedule/refresh")
                        .param("from", from.toString()).param("to", from.plusDays(6).toString())
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk()).andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private java.util.List<String> flattenNodeCodes(JsonNode schedule) {
        var result = new java.util.ArrayList<String>();
        schedule.get("days").forEach(day -> day.get("items").forEach(
                item -> result.add(item.get("nodeCode").asText())));
        return result;
    }

    private java.util.List<String> flattenItemIds(JsonNode schedule) {
        var result = new java.util.ArrayList<String>();
        schedule.get("days").forEach(day -> day.get("items").forEach(
                item -> result.add(item.get("id").asText())));
        return result;
    }

    private Registration register(String label) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"%s-%d@example.com","password":"Password123!",
                                 "displayName":"路线排期用户"}
                                """.formatted(label, System.nanoTime())))
                .andExpect(status().isCreated()).andReturn();
        JsonNode response = objectMapper.readTree(result.getResponse().getContentAsString());
        return new Registration(response.get("accessToken").asText(), response.get("user").get("id").asText());
    }

    private void enrollV2(String token) throws Exception {
        mockMvc.perform(post("/api/roadmap-enrollments")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"roadmapCode\":\"studypilot-java-ai\",\"templateVersion\":2}"))
                .andExpect(status().isCreated());
    }

    private static String bearer(String token) { return "Bearer " + token; }
    private record Registration(String token, String userId) { }
}
