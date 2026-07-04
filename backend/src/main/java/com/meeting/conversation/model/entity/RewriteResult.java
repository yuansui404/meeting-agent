package com.meeting.conversation.model.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import java.time.LocalDateTime;

@Getter
@Setter
@Entity
@Table(name = "rewrite_result")
public class RewriteResult {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "dialogue_id", nullable = false)
    private Long dialogueId;

    @Column(name = "source_file_ids", nullable = false, length = 500)
    private String sourceFileIds;

    @Column(name = "reference_ids", length = 500)
    private String referenceIds;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String content;

    @Column(name = "docx_path", length = 500)
    private String docxPath;

    @Column(nullable = false)
    private Integer version = 1;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }
}
