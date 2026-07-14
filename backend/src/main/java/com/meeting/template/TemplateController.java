package com.meeting.template;

import com.meeting.common.ApiResponse;
import com.meeting.common.BusinessException;
import com.meeting.document.service.DocumentTextExtractor;
import com.meeting.template.model.entity.TemplateEntity;
import com.meeting.template.service.TemplateService;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

@RestController
@RequestMapping("/api/template")
@RequiredArgsConstructor
public class TemplateController {

    private final TemplateService templateService;

    @PostMapping("/upload")
    public ApiResponse<TemplateEntity> upload(
            @RequestParam("file") MultipartFile file,
            @RequestParam("name") String name,
            @RequestParam(value = "styleTags", required = false) String styleTags) {
        TemplateEntity entity = templateService.upload(file, name, styleTags);
        return ApiResponse.ok(entity);
    }

    @GetMapping("/list")
    public ApiResponse<List<TemplateEntity>> list() {
        return ApiResponse.ok(templateService.list());
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable Long id) {
        templateService.delete(id);
        return ApiResponse.ok(null, "删除成功");
    }

    @GetMapping("/{id}/file")
    public ResponseEntity<?> getFile(@PathVariable Long id) {
        Path filePath = templateService.getTemplatePath(id);
        Resource resource = new FileSystemResource(filePath);
        String encodedFilename = filePath.getFileName().toString().replaceFirst("^[0-9a-f-]+_", "");
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename*=UTF-8''" + encodedFilename)
                .body(resource);
    }

    @GetMapping("/{id}/preview")
    public ApiResponse<String> preview(@PathVariable Long id) {
        Path filePath = templateService.getTemplatePath(id);
        String filename = filePath.getFileName().toString();
        String ext = filename.substring(filename.lastIndexOf('.'));
        try {
            String content = DocumentTextExtractor.extractText(filePath, ext);
            return ApiResponse.ok(content);
        } catch (IOException e) {
            throw BusinessException.processingFailed("模板预览解析失败", e);
        }
    }
}