package com.meeting.template.service;

import com.meeting.common.BusinessException;
import com.meeting.config.FileProperties;
import com.meeting.template.model.entity.TemplateEntity;
import com.meeting.template.repository.TemplateRepository;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class TemplateService {

    private final FileProperties fileProperties;
    private final TemplateRepository templateRepository;

    @PostConstruct
    public void init() {
        try {
            Files.createDirectories(getTemplateDir());
        } catch (IOException e) {
            throw new RuntimeException("无法创建模板目录: " + getTemplateDir(), e);
        }
    }

    public Path getTemplateDir() {
        return Paths.get(fileProperties.uploadDir(), "templates");
    }

    private static final long MAX_TEMPLATE_SIZE = 5 * 1024 * 1024; // 5MB

    public TemplateEntity upload(MultipartFile file, String name, String styleTags) {
        if (file.isEmpty()) {
            throw BusinessException.badRequest("上传文件为空");
        }

        if (file.getSize() > MAX_TEMPLATE_SIZE) {
            throw BusinessException.badRequest("模板文件大小不能超过 5MB");
        }

        String originalFilename = file.getOriginalFilename();
        if (originalFilename == null || !originalFilename.toLowerCase().endsWith(".docx")) {
            throw BusinessException.badRequest("仅支持 .docx 格式的模板文件");
        }

        // Save file to disk with UUID to avoid name conflicts
        String uuid = UUID.randomUUID().toString();
        String storedFilename = uuid + "_" + originalFilename;
        Path targetPath = getTemplateDir().resolve(storedFilename);

        try {
            Files.copy(file.getInputStream(), targetPath, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw BusinessException.processingFailed("模板文件保存失败", e);
        }

        // Save DB record
        TemplateEntity entity = new TemplateEntity();
        entity.setName(name);
        entity.setFilePath(targetPath.toString());
        entity.setStyleTags(styleTags);
        return templateRepository.save(entity);
    }

    public List<TemplateEntity> list() {
        return templateRepository.findAllByOrderByCreatedAtDesc();
    }

    public TemplateEntity getById(Long id) {
        return templateRepository.findById(id)
                .orElseThrow(() -> BusinessException.notFound("模板不存在: " + id));
    }

    public void delete(Long id) {
        TemplateEntity entity = getById(id);

        // Delete disk file
        try {
            Path filePath = Path.of(entity.getFilePath());
            Files.deleteIfExists(filePath);
        } catch (IOException e) {
            log.warn("删除模板文件失败: {}", entity.getFilePath(), e);
        }

        templateRepository.delete(entity);
    }

    /**
     * Returns the file Path for a template, verifying the file exists on disk.
     */
    public Path getTemplatePath(Long id) {
        TemplateEntity entity = getById(id);
        Path filePath = Path.of(entity.getFilePath());
        if (!Files.exists(filePath)) {
            throw BusinessException.notFound("模板文件不存在: " + filePath);
        }
        return filePath;
    }
}