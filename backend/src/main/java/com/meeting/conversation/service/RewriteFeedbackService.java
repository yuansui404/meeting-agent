package com.meeting.conversation.service;

import com.meeting.common.BusinessException;
import com.meeting.conversation.model.entity.RewriteFeedbackEntity;
import com.meeting.conversation.model.entity.RewriteResultEntity;
import com.meeting.conversation.repository.RewriteFeedbackRepository;
import com.meeting.conversation.repository.RewriteResultRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class RewriteFeedbackService {
    private static final double PRIORITY_DELTA = 0.5;
    private static final double PRIORITY_MIN = -5.0;
    private static final double PRIORITY_MAX = 5.0;

    private final RewriteFeedbackRepository feedbackRepository;
    private final RewriteResultRepository rewriteResultRepository;
    private final JdbcTemplate jdbcTemplate;

    @Transactional
    public void submitFeedback(Long rewriteResultId, Integer paragraphIndex, String action) {
        if (!"like".equals(action) && !"dislike".equals(action)) {
            throw new BusinessException("action must be 'like' or 'dislike'");
        }

        // 1. Look up referenced source documents
        RewriteResultEntity result = rewriteResultRepository.findById(rewriteResultId)
                .orElseThrow(() -> BusinessException.notFound("RewriteResult not found: " + rewriteResultId));

        // 2. Save feedback (linked via relationship)
        RewriteFeedbackEntity feedback = new RewriteFeedbackEntity();
        feedback.setRewriteResult(result);
        feedback.setParagraphIndex(paragraphIndex);
        feedback.setAction(action);
        feedbackRepository.save(feedback);

        List<Long> docIds = result.getReferenceIds();
        if (docIds == null || docIds.isEmpty()) {
            log.info("No reference documents to update priority for rewriteResult {}", rewriteResultId);
            return;
        }

        // 3. Update priority_score for all vector chunks of the referenced documents
        double delta = "like".equals(action) ? PRIORITY_DELTA : -PRIORITY_DELTA;

        for (Long docId : docIds) {
            int updated = jdbcTemplate.update(
                    "UPDATE meeting_vectors SET priority_score = GREATEST(?, LEAST(?, COALESCE(priority_score, 0) + ?)) WHERE meeting_id = ?",
                    PRIORITY_MIN, PRIORITY_MAX, delta, docId);
            log.info("Updated priority_score by {} for document {} ({} vectors affected)", delta, docId, updated);
        }
    }
}
