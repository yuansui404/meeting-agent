package com.meeting.conversation.model.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Getter
@Setter
@Entity
@Table(name = "rewrite_result")
public class RewriteResultEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "dialogue_id", nullable = false)
    @JsonIgnore
    private SessionEntity session;

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

    @OneToMany(mappedBy = "rewriteResult", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<RewriteFeedbackEntity> feedbacks = new ArrayList<>();

    public Long getDialogueId() {
        return session != null ? session.getId() : null;
    }

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }
}
