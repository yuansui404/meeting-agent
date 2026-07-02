package com.meeting.service;

import com.meeting.entity.MeetingMinutes;
import com.meeting.entity.DialogueMessageEntity;
import com.meeting.entity.SessionEntity;
import com.meeting.repository.MeetingMinutesRepository;
import com.meeting.repository.SessionRepository;
import com.meeting.repository.DialogueMessageRepository;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.state.AgentState;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.tool.AgentTool;
import io.agentscope.core.tool.ToolCallParam;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Component
public class UploadToKnowledgeBaseTool implements AgentTool {

    private static final Logger log = LoggerFactory.getLogger(UploadToKnowledgeBaseTool.class);

    private final MeetingMinutesRepository meetingRepository;
    private final VectorizationService vectorizationService;
    private final SessionRepository sessionRepository;
    private final DialogueMessageRepository dialogueMessageRepository;
    private final SessionService sessionService;

    public UploadToKnowledgeBaseTool(MeetingMinutesRepository meetingRepository,
                                     VectorizationService vectorizationService,
                                     SessionRepository sessionRepository,
                                     DialogueMessageRepository dialogueMessageRepository,
                                     SessionService sessionService) {
        this.meetingRepository = meetingRepository;
        this.vectorizationService = vectorizationService;
        this.sessionRepository = sessionRepository;
        this.dialogueMessageRepository = dialogueMessageRepository;
        this.sessionService = sessionService;
    }

    @Override
    public String getName() {
        return "upload_to_knowledge_base";
    }

    @Override
    public String getDescription() {
        return "Upload files to knowledge base for search. "
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

    /**
     * Execute the actual upload to knowledge base.
     * Reads files from state_json (new), falls back to meeting_minutes (backward compat).
     */
    private Mono<ToolResultBlock> executeUpload(Long dialogueId) {
        List<String> uploaded = new ArrayList<>();

        // Priority 1: New-style files from state_json
        List<Map<String, Object>> stateFiles = sessionService.extractFilesFromState(dialogueId);
        for (Map<String, Object> fm : stateFiles) {
            String fileId = (String) fm.get("fileId");
            String fileName = (String) fm.get("fileName");
            String filePathStr = (String) fm.get("filePath");
            if (fileId == null || filePathStr == null) continue;
            try {
                // Check if this file is already in meeting_minutes (already vectorized)
                MeetingMinutes existing = meetingRepository.findByFilePath(filePathStr);
                if (existing != null) {
                    uploaded.add(fileName != null ? fileName : fileId);
                    continue;
                }
                // Upload file content by extracting text and vectorizing
                Path filePath = Path.of(filePathStr);
                if (!Files.exists(filePath)) continue;
                String ext = FileProcessingService.getExtension(fileName != null ? fileName : "").toLowerCase();
                String content = extractFileContent(filePath, ext);
                if (content == null || content.isBlank()) continue;

                MeetingMinutes meeting = new MeetingMinutes();
                meeting.setTitle(fileName != null ? fileName : fileId);
                meeting.setFilePath(filePathStr);
                meeting.setFileSize(filePath.toFile().length());
                meeting.setStatus("completed");
                meeting.setTranscription(content);
                meeting.setDialogueId(dialogueId);
                meeting = meetingRepository.save(meeting);
                vectorizationService.vectorizeMeeting(meeting.getId());
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

    private String extractFileContent(Path filePath, String ext) {
        try {
            Set<String> textFormats = Set.of(".txt", ".md", ".csv", ".json", ".xml", ".html", ".yaml", ".yml", ".properties", ".log");
            Set<String> docFormats = Set.of(".pdf", ".doc", ".docx");
            if (textFormats.contains(ext)) {
                return Files.readString(filePath, java.nio.charset.StandardCharsets.UTF_8);
            } else if (docFormats.contains(ext)) {
                return com.meeting.common.DocumentTextExtractor.extractText(filePath, ext);
            }
        } catch (Exception e) {
            log.warn("Failed to extract file content: {}", e.getMessage());
        }
        return null;
    }

    /**
     * Check if the AI has already asked the user about saving to knowledge base.
     * Looks for "知识库" in the last assistant message from conversation context.
     */
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

    /**
     * Extract the last user message text from the dialogue_messages table.
     */
    private String extractLastUserMessage(Long dialogueId) {
        try {
            List<DialogueMessageEntity> msgs = dialogueMessageRepository
                    .findByDialogueIdOrderById(dialogueId);
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

    /**
     * Check if the user explicitly asked to save/upload to knowledge base.
     */
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

    /**
     * Check if the user responded with an affirmation (after being asked about KB upload).
     * Only short messages (≤5 chars) qualify — longer messages are not simple affirmations.
     */
    private boolean isAffirmation(String userMessage) {
        if (userMessage == null || userMessage.isBlank()) return false;
        // Only short messages can be simple affirmations like "好"/"可以"/"是的"
        // Longer messages like "要对这个PDF分析" should NOT match
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
