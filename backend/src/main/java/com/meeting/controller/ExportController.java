package com.meeting.controller;

import com.meeting.common.BusinessException;
import com.meeting.config.FileProperties;
import com.meeting.transcription.service.FileProcessingService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

@Slf4j
@RestController
@RequestMapping("/api/export")
@RequiredArgsConstructor
public class ExportController {

    private final FileProperties fileProperties;

    @GetMapping("/download/{fileId}")
    public ResponseEntity<?> download(@PathVariable String fileId) {
        Path exportDir = Path.of(fileProperties.uploadDir(), "exports");

        if (!Files.exists(exportDir)) {
            throw BusinessException.notFound("导出文件不存在");
        }

        // Find file matching {fileId}_*.docx
        Path filePath;
        try (Stream<Path> files = Files.list(exportDir)) {
            filePath = files.filter(p -> {
                        String name = p.getFileName().toString();
                        return name.startsWith(fileId + "_") && name.endsWith(".docx");
                    })
                    .findFirst()
                    .orElse(null);
        } catch (IOException e) {
            log.warn("Failed to list export directory: {}", exportDir, e);
            throw BusinessException.notFound("导出文件不存在");
        }

        if (filePath == null) {
            throw BusinessException.notFound("导出文件不存在");
        }

        if (!FileProcessingService.isPathSafe(filePath, fileProperties.uploadDir()) || !Files.exists(filePath)) {
            throw BusinessException.notFound("导出文件不存在");
        }

        Resource resource = new FileSystemResource(filePath);
        String downloadFilename = filePath.getFileName().toString()
                .replaceFirst("^[0-9a-f-]+_", "");

        return ResponseEntity.ok()
                .contentType(MediaType.valueOf("application/vnd.openxmlformats-officedocument.wordprocessingml.document"))
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename*=UTF-8''" + downloadFilename)
                .body(resource);
    }
}