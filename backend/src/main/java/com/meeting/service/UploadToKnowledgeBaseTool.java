package com.meeting.service;

import com.meeting.entity.MeetingMinutes;
import com.meeting.repository.MeetingMinutesRepository;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.tool.AgentTool;
import io.agentscope.core.tool.ToolCallParam;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Component
public class UploadToKnowledgeBaseTool implements AgentTool {

    private static final Logger log = LoggerFactory.getLogger(UploadToKnowledgeBaseTool.class);

    private final MeetingMinutesRepository meetingRepository;
    private final VectorizationService vectorizationService;

    public UploadToKnowledgeBaseTool(MeetingMinutesRepository meetingRepository,
                                     VectorizationService vectorizationService) {
        this.meetingRepository = meetingRepository;
        this.vectorizationService = vectorizationService;
    }

    @Override
    public String getName() {
        return "upload_to_knowledge_base";
    }

    @Override
    public String getDescription() {
        return "将对话中的文件上传到知识库，使其可通过搜索检索到。"
                + "当用户要求保存文档、加入知识库、存入资料库、放入知识库时调用此工具。"
                + "无需文件路径参数，工具会自动查找当前对话中未上传的文件。";
    }

    @Override
    public Map<String, Object> getParameters() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "dialogueId", Map.of(
                                "type", "number",
                                "description", "对话ID"
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
            return Mono.just(ToolResultBlock.error("缺少 dialogueId 参数"));
        }

        Long dialogueId;
        if (dialogueIdObj instanceof Number n) {
            dialogueId = n.longValue();
        } else {
            return Mono.just(ToolResultBlock.error("dialogueId 参数类型错误"));
        }

        List<MeetingMinutes> files = meetingRepository.findByDialogueId(dialogueId);
        if (files.isEmpty()) {
            return Mono.just(ToolResultBlock.text("当前对话中没有文件可以上传。"));
        }

        List<String> uploaded = new ArrayList<>();
        List<String> alreadyInKb = new ArrayList<>();
        for (MeetingMinutes file : files) {
            if (Boolean.TRUE.equals(file.getKnowledgeBase())) {
                alreadyInKb.add(file.getTitle());
            } else {
                try {
                    vectorizationService.vectorizeMeeting(file.getId());
                    uploaded.add(file.getTitle());
                    log.info("Tool: uploaded file {} to knowledge base", file.getId());
                } catch (Exception e) {
                    log.warn("Tool: KB upload failed for {}: {}", file.getId(), e.getMessage());
                }
            }
        }

        StringBuilder result = new StringBuilder();
        if (!uploaded.isEmpty()) {
            result.append("已成功将以下文件上传到知识库：").append(String.join("、", uploaded)).append("。");
        }
        if (!alreadyInKb.isEmpty()) {
            if (result.length() > 0) result.append(" ");
            result.append("以下文件已在知识库中：").append(String.join("、", alreadyInKb)).append("。");
        }
        if (result.isEmpty()) {
            result.append("文件上传到知识库失败，请稍后重试。");
        }

        return Mono.just(ToolResultBlock.text(result.toString()));
    }
}
