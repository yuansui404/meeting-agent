package com.meeting.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "rewrite_feedback")
public class RewriteFeedback {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "rewrite_result_id", nullable = false)
    private Long rewriteResultId;

    @Column(name = "paragraph_index", nullable = false)
    private Integer paragraphIndex;

    @Column(nullable = false, length = 10)
    private String action;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getRewriteResultId() { return rewriteResultId; }
    public void setRewriteResultId(Long rewriteResultId) { this.rewriteResultId = rewriteResultId; }
    public Integer getParagraphIndex() { return paragraphIndex; }
    public void setParagraphIndex(Integer paragraphIndex) { this.paragraphIndex = paragraphIndex; }
    public String getAction() { return action; }
    public void setAction(String action) { this.action = action; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
