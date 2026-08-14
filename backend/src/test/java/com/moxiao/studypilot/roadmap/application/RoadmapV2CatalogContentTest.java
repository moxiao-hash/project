package com.moxiao.studypilot.roadmap.application;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.util.HashSet;
import java.util.HashMap;
import java.util.List;
import java.util.Set;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

import static org.assertj.core.api.Assertions.assertThat;

class RoadmapV2CatalogContentTest {

    private static final List<String> MODULE_CODES = List.of(
            "java-language-start", "java-oop-foundations", "java-core-collections", "java-modern-engineering",
            "spring-business-backend", "mysql-foundations", "java-persistence", "cache-security",
            "backend-quality", "frontend-delivery", "python-engineering", "fastapi-integration",
            "llm-foundations", "model-engineering", "langgraph-agent", "tool-governance-mcp",
            "rag-retrieval", "search-assessment", "business-tools", "business-agent-loop",
            "repository-agent", "developer-automation", "runner-security", "release-recovery");

    private final JsonNode catalog = readCatalog();

    @Test
    void publishesTheGranularV2ShapeAndModuleAllocation() {
        assertThat(catalog.get("version").asInt()).isEqualTo(2);
        assertThat(catalog.get("stages")).hasSize(12);
        List<JsonNode> modules = modules();
        List<JsonNode> nodes = nodes();
        assertThat(modules).hasSize(24);
        assertThat(nodes).hasSize(125);
        assertThat(modules).extracting(module -> module.get("moduleCode").asString())
                .containsExactlyElementsOf(MODULE_CODES);
        assertThat(modules).extracting(module -> module.get("nodes").size())
                .containsExactly(7, 7, 6, 5, 10, 5, 5, 5, 5, 8, 4, 5,
                        4, 4, 5, 5, 5, 5, 4, 4, 4, 4, 5, 4);
        assertThat(catalog.get("stages")).extracting(stage -> nodeCount(stage))
                .containsExactly(25, 10, 10, 10, 8, 9, 8, 10, 10, 8, 8, 9);
    }

    @Test
    void everyModuleEndsWithItsOnlyMilestoneAndEveryNodeHasSpecificTeachingContent() {
        Set<String> objectiveArrays = new HashSet<>();
        Set<String> highFrequencyArrays = new HashSet<>();
        Set<String> mistakeArrays = new HashSet<>();
        Set<String> keywordArrays = new HashSet<>();
        Set<String> quizArrays = new HashSet<>();
        Set<String> codes = new HashSet<>();
        Set<String> optional = new HashSet<>();
        Set<String> forbidden = Set.of("掌握本主题核心概念", "完成对应练习", "常见错误一", "主题关键词");

        for (JsonNode module : modules()) {
            List<JsonNode> moduleNodes = stream(module.get("nodes"));
            assertThat(moduleNodes).isNotEmpty();
            for (int index = 0; index < moduleNodes.size(); index++) {
                JsonNode node = moduleNodes.get(index);
                String code = node.get("code").asString();
                JsonNode artifact = node.get("artifactRequirement");
                boolean milestone = index == moduleNodes.size() - 1;
                assertThat(codes.add(code)).as("unique node code %s", code).isTrue();
                assertThat(node.get("estimatedMinutes").asInt()).isBetween(30, 60);
                assertThat(node.get("practiceMinutes").asInt()).isBetween(15, 60);
                assertThat(node.get("objectives")).as(code).hasSizeGreaterThanOrEqualTo(2);
                assertThat(node.get("highFrequency")).as(code).hasSizeGreaterThanOrEqualTo(2);
                assertThat(node.get("commonMistakes")).as(code).hasSizeGreaterThanOrEqualTo(2);
                assertThat(node.get("searchKeywords")).as(code).hasSizeGreaterThanOrEqualTo(3);
                assertThat(node.get("quizBlueprint")).as(code).hasSize(5);
                assertThat(artifact.get("required").asBoolean()).as(code).isEqualTo(milestone);
                assertThat(artifact.get("evaluationMode").asString()).as(code)
                        .isEqualTo(milestone ? "AI_RUBRIC" : "PRESENCE");
                if (!milestone) assertThat(artifact.get("description").asString()).contains("无需提交里程碑成果");
                if (!node.get("required").asBoolean()) optional.add(code);
                String serialized = node.toString();
                forbidden.forEach(phrase -> assertThat(serialized).as(code).doesNotContain(phrase));
                assertThat(objectiveArrays.add(node.get("objectives").toString())).as(code).isTrue();
                assertThat(highFrequencyArrays.add(node.get("highFrequency").toString())).as(code).isTrue();
                assertThat(mistakeArrays.add(node.get("commonMistakes").toString())).as(code).isTrue();
                assertThat(keywordArrays.add(node.get("searchKeywords").toString())).as(code).isTrue();
                assertThat(quizArrays.add(node.get("quizBlueprint").toString())).as(code).isTrue();
            }
        }
        assertThat(optional).containsExactlyInAnyOrder("spring-ai-elective", "accessibility-desktop");
    }

    @Test
    void representativeNodesUseTheirConcreteTechnologyVocabulary() {
        assertTerms("java-environment-first-program", "JDK", "javac");
        assertTerms("variables-types-conversion", "基本类型", "类型转换");
        assertTerms("conditions-if-switch", "if", "switch");
        assertTerms("classes-objects", "class", "对象");
        assertTerms("encapsulation-access", "private", "封装");
        assertTerms("polymorphism", "多态", "动态绑定");
        assertTerms("string-content-comparison", "String", "equals");
        assertTerms("set-map-deduplication", "Set", "Map");
        assertTerms("exceptions-custom", "异常", "try");
        assertTerms("files-nio-streams", "NIO", "Path");
        assertTerms("record-sealed-maven-junit-checkstyle", "record", "sealed", "Maven", "JUnit", "Checkstyle");
        assertTerms("spring-ioc-di", "IoC", "构造器注入");
        assertTerms("spring-mvc-rest", "RestController", "HTTP");
        assertTerms("spring-validation-errors", "Valid", "ProblemDetail");
        assertTerms("mysql-index-transaction", "索引", "事务");
        assertTerms("mybatis-core", "MyBatis", "Mapper");
        assertTerms("data-access-comparison", "MyBatis-Plus", "JPA");
        assertTerms("redis-cache", "Redis", "缓存");
        assertTerms("auth-jwt-security", "JWT", "授权");
        assertTerms("vue-router-pinia", "Vue Router", "Pinia");
        assertTerms("fastapi-rest", "FastAPI", "Pydantic");
        assertTerms("structured-output", "JSON Schema", "结构化输出");
        assertTerms("langgraph-state", "LangGraph", "StateGraph");
        assertTerms("mcp", "MCP", "JSON-RPC");
        assertTerms("embedding-qdrant", "embedding", "Qdrant");
        assertTerms("hybrid-retrieval", "BM25", "向量");
        assertTerms("business-tool-contracts", "幂等", "工具契约");
        assertTerms("patch-diff", "diff", "补丁");
        assertTerms("runner-sandbox", "沙箱", "CPU/内存限制");
        assertTerms("release-e2e", "回滚", "端到端");
    }

    @Test
    void requiredNodesNeverDependDirectlyOrTransitivelyOnOptionalNodes() {
        Map<String, JsonNode> byCode = new HashMap<>();
        nodes().forEach(node -> byCode.put(node.get("code").asString(), node));
        Set<String> optional = byCode.values().stream().filter(node -> !node.get("required").asBoolean())
                .map(node -> node.get("code").asString()).collect(java.util.stream.Collectors.toSet());
        for (JsonNode node : byCode.values()) {
            if (node.get("required").asBoolean()) {
                assertThat(transitivePrerequisites(node, byCode)).as(node.get("code").asString())
                        .doesNotContainAnyElementsOf(optional);
            }
        }
    }

    @Test
    void rejectsCatalogWideGeneratedSentenceTemplates() {
        List<Pattern> forbidden = List.of(
                Pattern.compile("的可运行练习"),
                Pattern.compile("解释.*的行为，并用.*验证关键边界"),
                Pattern.compile("的核心机制"), Pattern.compile("的工程取舍"),
                Pattern.compile("混淆.*的职责，导致实现偏离契约"),
                Pattern.compile("忽略.*的边界条件"), Pattern.compile("辨析.*的适用场景"),
                Pattern.compile("语法、生命周期与典型调用链"),
                Pattern.compile("故障定位、边界处理及测试策略"),
                Pattern.compile("用于不匹配的场景"), Pattern.compile("只覆盖.*成功路径"),
                Pattern.compile("official documentation|troubleshooting|example test"),
                Pattern.compile("给定代码判断.*执行结果"), Pattern.compile("选择.*正确配置或 API"),
                Pattern.compile("定位一段示例中的.*缺陷"), Pattern.compile("比较.*两种实现并选择方案"),
                Pattern.compile("设计一个可判定通过或失败的测试"),
                Pattern.compile("关键配置与数据流|失败信号与保护措施|完成最小闭环"),
                Pattern.compile("故障日志根因|方案约束比较|边界用例设计|真实输入输出"),
                Pattern.compile("联用.*构成端到端示例"), Pattern.compile("根据日志复现问题"),
                Pattern.compile("关键参数、执行顺序与数据变化"),
                Pattern.compile("异常表现、恢复路径与观测点"), Pattern.compile("初始化或参数遗漏"),
                Pattern.compile("没有验证.*生产环境重复失败"),
                Pattern.compile("参数默认值对输出的影响|调用顺序与中间状态推演"),
                Pattern.compile("异常日志与根因定位|不同方案的约束与代价|边界输入和预期断言"));
        for (JsonNode node : nodes()) {
            String content = node.toString();
            forbidden.forEach(pattern -> assertThat(pattern.matcher(content).find())
                    .as("%s must not match %s", node.get("code").asString(), pattern).isFalse());
        }
    }

    @Test
    void rejectsKnownDangerousOrSemanticallyMismatchedQuizPrompts() {
        List<Pattern> forbidden = List.of(
                Pattern.compile("实现目录穿越"),
                Pattern.compile("SSRF开启前后"),
                Pattern.compile("aria-invalid开启前后的日志"),
                Pattern.compile("touched/dirty.*重试"),
                Pattern.compile("hashable key.*缺失字段"),
                Pattern.compile("hashable key.*降级"),
                Pattern.compile("LangGraph.*off-by-one"),
                Pattern.compile("tool schema.*off-by-one"));
        for (JsonNode node : nodes()) {
            for (JsonNode quiz : node.get("quizBlueprint")) {
                forbidden.forEach(pattern -> assertThat(pattern.matcher(quiz.asString()).find())
                        .as("%s must not match %s", node.get("code").asString(), pattern).isFalse());
            }
        }
    }

    @Test
    void everyQuizEntryIsACompleteQuestionOrExecutableTask() {
        Pattern action = Pattern.compile("[？?]|编写|分析|设计|定位|实现|解释|比较|判断|计算|选择|验证|说明|推演|修复|列出|阅读|改正|观察|模拟|写出|检查|运行|给出");
        for (JsonNode node : nodes()) {
            for (JsonNode quiz : node.get("quizBlueprint")) {
                assertThat(quiz.asString().length()).as(node.get("code").asString()).isGreaterThanOrEqualTo(12);
                assertThat(action.matcher(quiz.asString()).find()).as(node.get("code").asString()).isTrue();
            }
        }
    }

    @Test
    void everyPreviouslyTemplatedNodeContainsItsOwnSubjectMatter() {
        Map<String, List<String>> terms = Map.ofEntries(
                Map.entry("exceptions-custom", List.of("异常链", "cause")), Map.entry("files-nio-streams", List.of("Path", "UTF-8")),
                Map.entry("record-sealed-maven-junit-checkstyle", List.of("sealed", "mvn verify")), Map.entry("spring-scheduling", List.of("cron", "幂等")),
                Map.entry("spring-testing-slices", List.of("WebMvcTest", "DataJpaTest")), Map.entry("spring-module-project", List.of("MockMvc", "ProblemDetail")),
                Map.entry("mysql-transactions-locks", List.of("MVCC", "死锁")), Map.entry("jdbc-transactions", List.of("PreparedStatement", "rollback")),
                Map.entry("redis-data-structures", List.of("ZSET", "TTL")), Map.entry("spring-security-authentication", List.of("401", "403")),
                Map.entry("cache-security-project", List.of("JWT", "Cache Aside")), Map.entry("performance-profiling", List.of("JFR", "火焰图")),
                Map.entry("backend-quality-gate", List.of("JaCoCo", "SLO")), Map.entry("frontend-form-validation", List.of("aria-describedby", "重复提交")),
                Map.entry("git-workflow", List.of("rebase", "冲突")), Map.entry("linux-nginx", List.of("systemd", "proxy_pass")),
                Map.entry("frontend-delivery-project", List.of("Cypress", "docker compose")), Map.entry("python-data-structures", List.of("生成器", "StopIteration")),
                Map.entry("python-errors-files", List.of("with", "pathlib")), Map.entry("fastapi-integration-project", List.of("OpenAPI", "correlationId")),
                Map.entry("llm-foundations-project", List.of("JSON Schema", "finish_reason")), Map.entry("model-engineering-project", List.of("Retry-After", "TTFT")),
                Map.entry("langgraph-agent-project", List.of("checkpoint", "interrupt")), Map.entry("tool-contract-validation", List.of("JSON Schema", "timeout")),
                Map.entry("tool-permission-audit", List.of("allowlist", "审计")), Map.entry("mcp-governance-project", List.of("tools/list", "tools/call")),
                Map.entry("chunking-metadata", List.of("overlap", "metadata")), Map.entry("rag-retrieval-project", List.of("Qdrant", "Recall@k")),
                Map.entry("retrieval-evaluation", List.of("MRR", "nDCG")), Map.entry("search-assessment-project", List.of("citation", "拒答")),
                Map.entry("business-tools-project", List.of("dry-run", "ABAC")), Map.entry("business-agent-planning", List.of("DAG", "预算")),
                Map.entry("code-context-planning", List.of("调用链", "验收条件")), Map.entry("repository-agent-project", List.of("rg", "git diff --check")),
                Map.entry("runner-policy-engine", List.of("AST", "allowlist")), Map.entry("runner-security-project", List.of("seccomp", "SSRF")),
                Map.entry("string-content-comparison", List.of("==", "equals")));
        assertThat(terms).hasSize(37);
        terms.forEach((code, expected) -> assertTerms(code, expected.toArray(String[]::new)));
    }

    @Test
    void teachingSentencesAreNeverSharedAcrossNodes() {
        for (String field : List.of("objectives", "highFrequency", "commonMistakes", "quizBlueprint")) {
            List<String> sentences = nodes().stream().flatMap(node -> stream(node.get(field)).stream())
                    .map(JsonNode::asString).toList();
            assertThat(sentences).as(field).doesNotHaveDuplicates();
        }
    }

    @Test
    void quizSetsUseDiverseTaskShapesWithoutCatalogWideSuffixes() {
        Pattern fixedSuffix = Pattern.compile("在.*中的作用与限制。$|配置错误时的日志或响应症状。$|的正常路径与失败路径。$|与替代方案的约束和代价。$|的边界输入、操作步骤和预期断言。$|如何影响运行结果？$|配置错误时会出现什么症状？$|时最可能遗漏的保护措施。$");
        Map<String, Long> sequences = nodes().stream().collect(Collectors.groupingBy(
                node -> stream(node.get("quizBlueprint")).stream().map(RoadmapV2CatalogContentTest::openingVerb)
                        .collect(Collectors.joining("|")), Collectors.counting()));
        assertThat(sequences.values()).allMatch(count -> count <= 5);
        for (JsonNode node : nodes()) {
            for (JsonNode quiz : node.get("quizBlueprint")) {
                assertThat(fixedSuffix.matcher(quiz.asString()).find()).as(node.get("code").asString()).isFalse();
            }
        }
    }

    @Test
    void everyNodeQuizCoversAtLeastThreeOfItsSpecificConcepts() {
        List<JsonNode> laterStageNodes = nodes().stream().skip(45).toList();
        Map<String, List<String>> quizConceptTerms = laterStageNodes.stream().collect(Collectors.toMap(
                node -> node.get("code").asString(), node -> conceptTerms(node).stream().limit(3).toList()));
        assertThat(quizConceptTerms).hasSize(80).allSatisfy((code, terms) -> assertThat(terms).hasSize(3));
        quizConceptTerms.forEach((code, terms) -> {
            String quizzes = laterStageNodes.stream().filter(node -> code.equals(node.get("code").asString()))
                    .findFirst().orElseThrow().get("quizBlueprint").toString();
            assertThat(quizzes).as(code).contains(terms.toArray(String[]::new));
        });
    }

    private static List<String> conceptTerms(JsonNode node) {
        return stream(node.get("highFrequency")).stream().map(JsonNode::asString)
                .flatMap(value -> Pattern.compile("[；]+ ").splitAsStream(value.replace("；", "； ")))
                .map(String::strip).filter(term -> term.length() >= 3).distinct().toList();
    }

    private static String openingVerb(JsonNode quiz) {
        var matcher = Pattern.compile("^(解释|分析|编写|设计|定位|实现|比较|判断|计算|选择|验证|说明|推演|修复|列出|阅读|给出|改正|观察|模拟|写出|检查|运行)").matcher(quiz.asString());
        return matcher.find() ? matcher.group(1) : "OTHER";
    }

    @Test
    void representativeGuidanceNamesConcreteBehaviorsAndFailureSymptomsAcrossEveryStage() {
        assertTerms("variables-types-conversion", "溢出", "ClassCastException");
        assertTerms("string-content-comparison", "==", "equals");
        assertTerms("stream-optional", "惰性", "NoSuchElementException");
        assertTerms("spring-boot-layering", "启动失败", "ConditionEvaluationReport");
        assertTerms("spring-dto-validation", "漏加 @Valid", "400");
        assertTerms("spring-files-upload", "目录穿越", "normalize");
        assertTerms("mysql-schema-types", "DECIMAL", "隐式转换");
        assertTerms("mysql-index-basics", "EXPLAIN", "type", "rows");
        assertTerms("jpa-core", "N+1", "EntityGraph");
        assertTerms("redis-data-structures", "TTL", "内存淘汰");
        assertTerms("spring-security-authentication", "401", "403");
        assertTerms("performance-profiling", "JFR", "火焰图");
        assertTerms("vue-ts-basics", "ref", "reactive");
        assertTerms("frontend-api-integration", "CORS", "拦截器");
        assertTerms("docker-delivery", "HEALTHCHECK", "多阶段构建");
        assertTerms("python-engineering", "venv", "pyproject.toml");
        assertTerms("fastapi-routing-dependencies", "Depends", "dependency_overrides");
        assertTerms("python-async-http", "event loop", "TimeoutException");
        assertTerms("llm-api-basics", "temperature", "finish_reason");
        assertTerms("structured-output", "JSON Schema", "解析失败");
        assertTerms("model-cost-retry", "429", "Retry-After");
        assertTerms("model-observability", "TTFT", "token");
        assertTerms("llm-security", "提示注入", "数据泄露");
        assertTerms("langgraph-state", "StateGraph", "reducer");
        assertTerms("human-in-loop", "interrupt", "resume");
        assertTerms("mcp", "tools/list", "tools/call");
        assertTerms("document-parsing", "乱码", "页码");
        assertTerms("embedding-qdrant", "collection", "payload filter");
        assertTerms("retrieval-evaluation", "Recall@k", "MRR");
        assertTerms("business-tool-contracts", "副作用", "幂等键");
        assertTerms("authorization-governance", "RBAC", "ABAC");
        assertTerms("business-agent-recovery", "补偿", "死信");
        assertTerms("repo-read-search", "rg", ".gitignore");
        assertTerms("patch-diff", "hunk", "上下文行");
        assertTerms("playwright-dom", "locator", "strict mode");
        assertTerms("runner-sandbox", "OOM", "超时");
        assertTerms("secret-redaction", "熵", "误报");
        assertTerms("network-firewall", "SSRF", "DNS rebinding");
        assertTerms("release-strategy", "金丝雀", "错误率");
        assertTerms("rollback-drill", "RTO", "RPO");
        assertTerms("release-e2e", "回滚", "SLO");
    }

    @Test
    void stageOneContainsExactlyThePlannedTwentyFiveFoundationalNodes() {
        JsonNode stage = catalog.get("stages").get(0);
        assertThat(stream(stage.get("modules")).stream().flatMap(module -> stream(module.get("nodes")).stream())
                .map(node -> List.of(node.get("code").asString(), node.get("title").asString(),
                        Integer.toString(node.get("nodeOrder").asInt()))).toList()).containsExactly(
                row("java-environment-first-program", "JDK 环境与第一个程序", 1), row("variables-types-conversion", "变量、类型与转换", 2),
                row("operators-console-io", "运算符与控制台输入输出", 3), row("conditions-if-switch", "条件分支", 4), row("loops-control", "循环与流程控制", 5), row("methods-parameters-return", "方法、参数与返回值", 6), row("arrays-basic-traversal", "数组与基础遍历", 7),
                row("classes-objects", "类与对象", 8), row("constructors-this", "构造器与 this", 9), row("encapsulation-access", "封装与访问控制", 10), row("static-constants-members", "静态成员与常量", 11), row("inheritance-overriding", "继承与方法重写", 12), row("polymorphism", "多态", 13), row("abstract-classes-interfaces", "抽象类与接口", 14),
                row("string-content-comparison", "字符串与内容比较", 15), row("wrappers-enums-datetime", "包装类型、枚举与日期时间", 16), row("object-equals-hashcode-tostring", "Object 核心契约", 17), row("list-iteration", "List 与迭代", 18), row("set-map-deduplication", "Set、Map 与去重", 19), row("generics-comparator-type-safety", "泛型、比较器与类型安全", 20),
                row("exceptions-custom", "异常处理与自定义异常", 21), row("files-nio-streams", "文件、NIO 与流", 22), row("lambda-functional-interfaces", "Lambda 与函数式接口", 23), row("stream-optional", "Stream 与 Optional", 24), row("record-sealed-maven-junit-checkstyle", "现代 Java 建模与工程质量实践", 25));
        String firstModule = stage.get("modules").get(0).toString();
        assertThat(firstModule).doesNotContain("record", "sealed", "Stream", "Optional", "Checkstyle");
    }

    @Test
    void stageOneToThreeQuizzesContainHandwrittenNodeSpecificFragments() {
        Map<String, List<String>> expected = Map.ofEntries(
                Map.entry("java-environment-first-program", List.of("java -version 与 javac -version", "UnsupportedClassVersionError 日志")),
                Map.entry("variables-types-conversion", List.of("Math.addExact 验证数值溢出", "float 精度为何不适合金额")),
                Map.entry("operators-console-io", List.of("7/2 与 7/2.0", "左侧为 false 时 && 右侧计数器")),
                Map.entry("conditions-if-switch", List.of("分数 59、60、89、90", "switch(day) 作为表达式")),
                Map.entry("loops-control", List.of("i<=length 导致的循环边界错误", "求 1 到 n 之和的循环")),
                Map.entry("methods-parameters-return", List.of("print(int) 与 print(long)", "Java 值传递解释")),
                Map.entry("arrays-basic-traversal", List.of("a[3] 的异常", "int[][] ragged")),
                Map.entry("classes-objects", List.of("连续调用两次后输出值", "对象 A 引用 B")),
                Map.entry("constructors-this", List.of("默认年龄", "实例初始化块")),
                Map.entry("encapsulation-access", List.of("BankAccount.balance", "List.copyOf 返回不可变快照")),
                Map.entry("static-constants-members", List.of("static count++", "常量内联会产生什么旧值问题")),
                Map.entry("inheritance-overriding", List.of("Car extends Vehicle", "协变返回类型")),
                Map.entry("polymorphism", List.of("Animal a=new Dog()", "List<Animal> 同时放入 Dog 和 Cat")),
                Map.entry("abstract-classes-interfaces", List.of("ReportGenerator", "A.super 解决方式")),
                Map.entry("string-content-comparison", List.of("a==b 与 a.equals(b)", "StringBuilder 版本")),
                Map.entry("wrappers-enums-datetime", List.of("Integer.valueOf(127)", "2026-02-30")),
                Map.entry("object-equals-hashcode-tostring", List.of("Money 创建 a、b、c", "contains 查找为何失败")),
                Map.entry("list-iteration", List.of("new ArrayList<>(2)", "view=list.subList(1,3)")),
                Map.entry("set-map-deduplication", List.of("set.size() 以验证去重", "Map.of 对 null key")),
                Map.entry("generics-comparator-type-safety", List.of("List<? extends Number>", "producer 和 consumer 的角色")),
                Map.entry("exceptions-custom", List.of("new RepositoryException", "getSuppressed() 中的关闭异常")),
                Map.entry("files-nio-streams", List.of("base=/srv/data 与输入 ../secret", "CodingErrorAction.REPORT")),
                Map.entry("lambda-functional-interfaces", List.of("System.out::println", "nonBlank.and(shorterThan20)")),
                Map.entry("stream-optional", List.of("counter 仍为 0", "orElse(expensive())")),
                Map.entry("record-sealed-maven-junit-checkstyle", List.of("record Email(String value)", "target/checkstyle-result.xml")),
                Map.entry("spring-boot-layering", List.of("ConditionEvaluationReport 中某自动配置", "curl /actuator/conditions")),
                Map.entry("spring-ioc-di", List.of("final PaymentGateway 字段", "BeanCurrentlyInCreationException")),
                Map.entry("spring-mvc-rest", List.of("ResponseEntity.created(location)", "jsonPath")),
                Map.entry("spring-dto-validation", List.of("items[0].quantity=0", "OnCreate 与 OnUpdate")),
                Map.entry("spring-validation-errors", List.of("status=400 的 ProblemDetail", "application/problem+json")),
                Map.entry("spring-config-logging", List.of("app.client.timeout=2s", "MDC.clear() 防止线程复用串号")),
                Map.entry("spring-files-upload", List.of("MultipartFile.getInputStream()", "startsWith(base)")),
                Map.entry("spring-scheduling", List.of("Asia/Shanghai", "指数重试 1s、2s、4s")),
                Map.entry("spring-testing-slices", List.of("@WebMvcTest(OrderController.class)", "TestEntityManager.flush()")),
                Map.entry("spring-module-project", List.of("suppliedId", "库存不足返回 type")),
                Map.entry("mysql-schema-types", List.of("DECIMAL(12,2)", "Incorrect string value")),
                Map.entry("mysql-sql-modeling", List.of("ROW_NUMBER() OVER", "没有订单的客户也返回 count=0")),
                Map.entry("mysql-index-basics", List.of("idx_email(email)", "Extra 是否为 Using index")),
                Map.entry("mysql-transactions-locks", List.of("score BETWEEN 60 AND 70", "LATEST DETECTED DEADLOCK")),
                Map.entry("mysql-index-transaction", List.of("long_query_time=1", "Query_time 和 Rows_examined")),
                Map.entry("jdbc-transactions", List.of("setString(1, username)", "Statement.EXECUTE_FAILED")),
                Map.entry("mybatis-core", List.of("customer_id、customer_name", "foreach collection")),
                Map.entry("mybatis-plus", List.of("PaginationInnerInterceptor", "insertFill 写 createdAt")),
                Map.entry("jpa-core", List.of("transient、managed、detached", "ObjectOptimisticLockingFailureException")),
                Map.entry("data-access-comparison", List.of("executorType=BATCH", "先固定接口契约与集成测试")));
        assertThat(expected).hasSize(45);
        assertThat(expected.values()).allSatisfy(fragments -> assertThat(fragments).hasSize(2));
        assertThat(expected.values().stream().flatMap(List::stream).toList()).doesNotHaveDuplicates();
        expected.forEach((code, fragments) -> {
            JsonNode node = nodes().stream().filter(it -> code.equals(it.get("code").asString()))
                    .findFirst().orElseThrow();
            assertThat(node.get("quizBlueprint").toString()).as(code)
                    .contains(fragments.toArray(String[]::new));
        });
    }

    private static List<String> row(String code, String title, int order) {
        return List.of(code, title, Integer.toString(order));
    }

    private static Set<String> transitivePrerequisites(JsonNode start, Map<String, JsonNode> byCode) {
        Set<String> result = new HashSet<>();
        collectPrerequisites(start, byCode, result);
        return result;
    }

    private static void collectPrerequisites(JsonNode node, Map<String, JsonNode> byCode, Set<String> result) {
        for (JsonNode prerequisite : node.get("prerequisites")) {
            String code = prerequisite.asString();
            if (result.add(code)) collectPrerequisites(byCode.get(code), byCode, result);
        }
    }

    private void assertTerms(String code, String... terms) {
        JsonNode node = nodes().stream().filter(it -> code.equals(it.get("code").asString())).findFirst().orElseThrow();
        assertThat(node.toString()).as(code).contains(terms);
    }

    private List<JsonNode> modules() {
        return stream(catalog.get("stages")).stream().flatMap(stage -> stream(stage.get("modules")).stream()).toList();
    }

    private List<JsonNode> nodes() {
        return modules().stream().flatMap(module -> stream(module.get("nodes")).stream()).toList();
    }

    private static int nodeCount(JsonNode stage) {
        return stream(stage.get("modules")).stream().mapToInt(module -> module.get("nodes").size()).sum();
    }

    private static List<JsonNode> stream(JsonNode array) {
        return StreamSupport.stream(array.spliterator(), false).toList();
    }

    private static JsonNode readCatalog() {
        try (var input = new ClassPathResource("roadmaps/studypilot-java-ai-v2.json").getInputStream()) {
            return new ObjectMapper().readTree(input);
        } catch (IOException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
