package com.meeting.meeting.model.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import java.time.LocalDateTime;

@Getter
@Setter
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
}
