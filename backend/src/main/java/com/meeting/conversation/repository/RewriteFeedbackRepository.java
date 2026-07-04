package com.meeting.conversation.repository;

import com.meeting.conversation.model.entity.RewriteFeedbackEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface RewriteFeedbackRepository extends JpaRepository<RewriteFeedbackEntity, Long> {
    List<RewriteFeedbackEntity> findByRewriteResultId(Long rewriteResultId);
}
