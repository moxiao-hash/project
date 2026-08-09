# Roadmap Domain Foundation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 建立版本化 Java + AI Roadmap 领域、用户路线绑定、依赖解锁、兼容迁移和可访问路线图页面，让 Roadmap 取代 Course 成为主要学习入口。

**Architecture:** Java 新建独立 `roadmap` 包和 V24 数据结构，MySQL 保存模板与用户进度事实；内置 JSON 只负责幂等导入已发布模板。Vue 通过新的 `/api/roadmaps/**` 公共接口展示路线图和列表。Course/Lesson 保留为只读兼容数据，不删除、不伪造成新节点完成状态。

**Tech Stack:** Java 17、Spring Boot 4、Spring Data JPA、Flyway、MySQL/H2、JUnit 5、MockMvc、Vue 3、TypeScript、Vue Router、Axios、Vitest

---

## Program decomposition

完整设计拆为五个可独立验收的计划，本文件只执行第 1 项：

1. **Roadmap Domain Foundation（本计划）**：模板、阶段、节点、依赖、用户绑定、路线图、版本升级预览、Course/Lesson 兼容。
2. **Rolling Learning Loop**：入门诊断、滚动 7 日计划、打卡、成果验收、节点/阶段完成和复习。
3. **Grounded Learning Assistant**：节点对话、资源搜索缓存、可选分支、节点/阶段测验和引用。
4. **Business Operations Agent**：把全部 Java 业务能力封装为查询、预览、确认、执行工具。
5. **Developer Agent and Local Runner**：代码、测试、Git、浏览器工具及签名信封、敏感数据出口、防火墙和沙箱。

后四份计划在前一阶段实际落地后编写，以真实类名和迁移版本为准，避免提前编造已经变化的文件路径。

## File structure

### Backend files created

```text
backend/src/main/java/com/moxiao/studypilot/roadmap/
├── api/
│   ├── CreateRoadmapEnrollmentRequest.java
│   ├── RoadmapController.java
│   ├── RoadmapEnrollmentResponse.java
│   ├── RoadmapMapResponse.java
│   ├── RoadmapNodeResponse.java
│   ├── RoadmapStageResponse.java
│   ├── RoadmapUpgradeController.java
│   └── RoadmapUpgradeResponse.java
├── application/
│   ├── LegacyCourseMigrationService.java
│   ├── RoadmapCatalogImporter.java
│   ├── RoadmapCatalogValidator.java
│   ├── RoadmapEnrollmentService.java
│   ├── RoadmapQueryService.java
│   └── RoadmapUpgradeService.java
├── config/
│   └── RoadmapCatalogConfiguration.java
├── domain/
│   ├── ArtifactStatus.java
│   ├── AvailabilityStatus.java
│   ├── CheckInStatus.java
│   ├── CompletionStatus.java
│   ├── LearningStatus.java
│   ├── QuizStatus.java
│   ├── RoadmapPublicationStatus.java
│   ├── UserRoadmapStatus.java
│   └── UpgradeStatus.java
└── infrastructure/
    ├── LegacyLearningEvidenceEntity.java
    ├── LegacyLearningEvidenceJpaRepository.java
    ├── LegacyLessonRoadmapMappingEntity.java
    ├── LegacyLessonRoadmapMappingJpaRepository.java
    ├── RoadmapNodeEntity.java
    ├── RoadmapNodeJpaRepository.java
    ├── RoadmapNodePrerequisiteEntity.java
    ├── RoadmapNodePrerequisiteJpaRepository.java
    ├── RoadmapStageEntity.java
    ├── RoadmapStageJpaRepository.java
    ├── RoadmapTemplateEntity.java
    ├── RoadmapTemplateJpaRepository.java
    ├── RoadmapUpgradeEntity.java
    ├── RoadmapUpgradeJpaRepository.java
    ├── UserRoadmapEntity.java
    ├── UserRoadmapJpaRepository.java
    ├── UserRoadmapNodeEntity.java
    └── UserRoadmapNodeJpaRepository.java
```

### Resources and tests created

```text
backend/src/main/resources/db/migration/V24__create_roadmap_foundation.sql
backend/src/main/resources/roadmaps/studypilot-java-ai-v1.json
backend/src/test/java/com/moxiao/studypilot/roadmap/api/RoadmapWorkflowTest.java
backend/src/test/java/com/moxiao/studypilot/roadmap/api/RoadmapUpgradeWorkflowTest.java
backend/src/test/java/com/moxiao/studypilot/roadmap/application/LegacyCourseMigrationServiceTest.java
backend/src/test/java/com/moxiao/studypilot/roadmap/application/RoadmapCatalogImporterTest.java
backend/src/test/java/com/moxiao/studypilot/roadmap/application/RoadmapCatalogValidatorTest.java
backend/src/test/java/com/moxiao/studypilot/roadmap/application/RoadmapEnrollmentServiceTest.java
backend/src/test/java/com/moxiao/studypilot/roadmap/infrastructure/RoadmapEntityMappingTest.java
```

### Frontend files created or modified

```text
web/src/types/roadmap.ts
web/src/services/roadmap.ts
web/src/modules/roadmap/RoadmapView.vue
web/src/modules/roadmap/RoadmapView.spec.ts
web/src/modules/roadmap/StageView.vue
web/src/modules/roadmap/NodeView.vue
web/src/modules/roadmap/components/RoadmapGraph.vue
web/src/modules/roadmap/components/RoadmapList.vue
web/src/modules/roadmap/components/RoadmapNodeCard.vue
web/src/app/router.ts
web/src/components/AppShell.vue
web/src/modules/course/CourseCatalogView.vue
web/src/modules/course/CourseDetailView.vue
web/src/modules/course/LessonView.vue
web/tests/roadmap-navigation.spec.ts
```

## Task 1: Create the Roadmap persistence schema

**Files:**
- Create: `backend/src/test/java/com/moxiao/studypilot/roadmap/infrastructure/RoadmapEntityMappingTest.java`
- Create: `backend/src/main/resources/db/migration/V24__create_roadmap_foundation.sql`
- Create: `backend/src/main/java/com/moxiao/studypilot/roadmap/domain/*.java`
- Create: `backend/src/main/java/com/moxiao/studypilot/roadmap/infrastructure/*.java`

- [ ] **Step 1: Write the failing entity mapping test**

Create a reflection-based test that fails because the Roadmap entities do not exist:

```java
package com.moxiao.studypilot.roadmap.infrastructure;

import jakarta.persistence.Table;
import jakarta.persistence.Version;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

class RoadmapEntityMappingTest {

    @Test
    void mapsVersionedTemplatesAndOrthogonalUserNodeState() {
        assertThat(RoadmapTemplateEntity.class.getAnnotation(Table.class).name())
                .isEqualTo("roadmap_templates");
        assertThat(RoadmapStageEntity.class.getAnnotation(Table.class).name())
                .isEqualTo("roadmap_stages");
        assertThat(RoadmapNodeEntity.class.getAnnotation(Table.class).name())
                .isEqualTo("roadmap_nodes");
        assertThat(UserRoadmapEntity.class.getAnnotation(Table.class).name())
                .isEqualTo("user_roadmaps");
        assertThat(UserRoadmapNodeEntity.class.getAnnotation(Table.class).name())
                .isEqualTo("user_roadmap_nodes");
        assertThat(Arrays.stream(UserRoadmapNodeEntity.class.getDeclaredFields())
                .anyMatch(field -> field.isAnnotationPresent(Version.class))).isTrue();
    }
}
```

- [ ] **Step 2: Run the test and verify RED**

Run:

```bash
cd backend
./mvnw -q -Dtest=RoadmapEntityMappingTest test
```

Expected: test compilation fails because the five entity classes do not exist.

- [ ] **Step 3: Add the exact domain enums**

Create one public enum per file with these values:

```java
public enum RoadmapPublicationStatus { DRAFT, PUBLISHED, RETIRED }
public enum UserRoadmapStatus { ACTIVE, SUPERSEDED, ARCHIVED }
public enum AvailabilityStatus { LOCKED, AVAILABLE }
public enum LearningStatus { NOT_STARTED, SCHEDULED, IN_PROGRESS }
public enum CheckInStatus { MISSING, SUBMITTED }
public enum QuizStatus {
    NOT_GENERATED, GENERATING, READY, EVALUATING,
    PASSED, FAILED, PARTIALLY_GRADED
}
public enum ArtifactStatus {
    NOT_REQUIRED, MISSING, SUBMITTED, ACCEPTED, REJECTED
}
public enum CompletionStatus { INCOMPLETE, COMPLETED }
public enum UpgradeStatus { PREVIEW, COMPLETED, FAILED }
```

Each file starts with:

```java
package com.moxiao.studypilot.roadmap.domain;
```

- [ ] **Step 4: Add the complete V24 migration**

Create `V24__create_roadmap_foundation.sql` with these tables and constraints:

```sql
CREATE TABLE roadmap_templates (
    id VARCHAR(36) PRIMARY KEY,
    roadmap_code VARCHAR(80) NOT NULL,
    template_version INT NOT NULL,
    title VARCHAR(180) NOT NULL,
    description VARCHAR(2000) NOT NULL,
    publication_status VARCHAR(20) NOT NULL,
    content_checksum VARCHAR(64) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    CONSTRAINT uk_roadmap_template_version
        UNIQUE (roadmap_code, template_version)
);

CREATE TABLE roadmap_stages (
    id VARCHAR(80) PRIMARY KEY,
    template_id VARCHAR(36) NOT NULL,
    stage_code VARCHAR(80) NOT NULL,
    stage_order INT NOT NULL,
    title VARCHAR(180) NOT NULL,
    description VARCHAR(2000) NOT NULL,
    graduation_project_title VARCHAR(240) NOT NULL,
    CONSTRAINT fk_roadmap_stage_template
        FOREIGN KEY (template_id) REFERENCES roadmap_templates (id),
    CONSTRAINT uk_roadmap_stage_code UNIQUE (template_id, stage_code),
    CONSTRAINT uk_roadmap_stage_order UNIQUE (template_id, stage_order)
);

CREATE TABLE roadmap_nodes (
    id VARCHAR(100) PRIMARY KEY,
    template_id VARCHAR(36) NOT NULL,
    stage_id VARCHAR(80) NOT NULL,
    node_code VARCHAR(100) NOT NULL,
    node_order INT NOT NULL,
    title VARCHAR(180) NOT NULL,
    objectives_json LONGTEXT NOT NULL,
    high_frequency_json LONGTEXT NOT NULL,
    common_mistakes_json LONGTEXT NOT NULL,
    search_keywords_json LONGTEXT NOT NULL,
    artifact_requirement_json LONGTEXT NOT NULL,
    quiz_blueprint_json LONGTEXT NOT NULL,
    estimated_minutes INT NOT NULL,
    practice_minutes INT NOT NULL,
    difficulty VARCHAR(20) NOT NULL,
    required_node BOOLEAN NOT NULL,
    CONSTRAINT fk_roadmap_node_template
        FOREIGN KEY (template_id) REFERENCES roadmap_templates (id),
    CONSTRAINT fk_roadmap_node_stage
        FOREIGN KEY (stage_id) REFERENCES roadmap_stages (id),
    CONSTRAINT uk_roadmap_node_code UNIQUE (template_id, node_code),
    CONSTRAINT uk_roadmap_node_order UNIQUE (stage_id, node_order)
);

CREATE TABLE roadmap_node_prerequisites (
    id VARCHAR(36) PRIMARY KEY,
    template_id VARCHAR(36) NOT NULL,
    node_id VARCHAR(100) NOT NULL,
    prerequisite_node_id VARCHAR(100) NOT NULL,
    CONSTRAINT fk_roadmap_prerequisite_template
        FOREIGN KEY (template_id) REFERENCES roadmap_templates (id),
    CONSTRAINT fk_roadmap_prerequisite_node
        FOREIGN KEY (node_id) REFERENCES roadmap_nodes (id),
    CONSTRAINT fk_roadmap_prerequisite_required_node
        FOREIGN KEY (prerequisite_node_id) REFERENCES roadmap_nodes (id),
    CONSTRAINT uk_roadmap_prerequisite
        UNIQUE (node_id, prerequisite_node_id)
);

CREATE TABLE user_roadmaps (
    id VARCHAR(36) PRIMARY KEY,
    owner_id VARCHAR(36) NOT NULL,
    template_id VARCHAR(36) NOT NULL,
    status VARCHAR(20) NOT NULL,
    active_slot VARCHAR(20),
    enrolled_at TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    row_version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT fk_user_roadmap_owner
        FOREIGN KEY (owner_id) REFERENCES app_users (id),
    CONSTRAINT fk_user_roadmap_template
        FOREIGN KEY (template_id) REFERENCES roadmap_templates (id),
    CONSTRAINT uk_user_roadmap_template UNIQUE (owner_id, template_id),
    CONSTRAINT uk_user_roadmap_active_slot UNIQUE (owner_id, active_slot)
);

CREATE TABLE user_roadmap_nodes (
    id VARCHAR(36) PRIMARY KEY,
    user_roadmap_id VARCHAR(36) NOT NULL,
    node_id VARCHAR(100) NOT NULL,
    availability_status VARCHAR(20) NOT NULL,
    learning_status VARCHAR(20) NOT NULL,
    check_in_status VARCHAR(20) NOT NULL,
    quiz_status VARCHAR(30) NOT NULL,
    artifact_status VARCHAR(20) NOT NULL,
    completion_status VARCHAR(20) NOT NULL,
    completed_at TIMESTAMP(6),
    updated_at TIMESTAMP(6) NOT NULL,
    row_version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT fk_user_roadmap_node_roadmap
        FOREIGN KEY (user_roadmap_id) REFERENCES user_roadmaps (id),
    CONSTRAINT fk_user_roadmap_node_template_node
        FOREIGN KEY (node_id) REFERENCES roadmap_nodes (id),
    CONSTRAINT uk_user_roadmap_node UNIQUE (user_roadmap_id, node_id)
);

CREATE TABLE roadmap_upgrades (
    id VARCHAR(36) PRIMARY KEY,
    owner_id VARCHAR(36) NOT NULL,
    user_roadmap_id VARCHAR(36) NOT NULL,
    target_template_id VARCHAR(36) NOT NULL,
    status VARCHAR(20) NOT NULL,
    diff_json LONGTEXT NOT NULL,
    idempotency_key VARCHAR(180) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    completed_at TIMESTAMP(6),
    CONSTRAINT fk_roadmap_upgrade_owner
        FOREIGN KEY (owner_id) REFERENCES app_users (id),
    CONSTRAINT fk_roadmap_upgrade_user_roadmap
        FOREIGN KEY (user_roadmap_id) REFERENCES user_roadmaps (id),
    CONSTRAINT fk_roadmap_upgrade_target
        FOREIGN KEY (target_template_id) REFERENCES roadmap_templates (id),
    CONSTRAINT uk_roadmap_upgrade_idempotency
        UNIQUE (owner_id, idempotency_key)
);

CREATE TABLE legacy_lesson_roadmap_mappings (
    lesson_id VARCHAR(80) NOT NULL,
    template_id VARCHAR(36) NOT NULL,
    node_id VARCHAR(100) NOT NULL,
    PRIMARY KEY (lesson_id, template_id),
    CONSTRAINT fk_legacy_mapping_lesson
        FOREIGN KEY (lesson_id) REFERENCES lessons (id),
    CONSTRAINT fk_legacy_mapping_template
        FOREIGN KEY (template_id) REFERENCES roadmap_templates (id),
    CONSTRAINT fk_legacy_mapping_node
        FOREIGN KEY (node_id) REFERENCES roadmap_nodes (id)
);

CREATE TABLE legacy_learning_evidence (
    id VARCHAR(36) PRIMARY KEY,
    owner_id VARCHAR(36) NOT NULL,
    user_roadmap_node_id VARCHAR(36) NOT NULL,
    lesson_id VARCHAR(80) NOT NULL,
    original_status VARCHAR(20) NOT NULL,
    evidence_json LONGTEXT NOT NULL,
    migration_version INT NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    CONSTRAINT fk_legacy_evidence_owner
        FOREIGN KEY (owner_id) REFERENCES app_users (id),
    CONSTRAINT fk_legacy_evidence_user_node
        FOREIGN KEY (user_roadmap_node_id) REFERENCES user_roadmap_nodes (id),
    CONSTRAINT fk_legacy_evidence_lesson
        FOREIGN KEY (lesson_id) REFERENCES lessons (id),
    CONSTRAINT uk_legacy_evidence_migration
        UNIQUE (owner_id, lesson_id, migration_version)
);

CREATE INDEX idx_roadmap_stage_template
    ON roadmap_stages (template_id, stage_order);
CREATE INDEX idx_roadmap_node_stage
    ON roadmap_nodes (stage_id, node_order);
CREATE INDEX idx_roadmap_prerequisite_node
    ON roadmap_node_prerequisites (node_id);
CREATE INDEX idx_user_roadmap_owner
    ON user_roadmaps (owner_id, status);
CREATE INDEX idx_user_roadmap_node_status
    ON user_roadmap_nodes (user_roadmap_id, completion_status);
```

- [ ] **Step 5: Implement JPA entities and repositories**

Map every table with the same constructor/getter style as `CourseEntity`. The critical orthogonal state mapping in `UserRoadmapNodeEntity` must be exactly:

```java
@Enumerated(EnumType.STRING)
@Column(name = "availability_status", nullable = false, length = 20)
private AvailabilityStatus availabilityStatus;

@Enumerated(EnumType.STRING)
@Column(name = "learning_status", nullable = false, length = 20)
private LearningStatus learningStatus;

@Enumerated(EnumType.STRING)
@Column(name = "check_in_status", nullable = false, length = 20)
private CheckInStatus checkInStatus;

@Enumerated(EnumType.STRING)
@Column(name = "quiz_status", nullable = false, length = 30)
private QuizStatus quizStatus;

@Enumerated(EnumType.STRING)
@Column(name = "artifact_status", nullable = false, length = 20)
private ArtifactStatus artifactStatus;

@Enumerated(EnumType.STRING)
@Column(name = "completion_status", nullable = false, length = 20)
private CompletionStatus completionStatus;

@Version
@Column(name = "row_version", nullable = false)
private long version;
```

`RoadmapTemplateEntity` must map immutable catalog identity and `UserRoadmapEntity` must enforce one active enrollment per owner:

```java
@Column(name = "content_checksum", nullable = false, length = 64)
private String contentChecksum;

@Column(name = "active_slot", length = 20)
private String activeSlot;
```

An active enrollment uses `activeSlot = "CURRENT"`; `supersede(now)` sets status to `SUPERSEDED`, clears `activeSlot`, and updates the timestamp. The database unique constraint, not only a service pre-check, prevents two concurrent active enrollments.

The initial-state constructor must use:

```java
this.availabilityStatus = availabilityStatus;
this.learningStatus = LearningStatus.NOT_STARTED;
this.checkInStatus = CheckInStatus.MISSING;
this.quizStatus = QuizStatus.NOT_GENERATED;
this.artifactStatus = artifactRequired
        ? ArtifactStatus.MISSING
        : ArtifactStatus.NOT_REQUIRED;
this.completionStatus = CompletionStatus.INCOMPLETE;
this.updatedAt = now;
```

Required repository methods:

```java
Optional<RoadmapTemplateEntity> findByRoadmapCodeAndTemplateVersion(
        String roadmapCode, int templateVersion);
Optional<RoadmapTemplateEntity> findFirstByRoadmapCodeAndPublicationStatusOrderByTemplateVersionDesc(
        String roadmapCode, RoadmapPublicationStatus status);
List<RoadmapStageEntity> findAllByTemplateIdOrderByStageOrderAsc(String templateId);
List<RoadmapNodeEntity> findAllByTemplateIdOrderByStageIdAscNodeOrderAsc(String templateId);
List<RoadmapNodeEntity> findAllByStageIdOrderByNodeOrderAsc(String stageId);
List<RoadmapNodePrerequisiteEntity> findAllByTemplateId(String templateId);
List<RoadmapNodePrerequisiteEntity> findAllByNodeId(String nodeId);
Optional<UserRoadmapEntity> findByOwnerIdAndStatus(String ownerId, UserRoadmapStatus status);
Optional<UserRoadmapEntity> findByOwnerIdAndTemplateId(String ownerId, String templateId);
List<UserRoadmapNodeEntity> findAllByUserRoadmapId(String userRoadmapId);
Optional<UserRoadmapNodeEntity> findByUserRoadmapIdAndNodeId(
        String userRoadmapId, String nodeId);
```

- [ ] **Step 6: Run the focused test and full entity bootstrap**

Run:

```bash
cd backend
./mvnw -q -Dtest=RoadmapEntityMappingTest test
./mvnw -q -Dtest=StudyPilotApplicationTests test
```

Expected: both commands pass; Hibernate creates all Roadmap tables under H2.

- [ ] **Step 7: Commit**

```bash
git add backend/src/main/java/com/moxiao/studypilot/roadmap/domain \
  backend/src/main/java/com/moxiao/studypilot/roadmap/infrastructure \
  backend/src/main/resources/db/migration/V24__create_roadmap_foundation.sql \
  backend/src/test/java/com/moxiao/studypilot/roadmap/infrastructure/RoadmapEntityMappingTest.java
git commit -m "feat: create roadmap persistence foundation"
```

## Task 2: Validate and import the versioned Java + AI catalog

**Files:**
- Create: `backend/src/test/java/com/moxiao/studypilot/roadmap/application/RoadmapCatalogValidatorTest.java`
- Create: `backend/src/test/java/com/moxiao/studypilot/roadmap/application/RoadmapCatalogImporterTest.java`
- Create: `backend/src/main/java/com/moxiao/studypilot/roadmap/application/RoadmapCatalogValidator.java`
- Create: `backend/src/main/java/com/moxiao/studypilot/roadmap/application/RoadmapCatalogImporter.java`
- Create: `backend/src/main/java/com/moxiao/studypilot/roadmap/config/RoadmapCatalogConfiguration.java`
- Create: `backend/src/main/resources/roadmaps/studypilot-java-ai-v1.json`
- Modify: `backend/src/test/resources/application.properties`

- [ ] **Step 1: Write failing validation tests**

```java
package com.moxiao.studypilot.roadmap.application;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RoadmapCatalogValidatorTest {

    private final RoadmapCatalogValidator validator = new RoadmapCatalogValidator();

    @Test
    void rejectsUnknownPrerequisite() {
        var node = new RoadmapCatalogValidator.Node("java-oop", List.of("missing"));
        assertThatThrownBy(() -> validator.validate(List.of(node)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("missing");
    }

    @Test
    void rejectsCycles() {
        var first = new RoadmapCatalogValidator.Node("a", List.of("b"));
        var second = new RoadmapCatalogValidator.Node("b", List.of("a"));
        assertThatThrownBy(() -> validator.validate(List.of(first, second)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("环");
    }
}
```

- [ ] **Step 2: Run the tests and verify RED**

```bash
cd backend
./mvnw -q -Dtest=RoadmapCatalogValidatorTest test
```

Expected: compilation fails because `RoadmapCatalogValidator` does not exist.

- [ ] **Step 3: Implement deterministic graph validation**

The validator first checks unique codes and existing prerequisites, then uses DFS colors:

```java
public record Node(String code, List<String> prerequisites) {}

public void validate(List<Node> nodes) {
    Map<String, Node> byCode = nodes.stream().collect(Collectors.toMap(
            Node::code,
            Function.identity(),
            (left, right) -> {
                throw new IllegalArgumentException("重复节点编码: " + left.code());
            }
    ));
    for (Node node : nodes) {
        for (String prerequisite : node.prerequisites()) {
            if (!byCode.containsKey(prerequisite)) {
                throw new IllegalArgumentException("前置节点不存在: " + prerequisite);
            }
        }
    }
    Map<String, Visit> visits = new HashMap<>();
    for (Node node : nodes) {
        visit(node.code(), byCode, visits);
    }
}

private void visit(String code, Map<String, Node> byCode, Map<String, Visit> visits) {
    if (visits.get(code) == Visit.VISITING) {
        throw new IllegalArgumentException("路线前置关系存在环: " + code);
    }
    if (visits.get(code) == Visit.VISITED) {
        return;
    }
    visits.put(code, Visit.VISITING);
    for (String prerequisite : byCode.get(code).prerequisites()) {
        visit(prerequisite, byCode, visits);
    }
    visits.put(code, Visit.VISITED);
}

private enum Visit { VISITING, VISITED }
```

- [ ] **Step 4: Create the exact v1 curriculum catalog**

Use `roadmapCode = "studypilot-java-ai"`, `version = 1`, and the following 12 stages. Every row is a node; prerequisites refer to node codes. `R` means required and `O` means optional.

| Stage | Node code | Title | Prerequisite | Type |
|---|---|---|---|---|
| 1 | `java-syntax-oop` | Java 语法、面向对象与代码规范 | — | R |
| 1 | `java-collections-generics` | 集合、泛型与常用工具类 | `java-syntax-oop` | R |
| 1 | `java-exceptions-io` | 异常、IO 与资源管理 | `java-collections-generics` | R |
| 1 | `java-concurrency-jvm` | 并发基础与 JVM 核心概念 | `java-exceptions-io` | R |
| 1 | `java-maven-testing` | Maven、JUnit 与工程结构 | `java-exceptions-io` | R |
| 2 | `spring-ioc-di` | Spring IoC、依赖注入与 Bean | `java-maven-testing` | R |
| 2 | `spring-mvc-rest` | Spring MVC 与 REST API | `spring-ioc-di` | R |
| 2 | `spring-validation-errors` | 参数校验与统一异常处理 | `spring-mvc-rest` | R |
| 2 | `spring-config-logging` | 配置、Profile 与日志 | `spring-mvc-rest` | R |
| 2 | `spring-files-scheduling` | 文件与定时任务 | `spring-validation-errors` | R |
| 3 | `mysql-sql-modeling` | MySQL、SQL 与关系建模 | `spring-mvc-rest` | R |
| 3 | `mysql-index-transaction` | 索引、事务与锁 | `mysql-sql-modeling` | R |
| 3 | `mybatis-core` | MyBatis 映射与动态 SQL | `mysql-sql-modeling` | R |
| 3 | `mybatis-plus` | MyBatis-Plus 工程实践 | `mybatis-core` | R |
| 3 | `jpa-core` | JPA 实体、Repository 与事务 | `mysql-index-transaction` | R |
| 3 | `data-access-comparison` | MyBatis 与 JPA 选型实践 | `mybatis-plus,jpa-core` | R |
| 4 | `redis-cache` | Redis 与缓存设计 | `data-access-comparison` | R |
| 4 | `auth-jwt-security` | JWT、认证授权与接口安全 | `spring-validation-errors` | R |
| 4 | `api-docs-integration-test` | API 文档与集成测试 | `spring-validation-errors` | R |
| 4 | `idempotency-concurrency-audit` | 幂等、并发控制与审计 | `mysql-index-transaction,auth-jwt-security` | R |
| 4 | `monitoring-observability` | 健康检查、指标与故障定位 | `spring-config-logging` | R |
| 5 | `vue-ts-basics` | Vue 3 与 TypeScript 基础 | `spring-mvc-rest` | R |
| 5 | `vue-router-pinia` | Router、Pinia 与组件状态 | `vue-ts-basics` | R |
| 5 | `frontend-api-integration` | Axios、认证与前后端联调 | `vue-router-pinia,auth-jwt-security` | R |
| 5 | `git-linux-nginx` | Git、Linux 与 Nginx | `java-maven-testing` | R |
| 5 | `docker-delivery` | Docker 与本地交付 | `frontend-api-integration,git-linux-nginx` | R |
| 6 | `python-engineering` | Python 语法与项目工程化 | `java-maven-testing` | R |
| 6 | `pydantic` | Pydantic 数据契约 | `python-engineering` | R |
| 6 | `fastapi-rest` | FastAPI 与 REST API | `pydantic` | R |
| 6 | `python-async-http` | Python 异步与 HTTP 客户端 | `fastapi-rest` | R |
| 6 | `java-python-contract` | Java 与 Python 内部契约 | `python-async-http,spring-mvc-rest` | R |
| 7 | `llm-api-basics` | DeepSeek API 与模型参数 | `java-python-contract` | R |
| 7 | `prompt-engineering` | 提示词与上下文设计 | `llm-api-basics` | R |
| 7 | `structured-output` | 结构化输出与确定性校验 | `prompt-engineering,pydantic` | R |
| 7 | `model-cost-retry` | 成本、超时、重试与降级 | `llm-api-basics` | R |
| 7 | `llm-security` | 提示注入与敏感数据保护 | `structured-output,auth-jwt-security` | R |
| 8 | `langgraph-state` | LangGraph 状态图 | `structured-output` | R |
| 8 | `langgraph-memory` | 对话上下文与 Checkpointer | `langgraph-state` | R |
| 8 | `tool-calling` | Tool Calling 与业务工具 | `langgraph-state` | R |
| 8 | `human-in-loop` | 预览、确认与 Human-in-the-loop | `tool-calling` | R |
| 8 | `mcp` | MCP 协议与工具互操作 | `tool-calling` | R |
| 8 | `spring-ai-elective` | Spring AI Tool/MCP 对照 | `tool-calling` | O |
| 9 | `document-parsing` | 文档解析与结构化分段 | `java-python-contract` | R |
| 9 | `embedding-qdrant` | Embedding 与 Qdrant | `document-parsing` | R |
| 9 | `hybrid-retrieval` | Dense、Sparse 与混合检索 | `embedding-qdrant` | R |
| 9 | `tavily-search` | Tavily 联网搜索与缓存 | `model-cost-retry` | R |
| 9 | `grounded-answer` | 带引用的 Grounded Answer | `hybrid-retrieval,tavily-search` | R |
| 9 | `adaptive-assessment` | 自适应测验与掌握度 | `grounded-answer` | R |
| 10 | `business-tool-contracts` | Java 业务工具契约 | `human-in-loop,idempotency-concurrency-audit` | R |
| 10 | `intent-preview` | 意图识别与结构化预览 | `business-tool-contracts` | R |
| 10 | `authorization-governance` | 授权、风险与执行治理 | `intent-preview,llm-security` | R |
| 10 | `idempotent-execution` | 幂等执行与失败恢复 | `authorization-governance` | R |
| 10 | `business-agent-e2e` | 业务 Agent 端到端闭环 | `idempotent-execution` | R |
| 11 | `repo-read-search` | 代码仓库读取与检索 | `business-agent-e2e` | R |
| 11 | `patch-diff` | 补丁与 Diff 预览 | `repo-read-search` | R |
| 11 | `test-build-tools` | 测试与构建工具 | `patch-diff` | R |
| 11 | `git-tools` | 受控 Git 工具 | `test-build-tools` | R |
| 11 | `playwright-dom` | Playwright 与 DOM 操作 | `frontend-api-integration,test-build-tools` | R |
| 11 | `accessibility-desktop` | 无障碍树与桌面保底操作 | `playwright-dom` | O |
| 12 | `runner-sandbox` | Local Runner 沙箱 | `test-build-tools,llm-security` | R |
| 12 | `secret-redaction` | 敏感路径与输出脱敏 | `runner-sandbox` | R |
| 12 | `network-firewall` | 服务边界与防火墙 | `runner-sandbox,docker-delivery` | R |
| 12 | `observability-recovery` | Agent 可观测性与恢复 | `secret-redaction,monitoring-observability` | R |
| 12 | `release-e2e` | 全链路发布验收 | `network-firewall,observability-recovery,git-tools` | R |

For every node, the JSON must contain non-empty arrays for `objectives`, `highFrequency`, `commonMistakes`, and `searchKeywords`; `quizBlueprint` contains exactly 5 knowledge focus entries. Estimated learning time is 60–120 minutes and practice time is 30–120 minutes. No video URL or copied course body is stored.

Include the known compatibility mapping in the initial immutable v1 JSON so it does not require changing the checksum later:

```json
"legacyLessonMappings": [
  { "lessonId": "lesson-rest-controller", "nodeCode": "spring-mvc-rest" }
]
```

Task 2 deserializes and validates this array but does not persist it yet; Task 7 adds persistence after startup ordering is covered by tests.

- [ ] **Step 5: Write the failing importer test**

```java
@SpringBootTest
@Transactional
class RoadmapCatalogImporterTest {

    @Autowired RoadmapCatalogImporter importer;
    @Autowired RoadmapTemplateJpaRepository templates;
    @Autowired RoadmapStageJpaRepository stages;
    @Autowired RoadmapNodeJpaRepository nodes;
    @Autowired RoadmapNodePrerequisiteJpaRepository prerequisites;

    @Test
    void importsTwelveStagesAndTheVersionedDependencyGraphIdempotently() {
        importer.importCatalog();
        importer.importCatalog();

        assertThat(templates.count()).isEqualTo(1);
        assertThat(stages.count()).isEqualTo(12);
        assertThat(nodes.count()).isEqualTo(64);
        assertThat(prerequisites.count()).isEqualTo(79);
        assertThat(templates.findByRoadmapCodeAndTemplateVersion(
                "studypilot-java-ai", 1
        )).get().getPublicationStatus()).isEqualTo(
                RoadmapPublicationStatus.PUBLISHED
        );
    }
}
```

The table above contains exactly 64 node rows and 79 prerequisite edges. Keep the JSON and both assertions exact; do not weaken either assertion to `isGreaterThan`.

- [ ] **Step 6: Implement import and startup configuration**

`RoadmapCatalogImporter` must deserialize records, call the validator before any save, and use deterministic IDs:

```java
private String templateId(Catalog catalog) {
    return catalog.roadmapCode() + "-v" + catalog.version();
}

private String stageId(Catalog catalog, Stage stage) {
    return templateId(catalog) + "-" + stage.code();
}

private String nodeId(Catalog catalog, Node node) {
    return templateId(catalog) + "-" + node.code();
}
```

Save the template, all stages, all nodes, then prerequisite rows. Use deterministic prerequisite IDs derived from `nodeId + "--" + prerequisiteNodeId`, hashed to a 36-character UUID string. Compute SHA-256 over the normalized catalog JSON and store it as `contentChecksum`. Running the importer twice with the same checksum performs no writes; the same `(roadmapCode, version)` with a different checksum throws `IllegalStateException("已发布路线版本不可修改")` instead of silently changing published content.

Create startup configuration:

```java
@Configuration
public class RoadmapCatalogConfiguration {

    @Bean
    @ConditionalOnProperty(
            name = "studypilot.roadmap.catalog-import-enabled",
            havingValue = "true",
            matchIfMissing = true
    )
    ApplicationRunner roadmapCatalogRunner(RoadmapCatalogImporter importer) {
        return arguments -> importer.importCatalog();
    }
}
```

Add to test properties:

```properties
studypilot.roadmap.catalog-import-enabled=false
```

- [ ] **Step 7: Run focused and full backend tests**

```bash
cd backend
./mvnw -q -Dtest=RoadmapCatalogValidatorTest,RoadmapCatalogImporterTest test
./mvnw -q test
```

Expected: both commands pass; existing Course tests remain green.

- [ ] **Step 8: Commit**

```bash
git add backend/src/main/java/com/moxiao/studypilot/roadmap/application/RoadmapCatalogImporter.java \
  backend/src/main/java/com/moxiao/studypilot/roadmap/application/RoadmapCatalogValidator.java \
  backend/src/main/java/com/moxiao/studypilot/roadmap/config/RoadmapCatalogConfiguration.java \
  backend/src/main/resources/roadmaps/studypilot-java-ai-v1.json \
  backend/src/test/java/com/moxiao/studypilot/roadmap/application/RoadmapCatalogImporterTest.java \
  backend/src/test/java/com/moxiao/studypilot/roadmap/application/RoadmapCatalogValidatorTest.java \
  backend/src/test/resources/application.properties
git commit -m "feat: import versioned java ai roadmap"
```

## Task 3: Enroll a user and initialize node state

**Files:**
- Create: `backend/src/test/java/com/moxiao/studypilot/roadmap/application/RoadmapEnrollmentServiceTest.java`
- Create: `backend/src/main/java/com/moxiao/studypilot/roadmap/application/RoadmapEnrollmentService.java`
- Create: `backend/src/main/java/com/moxiao/studypilot/roadmap/api/CreateRoadmapEnrollmentRequest.java`
- Create: `backend/src/main/java/com/moxiao/studypilot/roadmap/api/RoadmapEnrollmentResponse.java`
- Create: `backend/src/main/java/com/moxiao/studypilot/roadmap/api/RoadmapController.java`

- [ ] **Step 1: Write failing service tests**

Test these exact rules with Mockito or H2 repositories:

```java
@Test
void enrollmentCreatesOneUserNodePerTemplateNodeAndUnlocksOnlyRoots() {
    RoadmapEnrollmentResponse result = service.enroll(
            "owner-1", "studypilot-java-ai", 1
    );

    assertThat(result.status()).isEqualTo("ACTIVE");
    assertThat(userNodes.findAllByUserRoadmapId(result.id()))
            .extracting(UserRoadmapNodeEntity::getAvailabilityStatus)
            .contains(AvailabilityStatus.AVAILABLE, AvailabilityStatus.LOCKED);
    assertThat(userNodes.findAllByUserRoadmapId(result.id()))
            .filteredOn(node -> prerequisites.findAllByNodeId(node.getNodeId()).isEmpty())
            .allMatch(node -> node.getAvailabilityStatus() == AvailabilityStatus.AVAILABLE);
}

@Test
void repeatedEnrollmentReturnsTheExistingEnrollment() {
    var first = service.enroll("owner-1", "studypilot-java-ai", 1);
    var second = service.enroll("owner-1", "studypilot-java-ai", 1);

    assertThat(second.id()).isEqualTo(first.id());
    assertThat(userRoadmaps.count()).isEqualTo(1);
}
```

- [ ] **Step 2: Run and verify RED**

```bash
cd backend
./mvnw -q -Dtest=RoadmapEnrollmentServiceTest test
```

Expected: compilation fails because the service and response do not exist.

- [ ] **Step 3: Implement enrollment transaction**

Use this service signature and root-node rule:

```java
@Transactional
public RoadmapEnrollmentResponse enroll(
        String ownerId,
        String roadmapCode,
        int templateVersion
) {
    RoadmapTemplateEntity template = templates
            .findByRoadmapCodeAndTemplateVersion(roadmapCode, templateVersion)
            .filter(item -> item.getPublicationStatus()
                    == RoadmapPublicationStatus.PUBLISHED)
            .orElseThrow(() -> new ResourceNotFoundException("路线版本不存在"));
    return userRoadmaps.findByOwnerIdAndTemplateId(ownerId, template.getId())
            .filter(item -> item.getStatus() == UserRoadmapStatus.ACTIVE)
            .map(RoadmapEnrollmentResponse::from)
            .orElseGet(() -> createEnrollment(ownerId, template));
}
```

`createEnrollment` must reject another `ACTIVE` enrollment, create exactly one `UserRoadmapEntity` with `activeSlot = "CURRENT"`, load all prerequisite rows once, and create each user node with `AVAILABLE` only when its prerequisite list is empty. All other orthogonal fields use Task 1 initial values. If concurrent requests hit the active-slot unique constraint, re-read and return the existing enrollment when it targets the same template; otherwise return 409.

Request and response records:

```java
public record CreateRoadmapEnrollmentRequest(
        @NotBlank @Size(max = 80) String roadmapCode,
        @Min(1) int templateVersion
) {}

public record RoadmapEnrollmentResponse(
        String id,
        String roadmapCode,
        int templateVersion,
        String title,
        String status,
        Instant enrolledAt
) {}
```

Add controller endpoint:

```java
@PostMapping("/roadmap-enrollments")
@ResponseStatus(HttpStatus.CREATED)
public RoadmapEnrollmentResponse enroll(
        @AuthenticationPrincipal AuthenticatedUser user,
        @Valid @RequestBody CreateRoadmapEnrollmentRequest request
) {
    return enrollmentService.enroll(
            user.id(), request.roadmapCode(), request.templateVersion()
    );
}
```

Repeated enrollment may return 201 with the same resource in this phase; the response body is idempotent and no duplicate rows are created.

- [ ] **Step 4: Run focused tests**

```bash
cd backend
./mvnw -q -Dtest=RoadmapEnrollmentServiceTest test
```

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/moxiao/studypilot/roadmap/api \
  backend/src/main/java/com/moxiao/studypilot/roadmap/application/RoadmapEnrollmentService.java \
  backend/src/test/java/com/moxiao/studypilot/roadmap/application/RoadmapEnrollmentServiceTest.java
git commit -m "feat: enroll users in published roadmap"
```

## Task 4: Expose the current Roadmap map and details

**Files:**
- Create: `backend/src/test/java/com/moxiao/studypilot/roadmap/api/RoadmapWorkflowTest.java`
- Create: `backend/src/main/java/com/moxiao/studypilot/roadmap/api/RoadmapMapResponse.java`
- Create: `backend/src/main/java/com/moxiao/studypilot/roadmap/api/RoadmapStageResponse.java`
- Create: `backend/src/main/java/com/moxiao/studypilot/roadmap/api/RoadmapNodeResponse.java`
- Create: `backend/src/main/java/com/moxiao/studypilot/roadmap/application/RoadmapQueryService.java`
- Modify: `backend/src/main/java/com/moxiao/studypilot/roadmap/api/RoadmapController.java`

- [ ] **Step 1: Write a failing authenticated API workflow**

Seed/import the catalog, register a user, enroll, then assert:

```java
mockMvc.perform(get("/api/roadmaps/current/map")
                .header("Authorization", "Bearer " + token))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.roadmapCode").value("studypilot-java-ai"))
        .andExpect(jsonPath("$.templateVersion").value(1))
        .andExpect(jsonPath("$.stages.length()").value(12))
        .andExpect(jsonPath("$.stages[0].code").value("java-core"))
        .andExpect(jsonPath("$.stages[0].nodes[0].code")
                .value("java-syntax-oop"))
        .andExpect(jsonPath("$.stages[0].nodes[0].displayStatus")
                .value("AVAILABLE"))
        .andExpect(jsonPath("$.stages[1].nodes[0].prerequisiteCodes[0]")
                .value("java-maven-testing"));
```

Also assert a second user without enrollment receives 404 and cannot see the first user's progress.

- [ ] **Step 2: Run and verify RED**

```bash
cd backend
./mvnw -q -Dtest=RoadmapWorkflowTest test
```

Expected: 404 because the map endpoint does not exist.

- [ ] **Step 3: Define response records**

```java
public record RoadmapMapResponse(
        String enrollmentId,
        String roadmapCode,
        int templateVersion,
        String title,
        String description,
        int completedRequiredNodes,
        int totalRequiredNodes,
        List<RoadmapStageResponse> stages
) {}

public record RoadmapStageResponse(
        String id,
        String code,
        int order,
        String title,
        String description,
        String graduationProjectTitle,
        int completedRequiredNodes,
        int totalRequiredNodes,
        List<RoadmapNodeResponse> nodes
) {}

public record RoadmapNodeResponse(
        String id,
        String code,
        int order,
        String title,
        List<String> objectives,
        List<String> highFrequency,
        List<String> commonMistakes,
        List<String> searchKeywords,
        int estimatedMinutes,
        int practiceMinutes,
        String difficulty,
        boolean required,
        List<String> prerequisiteCodes,
        String availabilityStatus,
        String learningStatus,
        String checkInStatus,
        String quizStatus,
        String artifactStatus,
        String completionStatus,
        String displayStatus,
        long version
) {}
```

- [ ] **Step 4: Implement one-query-set mapping and display status**

`RoadmapQueryService.currentMap(ownerId)` must load the active enrollment, template, all stages, nodes, prerequisites, and user nodes in bounded repository calls, group them in memory, and preserve stage/node order. Do not query repositories inside a node loop.

Derive display status exactly:

```java
private String displayStatus(UserRoadmapNodeEntity state) {
    if (state.getCompletionStatus() == CompletionStatus.COMPLETED) {
        return "COMPLETED";
    }
    if (state.getAvailabilityStatus() == AvailabilityStatus.LOCKED) {
        return "LOCKED";
    }
    if (state.getQuizStatus() == QuizStatus.FAILED
            || state.getQuizStatus() == QuizStatus.PARTIALLY_GRADED) {
        return "REVIEW_REQUIRED";
    }
    if (state.getCheckInStatus() == CheckInStatus.SUBMITTED
            && (state.getQuizStatus() == QuizStatus.NOT_GENERATED
            || state.getQuizStatus() == QuizStatus.GENERATING
            || state.getQuizStatus() == QuizStatus.EVALUATING)) {
        return "QUIZ_PENDING";
    }
    return state.getLearningStatus().name().equals("NOT_STARTED")
            ? "AVAILABLE"
            : state.getLearningStatus().name();
}
```

Expose:

```java
@GetMapping("/roadmaps/current")
public RoadmapEnrollmentResponse current(...)

@GetMapping("/roadmaps/current/map")
public RoadmapMapResponse currentMap(...)

@GetMapping("/roadmaps/current/stages/{stageId}")
public RoadmapStageResponse stage(...)

@GetMapping("/roadmaps/current/nodes/{nodeId}")
public RoadmapNodeResponse node(...)
```

All stage/node lookups include the current enrollment's template ID; a valid ID from another template returns 404.

- [ ] **Step 5: Run focused tests**

```bash
cd backend
./mvnw -q -Dtest=RoadmapWorkflowTest test
```

Expected: PASS, including user isolation and ordering.

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/java/com/moxiao/studypilot/roadmap/api \
  backend/src/main/java/com/moxiao/studypilot/roadmap/application/RoadmapQueryService.java \
  backend/src/test/java/com/moxiao/studypilot/roadmap/api/RoadmapWorkflowTest.java
git commit -m "feat: expose the current roadmap map"
```

## Task 5: Recalculate prerequisite availability deterministically

**Files:**
- Modify: `backend/src/test/java/com/moxiao/studypilot/roadmap/application/RoadmapEnrollmentServiceTest.java`
- Modify: `backend/src/main/java/com/moxiao/studypilot/roadmap/application/RoadmapEnrollmentService.java`
- Modify: `backend/src/main/java/com/moxiao/studypilot/roadmap/infrastructure/UserRoadmapNodeEntity.java`

- [ ] **Step 1: Add failing prerequisite tests**

```java
@Test
void unlocksANodeOnlyAfterEveryPrerequisiteIsCompleted() {
    UserRoadmapEntity roadmap = fixture.enrollOwner("owner-1");
    fixture.markNodeCompleted(roadmap, "mybatis-plus");
    fixture.markNodeIncomplete(roadmap, "jpa-core");

    service.recalculateAvailability(roadmap.getId());

    assertThat(fixture.nodeState(roadmap, "data-access-comparison")
            .getAvailabilityStatus()).isEqualTo(AvailabilityStatus.LOCKED);

    fixture.markNodeCompleted(roadmap, "jpa-core");
    service.recalculateAvailability(roadmap.getId());

    assertThat(fixture.nodeState(roadmap, "data-access-comparison")
            .getAvailabilityStatus()).isEqualTo(AvailabilityStatus.AVAILABLE);
}
```

Add another test proving a completed node never becomes locked when a future template dependency changes.

The fixture methods set test state without adding a production bypass:

```java
void markNodeCompleted(UserRoadmapEntity roadmap, String nodeCode) {
    jdbcTemplate.update("""
            UPDATE user_roadmap_nodes state
            SET completion_status = 'COMPLETED', updated_at = CURRENT_TIMESTAMP
            WHERE state.user_roadmap_id = ?
              AND state.node_id = (
                  SELECT node.id FROM roadmap_nodes node
                  WHERE node.template_id = ? AND node.node_code = ?
              )
            """, roadmap.getId(), roadmap.getTemplateId(), nodeCode);
}

void markNodeIncomplete(UserRoadmapEntity roadmap, String nodeCode) {
    jdbcTemplate.update("""
            UPDATE user_roadmap_nodes state
            SET completion_status = 'INCOMPLETE', updated_at = CURRENT_TIMESTAMP
            WHERE state.user_roadmap_id = ?
              AND state.node_id = (
                  SELECT node.id FROM roadmap_nodes node
                  WHERE node.template_id = ? AND node.node_code = ?
              )
            """, roadmap.getId(), roadmap.getTemplateId(), nodeCode);
}
```

- [ ] **Step 2: Run and verify RED**

```bash
cd backend
./mvnw -q -Dtest=RoadmapEnrollmentServiceTest#unlocksANodeOnlyAfterEveryPrerequisiteIsCompleted test
```

Expected: compilation fails because `recalculateAvailability` is absent.

- [ ] **Step 3: Implement availability recalculation**

```java
@Transactional
public void recalculateAvailability(String userRoadmapId) {
    UserRoadmapEntity roadmap = userRoadmaps.findById(userRoadmapId)
            .orElseThrow(() -> new ResourceNotFoundException("用户路线不存在"));
    Map<String, UserRoadmapNodeEntity> states = userNodes
            .findAllByUserRoadmapId(userRoadmapId).stream()
            .collect(Collectors.toMap(UserRoadmapNodeEntity::getNodeId, identity()));
    Map<String, List<String>> required = prerequisites
            .findAllByTemplateId(roadmap.getTemplateId()).stream()
            .collect(groupingBy(
                    RoadmapNodePrerequisiteEntity::getNodeId,
                    mapping(RoadmapNodePrerequisiteEntity::getPrerequisiteNodeId, toList())
            ));
    Instant now = Instant.now();
    for (UserRoadmapNodeEntity state : states.values()) {
        if (state.getCompletionStatus() == CompletionStatus.COMPLETED) {
            continue;
        }
        boolean unlocked = required.getOrDefault(state.getNodeId(), List.of())
                .stream()
                .allMatch(id -> states.get(id).getCompletionStatus()
                        == CompletionStatus.COMPLETED);
        state.changeAvailability(
                unlocked ? AvailabilityStatus.AVAILABLE : AvailabilityStatus.LOCKED,
                now
        );
    }
}
```

This method will be called by the later learning-loop plan after node completion. The rolling-learning plan will introduce an injectable `Clock` when user-time-zone date calculations are implemented; this availability-only method does not compare calendar dates.

- [ ] **Step 4: Run tests and commit**

```bash
cd backend
./mvnw -q -Dtest=RoadmapEnrollmentServiceTest,RoadmapWorkflowTest test
cd ..
git add backend/src/main/java/com/moxiao/studypilot/roadmap \
  backend/src/test/java/com/moxiao/studypilot/roadmap
git commit -m "feat: enforce roadmap prerequisites"
```

Expected: PASS.

## Task 6: Preview and confirm a safe template upgrade

**Files:**
- Create: `backend/src/test/java/com/moxiao/studypilot/roadmap/api/RoadmapUpgradeWorkflowTest.java`
- Create: `backend/src/main/java/com/moxiao/studypilot/roadmap/application/RoadmapUpgradeService.java`
- Create: `backend/src/main/java/com/moxiao/studypilot/roadmap/api/RoadmapUpgradeController.java`
- Create: `backend/src/main/java/com/moxiao/studypilot/roadmap/api/RoadmapUpgradeResponse.java`

- [ ] **Step 1: Write failing upgrade workflow tests**

Create v1 and v2 fixtures with stable `nodeCode` values. Assert:

```java
mockMvc.perform(get("/api/roadmaps/current/upgrades")
                .header("Authorization", bearer))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].status").value("PREVIEW"))
        .andExpect(jsonPath("$[0].unchangedNodeCodes[0]").value("java-syntax-oop"))
        .andExpect(jsonPath("$[0].manualReviewNodeCodes[0]").value("split-node"));

mockMvc.perform(post("/api/roadmaps/current/upgrades/{id}/confirm", upgradeId)
                .header("Authorization", bearer))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("COMPLETED"));
```

Also assert another user receives 404 and repeated confirmation returns the same completed result.

- [ ] **Step 2: Run and verify RED**

```bash
cd backend
./mvnw -q -Dtest=RoadmapUpgradeWorkflowTest test
```

Expected: 404 because the upgrade endpoints are absent.

- [ ] **Step 3: Implement deterministic preview categories**

Response:

```java
public record RoadmapUpgradeResponse(
        String id,
        int sourceVersion,
        int targetVersion,
        String status,
        List<String> unchangedNodeCodes,
        List<String> addedNodeCodes,
        List<String> removedNodeCodes,
        List<String> manualReviewNodeCodes
) {}
```

Rules:

```text
same nodeCode + equivalent artifact/quiz requirement -> unchanged
new nodeCode -> added
missing nodeCode -> removed, history remains read-only
same nodeCode + changed required prerequisites or completion contract -> manual review
```

Only create a preview when a higher published version exists. Use idempotency key `roadmap-upgrade:{userRoadmapId}:{targetTemplateId}`.

- [ ] **Step 4: Implement transactional confirmation**

Confirmation first calls `oldEnrollment.supersede(now)` to clear its active slot, then creates a new active enrollment for the target template, maps unchanged completed nodes, initializes added/manual-review nodes as incomplete, and marks the preview `COMPLETED` in one transaction. Removed history remains attached to the old enrollment. A preview containing `manualReviewNodeCodes` returns 409 until the client submits an explicit mapping choice; v1 only supports confirming previews without manual-review nodes.

- [ ] **Step 5: Run and commit**

```bash
cd backend
./mvnw -q -Dtest=RoadmapUpgradeWorkflowTest test
cd ..
git add backend/src/main/java/com/moxiao/studypilot/roadmap \
  backend/src/test/java/com/moxiao/studypilot/roadmap/api/RoadmapUpgradeWorkflowTest.java
git commit -m "feat: preview safe roadmap upgrades"
```

Expected: PASS.

## Task 7: Preserve Course/Lesson progress as legacy evidence

**Files:**
- Create: `backend/src/test/java/com/moxiao/studypilot/roadmap/application/LegacyCourseMigrationServiceTest.java`
- Create: `backend/src/main/java/com/moxiao/studypilot/roadmap/application/LegacyCourseMigrationService.java`
- Modify: `backend/src/main/java/com/moxiao/studypilot/roadmap/application/RoadmapCatalogImporter.java`
- Modify: `backend/src/main/java/com/moxiao/studypilot/roadmap/config/RoadmapCatalogConfiguration.java`
- Modify: `backend/src/main/java/com/moxiao/studypilot/course/config/CourseCatalogConfiguration.java`

- [ ] **Step 1: Write failing migration tests**

```java
@Test
void completedLessonBecomesLegacyEvidenceButDoesNotCompleteTheRoadmapNode() {
    fixture.mapLesson("lesson-rest-controller", "spring-mvc-rest");
    fixture.completeLesson("owner-1", "lesson-rest-controller");
    UserRoadmapEntity roadmap = fixture.enrollOwner("owner-1");

    service.migrateOwner(roadmap.getId(), 1);
    service.migrateOwner(roadmap.getId(), 1);

    assertThat(evidence.count()).isEqualTo(1);
    assertThat(fixture.nodeState(roadmap, "spring-mvc-rest")
            .getCompletionStatus()).isEqualTo(CompletionStatus.INCOMPLETE);
    assertThat(fixture.nodeState(roadmap, "spring-mvc-rest")
            .getCheckInStatus()).isEqualTo(CheckInStatus.MISSING);
}
```

Add a test proving an unmapped lesson produces no evidence and its existing Course endpoint still returns the original history.

- [ ] **Step 2: Run and verify RED**

```bash
cd backend
./mvnw -q -Dtest=LegacyCourseMigrationServiceTest test
```

Expected: compilation fails because the service does not exist.

- [ ] **Step 3: Persist the catalog's explicit mappings after Course import**

The v1 JSON already contains the mapping from Task 2. The importer resolves node codes to node IDs and upserts `legacy_lesson_roadmap_mappings`. It must reject unknown node codes and missing legacy lessons.

Make startup order deterministic:

```java
@Bean
@Order(10)
ApplicationRunner courseCatalogRunner(CourseCatalogImporter importer) {
    return arguments -> importer.importCatalog();
}

@Bean
@Order(20)
ApplicationRunner roadmapCatalogRunner(RoadmapCatalogImporter importer) {
    return arguments -> importer.importCatalog();
}
```

The Roadmap importer therefore sees the Course/Lesson rows before inserting foreign-key mappings.

- [ ] **Step 4: Implement idempotent evidence migration**

`migrateOwner(userRoadmapId, migrationVersion)` loads only mapped lesson progress owned by the enrollment owner. For each record, persist:

```json
{
  "type": "LEGACY_LESSON_PROGRESS",
  "videoCompleted": true,
  "readingCompleted": true,
  "checkpointPassed": true,
  "quizPassed": true,
  "completedAt": "original timestamp or null"
}
```

Do not modify `checkInStatus`, `quizStatus`, `artifactStatus`, or `completionStatus`. Catch duplicate-key races by re-reading the unique `(ownerId, lessonId, migrationVersion)` row, not by creating another row.

- [ ] **Step 5: Run all Course and Roadmap tests, then commit**

```bash
cd backend
./mvnw -q test
cd ..
git add backend/src/main/java/com/moxiao/studypilot/roadmap \
  backend/src/main/java/com/moxiao/studypilot/course/config/CourseCatalogConfiguration.java \
  backend/src/test/java/com/moxiao/studypilot/roadmap/application/LegacyCourseMigrationServiceTest.java
git commit -m "feat: preserve legacy course progress"
```

Expected: PASS; Course tables and APIs are unchanged.

## Task 8: Add the typed Roadmap frontend client

**Files:**
- Create: `web/src/types/roadmap.ts`
- Create: `web/src/services/roadmap.ts`
- Create: `web/tests/roadmap-navigation.spec.ts`

- [ ] **Step 1: Write the failing client contract test**

```ts
import { describe, expect, it, vi } from 'vitest'
import { http } from '@/services/http'
import { roadmapApi } from '@/services/roadmap'

describe('roadmapApi', () => {
  it('loads the current map only through the Java public API', async () => {
    const get = vi.spyOn(http, 'get').mockResolvedValueOnce({
      data: { roadmapCode: 'studypilot-java-ai', stages: [] },
    })

    await roadmapApi.getCurrentMap()

    expect(get).toHaveBeenCalledWith('/api/roadmaps/current/map')
  })
})
```

- [ ] **Step 2: Run and verify RED**

```bash
cd web
npm test -- roadmap-navigation.spec.ts
```

Expected: import fails because `@/services/roadmap` does not exist.

- [ ] **Step 3: Define exact frontend types**

```ts
export type RoadmapDisplayStatus =
  | 'LOCKED'
  | 'AVAILABLE'
  | 'SCHEDULED'
  | 'IN_PROGRESS'
  | 'QUIZ_PENDING'
  | 'REVIEW_REQUIRED'
  | 'COMPLETED'

export interface RoadmapNode {
  id: string
  code: string
  order: number
  title: string
  objectives: string[]
  highFrequency: string[]
  commonMistakes: string[]
  searchKeywords: string[]
  estimatedMinutes: number
  practiceMinutes: number
  difficulty: 'EASY' | 'MEDIUM' | 'HARD'
  required: boolean
  prerequisiteCodes: string[]
  availabilityStatus: 'LOCKED' | 'AVAILABLE'
  learningStatus: 'NOT_STARTED' | 'SCHEDULED' | 'IN_PROGRESS'
  checkInStatus: 'MISSING' | 'SUBMITTED'
  quizStatus: 'NOT_GENERATED' | 'GENERATING' | 'READY' | 'EVALUATING' |
    'PASSED' | 'FAILED' | 'PARTIALLY_GRADED'
  artifactStatus: 'NOT_REQUIRED' | 'MISSING' | 'SUBMITTED' |
    'ACCEPTED' | 'REJECTED'
  completionStatus: 'INCOMPLETE' | 'COMPLETED'
  displayStatus: RoadmapDisplayStatus
  version: number
}

export interface RoadmapStage {
  id: string
  code: string
  order: number
  title: string
  description: string
  graduationProjectTitle: string
  completedRequiredNodes: number
  totalRequiredNodes: number
  nodes: RoadmapNode[]
}

export interface RoadmapMap {
  enrollmentId: string
  roadmapCode: string
  templateVersion: number
  title: string
  description: string
  completedRequiredNodes: number
  totalRequiredNodes: number
  stages: RoadmapStage[]
}

export interface RoadmapEnrollment {
  id: string
  roadmapCode: string
  templateVersion: number
  title: string
  status: 'ACTIVE' | 'SUPERSEDED' | 'ARCHIVED'
  enrolledAt: string
}
```

- [ ] **Step 4: Implement the Java-only service**

```ts
import { http } from './http'
import type {
  RoadmapEnrollment,
  RoadmapMap,
  RoadmapNode,
  RoadmapStage,
} from '@/types/roadmap'

export const roadmapApi = {
  enroll(roadmapCode = 'studypilot-java-ai', templateVersion = 1) {
    return http.post<RoadmapEnrollment>('/api/roadmap-enrollments', {
      roadmapCode,
      templateVersion,
    }).then((response) => response.data)
  },
  getCurrentMap() {
    return http.get<RoadmapMap>('/api/roadmaps/current/map')
      .then((response) => response.data)
  },
  getStage(stageId: string) {
    return http.get<RoadmapStage>(`/api/roadmaps/current/stages/${stageId}`)
      .then((response) => response.data)
  },
  getNode(nodeId: string) {
    return http.get<RoadmapNode>(`/api/roadmaps/current/nodes/${nodeId}`)
      .then((response) => response.data)
  },
}
```

- [ ] **Step 5: Run tests and commit**

```bash
cd web
npm test -- roadmap-navigation.spec.ts
cd ..
git add web/src/types/roadmap.ts web/src/services/roadmap.ts \
  web/tests/roadmap-navigation.spec.ts
git commit -m "feat: add roadmap frontend client"
```

Expected: PASS.

## Task 9: Render an accessible map and list

**Files:**
- Create: `web/src/modules/roadmap/RoadmapView.spec.ts`
- Create: `web/src/modules/roadmap/RoadmapView.vue`
- Create: `web/src/modules/roadmap/StageView.vue`
- Create: `web/src/modules/roadmap/NodeView.vue`
- Create: `web/src/modules/roadmap/components/RoadmapGraph.vue`
- Create: `web/src/modules/roadmap/components/RoadmapList.vue`
- Create: `web/src/modules/roadmap/components/RoadmapNodeCard.vue`

- [ ] **Step 1: Write failing component tests**

```ts
import { flushPromises, mount } from '@vue/test-utils'
import { describe, expect, it, vi } from 'vitest'
import RoadmapView from './RoadmapView.vue'
import { roadmapApi } from '@/services/roadmap'
import type { RoadmapMap, RoadmapNode } from '@/types/roadmap'

vi.mock('@/services/roadmap')

function node(overrides: Partial<RoadmapNode>): RoadmapNode {
  return {
    id: 'node-java',
    code: 'java-syntax-oop',
    order: 1,
    title: 'Java 语法、面向对象与代码规范',
    objectives: ['能够编写结构清晰的 Java 类'],
    highFrequency: ['封装、继承与多态'],
    commonMistakes: ['把继承当作代码复用的默认方式'],
    searchKeywords: ['黑马程序员 Java 面向对象'],
    estimatedMinutes: 60,
    practiceMinutes: 45,
    difficulty: 'EASY',
    required: true,
    prerequisiteCodes: [],
    availabilityStatus: 'AVAILABLE',
    learningStatus: 'NOT_STARTED',
    checkInStatus: 'MISSING',
    quizStatus: 'NOT_GENERATED',
    artifactStatus: 'NOT_REQUIRED',
    completionStatus: 'INCOMPLETE',
    displayStatus: 'AVAILABLE',
    version: 0,
    ...overrides,
  }
}

function fixtureRoadmap(): RoadmapMap {
  return {
    enrollmentId: 'enrollment-1',
    roadmapCode: 'studypilot-java-ai',
    templateVersion: 1,
    title: 'StudyPilot Java + AI 学习路线',
    description: '从传统 Java 后端到可操作项目的 Agent',
    completedRequiredNodes: 0,
    totalRequiredNodes: 2,
    stages: [{
      id: 'stage-java',
      code: 'java-core',
      order: 1,
      title: 'Java 核心与工程基础',
      description: '掌握传统后端所需的 Java 基础',
      graduationProjectTitle: 'Java 命令行学习记录器',
      completedRequiredNodes: 0,
      totalRequiredNodes: 2,
      nodes: [
        node({}),
        node({
          id: 'node-collections',
          code: 'java-collections-generics',
          order: 2,
          title: '集合、泛型与常用工具类',
          prerequisiteCodes: ['java-syntax-oop'],
          availabilityStatus: 'LOCKED',
          displayStatus: 'LOCKED',
        }),
      ],
    }],
  }
}

describe('RoadmapView', () => {
  it('shows graph and accessible list views from the same data', async () => {
    vi.mocked(roadmapApi.getCurrentMap).mockResolvedValue(fixtureRoadmap())
    const wrapper = mount(RoadmapView, {
      global: { stubs: ['RouterLink'] },
    })
    await flushPromises()

    expect(wrapper.get('[data-testid="roadmap-graph"]').text())
      .toContain('Java 语法、面向对象与代码规范')
    await wrapper.get('button[aria-controls="roadmap-list"]').trigger('click')
    expect(wrapper.get('#roadmap-list').text())
      .toContain('Java 语法、面向对象与代码规范')
  })

  it('explains why a node is locked', async () => {
    vi.mocked(roadmapApi.getCurrentMap).mockResolvedValue(fixtureRoadmap())
    const wrapper = mount(RoadmapView, {
      global: { stubs: ['RouterLink'] },
    })
    await flushPromises()

    expect(wrapper.text()).toContain('完成前置节点后解锁')
  })
})
```

- [ ] **Step 2: Run and verify RED**

```bash
cd web
npm test -- src/modules/roadmap/RoadmapView.spec.ts
```

Expected: import fails because the component does not exist.

- [ ] **Step 3: Implement shared node cards**

`RoadmapNodeCard.vue` accepts only typed props and emits no mutation:

```ts
const props = defineProps<{
  node: RoadmapNode
  compact?: boolean
}>()

const lockedReason = computed(() =>
  props.node.displayStatus === 'LOCKED'
    ? `完成前置节点后解锁：${props.node.prerequisiteCodes.join('、')}`
    : '',
)
```

Render a `RouterLink` only when the node is not locked. Include visible text for status; do not rely on color.

- [ ] **Step 4: Implement graph and list from the same model**

`RoadmapGraph.vue` renders stages as a vertical spine, nodes as branches, and optional nodes with a dashed class. `RoadmapList.vue` renders semantic headings and lists. Both receive `stages: RoadmapStage[]` and use `RoadmapNodeCard`; neither fetches data.

`RoadmapView.vue` owns loading, 404 enrollment empty state, error retry, map/list toggle, and progress summary. The 404 empty state has one explicit button that calls `roadmapApi.enroll()` and then reloads; no enrollment happens silently during GET.

- [ ] **Step 5: Implement stage and node read pages**

`StageView.vue` loads by `route.params.id` and shows stage objective, required-node progress, graduation project title, and node list. `NodeView.vue` shows objectives, high-frequency points, common mistakes, search keywords, prerequisite links, and the message “打卡、测验和必交成果全部满足后才完成节点”. It does not add check-in or quiz buttons yet; those belong to plan 2/3.

- [ ] **Step 6: Run tests, typecheck, and commit**

```bash
cd web
npm test -- src/modules/roadmap/RoadmapView.spec.ts
npm run typecheck
cd ..
git add web/src/modules/roadmap
git commit -m "feat: render accessible java ai roadmap"
```

Expected: PASS and no TypeScript diagnostics.

## Task 10: Make Roadmap the primary navigation and preserve legacy URLs

**Files:**
- Modify: `web/src/app/router.ts`
- Modify: `web/src/components/AppShell.vue`
- Modify: `web/src/modules/course/CourseCatalogView.vue`
- Modify: `web/src/modules/course/CourseDetailView.vue`
- Modify: `web/src/modules/course/LessonView.vue`
- Modify: `web/tests/roadmap-navigation.spec.ts`

- [ ] **Step 1: Add failing navigation source assertions**

```ts
import routerSource from '@/app/router.ts?raw'
import shellSource from '@/components/AppShell.vue?raw'

it('makes roadmap primary and removes courses from navigation', () => {
  expect(routerSource).toContain("path: 'roadmap'")
  expect(routerSource).toContain("path: 'roadmap/stages/:id'")
  expect(routerSource).toContain("path: 'roadmap/nodes/:id'")
  expect(shellSource).toContain("to: '/roadmap'")
  expect(shellSource).not.toContain("to: '/courses'")
})
```

- [ ] **Step 2: Run and verify RED**

```bash
cd web
npm test -- roadmap-navigation.spec.ts
```

Expected: assertions fail because the Roadmap routes are absent.

- [ ] **Step 3: Add the new routes**

Add children under `AppShell`:

```ts
{
  path: 'roadmap',
  name: 'roadmap',
  component: () => import('@/modules/roadmap/RoadmapView.vue'),
  meta: { title: 'Java + AI 学习路线' },
},
{
  path: 'roadmap/stages/:id',
  name: 'roadmap-stage',
  component: () => import('@/modules/roadmap/StageView.vue'),
  meta: { title: '路线阶段' },
},
{
  path: 'roadmap/nodes/:id',
  name: 'roadmap-node',
  component: () => import('@/modules/roadmap/NodeView.vue'),
  meta: { title: '知识节点' },
},
```

Keep `/courses`, `/courses/:slug`, and `/lessons/:lessonId` routes reachable during the compatibility period, but add `meta: { legacy: true }` and remove their navigation item.

- [ ] **Step 4: Replace the navigation label**

Use this learning group order:

```ts
items: [
  { to: '/roadmap', icon: '🧭', label: '学习路线' },
  { to: '/today', icon: '✅', label: '今日学习' },
  { to: '/reviews', icon: '🔁', label: '复习' },
  { to: '/materials', icon: '📚', label: '学习资料' },
  { to: '/mastery', icon: '📈', label: '掌握度' },
]
```

Move `/today` out of the existing “总览” group when adding it to “学习”, so the sidebar contains it exactly once. Keep dashboard and notifications in “总览”.

Do not add `/reviews` until its placeholder route points to an `EmptyState` explaining that the rolling learning-loop phase will activate it; alternatively omit it in this task and add it in plan 2. For this plan, omit it to avoid a dead navigation entry.

- [ ] **Step 5: Mark legacy pages clearly**

At the top of each Course/Lesson page render:

```html
<div class="legacy-banner" role="status">
  这是旧版课程兼容页面，历史学习记录仍可查看；新的学习进度请使用路线节点。
  <RouterLink to="/roadmap">前往新的 Java + AI 学习路线</RouterLink>
</div>
```

Do not delete old write calls in this plan; hiding and read-only enforcement at the API level happens only after legacy evidence migration has been verified on real MySQL.

- [ ] **Step 6: Run frontend tests and commit**

```bash
cd web
npm test -- roadmap-navigation.spec.ts
npm test
npm run build
cd ..
git add web/src/app/router.ts web/src/components/AppShell.vue \
  web/src/modules/course web/tests/roadmap-navigation.spec.ts
git commit -m "refactor: make roadmap the primary learning entry"
```

Expected: all Vitest tests pass and Vite build succeeds.

## Task 11: Complete the foundation end-to-end workflow and documentation

**Files:**
- Create: `docs/roadmap-foundation-e2e.http`
- Modify: `README.md`
- Modify: `项目开发步骤.md`
- Test: all backend and frontend tests

- [ ] **Step 1: Add the authenticated HTTP workflow**

Create `docs/roadmap-foundation-e2e.http` with variables and requests for:

```http
### 1. Register or login
POST http://localhost:8080/api/auth/login
Content-Type: application/json

{
  "email": "166973742@qq.com",
  "password": "jiopuoJ135780"
}

### 2. Bind Java + AI Roadmap v1
POST http://localhost:8080/api/roadmap-enrollments
Authorization: Bearer {{accessToken}}
Content-Type: application/json

{
  "roadmapCode": "studypilot-java-ai",
  "templateVersion": 1
}

### 3. Read the complete map
GET http://localhost:8080/api/roadmaps/current/map
Authorization: Bearer {{accessToken}}

### 4. Read the first stage
GET http://localhost:8080/api/roadmaps/current/stages/{{stageId}}
Authorization: Bearer {{accessToken}}

### 5. Read one node
GET http://localhost:8080/api/roadmaps/current/nodes/{{nodeId}}
Authorization: Bearer {{accessToken}}

### 6. Repeat enrollment and verify the same enrollmentId
POST http://localhost:8080/api/roadmap-enrollments
Authorization: Bearer {{accessToken}}
Content-Type: application/json

{
  "roadmapCode": "studypilot-java-ai",
  "templateVersion": 1
}
```

Do not hard-code an access token or internal service token in the committed file.

- [ ] **Step 2: Run fresh backend verification**

```bash
cd backend
./mvnw -q test
```

Expected: all Maven tests pass.

- [ ] **Step 3: Run fresh frontend verification**

```bash
cd web
npm test
npm run build
```

Expected: all Vitest tests pass; TypeScript and Vite build succeed.

- [ ] **Step 4: Validate migration on real MySQL**

With a backup of the local `studypilot` database and Spring Boot stopped, start the backend once so Flyway applies V24:

```bash
cd backend
./mvnw spring-boot:run
```

Expected startup log includes a successful migration to version 24. In MySQL run:

```sql
SELECT version, description, success
FROM flyway_schema_history
WHERE version = '24';

SELECT COUNT(*) FROM roadmap_templates;
SELECT COUNT(*) FROM roadmap_stages;
SELECT COUNT(*) FROM roadmap_nodes;
SELECT COUNT(*) FROM courses;
SELECT COUNT(*) FROM lessons;
```

Expected: V24 success is `1`; one Roadmap template, 12 stages, the exact catalog node count, and the old Course/Lesson counts remain non-zero and unchanged.

- [ ] **Step 5: Run the HTTP workflow and inspect the UI**

Start Vue with `npm run dev`, open `http://localhost:5173/roadmap`, and verify:

```text
complete 12-stage map is visible
graph and list show the same nodes
locked nodes explain prerequisites
old Course item is absent from sidebar
direct legacy Course/Lesson URLs still render the compatibility banner
browser network contains only Java /api/** requests
```

- [ ] **Step 6: Update documentation**

Add a README entry linking the design spec, this plan, and the E2E file. In `项目开发步骤.md`, record:

```text
Roadmap Foundation
- 新建独立 Roadmap 领域，没有改名复用 Course/Lesson
- 发布 12 阶段 Java + AI v1 模板
- 用户可绑定路线并查看依赖、状态和完整地图
- 旧课程历史仅作为兼容证据，不授予新节点完成
- 下一步：诊断、滚动 7 日计划、打卡和成果闭环
```

- [ ] **Step 7: Check the exact diff and commit**

```bash
git diff --check
git status --short
git add docs/roadmap-foundation-e2e.http README.md 项目开发步骤.md
git commit -m "test: complete roadmap foundation workflow"
```

Expected: only intended Roadmap files are committed. Do not stage:

```text
backend/src/main/resources/application.properties
docs/agent-api-examples.http
.superpowers/
```

- [ ] **Step 8: Push after all commits are verified**

```bash
git push origin main
```

Expected: `origin/main` contains every Roadmap foundation commit and no local-only configuration.

## Final verification checklist

- [ ] `./mvnw -q test` passes from `backend/`.
- [ ] `npm test` and `npm run build` pass from `web/`.
- [ ] `git diff --check` reports no whitespace errors.
- [ ] V24 succeeds against real MySQL without dropping Course/Lesson data.
- [ ] Catalog imports exactly one template, 12 stages, the fixed node count, and an acyclic dependency graph.
- [ ] Repeated enrollment and import are idempotent.
- [ ] A user's current map never exposes another user's state.
- [ ] Root nodes are available and dependent nodes are locked until every prerequisite completes.
- [ ] Legacy progress creates evidence but never fabricates check-in, quiz pass, artifact acceptance, or node completion.
- [ ] `/roadmap` has both graph and accessible list views.
- [ ] `/courses` is absent from navigation but direct legacy URLs remain readable.
- [ ] The browser never calls FastAPI `/internal/**`.
- [ ] User-local configuration files remain uncommitted.
