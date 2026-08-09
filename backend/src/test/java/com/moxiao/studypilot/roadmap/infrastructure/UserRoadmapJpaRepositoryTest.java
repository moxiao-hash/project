package com.moxiao.studypilot.roadmap.infrastructure;

import com.moxiao.studypilot.roadmap.domain.UserRoadmapStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Transactional
class UserRoadmapJpaRepositoryTest {

    @Autowired
    private UserRoadmapJpaRepository repository;

    @Test
    void findsUniqueCurrentRoadmapAndAllHistoricalRoadmaps() {
        Instant now = Instant.parse("2026-08-09T00:00:00Z");
        UserRoadmapEntity firstHistorical = historical(
                "roadmap-1", "owner-1", "template-1", now
        );
        UserRoadmapEntity secondHistorical = historical(
                "roadmap-2", "owner-1", "template-2", now
        );
        UserRoadmapEntity current = new UserRoadmapEntity(
                "roadmap-3", "owner-1", "template-3", now
        );
        repository.saveAll(List.of(firstHistorical, secondHistorical, current));

        assertThat(repository.findAllByOwnerIdAndStatus("owner-1", UserRoadmapStatus.SUPERSEDED))
                .extracting(UserRoadmapEntity::getId)
                .containsExactlyInAnyOrder("roadmap-1", "roadmap-2");
        assertThat(repository.findByOwnerIdAndActiveSlot("owner-1", "CURRENT"))
                .map(UserRoadmapEntity::getId)
                .contains("roadmap-3");
    }

    private UserRoadmapEntity historical(
            String id,
            String ownerId,
            String templateId,
            Instant now
    ) {
        UserRoadmapEntity roadmap = new UserRoadmapEntity(id, ownerId, templateId, now);
        roadmap.supersede(now.plusSeconds(1));
        return roadmap;
    }
}
