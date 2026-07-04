package com.meeting.conversation.repository;

import com.meeting.conversation.model.entity.RewriteResultEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface RewriteResultRepository extends JpaRepository<RewriteResultEntity, Long> {
    List<RewriteResultEntity> findByDialogueIdOrderByVersionDesc(Long dialogueId);
}
