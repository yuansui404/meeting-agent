package com.meeting.agent;

import com.meeting.entity.Dialogue;
import com.meeting.service.DialogueService;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 对话管理 Agent
 * 负责对话的创建、消息管理、历史查询和知识库导入
 */
@Component
public class DialogueAgent {

    private final DialogueService dialogueService;

    public DialogueAgent(DialogueService dialogueService) {
        this.dialogueService = dialogueService;
    }

    public Dialogue createDialogue(String title, Long meetingId) {
        return dialogueService.createDialogue(title, meetingId);
    }

    public Map<String, Object> getDialogueHistory(Long dialogueId) {
        return dialogueService.getDialogueHistory(dialogueId);
    }

    public void importToKnowledgeBase(Long dialogueId) {
        dialogueService.importToKnowledgeBase(dialogueId);
    }
}
