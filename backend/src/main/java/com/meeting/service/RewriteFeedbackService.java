package com.meeting.service;

import com.meeting.entity.RewriteFeedback;
import com.meeting.entity.RewriteResult;
import com.meeting.repository.RewriteFeedbackRepository;
import com.meeting.repository.RewriteResultRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class RewriteFeedbackService {

    private static final Logger log = LoggerFactory.getLogger(RewriteFeedbackService.class);
    private static final double PRIORITY_DELTA = 0.5;
    private static final double PRIORITY_MIN = -5.0;
    private static final double PRIORITY_MAX = 5.0;

    private final RewriteFeedbackRepository feedbackRepository;
    private final RewriteResultRepository rewriteResultRepository;
    private final JdbcTemplate jdbcTemplate;

    public RewriteFeedbackService(RewriteFeedbackRepository feedbackRepository,
                                  RewriteResultRepository rewriteResultRepository,
                                  JdbcTemplate jdbcTemplate) {
        this.feedbackRepository = feedbackRepository;
        this.rewriteResultRepository = rewriteResultRepository;
        this.jdbcTemplate = jdbcTemplate;
    }

    @Transactional
    public void submitFeedback(Long rewriteResultId, Integer paragraphIndex, String action) {
        if (!"like".equals(action) && !"dislike".equals(action)) {
            throw new IllegalArgumentException("action must be 'like' or 'dislike'");
        }

        // 1. Save feedback
        RewriteFeedback feedback = new RewriteFeedback();
        feedback.setRewriteResultId(rewriteResultId);
        feedback.setParagraphIndex(paragraphIndex);
        feedback.setAction(action);
        feedbackRepository.save(feedback);

        // 2. Look up referenced source documents
        RewriteResult result = rewriteResultRepository.findById(rewriteResultId)
                .orElseThrow(() -> new IllegalArgumentException("RewriteResult not found: " + rewriteResultId));

        String referenceIds = result.getReferenceIds();
        if (referenceIds == null || referenceIds.isBlank()) {
            log.info("No reference documents to update priority for rewriteResult {}", rewriteResultId);
            return;
        }

        // 3. Update priority_score for all vector chunks of the referenced documents
        double delta = "like".equals(action) ? PRIORITY_DELTA : -PRIORITY_DELTA;

        List<Long> docIds = parseIdList(referenceIds);
        for (Long docId : docIds) {
            int updated = jdbcTemplate.update(
                    "UPDATE meeting_vectors SET priority_score = GREATEST(?, LEAST(?, COALESCE(priority_score, 0) + ?)) WHERE meeting_id = ?",
                    PRIORITY_MIN, PRIORITY_MAX, delta, docId);
            log.info("Updated priority_score by {} for document {} ({} vectors affected)", delta, docId, updated);
        }
    }

    private List<Long> parseIdList(String json) {
        String trimmed = json.trim();
        if (trimmed.isBlank() || "{}".equals(trimmed) || "[]".equals(trimmed)) return List.of();
        if (trimmed.startsWith("[") && trimmed.endsWith("]")) {
            trimmed = trimmed.substring(1, trimmed.length() - 1);
        }
        if (trimmed.isBlank()) return List.of();
        return java.util.Arrays.stream(trimmed.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .map(Long::parseLong)
                .toList();
    }
}
