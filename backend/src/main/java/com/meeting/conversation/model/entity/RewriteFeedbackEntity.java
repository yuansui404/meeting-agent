package com.meeting.conversation.model.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import java.time.LocalDateTime;

@Getter
@Setter
@Entity
@Table(name = "rewrite_feedback")
public class RewriteFeedbackEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "rewrite_result_id", nullable = false)
    @JsonIgnore
    private RewriteResultEntity rewriteResult;

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
