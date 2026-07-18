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

    @Query("SELECT c FROM DocumentChunkEntity c WHERE c.document.id = :documentId ORDER BY c.chunkIndex")
    List<DocumentChunkEntity> findByDocumentIdOrderByChunkIndex(@Param("documentId") Long documentId);

    @Query("SELECT c FROM DocumentChunkEntity c WHERE c.document.id IN :documentIds ORDER BY c.chunkIndex")
    List<DocumentChunkEntity> findByDocumentIdInOrderByChunkIndex(@Param("documentIds") List<Long> documentIds);

    @Query(name = "DocumentChunkEntity.vectorSearch", nativeQuery = true)
    List<VectorSearchHit> vectorSearch(@Param("embedding") String embedding, @Param("topK") int topK);

    @Query(name = "DocumentChunkEntity.styleExemplarSearch", nativeQuery = true)
    List<VectorSearchHit> styleExemplarSearch(@Param("embedding") String embedding, @Param("topK") int topK);
}
