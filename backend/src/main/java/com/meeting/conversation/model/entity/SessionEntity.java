package com.meeting.conversation.model.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Getter
@Setter
@Entity
@Table(name = "agent_sessions")
public class SessionEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "session_id", nullable = false, unique = true, length = 255)
    private String sessionId;

    @Column(length = 500)
    private String title;

    @Column(length = 50)
    private String status;

    @Column(name = "context_summary", columnDefinition = "TEXT")
    private String contextSummary;

    @Column(name = "state_json", columnDefinition = "TEXT")
    private String stateJson;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @OneToMany(mappedBy = "session", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<DialogueMessageEntity> messages = new ArrayList<>();

    @OneToMany(mappedBy = "session", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<RewriteResultEntity> rewriteResults = new ArrayList<>();

    @PrePersist
    protected void onCreate() {
        if (title == null) title = "新对话";
        if (status == null) status = "active";
        if (createdAt == null) createdAt = LocalDateTime.now();
        if (updatedAt == null) updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }

    public void addMessage(DialogueMessageEntity message) {
        messages.add(message);
        message.setSession(this);
    }
}
