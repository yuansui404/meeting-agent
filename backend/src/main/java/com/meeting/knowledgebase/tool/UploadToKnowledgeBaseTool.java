package com.meeting.knowledgebase.tool;

import com.meeting.common.FileMetadata;
import com.meeting.conversation.model.entity.DialogueMessageEntity;
import com.meeting.conversation.model.entity.SessionEntity;
import com.meeting.conversation.repository.DialogueMessageRepository;
import com.meeting.conversation.repository.SessionRepository;
import com.meeting.conversation.service.SessionService;
import com.meeting.document.model.entity.DocumentEntity;
import com.meeting.document.repository.DocumentRepository;
import com.meeting.document.service.DocumentUploadService;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.state.AgentState;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.tool.AgentTool;
import io.agentscope.core.tool.ToolCallParam;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class UploadToKnowledgeBaseTool implements AgentTool {

    private final DocumentRepository documentRepository;
    private final DocumentUploadService documentUploadService;
    private final SessionRepository sessionRepository;
    private final DialogueMessageRepository dialogueMessageRepository;
    private final SessionService sessionService;

    @Override
    public String getName() {
        return "upload_to_knowledge_base";
    }

    @Override
    public String getDescription() {
        return "Upload files from the current conversation to knowledge base for search. "
                + "When the user requests file analysis, do NOT upload automatically. "
                + "First ask the user if they want to save to knowledge base. "
                + "Only call this tool after the user explicitly confirms. "
                + "Do NOT call when the user only asks for summary, analysis, or document review "
                + "without explicitly agreeing to save.";
    }

    @Override
    public Map<String, Object> getParameters() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "dialogueId", Map.of(
                                "type", "number",
                                "description", "Dialogue ID"
                        )
                ),
                "required", List.of("dialogueId")
        );
    }

    @Override
    public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
        Map<String, Object> input = param.getInput();

        Object dialogueIdObj = input.get("dialogueId");
        if (dialogueIdObj == null) {
            return Mono.just(ToolResultBlock.error("Missing dialogueId parameter"));
        }

        Long dialogueId;
        if (dialogueIdObj instanceof Number n) {
            dialogueId = n.longValue();
        } else {
            return Mono.just(ToolResultBlock.error("dialogueId parameter type error"));
        }

        String lastUserMessage = extractLastUserMessage(dialogueId);
        log.debug("Tool: dialogueId={}, lastUserMessage='{}', explicitSave={}, wasAsked={}, affirmation={}",
                dialogueId, truncate(lastUserMessage, 80),
                hasExplicitSaveIntent(lastUserMessage),
                wasUserAskedAboutKb(dialogueId),
                isAffirmation(lastUserMessage));

        // Case 1: User explicitly asked to save/upload → execute directly
        if (hasExplicitSaveIntent(lastUserMessage)) {
            log.info("Tool: user explicitly asked to save dialogue {}", dialogueId);
            return executeUpload(dialogueId);
        }

        // Case 2: AI already asked about saving to KB, and user confirmed → execute
        if (wasUserAskedAboutKb(dialogueId) && isAffirmation(lastUserMessage)) {
            log.info("Tool: user confirmed KB upload for dialogue {}", dialogueId);
            return executeUpload(dialogueId);
        }

        // Case 3: Otherwise → tell AI to ask the user
        log.info("Tool: asking AI to confirm with user for dialogue {} — user msg: {}",
                dialogueId, truncate(lastUserMessage, 80));
        return Mono.just(ToolResultBlock.text(
                "I have completed the analysis. Please ask the user if they would like to "
                + "save these files to the knowledge base for future reference. "
                + "If the user agrees, call this tool again and I will proceed."));
    }

    private Mono<ToolResultBlock> executeUpload(Long dialogueId) {
        List<String> uploaded = new ArrayList<>();

        List<FileMetadata> stateFiles = sessionService.extractFilesFromState(dialogueId);
        for (FileMetadata fm : stateFiles) {
            String fileId = fm.fileId();
            String fileName = fm.fileName();
            String filePathStr = fm.filePath();
            if (fileId == null || filePathStr == null) continue;
            try {
                // Check if this file is already in document table
                DocumentEntity existing = documentRepository.findByFilePath(filePathStr);
                if (existing != null) {
                    uploaded.add(fileName != null ? fileName : fileId);
                    continue;
                }

                // Full processing: parse → preprocess → save metadata → chunk
                documentUploadService.processFile(filePathStr, fileName != null ? fileName : fileId);
                uploaded.add(fileName != null ? fileName : fileId);
                log.info("Tool: uploaded state file {} to knowledge base", fileId);
            } catch (Exception e) {
                log.warn("Tool: KB upload failed for state file {}: {}", fileId, e.getMessage());
            }
        }

        StringBuilder result = new StringBuilder();
        if (!uploaded.isEmpty()) {
            result.append("Successfully uploaded to knowledge base: ")
                  .append(String.join(", ", uploaded)).append(".");
        }
        if (result.isEmpty()) {
            result.append("Upload failed, please try again later.");
        }

        return Mono.just(ToolResultBlock.text(result.toString()));
    }

    private boolean wasUserAskedAboutKb(Long dialogueId) {
        try {
            return sessionRepository.findById(dialogueId)
                    .filter(e -> e.getStateJson() != null && !e.getStateJson().isBlank())
                    .map(e -> {
                        try {
                            AgentState state = AgentState.fromJsonString(e.getStateJson());
                            List<Msg> ctx = state.getContext();
                            for (int i = ctx.size() - 1; i >= 0; i--) {
                                Msg msg = ctx.get(i);
                                if (msg.getRole() == MsgRole.ASSISTANT) {
                                    String content = msg.getTextContent();
                                    return content != null && content.contains("知识库");
                                }
                            }
                        } catch (Exception ex) {
                            log.warn("Failed to parse AgentState for KB-ask check: {}", ex.getMessage());
                        }
                        return false;
                    })
                    .orElse(false);
        } catch (Exception e) {
            log.warn("Failed to read session for KB-ask check: {}", e.getMessage());
            return false;
        }
    }

    private String extractLastUserMessage(Long dialogueId) {
        try {
            SessionEntity session = sessionRepository.findById(dialogueId)
                    .orElse(null);
            if (session == null) return null;
            List<DialogueMessageEntity> msgs = dialogueMessageRepository
                    .findBySessionOrderById(session);
            for (int i = msgs.size() - 1; i >= 0; i--) {
                if ("user".equals(msgs.get(i).getRole())) {
                    String content = msgs.get(i).getContent();
                    return content != null ? content.trim() : null;
                }
            }
        } catch (Exception e) {
            log.warn("Failed to read last user message for dialogue {}: {}", dialogueId, e.getMessage());
        }
        return null;
    }

    private boolean hasExplicitSaveIntent(String userMessage) {
        if (userMessage == null || userMessage.isBlank()) return false;
        String[] keywords = {"保存", "上传", "入库", "存档"};
        for (String kw : keywords) {
            if (userMessage.contains(kw)) {
                return true;
            }
        }
        return false;
    }

    private boolean isAffirmation(String userMessage) {
        if (userMessage == null || userMessage.isBlank()) return false;
        if (userMessage.length() > 5) return false;
        String[] affirmatives = {"好", "可以", "是的", "行", "嗯", "上传", "保存"};
        for (String kw : affirmatives) {
            if (userMessage.contains(kw)) {
                return true;
            }
        }
        return false;
    }

    private String truncate(String text, int maxLen) {
        if (text == null) return null;
        return text.length() <= maxLen ? text : text.substring(0, maxLen) + "...";
    }
}