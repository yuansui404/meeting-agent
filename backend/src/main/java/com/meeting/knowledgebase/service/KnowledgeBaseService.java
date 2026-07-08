package com.meeting.knowledgebase.service;

import com.meeting.common.BusinessException;
import com.meeting.document.model.entity.DocumentEntity;
import com.meeting.document.repository.DocumentRepository;
import com.meeting.document.service.DocumentUploadService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class KnowledgeBaseService {

    private final DocumentUploadService documentUploadService;
    private final DocumentRepository documentRepository;

    public DocumentEntity uploadDocument(MultipartFile file) {
        return documentUploadService.processUpload(file);
    }

    public DocumentEntity setStyleExemplar(Long id, boolean exemplar, String styleTags) {
        DocumentEntity doc = documentRepository.findById(id)
                .orElseThrow(() -> BusinessException.notFound("文档不存在: " + id));
        doc.setStyleExemplar(exemplar);
        if (styleTags != null) {
            doc.setStyleTags(styleTags);
        }
        return documentRepository.save(doc);
    }

    public List<Map<String, Object>> listStyleExemplars() {
        return documentRepository.findByStyleExemplarTrue().stream()
                .map(d -> {
                    Map<String, Object> map = new java.util.HashMap<>();
                    map.put("id", d.getId());
                    map.put("title", d.getTitle());
                    map.put("styleTags", d.getStyleTags() != null ? d.getStyleTags() : "");
                    return map;
                }).collect(Collectors.toList());
    }
}
