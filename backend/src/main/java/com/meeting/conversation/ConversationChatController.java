package com.meeting.conversation;

import com.meeting.common.ApiResponse;
import com.meeting.conversation.model.dto.ChatRequest;
import com.meeting.conversation.model.entity.MessageEntity;
import com.meeting.conversation.service.ConversationService;
import com.meeting.conversation.service.SummaryService;
import com.meeting.retrieval.service.HybridSearchService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class ConversationChatController {

    private final ConversationService conversationService;
    private final HybridSearchService hybridSearchService;
    private final SummaryService summaryService;

    @PostMapping(value = "/conversation/{id}/chat", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamChat(@PathVariable Long id, @Valid @RequestBody ChatRequest request) {
        if (!id.equals(request.getConversationId())) {
            throw new IllegalArgumentException("会话ID不匹配");
        }

        SseEmitter emitter = new SseEmitter(300_000L);
        String traceId = MDC.get("traceId");

        java.util.concurrent.Executors.newSingleThreadExecutor().submit(() -> {
            try {
                conversationService.addMessage(id, "user", request.getContent(), traceId, "{}");

                var searchResult = hybridSearchService.search(request.getContent(), null);
                String ragContext = searchResult.chunks().stream()
                        .map(c -> "[会议片段] " + c.getContent())
                        .collect(Collectors.joining("\n\n"));

                String enrichedMessage = ragContext.isEmpty()
                        ? request.getContent()
                        : "以下是知识库中相关的会议内容参考：\n\n" + ragContext + "\n\n用户问题：" + request.getContent();

                String response = "这是基于检索增强生成的回答。\n\n";
                response += "检索来源：" + searchResult.chunks().size() + "个文档片段\n";
                response += "证据等级：" + searchResult.evidenceLevel() + "\n";

                for (char ch : response.toCharArray()) {
                    try {
                        emitter.send(SseEmitter.event().data(String.valueOf(ch)));
                        Thread.sleep(10);
                    } catch (IOException e) {
                        throw new RuntimeException("Client disconnected", e);
                    }
                }

                conversationService.addMessage(id, "assistant", response, traceId, "{}");

                List<MessageEntity> messages = conversationService.getMessages(id);
                summaryService.checkAndCompress(id, messages);

                emitter.send(SseEmitter.event().name("done").data(""));
                emitter.complete();

            } catch (Exception e) {
                log.error("Chat stream error", e);
                try {
                    emitter.send(SseEmitter.event().name("error").data(e.getMessage()));
                } catch (IOException ex) {
                    // ignore
                }
                emitter.completeWithError(e);
            }
        });

        return emitter;
    }

    @PostMapping("/conversation/{id}/search")
    public ApiResponse<?> search(@PathVariable Long id, @RequestBody Map<String, String> request) {
        String query = request.get("content");
        if (query == null || query.isBlank()) {
            return ApiResponse.error("搜索内容不能为空");
        }
        var result = hybridSearchService.search(query, null);
        return ApiResponse.ok(result);
    }
}
