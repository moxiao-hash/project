package com.moxiao.studypilot.assessment.infrastructure;

import com.moxiao.studypilot.assessment.domain.MasteryEvidenceType;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Column;

import java.time.Instant;

@Entity
@Table(name = "mastery_evidence")
public class MasteryEvidenceEntity {
    @Id
    private String id;
    @Column(name = "owner_id", nullable = false)
    private String ownerId;
    @Column(name = "knowledge_point", nullable = false, length = 180)
    private String knowledgePoint;
    @Enumerated(EnumType.STRING)
    @Column(name = "evidence_type", nullable = false, length = 30)
    private MasteryEvidenceType type;
    @Column(nullable = false)
    private double score;
    @Column(name = "evidence_weight", nullable = false)
    private double evidenceWeight;
    @Column(name = "source_reference", nullable = false, length = 180)
    private String sourceReference;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected MasteryEvidenceEntity() {
    }

    public MasteryEvidenceEntity(
            String id,
            String ownerId,
            String knowledgePoint,
            MasteryEvidenceType type,
            double score,
            double evidenceWeight,
            String sourceReference,
            Instant createdAt
    ) {
        this.id = id;
        this.ownerId = ownerId;
        this.knowledgePoint = knowledgePoint;
        this.type = type;
        this.score = score;
        this.evidenceWeight = evidenceWeight;
        this.sourceReference = sourceReference;
        this.createdAt = createdAt;
    }
}
