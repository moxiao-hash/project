package com.moxiao.studypilot.roadmap.infrastructure;

import com.moxiao.studypilot.roadmap.domain.WorkspaceStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.Instant;

@Entity
@Table(name = "project_workspaces")
public class ProjectWorkspaceEntity {
    @Id
    @Column(nullable = false, length = 36)
    private String id;
    @Column(name = "owner_id", nullable = false, length = 36)
    private String ownerId;
    @Column(nullable = false, length = 100)
    private String name;
    @Column(name = "root_path", nullable = false, length = 1024)
    private String rootPath;
    @Column(name = "root_path_hash", nullable = false, length = 64)
    private String rootPathHash;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private WorkspaceStatus status;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
    @Version
    @Column(name = "row_version", nullable = false)
    private long rowVersion;

    protected ProjectWorkspaceEntity() { }

    public ProjectWorkspaceEntity(
            String id, String ownerId, String name, String rootPath,
            String rootPathHash, Instant now
    ) {
        this.id = id;
        this.ownerId = ownerId;
        this.name = name;
        this.rootPath = rootPath;
        this.rootPathHash = rootPathHash;
        this.status = WorkspaceStatus.ACTIVE;
        this.createdAt = now;
        this.updatedAt = now;
    }

    public String getId() { return id; }
    public String getOwnerId() { return ownerId; }
    public String getName() { return name; }
    public String getRootPath() { return rootPath; }
    public String getRootPathHash() { return rootPathHash; }
    public WorkspaceStatus getStatus() { return status; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public long getRowVersion() { return rowVersion; }
}
