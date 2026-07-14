package com.meeting.knowledgebase.service;

import com.meeting.common.BusinessException;
import com.meeting.document.model.entity.DocumentEntity;
import com.meeting.document.repository.DocumentRepository;
import com.meeting.document.service.DocumentUploadService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

@Slf4j
@Service
@RequiredArgsConstructor
public class KnowledgeBaseService {

    private final DocumentUploadService documentUploadService;

    public DocumentEntity uploadDocument(MultipartFile file) {
        return documentUploadService.processUpload(file);
    }
}
