package com.meeting.repository;

import com.meeting.entity.DialogueMessage;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface DialogueMessageRepository extends JpaRepository<DialogueMessage, Long> {

    List<DialogueMessage> findByDialogueIdOrderByTimestampAsc(Long dialogueId);
}
