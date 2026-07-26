package com.moxiao.studypilot.material.infrastructure;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "web_search_results")
public class WebSearchResultEntity {

    @Id
    private String id;

    @Column(name = "search_id", nullable = false)
    private String searchId;

    @Column(name = "owner_id", nullable = false)
    private String ownerId;

    @Column(nullable = false, length = 300)
    private String title;

    @Column(nullable = false, length = 2048)
    private String url;

    @Column(nullable = false, length = 2_000)
    private String snippet;

    @Column(nullable = false)
    private double score;

    @Column(name = "imported_material_id")
    private String importedMaterialId;

    protected WebSearchResultEntity() {
    }

    public WebSearchResultEntity(
            String id,
            String searchId,
            String ownerId,
            String title,
            String url,
            String snippet,
            double score
    ) {
        this.id = id;
        this.searchId = searchId;
        this.ownerId = ownerId;
        this.title = title;
        this.url = url;
        this.snippet = snippet;
        this.score = score;
    }

    public void markImported(String materialId) {
        importedMaterialId = materialId;
    }

    public String getId() {
        return id;
    }

    public String getSearchId() {
        return searchId;
    }

    public String getOwnerId() {
        return ownerId;
    }

    public String getTitle() {
        return title;
    }

    public String getUrl() {
        return url;
    }

    public String getSnippet() {
        return snippet;
    }

    public double getScore() {
        return score;
    }

    public String getImportedMaterialId() {
        return importedMaterialId;
    }
}
