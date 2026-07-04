package com.meeting.document.repository;

import com.meeting.document.model.VectorSearchHit;
import com.meeting.document.model.entity.DocumentChunkEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface DocumentChunkRepository extends JpaRepository<DocumentChunkEntity, Long> {

    List<DocumentChunkEntity> findByDocumentIdOrderByChunkIndex(Long documentId);

    List<DocumentChunkEntity> findByDocumentIdInOrderByChunkIndex(List<Long> documentIds);

    @Query(name = "DocumentChunkEntity.vectorSearch", nativeQuery = true)
    List<VectorSearchHit> vectorSearch(@Param("embedding") String embedding, @Param("topK") int topK);
}
