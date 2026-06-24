package com.meeting.document;

import com.meeting.common.ApiResponse;
import com.meeting.document.model.entity.DocumentEntity;
import com.meeting.document.repository.DocumentRepository;
import com.meeting.document.service.ChunkService;
import com.meeting.document.service.DocumentParserService;
import com.meeting.document.service.DocumentUploadService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

@RestController
@RequestMapping("/api/document")
@RequiredArgsConstructor
public class DocumentController {

    private final DocumentUploadService uploadService;
    private final DocumentRepository documentRepository;
    private final ChunkService chunkService;
    private final DocumentParserService documentParserService;

    @PostMapping("/upload")
    public ApiResponse<DocumentEntity> upload(@RequestParam("file") MultipartFile file) {
        DocumentEntity doc = uploadService.upload(file);
        String text = documentParserService.parse(doc.getFilePath());
        chunkService.processDocument(doc.getId(), text);
        DocumentEntity updated = documentRepository.findById(doc.getId()).orElse(null);
        return ApiResponse.ok(updated);
    }

    @GetMapping
    public ApiResponse<List<DocumentEntity>> list() {
        List<DocumentEntity> docs = documentRepository.findAllByOrderByCreatedAtDesc();
        return ApiResponse.ok(docs);
    }

    @GetMapping("/{id}")
    public ApiResponse<DocumentEntity> get(@PathVariable Long id) {
        DocumentEntity doc = documentRepository.findById(id).orElse(null);
        if (doc == null) {
            return ApiResponse.error("文档不存在");
        }
        return ApiResponse.ok(doc);
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable Long id) {
        DocumentEntity doc = documentRepository.findById(id).orElse(null);
        if (doc == null) {
            return ApiResponse.error("文档不存在");
        }
        documentRepository.deleteById(id);
        return ApiResponse.ok(null, "删除成功");
    }
}
