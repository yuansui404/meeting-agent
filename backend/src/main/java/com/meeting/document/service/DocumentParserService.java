package com.meeting.document.service;

import com.meeting.common.BusinessException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;

@Slf4j
@Service
public class DocumentParserService {

    /**
     * 解析文档为纯文本
     */
    public String parse(String filePath) {
        try {
            Path path = Path.of(filePath);
            if (!Files.exists(path)) {
                throw BusinessException.notFound("文件不存在: " + filePath);
            }
            String ext = getExtension(filePath);
            return switch (ext) {
                case "txt", "md" -> Files.readString(path);
                case "pdf", "docx", "doc" -> DocumentTextExtractor.extractText(path, "." + ext);
                default -> throw new BusinessException("不支持的文件格式: " + ext);
            };
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.error("Document parse failed", e);
            throw BusinessException.processingFailed("文档解析失败", e);
        }
    }

    private String getExtension(String filename) {
        int idx = filename.lastIndexOf('.');
        return idx == -1 ? "" : filename.substring(idx + 1);
    }
}
