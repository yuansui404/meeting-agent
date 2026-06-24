package com.meeting.conversation.repository;

import com.meeting.conversation.model.entity.ConversationEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ConversationRepository extends JpaRepository<ConversationEntity, Long> {
    List<ConversationEntity> findByStatusOrderByUpdatedAtDesc(String status);
}
