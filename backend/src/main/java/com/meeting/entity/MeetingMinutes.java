package com.meeting.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "meeting_minutes")
public class MeetingMinutes {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String title;

    @Column(name = "file_path", nullable = false)
    private String filePath;

    @Column(name = "file_size")
    private Long fileSize;

    private Integer duration;

    @Column(columnDefinition = "TEXT")
    private String transcription;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @Column(length = 20)
    private String status;

    @Column(name = "dialogue_id")
    private Long dialogueId;

    @Column(name = "md_file_path")
    private String mdFilePath;

    @Column(name = "meeting_date")
    private LocalDateTime meetingDate;

    @Column(name = "style_exemplar")
    private Boolean styleExemplar = false;

    @Column(name = "style_tags", length = 500)
    private String styleTags;

    @Column(columnDefinition = "TEXT")
    private String participants;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
        if (status == null) status = "processing";
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getFilePath() { return filePath; }
    public void setFilePath(String filePath) { this.filePath = filePath; }
    public Long getFileSize() { return fileSize; }
    public void setFileSize(Long fileSize) { this.fileSize = fileSize; }
    public Integer getDuration() { return duration; }
    public void setDuration(Integer duration) { this.duration = duration; }
    public String getTranscription() { return transcription; }
    public void setTranscription(String transcription) { this.transcription = transcription; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public Long getDialogueId() { return dialogueId; }
    public void setDialogueId(Long dialogueId) { this.dialogueId = dialogueId; }
    public String getMdFilePath() { return mdFilePath; }
    public void setMdFilePath(String mdFilePath) { this.mdFilePath = mdFilePath; }
    public LocalDateTime getMeetingDate() { return meetingDate; }
    public void setMeetingDate(LocalDateTime meetingDate) { this.meetingDate = meetingDate; }
    public Boolean getStyleExemplar() { return styleExemplar; }
    public void setStyleExemplar(Boolean styleExemplar) { this.styleExemplar = styleExemplar; }
    public String getStyleTags() { return styleTags; }
    public void setStyleTags(String styleTags) { this.styleTags = styleTags; }
    public String getParticipants() { return participants; }
    public void setParticipants(String participants) { this.participants = participants; }
}
