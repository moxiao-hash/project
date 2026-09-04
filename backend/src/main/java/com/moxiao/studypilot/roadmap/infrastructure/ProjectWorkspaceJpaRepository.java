package com.moxiao.studypilot.roadmap.infrastructure;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ProjectWorkspaceJpaRepository extends JpaRepository<ProjectWorkspaceEntity, String> {
    List<ProjectWorkspaceEntity> findAllByOwnerIdOrderByCreatedAtAsc(String ownerId);
    Optional<ProjectWorkspaceEntity> findByIdAndOwnerId(String id, String ownerId);
    Optional<ProjectWorkspaceEntity> findByOwnerIdAndRootPath(String ownerId, String rootPath);
}
