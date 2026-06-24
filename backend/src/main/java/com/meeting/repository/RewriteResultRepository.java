package com.meeting.repository;

import com.meeting.entity.RewriteResult;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface RewriteResultRepository extends JpaRepository<RewriteResult, Long> {
    List<RewriteResult> findByDialogueIdOrderByVersionDesc(Long dialogueId);
}
