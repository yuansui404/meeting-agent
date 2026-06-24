package com.meeting.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

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

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getDialogueId() { return dialogueId; }
    public void setDialogueId(Long dialogueId) { this.dialogueId = dialogueId; }
    public String getSourceFileIds() { return sourceFileIds; }
    public void setSourceFileIds(String sourceFileIds) { this.sourceFileIds = sourceFileIds; }
    public String getReferenceIds() { return referenceIds; }
    public void setReferenceIds(String referenceIds) { this.referenceIds = referenceIds; }
    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }
    public String getDocxPath() { return docxPath; }
    public void setDocxPath(String docxPath) { this.docxPath = docxPath; }
    public Integer getVersion() { return version; }
    public void setVersion(Integer version) { this.version = version; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
