package com.moxiao.studypilot.material.api;

import com.moxiao.studypilot.material.infrastructure.MaterialChunkEntity;

public record MaterialChunkResponse(
        String id,
        int position,
        String text,
        String locator
) {
    public static MaterialChunkResponse from(MaterialChunkEntity entity) {
        return new MaterialChunkResponse(
                entity.getId(),
                entity.getPosition(),
                entity.getText(),
                entity.getLocator()
        );
    }
}
