package com.meeting.controller;

import com.meeting.common.DocumentTextExtractor;
import com.meeting.entity.MeetingMinutes;
import com.meeting.repository.MeetingMinutesRepository;
import com.meeting.repository.MeetingVectorRepository;
import com.meeting.service.FileProcessingService;
import com.meeting.service.TranscriptionService;
import com.meeting.service.VectorizationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import com.meeting.service.RewriteFeedbackService;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api")
public class MeetingController {

    private static final Logger log = LoggerFactory.getLogger(MeetingController.class);

    @Value("${file.upload-dir:/app/data/uploads}")
    private String uploadDir;

    private final FileProcessingService fileProcessingService;
    private final TranscriptionService transcriptionService;
    private final MeetingMinutesRepository meetingRepository;
    private final MeetingVectorRepository vectorRepository;
    private final VectorizationService vectorizationService;
    private final RewriteFeedbackService rewriteFeedbackService;
    private final JdbcTemplate jdbcTemplate;

    public MeetingController(FileProcessingService fileProcessingService,
                             TranscriptionService transcriptionService,
                             MeetingMinutesRepository meetingRepository,
                             MeetingVectorRepository vectorRepository,
                             VectorizationService vectorizationService,
                             RewriteFeedbackService rewriteFeedbackService,
                             JdbcTemplate jdbcTemplate) {
        this.fileProcessingService = fileProcessingService;
        this.transcriptionService = transcriptionService;
        this.meetingRepository = meetingRepository;
        this.vectorRepository = vectorRepository;
        this.vectorizationService = vectorizationService;
        this.rewriteFeedbackService = rewriteFeedbackService;
        this.jdbcTemplate = jdbcTemplate;
    }

    @PostMapping("/upload")
    public ResponseEntity<?> uploadFile(
            @RequestParam MultipartFile file,
            @RequestParam Long dialogueId) {
        try {
            MeetingMinutes meeting = fileProcessingService.uploadFile(file, dialogueId);

            String ext = FileProcessingService.getExtension(file.getOriginalFilename());
            if (FileProcessingService.isTranscribable(ext)) {
                transcriptionService.startTranscription(meeting.getId());
            }

            java.util.Map<String, Object> uploadResult = new java.util.HashMap<>();
            uploadResult.put("meetingId", meeting.getId());
            uploadResult.put("status", meeting.getStatus());
            uploadResult.put("dialogueId", meeting.getDialogueId());
            return ResponseEntity.ok(uploadResult);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping("/meeting/{id}")
    public ResponseEntity<?> getMeeting(@PathVariable Long id) {
        return meetingRepository.findById(id)
                .map(meeting -> {
                    Map<String, Object> result = new java.util.HashMap<>();
                    result.put("id", meeting.getId());
                    result.put("title", meeting.getTitle());
                    result.put("transcription", meeting.getTranscription());
                    result.put("duration", meeting.getDuration());
                    result.put("fileSize", meeting.getFileSize());
                    result.put("status", meeting.getStatus());
                    result.put("createdAt", meeting.getCreatedAt());
                    result.put("knowledgeBase", meeting.getKnowledgeBase());
                    result.put("dialogueId", meeting.getDialogueId());
                    result.put("mdFilePath", meeting.getMdFilePath());
                    return ResponseEntity.ok(result);
                })
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

    @GetMapping("/dialogue/{dialogueId}/meetings")
    public ResponseEntity<?> listDialogueMeetings(@PathVariable Long dialogueId) {
        List<Map<String, Object>> files = meetingRepository.findByDialogueId(dialogueId)
                .stream().map(m -> {
                    Map<String, Object> item = new java.util.HashMap<>();
                    item.put("id", m.getId());
                    item.put("title", m.getTitle());
                    item.put("fileSize", m.getFileSize());
                    item.put("status", m.getStatus());
                    item.put("createdAt", m.getCreatedAt());
                    item.put("knowledgeBase", m.getKnowledgeBase());
                    item.put("dialogueId", m.getDialogueId());
                    item.put("ext", FileProcessingService.getExtension(m.getTitle()));
                    item.put("hasMd", m.getMdFilePath() != null);
                    item.put("mdFilePath", m.getMdFilePath());
                    return item;
                }).collect(Collectors.toList());
        return ResponseEntity.ok(files);
    }

    @DeleteMapping("/meeting/{id}")
    public ResponseEntity<?> deleteMeeting(@PathVariable Long id) {
        return meetingRepository.findById(id).map(meeting -> {
            // Delete vectors via JdbcTemplate (Hibernate cannot handle VECTOR columns)
            jdbcTemplate.update("DELETE FROM meeting_vectors WHERE meeting_id = ?", id);
            // Clean up files
            fileProcessingService.cleanupFile(id);
            // Delete record
            meetingRepository.delete(meeting);
            return ResponseEntity.ok(Map.of("success", true));
        }).orElse(ResponseEntity.notFound().build());
    }

    @PostMapping("/meetings/knowledge-base/upload")
    public ResponseEntity<?> uploadToKnowledgeBase(@RequestParam("file") MultipartFile file) {
        try {
            String filename = file.getOriginalFilename();
            if (filename == null || filename.isBlank()) {
                return ResponseEntity.badRequest().body(Map.of("error", "文件名为空"));
            }

            String ext = FileProcessingService.getExtension(filename);
            if (!FileProcessingService.isDocument(ext)) {
                return ResponseEntity.badRequest().body(Map.of("error", "仅支持文档格式 (txt, md, pdf, doc, docx 等)"));
            }

            // Save file
            String dateStr = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd"));
            Path uploadPath = Paths.get(uploadDir, "knowledge-base", dateStr);
            Files.createDirectories(uploadPath);

            String savedFilename = UUID.randomUUID() + "_" + filename;
            Path filePath = uploadPath.resolve(savedFilename);
            Files.copy(file.getInputStream(), filePath, StandardCopyOption.REPLACE_EXISTING);

            // Read file content as transcription
            String content = Files.readString(filePath, StandardCharsets.UTF_8);

            // Create MeetingMinutes
            MeetingMinutes meeting = new MeetingMinutes();
            meeting.setTitle(filename);
            meeting.setFilePath(filePath.toString());
            meeting.setFileSize(file.getSize());
            meeting.setStatus("completed");
            meeting.setKnowledgeBase(true);
            meeting.setTranscription(content);
            meeting = meetingRepository.save(meeting);

            // Vectorize
            try {
                vectorizationService.vectorizeMeeting(meeting.getId());
            } catch (Exception e) {
                log.warn("Knowledge base upload vectorization failed for {}: {}", meeting.getId(), e.getMessage());
            }

            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "meetingId", meeting.getId(),
                    "title", meeting.getTitle()
            ));
        } catch (Exception e) {
            log.error("Knowledge base upload failed", e);
            return ResponseEntity.internalServerError().body(Map.of("error", "上传失败: " + e.getMessage()));
        }
    }

    @GetMapping("/meeting/{id}/file")
    public ResponseEntity<?> getMeetingFile(@PathVariable Long id) {
        return meetingRepository.findById(id).map(meeting -> {
            Path filePath = Path.of(meeting.getFilePath());
            if (!filePath.toFile().exists()) {
                return ResponseEntity.notFound().build();
            }
            Resource resource = new FileSystemResource(filePath);
            String encodedFilename = filePath.getFileName().toString()
                    .replaceFirst("^[0-9a-f-]+_", ""); // remove uuid prefix
            return ResponseEntity.ok()
                    .contentType(MediaType.APPLICATION_OCTET_STREAM)
                    .header(HttpHeaders.CONTENT_DISPOSITION,
                            "inline; filename*=UTF-8''" + encodedFilename)
                    .body(resource);
        }).orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/meeting/{id}/text-content")
    public ResponseEntity<?> getMeetingTextContent(@PathVariable Long id) {
        return meetingRepository.findById(id).map(meeting -> {
            Path filePath = Path.of(meeting.getFilePath());
            if (!filePath.toFile().exists()) {
                return ResponseEntity.notFound().build();
            }
            String ext = FileProcessingService.getExtension(meeting.getTitle()).toLowerCase();
            try {
                String content;
                Set<String> textFormats = Set.of(".txt", ".md", ".csv", ".json", ".xml", ".html", ".yaml", ".yml", ".properties", ".log");
                Set<String> docFormats = Set.of(".pdf", ".doc", ".docx");
                if (textFormats.contains(ext)) {
                    content = Files.readString(filePath, StandardCharsets.UTF_8);
                } else if (docFormats.contains(ext)) {
                    content = DocumentTextExtractor.extractText(filePath, ext);
                } else {
                    return ResponseEntity.badRequest().body(Map.of("error", "不支持的文件格式"));
                }
                if (content == null || content.isBlank()) {
                    return ResponseEntity.ok(Map.of("content", "", "warning", "无法提取文本内容"));
                }
                return ResponseEntity.ok(Map.of("content", content));
            } catch (IOException e) {
                log.error("Failed to read file {}: {}", id, e.getMessage());
                return ResponseEntity.internalServerError().body(Map.of("error", "读取文件失败: " + e.getMessage()));
            }
        }).orElse(ResponseEntity.notFound().build());
    }

    @PostMapping("/meeting/{id}/vectorize")
    public ResponseEntity<?> vectorizeMeeting(@PathVariable Long id) {
        try {
            vectorizationService.vectorizeMeeting(id);
            return ResponseEntity.ok(Map.of("success", true, "message", "向量化完成"));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (IllegalStateException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of("error", "向量化失败: " + e.getMessage()));
        }
    }

    @PostMapping("/meeting/{id}/knowledge-base")
    public ResponseEntity<?> toggleKnowledgeBase(@PathVariable Long id) {
        return meetingRepository.findById(id).map(meeting -> {
            boolean newStatus = !Boolean.TRUE.equals(meeting.getKnowledgeBase());
            meeting.setKnowledgeBase(newStatus);
            meetingRepository.save(meeting);

            // Auto-vectorize when enabling knowledge base
            if (newStatus) {
                try {
                    vectorizationService.vectorizeMeeting(id);
                } catch (Exception e) {
                    return ResponseEntity.ok(Map.of(
                            "success", true,
                            "knowledgeBase", true,
                            "warning", "标记成功但向量化失败: " + e.getMessage()
                    ));
                }
            }

            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "knowledgeBase", newStatus
            ));
        }).orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/meetings/knowledge-base")
    public ResponseEntity<?> listKnowledgeBase() {
        var meetings = meetingRepository.findByKnowledgeBaseTrue();
        return ResponseEntity.ok(meetings);
    }

    // ---- Style exemplar endpoints ----

    @PostMapping("/meeting/{id}/style-exemplar")
    public ResponseEntity<?> setStyleExemplar(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        return meetingRepository.findById(id).map(meeting -> {
            boolean exemplar = body.containsKey("styleExemplar") && Boolean.TRUE.equals(body.get("styleExemplar"));
            meeting.setStyleExemplar(exemplar);
            if (body.containsKey("styleTags")) {
                meeting.setStyleTags((String) body.get("styleTags"));
            }
            meetingRepository.save(meeting);
            return ResponseEntity.ok(Map.of("success", true, "styleExemplar", exemplar));
        }).orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/meetings/style-exemplars")
    public ResponseEntity<?> listStyleExemplars() {
        var meetings = meetingRepository.findByKnowledgeBaseTrue().stream()
                .filter(m -> Boolean.TRUE.equals(m.getStyleExemplar()))
                .map(m -> Map.of(
                        "id", m.getId(),
                        "title", m.getTitle(),
                        "styleTags", m.getStyleTags() != null ? m.getStyleTags() : ""
                )).collect(Collectors.toList());
        return ResponseEntity.ok(meetings);
    }

    // ---- Rewrite feedback endpoint ----

    @PostMapping("/rewrite-feedback")
    public ResponseEntity<?> submitRewriteFeedback(@RequestBody Map<String, Object> body) {
        try {
            Long rewriteResultId = ((Number) body.get("rewriteResultId")).longValue();
            Integer paragraphIndex = ((Number) body.get("paragraphIndex")).intValue();
            String action = (String) body.get("action");
            rewriteFeedbackService.submitFeedback(rewriteResultId, paragraphIndex, action);
            return ResponseEntity.ok(Map.of("success", true));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }
}
