package com.meeting.controller;

import com.meeting.entity.MeetingMinutes;
import com.meeting.repository.MeetingMinutesRepository;
import com.meeting.service.FileProcessingService;
import com.meeting.service.TranscriptionService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.Map;

@RestController
@RequestMapping("/api")
public class MeetingController {

    private final FileProcessingService fileProcessingService;
    private final TranscriptionService transcriptionService;
    private final MeetingMinutesRepository meetingRepository;

    public MeetingController(FileProcessingService fileProcessingService,
                             TranscriptionService transcriptionService,
                             MeetingMinutesRepository meetingRepository) {
        this.fileProcessingService = fileProcessingService;
        this.transcriptionService = transcriptionService;
        this.meetingRepository = meetingRepository;
    }

    @PostMapping("/upload")
    public ResponseEntity<?> uploadFile(@RequestParam MultipartFile file) {
        try {
            MeetingMinutes meeting = fileProcessingService.uploadFile(file);
            transcriptionService.startTranscription(meeting.getId());

            return ResponseEntity.ok(Map.of(
                    "meetingId", meeting.getId(),
                    "status", "processing"
            ));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping("/meeting/{id}")
    public ResponseEntity<?> getMeeting(@PathVariable Long id) {
        return meetingRepository.findById(id)
                .map(meeting -> ResponseEntity.ok(Map.of(
                        "id", meeting.getId(),
                        "title", meeting.getTitle(),
                        "transcription", meeting.getTranscription(),
                        "duration", meeting.getDuration(),
                        "fileSize", meeting.getFileSize(),
                        "status", meeting.getStatus(),
                        "createdAt", meeting.getCreatedAt()
                )))
                .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/meetings")
    public ResponseEntity<?> listMeetings() {
        var meetings = meetingRepository.findAll();
        return ResponseEntity.ok(meetings);
    }

    @GetMapping("/health")
    public ResponseEntity<?> health() {
        return ResponseEntity.ok(Map.of(
                "status", "OK",
                "service", "meeting-agent"
        ));
    }
}
