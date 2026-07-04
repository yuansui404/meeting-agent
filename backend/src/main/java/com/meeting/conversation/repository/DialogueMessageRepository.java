package com.meeting.conversation.repository;

import com.meeting.conversation.model.entity.DialogueMessageEntity;
import com.meeting.conversation.model.entity.SessionEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface DialogueMessageRepository extends JpaRepository<DialogueMessageEntity, Long> {

    List<DialogueMessageEntity> findBySessionOrderById(SessionEntity session);

    List<DialogueMessageEntity> findBySessionAndRoleAndFilesIsNotNull(SessionEntity session, String role);
}
