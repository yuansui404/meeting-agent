package com.meeting.document;

import com.meeting.common.ApiResponse;
import com.meeting.common.BusinessException;
import com.meeting.config.FileProperties;
import com.meeting.controller.dto.response.TextContentVO;
import com.meeting.document.model.entity.DocumentEntity;
import com.meeting.document.repository.DocumentRepository;
import com.meeting.document.service.DocumentUploadService;
import com.meeting.transcription.service.FileProcessingService;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.nio.file.Path;
import java.util.List;

@RestController
@RequestMapping("/api/document")
@RequiredArgsConstructor
public class DocumentController {

    private final DocumentUploadService documentService;
    private final DocumentRepository documentRepository;
    private final FileProperties fileProps;

    @PostMapping("/upload")
    public ApiResponse<DocumentEntity> upload(@RequestParam("file") MultipartFile file) {
        if (file.getSize() > 10L * 1024 * 1024) {
            throw BusinessException.badRequest("文件大小超过 10MB 限制");
        }
        DocumentEntity doc = documentService.processUpload(file);
        return ApiResponse.ok(doc);
    }

    @GetMapping
    public ApiResponse<Page<DocumentEntity>> list(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        Pageable pageable = PageRequest.of(page, size);
        return ApiResponse.ok(documentService.listAll(pageable));
    }

    @GetMapping("/{id}")
    public ApiResponse<DocumentEntity> get(@PathVariable Long id) {
        return ApiResponse.ok(documentService.getById(id));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable Long id) {
        documentService.delete(id);
        return ApiResponse.ok(null, "删除成功");
    }

    @GetMapping("/{id}/text-content")
    public ApiResponse<TextContentVO> getTextContent(@PathVariable Long id) {
        DocumentEntity doc = documentService.getById(id);
        Path filePath = Path.of(doc.getFilePath());
        if (!FileProcessingService.isPathSafe(filePath, fileProps.uploadDir()) || !filePath.toFile().exists()) {
            throw BusinessException.notFound("文件不存在");
        }
        String ext = FileProcessingService.getExtension(doc.getTitle()).toLowerCase();
        String content = FileProcessingService.readFileContent(filePath, ext);
        return ApiResponse.ok(new TextContentVO(content != null ? content : ""));
    }

    @GetMapping("/{id}/file")
    public ResponseEntity<?> getFile(@PathVariable Long id) {
        DocumentEntity doc = documentService.getById(id);
        Path filePath = Path.of(doc.getFilePath());
        if (!FileProcessingService.isPathSafe(filePath, fileProps.uploadDir()) || !filePath.toFile().exists()) {
            throw BusinessException.notFound("文件不存在");
        }
        Resource resource = new FileSystemResource(filePath);
        String encodedFilename = filePath.getFileName().toString().replaceFirst("^[0-9a-f-]+_", "");
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename*=UTF-8''" + encodedFilename)
                .body(resource);
    }

    @GetMapping("/search")
    public ApiResponse<List<DocumentEntity>> searchByTitle(@RequestParam String keyword,
                                                           @RequestParam(defaultValue = "20") int limit) {
        return ApiResponse.ok(documentRepository.searchByTitleKeyword(keyword, limit));
    }
}
