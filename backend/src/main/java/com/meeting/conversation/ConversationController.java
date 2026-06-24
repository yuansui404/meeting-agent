package com.meeting.conversation;

import com.meeting.common.ApiResponse;
import com.meeting.conversation.model.entity.ConversationEntity;
import com.meeting.conversation.model.entity.MessageEntity;
import com.meeting.conversation.service.ConversationService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/conversation")
@RequiredArgsConstructor
public class ConversationController {

    private final ConversationService conversationService;

    @PostMapping
    public ApiResponse<ConversationEntity> create(@RequestParam(required = false) String title) {
        return ApiResponse.ok(conversationService.create(title));
    }

    @GetMapping
    public ApiResponse<List<ConversationEntity>> list() {
        return ApiResponse.ok(conversationService.list());
    }

    @GetMapping("/{id}")
    public ApiResponse<ConversationEntity> get(@PathVariable Long id) {
        return ApiResponse.ok(conversationService.getById(id));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable Long id) {
        conversationService.delete(id);
        return ApiResponse.ok(null, "删除成功");
    }

    @GetMapping("/{id}/messages")
    public ApiResponse<List<MessageEntity>> messages(@PathVariable Long id) {
        return ApiResponse.ok(conversationService.getMessages(id));
    }
}
