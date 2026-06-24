package com.meeting.conversation.service;

import com.meeting.common.BusinessException;
import com.meeting.conversation.model.entity.ConversationEntity;
import com.meeting.conversation.model.entity.MessageEntity;
import com.meeting.conversation.repository.ConversationRepository;
import com.meeting.conversation.repository.MessageRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
public class ConversationService {

    private final ConversationRepository conversationRepository;
    private final MessageRepository messageRepository;

    public ConversationEntity create(String title) {
        ConversationEntity conv = new ConversationEntity();
        conv.setTitle(title != null ? title : "新对话");
        conv.setStatus("ACTIVE");
        conv.setCompressionHistory("[]");
        conversationRepository.save(conv);
        return conv;
    }

    public List<ConversationEntity> list() {
        return conversationRepository.findByStatusOrderByUpdatedAtDesc("ACTIVE");
    }

    public ConversationEntity getById(Long id) {
        return conversationRepository.findById(id)
                .orElseThrow(() -> BusinessException.notFound("会话不存在"));
    }

    public void delete(Long id) {
        conversationRepository.deleteById(id);
    }

    @Transactional
    public MessageEntity addMessage(Long conversationId, String role, String content, String traceId, String metadata) {
        MessageEntity msg = new MessageEntity();
        msg.setConversationId(conversationId);
        msg.setRole(role);
        msg.setContent(content);
        msg.setTraceId(traceId);
        msg.setMetadata(metadata);
        messageRepository.save(msg);

        ConversationEntity conv = getById(conversationId);
        conv.setMessageCount(conv.getMessageCount() + 1);
        conv.setUpdatedAt(LocalDateTime.now());
        conversationRepository.save(conv);

        return msg;
    }

    public List<MessageEntity> getMessages(Long conversationId) {
        return messageRepository.findByConversationIdOrderByCreatedAtAsc(conversationId);
    }
}
