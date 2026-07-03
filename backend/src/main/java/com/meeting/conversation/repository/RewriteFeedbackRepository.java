package com.meeting.conversation.repository;

import com.meeting.conversation.model.entity.RewriteFeedback;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface RewriteFeedbackRepository extends JpaRepository<RewriteFeedback, Long> {
    List<RewriteFeedback> findByRewriteResultId(Long rewriteResultId);
}
