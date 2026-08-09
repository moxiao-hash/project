package com.moxiao.studypilot.roadmap.infrastructure;

import com.moxiao.studypilot.roadmap.domain.RoadmapPublicationStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface RoadmapTemplateJpaRepository extends JpaRepository<RoadmapTemplateEntity, String> {
    Optional<RoadmapTemplateEntity> findByRoadmapCodeAndTemplateVersion(
            String roadmapCode,
            int templateVersion
    );

    Optional<RoadmapTemplateEntity> findFirstByRoadmapCodeAndPublicationStatusOrderByTemplateVersionDesc(
            String roadmapCode,
            RoadmapPublicationStatus publicationStatus
    );

    /**
     * A locking read is a current read under MySQL REPEATABLE READ. Upgrade workflows use this
     * method after locking the enrollment and before locking an upgrade preview.
     */
    @Lock(LockModeType.PESSIMISTIC_READ)
    @Query("""
            select template from RoadmapTemplateEntity template
            where template.roadmapCode = :roadmapCode
              and template.publicationStatus = :publicationStatus
            order by template.templateVersion desc
            """)
    List<RoadmapTemplateEntity> findPublishedVersionsForUpgrade(
            @Param("roadmapCode") String roadmapCode,
            @Param("publicationStatus") RoadmapPublicationStatus publicationStatus
    );
}
