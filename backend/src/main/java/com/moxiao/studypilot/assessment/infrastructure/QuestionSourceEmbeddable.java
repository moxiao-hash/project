package com.moxiao.studypilot.assessment.infrastructure;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

@Embeddable
public class QuestionSourceEmbeddable {

    @Column(name = "source_type", nullable = false, length = 30)
    private String sourceType;

    @Column(name = "material_id", length = 36)
    private String materialId;

    @Column(name = "web_result_id", length = 36)
    private String webResultId;

    @Column(nullable = false, length = 500)
    private String title;

    @Column(length = 500)
    private String locator;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String snippet;

    protected QuestionSourceEmbeddable() {
    }

    public QuestionSourceEmbeddable(
            String sourceType,
            String materialId,
            String webResultId,
            String title,
            String locator,
            String snippet
    ) {
        this.sourceType = sourceType;
        this.materialId = materialId;
        this.webResultId = webResultId;
        this.title = title;
        this.locator = locator;
        this.snippet = snippet;
    }

    public String getSourceType() { return sourceType; }
    public String getMaterialId() { return materialId; }
    public String getWebResultId() { return webResultId; }
    public String getTitle() { return title; }
    public String getLocator() { return locator; }
    public String getSnippet() { return snippet; }
}
