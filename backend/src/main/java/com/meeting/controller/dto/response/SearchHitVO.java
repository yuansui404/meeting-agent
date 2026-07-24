package com.meeting.controller.dto.response;

public record SearchHitVO(
        String source,
        String content,
        double score,
        String speaker,
        String participants,
        String topic,
        String sectionHeading,
        Integer documentId
) {}