package com.meeting.conversation.repository;

import com.meeting.conversation.model.entity.RewriteResultEntity;
import com.meeting.conversation.model.entity.SessionEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface RewriteResultRepository extends JpaRepository<RewriteResultEntity, Long> {
    List<RewriteResultEntity> findBySessionOrderByVersionDesc(SessionEntity session);
}
