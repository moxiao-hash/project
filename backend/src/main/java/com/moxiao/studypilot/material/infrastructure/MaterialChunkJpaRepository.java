package com.moxiao.studypilot.material.infrastructure;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface MaterialChunkJpaRepository extends JpaRepository<MaterialChunkEntity, String> {

    List<MaterialChunkEntity> findAllByMaterialIdOrderByPosition(String materialId);

    void deleteAllByMaterialId(String materialId);
}
