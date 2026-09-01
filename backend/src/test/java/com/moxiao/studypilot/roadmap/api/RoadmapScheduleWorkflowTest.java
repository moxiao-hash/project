package com.moxiao.studypilot.roadmap.api;

import com.moxiao.studypilot.course.application.CourseCatalogImporter;
import com.moxiao.studypilot.roadmap.application.RoadmapCatalogImporter;
import com.moxiao.studypilot.roadmap.application.RoadmapScheduleRefreshProcessor;
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
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "studypilot.roadmap.catalog-import-enabled=false",
        "studypilot.roadmap.schedule-refresh-delay-ms=600000"
})
@AutoConfigureMockMvc
class RoadmapScheduleWorkflowTest {
    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired CourseCatalogImporter courseCatalogImporter;
    @Autowired RoadmapCatalogImporter roadmapCatalogImporter;
    @Autowired RoadmapScheduleRefreshProcessor refreshProcessor;

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
        LocalDate from = today();

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
        LocalDate today = today();
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
        LocalDate today = today();
        schedule(owner.token(), today, today.plusDays(6));
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
        refreshProcessor.processRequested();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT refresh_requested_at IS NULL FROM roadmap_schedule_states "
                        + "WHERE owner_id = ? AND user_roadmap_id = "
                        + "(SELECT id FROM user_roadmaps WHERE owner_id = ? AND active_slot = 'CURRENT')",
                Boolean.class, owner.userId(), owner.userId())).isTrue();
    }

    @Test
    void realCheckInMarksTheProjectedNodeStartedWithoutMovingItsDate() throws Exception {
        Registration owner = register("schedule-check-in-start");
        enrollV2(owner.token());
        LocalDate today = today();
        JsonNode initial = objectMapper.readTree(
                schedule(owner.token(), today, today.plusDays(6)).getResponse().getContentAsString());
        JsonNode firstItem = initial.at("/days/0/items/0");
        String itemId = firstItem.get("id").asText();
        LocalDate originalDate = jdbcTemplate.queryForObject(
                "SELECT scheduled_date FROM roadmap_schedule_items WHERE id = ?",
                LocalDate.class, itemId);

        mockMvc.perform(post("/api/roadmap-nodes/{nodeId}/check-ins", firstItem.get("nodeId").asText())
                        .header("Authorization", bearer(owner.token()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"summary":"我完成了环境搭建并验证 javac 与 java 命令，记录了类路径疑问。",
                                 "idempotencyKey":"schedule-start-check-in"}
                                """))
                .andExpect(status().isCreated());

        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM roadmap_schedule_items WHERE id = ?", String.class, itemId))
                .isEqualTo("STARTED");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT scheduled_date FROM roadmap_schedule_items WHERE id = ?",
                LocalDate.class, itemId)).isEqualTo(originalDate);
    }

    @Test
    void firstScheduleReflectsACheckInThatHappenedBeforeProjectionExisted() throws Exception {
        Registration owner = register("schedule-late-projection");
        enrollV2(owner.token());
        String nodeId = jdbcTemplate.queryForObject("""
                SELECT node_id FROM user_roadmap_nodes
                WHERE owner_id = ? AND availability_status = 'AVAILABLE'
                ORDER BY node_id LIMIT 1
                """, String.class, owner.userId());
        mockMvc.perform(post("/api/roadmap-nodes/{nodeId}/check-ins", nodeId)
                        .header("Authorization", bearer(owner.token()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"summary":"我先完成了环境搭建和命令验证，随后才打开今日学习页面。",
                                 "idempotencyKey":"before-schedule-check-in"}
                                """))
                .andExpect(status().isCreated());
        assertThat(jdbcTemplate.queryForObject(
                "SELECT learning_status FROM user_roadmap_nodes WHERE owner_id = ? AND node_id = ?",
                String.class, owner.userId(), nodeId)).isEqualTo("IN_PROGRESS");

        JsonNode schedule = objectMapper.readTree(mockMvc.perform(
                        get("/api/roadmaps/current/schedule")
                                .header("Authorization", bearer(owner.token())))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());

        var matching = new java.util.ArrayList<JsonNode>();
        schedule.get("days").forEach(day -> day.get("items").forEach(item -> {
            if (item.get("nodeId").asText().equals(nodeId)) {
                matching.add(item);
            }
        }));
        assertThat(matching).singleElement()
                .satisfies(item -> assertThat(item.get("status").asText()).isEqualTo("STARTED"));
    }

    @Test
    void readingHistoricalWindowDoesNotMoveCurrentPlannedItems() throws Exception {
        Registration owner = register("schedule-history-read");
        enrollV2(owner.token());
        LocalDate today = today();
        schedule(owner.token(), today, today.plusDays(6));
        var before = jdbcTemplate.queryForList(
                "SELECT id, scheduled_date FROM roadmap_schedule_items WHERE owner_id = ? ORDER BY id",
                owner.userId());

        schedule(owner.token(), today.minusDays(14), today.minusDays(8));

        assertThat(jdbcTemplate.queryForList(
                "SELECT id, scheduled_date FROM roadmap_schedule_items WHERE owner_id = ? ORDER BY id",
                owner.userId())).isEqualTo(before);
    }

    @Test
    void refreshRepairsCompletedItemWithoutMovingItsHistoricalDate() throws Exception {
        Registration owner = register("schedule-completed-state");
        enrollV2(owner.token());
        LocalDate today = today();
        JsonNode initial = objectMapper.readTree(
                schedule(owner.token(), today, today.plusDays(6)).getResponse().getContentAsString());
        String itemId = initial.at("/days/0/items/0/id").asText();
        LocalDate originalDate = jdbcTemplate.queryForObject(
                "SELECT scheduled_date FROM roadmap_schedule_items WHERE id = ?",
                LocalDate.class, itemId);
        String stateId = jdbcTemplate.queryForObject(
                "SELECT user_roadmap_node_id FROM roadmap_schedule_items WHERE id = ?",
                String.class, itemId);
        jdbcTemplate.update(
                "UPDATE user_roadmap_nodes SET completion_status = 'COMPLETED' WHERE id = ?",
                stateId);

        refresh(owner.token(), today);

        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM roadmap_schedule_items WHERE id = ?", String.class, itemId))
                .isEqualTo("COMPLETED");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT scheduled_date FROM roadmap_schedule_items WHERE id = ?",
                LocalDate.class, itemId)).isEqualTo(originalDate);
    }

    @Test
    void routeUpgradeCreatesANewScheduleAndPreservesOldScheduleItems() throws Exception {
        Registration owner = register("schedule-upgrade");
        enrollV1(owner.token());
        LocalDate today = today();
        JsonNode oldSchedule = objectMapper.readTree(
                schedule(owner.token(), today, today.plusDays(6)).getResponse().getContentAsString());
        String oldEnrollmentId = jdbcTemplate.queryForObject(
                "SELECT id FROM user_roadmaps WHERE owner_id = ? AND active_slot = 'CURRENT'",
                String.class, owner.userId());
        var oldNode = jdbcTemplate.queryForMap(
                "SELECT id, node_id FROM user_roadmap_nodes WHERE user_roadmap_id = ? LIMIT 1",
                oldEnrollmentId);
        String oldItemId = java.util.UUID.randomUUID().toString();
        jdbcTemplate.update("""
                INSERT INTO roadmap_schedule_items
                    (id, schedule_id, owner_id, user_roadmap_id, user_roadmap_node_id,
                     node_id, scheduled_date, planned_minutes, status, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, 60, 'PLANNED', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """, oldItemId, oldSchedule.get("scheduleId").asText(), owner.userId(),
                oldEnrollmentId, oldNode.get("ID"), oldNode.get("NODE_ID"), today);
        jdbcTemplate.update("""
                UPDATE user_roadmaps SET status = 'SUPERSEDED', active_slot = NULL
                WHERE id = ?
                """, oldEnrollmentId);
        enrollV2(owner.token());

        JsonNode current = objectMapper.readTree(
                schedule(owner.token(), today, today.plusDays(6)).getResponse().getContentAsString());

        assertThat(current.get("scheduleId").asText())
                .isNotEqualTo(oldSchedule.get("scheduleId").asText());
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM roadmap_schedule_states WHERE owner_id = ?",
                Integer.class, owner.userId())).isEqualTo(2);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM roadmap_schedule_items WHERE id = ?",
                Integer.class, oldItemId)).isEqualTo(1);
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

    private void enrollV1(String token) throws Exception {
        mockMvc.perform(post("/api/roadmap-enrollments")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"roadmapCode\":\"studypilot-java-ai\",\"templateVersion\":1}"))
                .andExpect(status().isCreated());
    }

    private static LocalDate today() {
        return LocalDate.now(ZoneId.of("Asia/Shanghai"));
    }

    private static String bearer(String token) { return "Bearer " + token; }
    private record Registration(String token, String userId) { }
}
