package com.moxiao.studypilot.roadmap.infrastructure;

import com.moxiao.studypilot.roadmap.domain.UserRoadmapStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface UserRoadmapJpaRepository extends JpaRepository<UserRoadmapEntity, String> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select roadmap from UserRoadmapEntity roadmap where roadmap.id = :id")
    Optional<UserRoadmapEntity> findByIdForUpdate(@Param("id") String id);

    Optional<UserRoadmapEntity> findByOwnerIdAndActiveSlot(String ownerId, String activeSlot);

    List<UserRoadmapEntity> findAllByOwnerIdAndStatus(String ownerId, UserRoadmapStatus status);

    Optional<UserRoadmapEntity> findByOwnerIdAndTemplateId(String ownerId, String templateId);
}
