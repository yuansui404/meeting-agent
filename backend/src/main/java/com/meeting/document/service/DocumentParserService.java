package com.meeting.document.service;

import com.meeting.common.BusinessException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;

@Slf4j
@Service
@RequiredArgsConstructor
public class DocumentParserService {

    /**
     * 解析文档为纯文本
     * V1 简化实现：直接读取本地文件的文本内容
     * PDF/Doc 格式需要后续集成 PDFBox 等库
     */
    public String parse(String filePath) {
        try {
            Path path = Path.of(filePath);
            if (!Files.exists(path)) {
                throw BusinessException.notFound("文件不存在: " + filePath);
            }
            String ext = getExtension(filePath);
            if ("txt".equals(ext) || "md".equals(ext)) {
                return Files.readString(path);
            }
            // PDF/Doc 等格式暂不支持解析，返回空字符串
            log.warn("Document parsing not yet implemented for format: {}, returning raw text attempt", ext);
            try {
                return Files.readString(path);
            } catch (Exception e) {
                log.warn("Cannot read file as text: {}", filePath);
                return "";
            }
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.error("Document parse failed", e);
            throw new BusinessException("文档解析失败");
        }
    }

    private String getExtension(String filename) {
        int idx = filename.lastIndexOf('.');
        return idx == -1 ? "" : filename.substring(idx + 1);
    }
}
