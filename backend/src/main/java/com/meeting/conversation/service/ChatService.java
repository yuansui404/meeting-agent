package com.meeting.conversation.service;

import com.meeting.common.FileMetadata;
import com.meeting.transcription.service.FileContextBuilder;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.UserMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.context.config.annotation.RefreshScope;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;

/**
 * 对话入口 Facade。负责 metadata 解析和组件编排。
 */
@Slf4j
@Service
@RefreshScope
@RequiredArgsConstructor
public class ChatService {

    private final FileContextBuilder fileContextBuilder;
    private final ChatStreamService chatStreamService;

    public void streamChat(SseEmitter emitter, Long dialogueId, String userMessage,
                           List<FileMetadata> files) {
        List<FileMetadata> messageFiles = files != null ? files : List.of();

        // 用户上传文件具体内容
        String fileContext = fileContextBuilder.build(messageFiles);

        UserMessage msg = new UserMessage(userMessage);

        RuntimeContext ctx = RuntimeContext.builder()
                .sessionId("dialogue-" + dialogueId)
                .put("fileContext", fileContext)
                .build();

        chatStreamService.stream(emitter, dialogueId, msg, ctx, userMessage, messageFiles);
    }
}
