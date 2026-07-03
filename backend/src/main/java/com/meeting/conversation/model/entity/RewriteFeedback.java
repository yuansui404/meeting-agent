package com.meeting.conversation.model.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import java.time.LocalDateTime;

@Getter
@Setter
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
}
