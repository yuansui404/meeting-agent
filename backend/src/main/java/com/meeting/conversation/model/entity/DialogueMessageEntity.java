package com.meeting.conversation.model.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import java.time.LocalDateTime;

@Getter
@Setter
@Entity
@Table(name = "dialogue_messages")
public class DialogueMessageEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "dialogue_id", nullable = false)
    @JsonIgnore
    private SessionEntity session;

    @Column(nullable = false, length = 16)
    private String role;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String content;

    @Column(columnDefinition = "TEXT")
    private String files;

    @Column(name = "message_type", length = 32)
    private String messageType;

    @Column(columnDefinition = "TEXT")
    private String metadata;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    public Long getDialogueId() {
        return session != null ? session.getId() : null;
    }

    @PrePersist
    protected void onCreate() {
        if (content == null) content = "";
        if (messageType == null) messageType = "text";
        if (createdAt == null) createdAt = LocalDateTime.now();
    }
}
