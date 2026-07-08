package com.meeting.document.service;

import com.meeting.common.BusinessException;
import com.meeting.config.FileProperties;
import com.meeting.document.model.entity.DocumentEntity;
import com.meeting.document.repository.DocumentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

@Slf4j
@Service
@RequiredArgsConstructor
public class DocumentUploadService {

    private final DocumentRepository documentRepository;
    private final DocumentParserService documentParserService;
    private final ChunkService chunkService;
    private final FileProperties fileProperties;

    private Path getUploadDir() {
        return Paths.get(fileProperties.uploadDir(), "rag-documents");
    }

    /**
     * 上传文件并触发异步处理。立即返回文档实体（status=UPLOADED）。
     */
    public DocumentEntity processUpload(MultipartFile file) {
        DocumentEntity doc = upload(file);
        processDocumentAsync(doc.getId());
        return doc;
    }

    /**
     * 异步执行：解析文本 → 保存 markdown → 分块向量化
     */
    @Async
    public void processDocumentAsync(Long documentId) {
        try {
            DocumentEntity doc = documentRepository.findById(documentId)
                    .orElseThrow(() -> BusinessException.notFound("文档不存在"));
            String text = documentParserService.parse(doc.getFilePath());

            // 保存清洗后的 markdown 到磁盘
            String mdPath = saveMarkdownFile(doc.getFilePath(), text);
            if (mdPath != null) {
                doc.setMdFilePath(mdPath);
            }

            doc.setTranscription(text);
            doc.setStatus("COMPLETED");
            documentRepository.save(doc);

            chunkService.processDocument(documentId, text);
        } catch (Exception e) {
            log.error("Async document processing failed for id={}", documentId, e);
            // Mark as failed
            documentRepository.findById(documentId).ifPresent(doc -> {
                doc.setStatus("FAILED");
                documentRepository.save(doc);
            });
        }
    }

    /**
     * 保存清洗后的 markdown 到磁盘。路径与原文件同目录，扩展名改为 .md。
     */
    private String saveMarkdownFile(String originalPath, String markdown) {
        try {
            Path original = Path.of(originalPath);
            String name = original.getFileName().toString();
            int dot = name.lastIndexOf('.');
            String baseName = dot > 0 ? name.substring(0, dot) : name;
            Path mdPath = original.getParent().resolve(baseName + ".cleaned.md");
            Files.writeString(mdPath, markdown);
            log.info("Saved cleaned markdown: {}", mdPath);
            return mdPath.toString();
        } catch (IOException e) {
            log.warn("Failed to save markdown file: {}", e.getMessage());
            return null;
        }
    }

    public Page<DocumentEntity> listAll(Pageable pageable) {
        return documentRepository.findAllByOrderByCreatedAtDesc(pageable);
    }

    public DocumentEntity getById(Long id) {
        return documentRepository.findById(id)
                .orElseThrow(() -> BusinessException.notFound("文档不存在"));
    }

    public void delete(Long id) {
        DocumentEntity doc = documentRepository.findById(id)
                .orElseThrow(() -> BusinessException.notFound("文档不存在"));
        documentRepository.deleteById(id);
        // 删除物理文件
        if (doc.getFilePath() != null) {
            try {
                Files.deleteIfExists(Path.of(doc.getFilePath()));
            } catch (IOException e) {
                log.warn("Failed to delete physical file: {}", doc.getFilePath(), e);
            }
        }
    }

    public DocumentEntity upload(MultipartFile file) {
        String originalName = file.getOriginalFilename();
        if (originalName == null || originalName.isBlank()) {
            throw new BusinessException("文件名不能为空");
        }

        String ext = getExtension(originalName).toLowerCase();
        if (!List.of("pdf", "doc", "docx", "txt", "md").contains(ext)) {
            throw new BusinessException("不支持的文件格式: " + ext);
        }

        try {
            Path dir = getUploadDir();
            Files.createDirectories(dir);
            String storedName = UUID.randomUUID() + "." + ext;
            Path targetPath = dir.resolve(storedName);
            file.transferTo(targetPath.toFile());

            DocumentEntity entity = new DocumentEntity();
            entity.setTitle(originalName);
            entity.setFileType(ext);
            entity.setFilePath(targetPath.toString());
            entity.setFileSize(file.getSize());
            entity.setMeetingDate(extractMeetingDate(originalName));
            entity.setStatus("UPLOADED");
            documentRepository.save(entity);

            log.info("Document uploaded: id={}, name={}, size={}", entity.getId(), originalName, file.getSize());
            return entity;
        } catch (IOException e) {
            log.error("File upload failed", e);
            throw BusinessException.processingFailed("文件上传失败", e);
        }
    }

    private String getExtension(String filename) {
        int idx = filename.lastIndexOf('.');
        return idx == -1 ? "" : filename.substring(idx + 1);
    }

    LocalDate extractMeetingDate(String filename) {
        var matcher = Pattern.compile("(\\d{4})-(\\d{2})-(\\d{2})").matcher(filename);
        if (matcher.find()) {
            return LocalDate.of(
                    Integer.parseInt(matcher.group(1)),
                    Integer.parseInt(matcher.group(2)),
                    Integer.parseInt(matcher.group(3))
            );
        }
        var qMatcher = Pattern.compile("(20\\d{2})-Q([1-4])").matcher(filename);
        if (qMatcher.find()) {
            int year = Integer.parseInt(qMatcher.group(1));
            int quarter = Integer.parseInt(qMatcher.group(2));
            return LocalDate.of(year, quarter * 3 - 2, 1);
        }
        return null;
    }
}
