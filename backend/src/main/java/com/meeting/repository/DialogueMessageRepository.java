package com.meeting.repository;

import com.meeting.entity.DialogueMessageEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface DialogueMessageRepository extends JpaRepository<DialogueMessageEntity, Long> {

    List<DialogueMessageEntity> findByDialogueIdOrderById(Long dialogueId);

    List<DialogueMessageEntity> findByDialogueIdAndRoleAndFilesIsNotNull(Long dialogueId, String role);

    void deleteByDialogueId(Long dialogueId);
}
