package com.meeting.document.repository;

import com.meeting.document.model.entity.DocumentChunkEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Map;

@Repository
public interface DocumentChunkRepository extends JpaRepository<DocumentChunkEntity, Long> {

    List<DocumentChunkEntity> findByDocumentIdOrderByChunkIndex(Long documentId);

    void deleteByDocumentId(Long documentId);

    @Query(value = """
        SELECT id, document_id, content, chunk_index, speaker, section_type, metadata,
               1 - (embedding <=> CAST(:embedding AS vector)) AS similarity
        FROM document_chunk
        WHERE embedding IS NOT NULL
        ORDER BY embedding <=> CAST(:embedding AS vector)
        LIMIT :topK
        """, nativeQuery = true)
    List<Map<String, Object>> vectorSearch(@Param("embedding") String embedding, @Param("topK") int topK);
}
