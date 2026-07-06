package com.meeting.conversation.service;

import com.meeting.conversation.model.entity.RewriteResultEntity;
import com.meeting.conversation.model.entity.SessionEntity;
import com.meeting.conversation.repository.RewriteResultRepository;
import com.meeting.conversation.repository.SessionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class RewriteService {

    private final RewriteResultRepository rewriteResultRepository;
    private final SessionRepository sessionRepository;

    public RewriteResultEntity saveRewriteResult(Long dialogueId, List<Long> sourceFileIds,
                                             List<Long> referenceIds, String content) {
        SessionEntity session = sessionRepository.findById(dialogueId)
                .orElseThrow(() -> new IllegalArgumentException("Session not found: " + dialogueId));

        List<RewriteResultEntity> previous = rewriteResultRepository.findBySessionOrderByVersionDesc(session);
        int nextVersion = previous.isEmpty() ? 1 : previous.get(0).getVersion() + 1;

        RewriteResultEntity result = new RewriteResultEntity();
        result.setSession(session);
        result.setSourceFileIds(sourceFileIds != null ? sourceFileIds : new ArrayList<>());
        result.setReferenceIds(referenceIds != null ? referenceIds : new ArrayList<>());
        result.setContent(content);
        result.setVersion(nextVersion);
        result.setCreatedAt(LocalDateTime.now());
        return rewriteResultRepository.save(result);
    }

    public List<RewriteResultEntity> getRewriteHistory(Long dialogueId) {
        SessionEntity session = sessionRepository.findById(dialogueId)
                .orElseThrow(() -> new IllegalArgumentException("Session not found: " + dialogueId));
        return rewriteResultRepository.findBySessionOrderByVersionDesc(session);
    }

    public Optional<RewriteResultEntity> getRewriteResult(Long resultId) {
        return rewriteResultRepository.findById(resultId);
    }
}
