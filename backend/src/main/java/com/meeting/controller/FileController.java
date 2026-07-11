package com.meeting.controller;

import com.meeting.common.ApiResponse;
import com.meeting.common.BusinessException;
import com.meeting.common.FileMetadata;
import com.meeting.config.FileProperties;
import com.meeting.controller.dto.response.FileUploadVO;
import com.meeting.controller.dto.response.TextContentVO;
import com.meeting.transcription.service.FileProcessingService;
import com.meeting.conversation.service.SessionService;
import com.meeting.transcription.service.TranscriptionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.nio.file.Path;

@Slf4j
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class FileController {

    private final FileProperties fileProps;
    private final FileProcessingService fileProcessingService;
    private final TranscriptionService transcriptionService;
    private final SessionService sessionService;

    private static final long MAX_FILE_SIZE = 10L * 1024 * 1024;       // 10MB per file
    private static final long MAX_TOTAL_SIZE = 100L * 1024 * 1024;     // 100MB total
    private static final int MAX_FILE_COUNT = 10;

    @PostMapping("/upload")
    public ApiResponse<FileUploadVO> uploadFile(
            @RequestParam MultipartFile file,
            @RequestParam Long dialogueId) throws Exception {
        validateUpload(file, dialogueId);

        String ext = FileProcessingService.getExtension(file.getOriginalFilename());
        FileMetadata fileMeta = fileProcessingService.saveDialogueFile(file, dialogueId);

        if (FileProcessingService.isTranscribable(ext)) {
            transcriptionService.startTranscription(fileMeta.filePath(), fileMeta.fileName(), dialogueId);
        }

        return ApiResponse.ok(new FileUploadVO(
                fileMeta.fileId(),
                fileMeta.fileName(),
                fileMeta.filePath(),
                fileMeta.fileSize(),
                dialogueId
        ));
    }

    @GetMapping("/dialogue/{dialogueId}/file/{fileId}")
    public ResponseEntity<?> getDialogueFile(@PathVariable Long dialogueId, @PathVariable String fileId) {
        String filePath = sessionService.findFilePathInState(dialogueId, fileId);
        if (filePath == null) {
            throw BusinessException.notFound("文件不存在");
        }
        Path path = Path.of(filePath);
        if (!FileProcessingService.isPathSafe(path, fileProps.uploadDir()) || !path.toFile().exists()) {
            throw BusinessException.notFound("文件不存在");
        }
        return buildFileResponse(path);
    }

    @GetMapping("/dialogue/{dialogueId}/file/{fileId}/text-content")
    public ApiResponse<TextContentVO> getDialogueFileTextContent(@PathVariable Long dialogueId, @PathVariable String fileId) {
        String filePath = sessionService.findFilePathInState(dialogueId, fileId);
        if (filePath == null) {
            throw BusinessException.notFound("文件不存在");
        }
        Path path = Path.of(filePath);
        if (!FileProcessingService.isPathSafe(path, fileProps.uploadDir()) || !path.toFile().exists()) {
            throw BusinessException.notFound("文件不存在");
        }
        String ext = FileProcessingService.getExtension(path.getFileName().toString()).toLowerCase();
        String content = FileProcessingService.readFileContentWithSidecar(path, ext);
        return ApiResponse.ok(new TextContentVO(content != null ? content : ""));
    }

    private void validateUpload(MultipartFile file, Long dialogueId) {
        if (file.getSize() > MAX_FILE_SIZE) {
            throw BusinessException.badRequest("文件大小超过 10MB 限制");
        }

        java.util.List<FileMetadata> existingFiles = sessionService.extractFilesFromState(dialogueId);
        if (existingFiles.size() >= MAX_FILE_COUNT) {
            throw BusinessException.badRequest("最多上传 " + MAX_FILE_COUNT + " 个文件");
        }

        long totalSize = existingFiles.stream()
                .mapToLong(f -> f.fileSize() != null ? f.fileSize() : 0)
                .sum();
        if (totalSize + file.getSize() > MAX_TOTAL_SIZE) {
            throw BusinessException.badRequest("文件总大小超过 100MB 限制");
        }
    }

    private ResponseEntity<Resource> buildFileResponse(Path filePath) {
        Resource resource = new FileSystemResource(filePath);
        String encodedFilename = filePath.getFileName().toString()
                .replaceFirst("^[0-9a-f-]+_", "");

        // 根据文件扩展名设置正确的 Content-Type
        MediaType mediaType = getMediaType(encodedFilename);

        return ResponseEntity.ok()
                .contentType(mediaType)
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "inline; filename*=UTF-8''" + encodedFilename)
                .body(resource);
    }

    private MediaType getMediaType(String filename) {
        String ext = filename.substring(filename.lastIndexOf('.') + 1).toLowerCase();
        return switch (ext) {
            case "pdf" -> MediaType.APPLICATION_PDF;
            case "png" -> MediaType.IMAGE_PNG;
            case "jpg", "jpeg" -> MediaType.IMAGE_JPEG;
            case "gif" -> MediaType.IMAGE_GIF;
            case "webp" -> MediaType.valueOf("image/webp");
            case "svg" -> MediaType.valueOf("image/svg+xml");
            case "bmp" -> MediaType.valueOf("image/bmp");
            case "mp4" -> MediaType.valueOf("video/mp4");
            case "webm" -> MediaType.valueOf("video/webm");
            case "mp3" -> MediaType.valueOf("audio/mpeg");
            case "wav" -> MediaType.valueOf("audio/wav");
            case "m4a" -> MediaType.valueOf("audio/mp4");
            case "html", "htm" -> MediaType.TEXT_HTML;
            case "txt", "md" -> MediaType.TEXT_PLAIN;
            case "json" -> MediaType.APPLICATION_JSON;
            default -> MediaType.APPLICATION_OCTET_STREAM;
        };
    }
}
