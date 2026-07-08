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

    @Query(value = "SELECT dc.id, dc.document_id AS documentId, dc.content, dc.chunk_index AS chunkIndex, "
            + "dc.speaker, dc.section_type AS sectionType, "
            + "1 - (dc.embedding <=> CAST(:embedding AS vector)) AS similarityScore "
            + "FROM document_chunk dc "
            + "JOIN document d ON dc.document_id = d.id "
            + "WHERE d.style_exemplar = true AND dc.embedding IS NOT NULL "
            + "ORDER BY 1 - (dc.embedding <=> CAST(:embedding AS vector)) DESC "
            + "LIMIT :topK", nativeQuery = true)
    List<VectorSearchHit> styleExemplarSearch(@Param("embedding") String embedding, @Param("topK") int topK);
}
