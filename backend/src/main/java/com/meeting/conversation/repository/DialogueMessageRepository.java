package com.meeting.conversation.repository;

import com.meeting.conversation.model.entity.DialogueMessageEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

public interface DialogueMessageRepository extends JpaRepository<DialogueMessageEntity, Long> {

    List<DialogueMessageEntity> findByDialogueIdOrderById(Long dialogueId);

    List<DialogueMessageEntity> findByDialogueIdAndRoleAndFilesIsNotNull(Long dialogueId, String role);

    @Modifying
    @Transactional
    @Query("DELETE FROM DialogueMessageEntity d WHERE d.dialogueId = :dialogueId")
    void deleteByDialogueId(@Param("dialogueId") Long dialogueId);
}
