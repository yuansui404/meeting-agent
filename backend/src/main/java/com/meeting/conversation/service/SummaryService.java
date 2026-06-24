package com.meeting.conversation.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.meeting.config.DeepSeekChatClient;
import com.meeting.config.RagProperties;
import com.meeting.conversation.model.entity.ConversationEntity;
import com.meeting.conversation.model.entity.MessageEntity;
import com.meeting.conversation.repository.ConversationRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class SummaryService {

    private final DeepSeekChatClient deepSeekChatClient;
    private final ConversationRepository conversationRepository;
    private final RagProperties ragProperties;
    private final ObjectMapper objectMapper;

    public boolean checkAndCompress(Long conversationId, List<MessageEntity> messages) {
        int estimatedTokens = estimateTokens(messages);
        if (estimatedTokens <= ragProperties.getConversation().getSummaryTrigger()) {
            return false;
        }

        ConversationEntity conv = conversationRepository.findById(conversationId)
                .orElseThrow(() -> new RuntimeException("Conversation not found: " + conversationId));
        String oldSummary = conv.getContextSummary();

        StringBuilder sb = new StringBuilder();
        if (oldSummary != null && !oldSummary.isBlank()) {
            sb.append("历史摘要：").append(oldSummary).append("\n\n");
        }
        for (MessageEntity msg : messages) {
            sb.append(msg.getRole()).append(": ").append(msg.getContent()).append("\n");
        }

        String newSummary = compress(sb.toString());

        conv.setContextSummary(newSummary);

        try {
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> history = objectMapper.readValue(
                    conv.getCompressionHistory() != null ? conv.getCompressionHistory() : "[]",
                    List.class
            );
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("version", history.size() + 1);
            entry.put("triggered_at", new Date().toString());
            entry.put("tokens_before", estimatedTokens);
            history.add(entry);
            conv.setCompressionHistory(objectMapper.writeValueAsString(history));
        } catch (Exception e) {
            log.warn("Failed to update compression history", e);
        }

        conversationRepository.save(conv);
        log.info("Conversation {} compressed: {} tokens -> summary", conversationId, estimatedTokens);
        return true;
    }

    public int estimateTokens(List<MessageEntity> messages) {
        int total = 0;
        for (MessageEntity msg : messages) {
            if (msg.getContent() != null) {
                total += msg.getContent().length() / 2;
            }
        }
        return total;
    }

    private String compress(String text) {
        List<Map<String, String>> messages = List.of(
                Map.of("role", "system", "content",
                        "请将以下对话压缩为简洁的摘要，保留关键讨论要点、决策和结论。"),
                Map.of("role", "user", "content", text)
        );
        var result = deepSeekChatClient.chat(messages, false);
        return result.path("choices").get(0).path("message").path("content").asText();
    }
}
