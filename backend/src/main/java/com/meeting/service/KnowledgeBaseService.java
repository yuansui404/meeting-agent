package com.meeting.service;

import com.meeting.common.BusinessException;
import com.meeting.config.FileProperties;
import com.meeting.document.service.DocumentTextExtractor;
import com.meeting.meeting.model.entity.MeetingMinutes;
import com.meeting.meeting.repository.MeetingMinutesRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class KnowledgeBaseService {

    private final FileProperties fileProps;
    private final MeetingMinutesRepository meetingRepository;
    private final VectorizationService vectorizationService;
    private final MeetingDateExtractor meetingDateExtractor;

    public MeetingMinutes uploadDocument(MultipartFile file) throws Exception {
        String filename = file.getOriginalFilename();
        if (filename == null || filename.isBlank()) {
            throw new BusinessException("文件名不能为空");
        }

        String ext = FileProcessingService.getExtension(filename);
        if (!FileProcessingService.isDocument(ext)) {
            throw new BusinessException("仅支持文档格式 (txt, md, pdf, doc, docx 等)");
        }

        // Save file
        String dateStr = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd"));
        Path uploadPath = Paths.get(fileProps.uploadDir(), "knowledge-base", dateStr);
        Files.createDirectories(uploadPath);

        String savedFilename = UUID.randomUUID() + "_" + filename;
        Path filePath = uploadPath.resolve(savedFilename);
        Files.copy(file.getInputStream(), filePath, StandardCopyOption.REPLACE_EXISTING);

        // Read content
        String content = FileProcessingService.readFileContent(filePath, ext);
        if (content == null || content.isBlank()) {
            throw new BusinessException("无法提取文件内容，请确认文件格式正确");
        }

        // Create entity
        MeetingMinutes meeting = new MeetingMinutes();
        meeting.setTitle(filename);
        meeting.setFilePath(filePath.toString());
        meeting.setFileSize(file.getSize());
        meeting.setStatus("completed");
        meeting.setTranscription(content);
        meeting.setMeetingDate(LocalDateTime.now());
        meeting = meetingRepository.save(meeting);

        // Extract meeting date
        try {
            var md = meetingDateExtractor.extract(content);
            if (md != null) {
                meeting.setMeetingDate(md);
                meetingRepository.save(meeting);
            }
        } catch (Exception e) {
            log.warn("Meeting date extraction failed for KB upload {}: {}", meeting.getId(), e.getMessage());
        }

        // Vectorize
        try {
            vectorizationService.vectorizeMeeting(meeting.getId());
        } catch (Exception e) {
            log.warn("Knowledge base upload vectorization failed for {}: {}", meeting.getId(), e.getMessage());
        }

        return meeting;
    }

    public MeetingMinutes setStyleExemplar(Long id, boolean exemplar, String styleTags) {
        MeetingMinutes meeting = meetingRepository.findById(id)
                .orElseThrow(() -> BusinessException.notFound("会议不存在: " + id));
        meeting.setStyleExemplar(exemplar);
        if (styleTags != null) {
            meeting.setStyleTags(styleTags);
        }
        return meetingRepository.save(meeting);
    }

    public List<Map<String, Object>> listStyleExemplars() {
        return meetingRepository.findAll().stream()
                .filter(m -> Boolean.TRUE.equals(m.getStyleExemplar()))
                .map(m -> {
                    Map<String, Object> map = new java.util.HashMap<>();
                    map.put("id", m.getId());
                    map.put("title", m.getTitle());
                    map.put("styleTags", m.getStyleTags() != null ? m.getStyleTags() : "");
                    return map;
                }).collect(Collectors.toList());
    }

    public List<Map<String, Object>> backfillParticipants() {
        List<MeetingMinutes> kbMeetings = meetingRepository.findByStatus("completed");
        List<Map<String, Object>> results = new ArrayList<>();
        for (MeetingMinutes m : kbMeetings) {
            try {
                vectorizationService.backfillParticipants(m.getId());
                Map<String, Object> row = new java.util.HashMap<>();
                row.put("id", m.getId());
                row.put("title", m.getTitle());
                row.put("status", "ok");
                results.add(row);
            } catch (Exception e) {
                log.warn("Backfill failed for meeting {}: {}", m.getId(), e.getMessage());
                Map<String, Object> row = new java.util.HashMap<>();
                row.put("id", m.getId());
                row.put("title", m.getTitle());
                row.put("status", "error");
                row.put("message", e.getMessage());
                results.add(row);
            }
        }
        return results;
    }
}
