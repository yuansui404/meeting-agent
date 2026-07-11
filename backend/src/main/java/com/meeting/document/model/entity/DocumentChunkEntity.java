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
@Table(name = "document_chunk", indexes = {
    @Index(name = "idx_dc_document_id", columnList = "document_id")
})
@NamedNativeQuery(
    name = "DocumentChunkEntity.vectorSearch",
    query = """
        SELECT id, document_id, content, chunk_index, speaker,
               1 - (embedding <=> CAST(:embedding AS vector)) AS similarity
        FROM document_chunk
        WHERE embedding IS NOT NULL
        ORDER BY embedding <=> CAST(:embedding AS vector)
        LIMIT :topK
        """,
    resultSetMapping = "VectorSearchHitMapping"
)
@SqlResultSetMapping(
    name = "VectorSearchHitMapping",
    classes = @ConstructorResult(
        targetClass = VectorSearchHit.class,
        columns = {
            @ColumnResult(name = "id", type = Long.class),
            @ColumnResult(name = "document_id", type = Long.class),
            @ColumnResult(name = "content", type = String.class),
            @ColumnResult(name = "chunk_index", type = Integer.class),
            @ColumnResult(name = "speaker", type = String.class),
            @ColumnResult(name = "similarity", type = Double.class)
        }
    )
)
public class DocumentChunkEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "document_id", nullable = false)
    @JsonIgnore
    private DocumentEntity document;

    @Column(columnDefinition = "TEXT", nullable = false)
    private String content;

    @Column(columnDefinition = "VECTOR(1024)")
    private float[] embedding;

    @Column(name = "chunk_index")
    private Integer chunkIndex;

    @Column(length = 100)
    private String speaker;

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