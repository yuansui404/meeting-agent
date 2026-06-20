package com.meeting.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "dialogue_messages")
public class DialogueMessage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "dialogue_id", nullable = false)
    private Long dialogueId;

    @Column(length = 20, nullable = false)
    private String role;

    @Column(columnDefinition = "TEXT", nullable = false)
    private String content;

    @Column(name = "message_type", length = 50)
    private String messageType;

    @Column(name = "meeting_context_id")
    private Long meetingContextId;

    private LocalDateTime timestamp;

    @PrePersist
    protected void onCreate() {
        if (timestamp == null) timestamp = LocalDateTime.now();
        if (messageType == null) messageType = "text";
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getDialogueId() { return dialogueId; }
    public void setDialogueId(Long dialogueId) { this.dialogueId = dialogueId; }
    public String getRole() { return role; }
    public void setRole(String role) { this.role = role; }
    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }
    public String getMessageType() { return messageType; }
    public void setMessageType(String messageType) { this.messageType = messageType; }
    public Long getMeetingContextId() { return meetingContextId; }
    public void setMeetingContextId(Long meetingContextId) { this.meetingContextId = meetingContextId; }
    public LocalDateTime getTimestamp() { return timestamp; }
    public void setTimestamp(LocalDateTime timestamp) { this.timestamp = timestamp; }
}
