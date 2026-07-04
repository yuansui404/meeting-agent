package com.meeting.controller;

import com.meeting.common.ApiResponse;
import com.meeting.common.BusinessException;
import com.meeting.config.FileProperties;
import com.meeting.controller.dto.response.FileUploadVO;
import com.meeting.controller.dto.response.TextContentVO;
import com.meeting.meeting.repository.MeetingMinutesRepository;
import com.meeting.service.FileProcessingService;
import com.meeting.service.SessionService;
import com.meeting.service.TranscriptionService;
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
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class FileController {

    private final FileProperties fileProps;
    private final FileProcessingService fileProcessingService;
    private final TranscriptionService transcriptionService;
    private final MeetingMinutesRepository meetingRepository;
    private final SessionService sessionService;

    @PostMapping("/upload")
    public ApiResponse<FileUploadVO> uploadFile(
            @RequestParam MultipartFile file,
            @RequestParam Long dialogueId) throws Exception {
        String ext = FileProcessingService.getExtension(file.getOriginalFilename());
        Map<String, Object> fileMeta = fileProcessingService.saveDialogueFile(file, dialogueId);

        if (FileProcessingService.isTranscribable(ext)) {
            transcriptionService.startTranscription(
                    (String) fileMeta.get("filePath"),
                    (String) fileMeta.get("fileName"),
                    dialogueId);
        }

        return ApiResponse.ok(new FileUploadVO(
                (String) fileMeta.get("fileId"),
                (String) fileMeta.get("fileName"),
                (String) fileMeta.get("filePath"),
                (Long) fileMeta.get("fileSize"),
                dialogueId
        ));
    }

    @GetMapping("/meeting/{id}/file")
    public ResponseEntity<?> getMeetingFile(@PathVariable Long id) {
        return meetingRepository.findById(id).map(meeting -> {
            Path filePath = Path.of(meeting.getFilePath());
            if (!FileProcessingService.isPathSafe(filePath, fileProps.uploadDir()) || !filePath.toFile().exists()) {
                throw BusinessException.notFound("文件不存在");
            }
            return buildFileResponse(filePath);
        }).orElseThrow(() -> BusinessException.notFound("会议不存在: " + id));
    }

    @GetMapping("/meeting/{id}/text-content")
    public ApiResponse<TextContentVO> getMeetingTextContent(@PathVariable Long id) {
        return meetingRepository.findById(id).map(meeting -> {
            Path filePath = Path.of(meeting.getFilePath());
            if (!FileProcessingService.isPathSafe(filePath, fileProps.uploadDir()) || !filePath.toFile().exists()) {
                throw BusinessException.notFound("文件不存在");
            }
            String ext = FileProcessingService.getExtension(meeting.getTitle()).toLowerCase();
            String content = FileProcessingService.readFileContent(filePath, ext);
            return ApiResponse.ok(new TextContentVO(content != null ? content : ""));
        }).orElseThrow(() -> BusinessException.notFound("会议不存在: " + id));
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

    private ResponseEntity<Resource> buildFileResponse(Path filePath) {
        Resource resource = new FileSystemResource(filePath);
        String encodedFilename = filePath.getFileName().toString()
                .replaceFirst("^[0-9a-f-]+_", "");
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "inline; filename*=UTF-8''" + encodedFilename)
                .body(resource);
    }
}
