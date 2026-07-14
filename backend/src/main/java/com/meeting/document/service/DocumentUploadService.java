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
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class DocumentUploadService {

    private final DocumentRepository documentRepository;
    private final DocumentParserService documentParserService;
    private final ChunkService chunkService;
    private final FileProperties fileProperties;
    private final MeetingMinutesPreprocessor meetingMinutesPreprocessor;

    private Path getUploadDir() {
        return Paths.get(fileProperties.uploadDir(), "rag-documents");
    }

    /**
     * 处理已存在的文件：创建 DocumentEntity + 解析 + 预处理 + 分块向量化。
     * 与 {@link #processUpload(MultipartFile)} 走相同逻辑，适用于文件已在磁盘上的场景。
     *
     * @return 文档实体（status=UPLOADED，异步处理完成后变为 COMPLETED/FAILED）
     */
    public DocumentEntity processFile(String filePath, String title) {
        Path path = Path.of(filePath);
        String ext = getExtension(title).toLowerCase();
        if (!List.of("pdf", "doc", "docx", "txt", "md").contains(ext)) {
            throw new BusinessException("不支持的文件格式: " + ext);
        }

        DocumentEntity entity = new DocumentEntity();
        entity.setTitle(title);
        entity.setFileType(ext);
        entity.setFilePath(filePath);
        try {
            entity.setFileSize(Files.size(path));
        } catch (IOException e) {
            throw BusinessException.processingFailed("文件读取失败", e);
        }
        entity.setMeetingDate(extractMeetingDate(title));
        entity.setStatus("UPLOADED");
        documentRepository.save(entity);

        log.info("Document registered from file: id={}, name={}, path={}", entity.getId(), title, filePath);
        processDocumentAsync(entity.getId());
        return entity;
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

            // 预处理：一次扫描同时提取元数据 + 剥离元数据章节
            var result = meetingMinutesPreprocessor.preprocess(text);
            var meta = result.metadata();

            // 提取的元数据写入 DocumentEntity
            if (meta.containsKey("meeting_date")) doc.setMeetingDate((LocalDate) meta.get("meeting_date"));
            if (meta.containsKey("participants")) doc.setParticipants((String) meta.get("participants"));

            // 保存清洗后的 markdown 到磁盘
            String mdPath = saveMarkdownFile(doc.getFilePath(), text);
            if (mdPath != null) {
                doc.setMdFilePath(mdPath);
            }

            doc.setStatus("COMPLETED");
            documentRepository.save(doc);

            chunkService.processDocument(documentId, result.cleanedText());
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

    @Transactional
    public void delete(Long id) {
        DocumentEntity doc = documentRepository.findById(id)
                .orElseThrow(() -> BusinessException.notFound("文档不存在"));
        // 先用原生 SQL 删除 chunks，避免 Hibernate 加载 VECTOR 列
        documentRepository.deleteChunksByDocumentId(id);
        documentRepository.deleteById(id);
        // 删除物理文件
        if (doc.getFilePath() != null) {
            try {
                Files.deleteIfExists(Path.of(doc.getFilePath()));
            } catch (IOException e) {
                log.warn("Failed to delete physical file: {}", doc.getFilePath(), e);
            }
        }
        if (doc.getMdFilePath() != null) {
            try {
                Files.deleteIfExists(Path.of(doc.getMdFilePath()));
            } catch (IOException e) {
                log.warn("Failed to delete markdown file: {}", doc.getMdFilePath(), e);
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
