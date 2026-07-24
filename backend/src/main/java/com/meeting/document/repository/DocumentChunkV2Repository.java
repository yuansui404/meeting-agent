package com.meeting.document.repository;

import com.meeting.document.model.VectorSearchHit;
import com.meeting.document.model.entity.DocumentChunkV2Entity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface DocumentChunkV2Repository extends JpaRepository<DocumentChunkV2Entity, Long> {

    @Query("SELECT c FROM DocumentChunkV2Entity c WHERE c.document.id = :documentId ORDER BY c.chunkIndex")
    List<DocumentChunkV2Entity> findByDocumentIdOrderByChunkIndex(@Param("documentId") Long documentId);

    @Query("SELECT c FROM DocumentChunkV2Entity c WHERE c.document.id IN :documentIds ORDER BY c.chunkIndex")
    List<DocumentChunkV2Entity> findByDocumentIdInOrderByChunkIndex(@Param("documentIds") List<Long> documentIds);

    @Query(name = "DocumentChunkV2Entity.vectorSearch", nativeQuery = true)
    List<VectorSearchHit> vectorSearch(@Param("embedding") String embedding, @Param("topK") int topK);

    @Query(name = "DocumentChunkV2Entity.styleExemplarSearch", nativeQuery = true)
    List<VectorSearchHit> styleExemplarSearch(@Param("embedding") String embedding, @Param("topK") int topK);
}