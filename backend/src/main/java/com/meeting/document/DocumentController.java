package com.meeting.document;

import com.meeting.common.ApiResponse;
import com.meeting.document.model.entity.DocumentEntity;
import com.meeting.document.service.DocumentUploadService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/document")
@RequiredArgsConstructor
public class DocumentController {

    private final DocumentUploadService documentService;

    @PostMapping("/upload")
    public ApiResponse<DocumentEntity> upload(@RequestParam("file") MultipartFile file) {
        if (file.getSize() > 10L * 1024 * 1024) {
            throw com.meeting.common.BusinessException.badRequest("文件大小超过 10MB 限制");
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
}
