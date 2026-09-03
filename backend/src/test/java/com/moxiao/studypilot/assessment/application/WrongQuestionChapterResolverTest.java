package com.moxiao.studypilot.assessment.application;

import com.moxiao.studypilot.assessment.infrastructure.QuizEntity;
import com.moxiao.studypilot.roadmap.domain.RoadmapQuizPurpose;
import com.moxiao.studypilot.roadmap.infrastructure.RoadmapModuleEntity;
import com.moxiao.studypilot.roadmap.infrastructure.RoadmapModuleJpaRepository;
import com.moxiao.studypilot.roadmap.infrastructure.RoadmapNodeEntity;
import com.moxiao.studypilot.roadmap.infrastructure.RoadmapNodeJpaRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WrongQuestionChapterResolverTest {
    @Mock RoadmapNodeJpaRepository nodeRepository;
    @Mock RoadmapModuleJpaRepository moduleRepository;

    @Test
    void roadmapNodeQuizUsesItsModuleAsChapter() {
        WrongQuestionChapterResolver resolver = new WrongQuestionChapterResolver(
                nodeRepository, moduleRepository, null, null, null);
        QuizEntity quiz = new QuizEntity(
                "quiz", "owner", null, null, null, "node", "enrollment", "state",
                null, "template", RoadmapQuizPurpose.NODE, "变量测验", "model", Instant.now());
        RoadmapNodeEntity node = new RoadmapNodeEntity(
                "node", "template", "stage", "module", "variables", 1, "变量与类型",
                "[]", "[]", "[]", "[]", "{}", "{}", 45, 15, "EASY", true);
        RoadmapModuleEntity module = new RoadmapModuleEntity(
                "module", "template", "stage", "java-start", 1,
                "Java 语言起步", "从零开始");
        when(nodeRepository.findByIdAndTemplateId("node", "template"))
                .thenReturn(Optional.of(node));
        when(moduleRepository.findByIdAndTemplateId("module", "template"))
                .thenReturn(Optional.of(module));

        WrongQuestionChapterResolver.Chapter chapter = resolver.resolve(quiz);

        assertThat(chapter.key()).isEqualTo("roadmap-module:module");
        assertThat(chapter.title()).isEqualTo("Java 语言起步");
    }
}
