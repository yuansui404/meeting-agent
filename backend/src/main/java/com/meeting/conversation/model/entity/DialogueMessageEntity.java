package com.meeting.conversation.model.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.meeting.conversation.converter.JsonListConverter;
import com.meeting.conversation.converter.JsonMapConverter;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

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
    @Convert(converter = JsonListConverter.class)
    private List<Map<String, Object>> files;

    @Column(name = "message_type", length = 32)
    private String messageType;

    @Column(columnDefinition = "TEXT")
    @Convert(converter = JsonMapConverter.class)
    private Map<String, Object> metadata;

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
