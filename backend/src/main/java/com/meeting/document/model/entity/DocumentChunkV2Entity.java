package com.meeting.document.model.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.meeting.document.model.VectorSearchHit;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import java.time.LocalDateTime;

@Getter
@Setter
@Entity
@Table(name = "document_chunk_v2", indexes = {
    @Index(name = "idx_dc_v2_document_id", columnList = "document_id")
})
/**
 * 向量搜索：余弦距离最近邻检索。
 * embedding <=> CAST(:embedding AS vector) 是 pgvector 的余弦距离运算符，值域 [0,2]。
 * 1 - 距离 转为 [-1,1] 的相似度，越大越相似。
 * WHERE embedding IS NOT NULL 排除没有向量的记录（如纯元数据块）。
 */
@NamedNativeQuery(
    name = "DocumentChunkV2Entity.vectorSearch",
    query = """
        SELECT id, document_id, content, chunk_index, speaker, metadata,
               1 - (embedding <=> CAST(:embedding AS vector)) AS similarity
        FROM document_chunk_v2
        WHERE embedding IS NOT NULL
        ORDER BY embedding <=> CAST(:embedding AS vector)
        LIMIT :topK
        """,
    resultSetMapping = "VectorSearchHitV2Mapping"
)
/**
 * styleExemplarSearch 用于改写风格示例匹配，与 vectorSearch 的区别：
 * 1. ORDER BY 直接按相似度降序（而非按距离升序，实际效果等价）
 * 2. 检索结果用于风格模仿而非语义检索，不参与后续 RRF 融合
 */
@NamedNativeQuery(
    name = "DocumentChunkV2Entity.styleExemplarSearch",
    query = """
        SELECT id, document_id, content, chunk_index, speaker, metadata,
               1 - (embedding <=> CAST(:embedding AS vector)) AS similarity
        FROM document_chunk_v2
        WHERE embedding IS NOT NULL
        ORDER BY 1 - (embedding <=> CAST(:embedding AS vector)) DESC
        LIMIT :topK
        """,
    resultSetMapping = "VectorSearchHitV2Mapping"
)
@SqlResultSetMapping(
    name = "VectorSearchHitV2Mapping",
    classes = @ConstructorResult(
        targetClass = VectorSearchHit.class,
        columns = {
            @ColumnResult(name = "id", type = Long.class),
            @ColumnResult(name = "document_id", type = Long.class),
            @ColumnResult(name = "content", type = String.class),
            @ColumnResult(name = "chunk_index", type = Integer.class),
            @ColumnResult(name = "speaker", type = String.class),
            @ColumnResult(name = "metadata", type = String.class),
            @ColumnResult(name = "similarity", type = Double.class)
        }
    )
)
public class DocumentChunkV2Entity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "document_id", nullable = false)
    @JsonIgnore
    private DocumentEntity document;

    @Column(columnDefinition = "TEXT", nullable = false)
    private String content;

    /** 1024 维 pgvector 向量，由 EmbeddingService 生成，供余弦距离检索 */
    @Column(columnDefinition = "VECTOR(1024)")
    private float[] embedding;

    @Column(name = "chunk_index")
    private Integer chunkIndex;

    @Column(length = 100)
    private String speaker;

    /** 分块元数据 JSON：document_title, meeting_date, participants, topic, section_heading */
    @Column(columnDefinition = "TEXT")
    private String metadata;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    public Long getDocumentId() {
        return document != null ? document.getId() : null;
    }

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }
}