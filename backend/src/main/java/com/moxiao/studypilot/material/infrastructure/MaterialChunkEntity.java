package com.moxiao.studypilot.material.infrastructure;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "material_chunks")
public class MaterialChunkEntity {

    @Id
    private String id;

    @Column(name = "material_id", nullable = false)
    private String materialId;

    @Column(nullable = false)
    private int position;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String text;

    @Column(nullable = false, length = 255)
    private String locator;

    protected MaterialChunkEntity() {
    }

    public MaterialChunkEntity(
            String id,
            String materialId,
            int position,
            String text,
            String locator
    ) {
        this.id = id;
        this.materialId = materialId;
        this.position = position;
        this.text = text;
        this.locator = locator;
    }

    public String getId() {
        return id;
    }

    public String getMaterialId() {
        return materialId;
    }

    public int getPosition() {
        return position;
    }

    public String getText() {
        return text;
    }

    public String getLocator() {
        return locator;
    }
}
