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

    /** 按 documentId 查询所有分块，按 chunkIndex 升序排列 */
    @Query("SELECT c FROM DocumentChunkV2Entity c WHERE c.document.id = :documentId ORDER BY c.chunkIndex")
    List<DocumentChunkV2Entity> findByDocumentIdOrderByChunkIndex(@Param("documentId") Long documentId);

    /** 批量查询多个文档的所有分块 */
    @Query("SELECT c FROM DocumentChunkV2Entity c WHERE c.document.id IN :documentIds ORDER BY c.chunkIndex")
    List<DocumentChunkV2Entity> findByDocumentIdInOrderByChunkIndex(@Param("documentIds") List<Long> documentIds);

    /** 向量搜索：余弦距离最近邻检索，返回相似度降序的前 topK 条 */
    @Query(name = "DocumentChunkV2Entity.vectorSearch", nativeQuery = true)
    List<VectorSearchHit> vectorSearch(@Param("embedding") String embedding, @Param("topK") int topK);

    /** 风格示例搜索：用于改写风格匹配，检索结果不参与 RRF 融合 */
    @Query(name = "DocumentChunkV2Entity.styleExemplarSearch", nativeQuery = true)
    List<VectorSearchHit> styleExemplarSearch(@Param("embedding") String embedding, @Param("topK") int topK);
}