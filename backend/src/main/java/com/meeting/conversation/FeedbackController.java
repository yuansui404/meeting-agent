package com.meeting.conversation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.meeting.common.ApiResponse;
import com.meeting.conversation.model.entity.MessageEntity;
import com.meeting.conversation.repository.MessageRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/feedback")
@RequiredArgsConstructor
public class FeedbackController {

    private final MessageRepository messageRepository;
    private final ObjectMapper objectMapper;

    @PostMapping("/{messageId}")
    public ApiResponse<Void> feedback(
            @PathVariable Long messageId,
            @RequestParam String type) {

        MessageEntity msg = messageRepository.findById(messageId).orElse(null);
        if (msg == null) {
            return ApiResponse.error("消息不存在");
        }

        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> meta = objectMapper.readValue(msg.getMetadata(), Map.class);
            meta.put("feedback", type);
            msg.setMetadata(objectMapper.writeValueAsString(meta));
            messageRepository.save(msg);
            log.info("Feedback recorded: message={}, type={}", messageId, type);
        } catch (Exception e) {
            log.warn("Failed to record feedback", e);
        }

        return ApiResponse.ok(null, "反馈已记录");
    }
}
