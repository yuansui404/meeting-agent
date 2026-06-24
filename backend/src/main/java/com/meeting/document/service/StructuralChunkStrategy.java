package com.meeting.document.service;

import com.meeting.config.RagProperties;
import com.meeting.document.model.ChunkSegment;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
@ConditionalOnProperty(value = "rag.chunk.strategy", havingValue = "structural", matchIfMissing = true)
public class StructuralChunkStrategy implements ChunkStrategy {

    private static final Pattern SPEAKER_PATTERN = Pattern.compile(
            "^([\\u4e00-\\u9fa5]{2,4}[：:])\\s*(.*)", Pattern.MULTILINE
    );

    private static final Pattern SECTION_PATTERN = Pattern.compile(
            "^\\[?(总结|决策|会议结论|决议|下一步|决定)]?[:：]?\\s*(.*)", Pattern.MULTILINE
    );

    @Override
    public List<ChunkSegment> chunk(String text, RagProperties.Chunk config) {
        List<ChunkSegment> segments = new ArrayList<>();
        String[] lines = text.split("\n");
        StringBuilder current = new StringBuilder();
        String currentSpeaker = null;
        int chunkIndex = 0;
        String lastSectionType = null;

        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.isBlank()) continue;

            boolean isNewSegment = false;
            String sectionType = null;

            Matcher sectionMatcher = SECTION_PATTERN.matcher(trimmed);
            if (sectionMatcher.find()) {
                sectionType = switch (sectionMatcher.group(1)) {
                    case "总结", "会议结论", "决定" -> "SUMMARY";
                    case "决策", "决议", "下一步" -> "DECISION";
                    default -> "STATEMENT";
                };
                isNewSegment = true;
                lastSectionType = sectionType;
            } else {
                Matcher speakerMatcher = SPEAKER_PATTERN.matcher(trimmed);
                if (speakerMatcher.find()) {
                    String newSpeaker = speakerMatcher.group(1).replace("：", "").replace(":", "");
                    if (currentSpeaker != null && !newSpeaker.equals(currentSpeaker)) {
                        isNewSegment = true;
                    }
                    currentSpeaker = newSpeaker;
                    sectionType = "STATEMENT";
                }
            }

            if (isNewSegment && current.length() > 0) {
                segments.add(buildSegment(current.toString(), chunkIndex++, currentSpeaker, lastSectionType));
                current = new StringBuilder();

                if (!segments.isEmpty() && config.getOverlap() > 0) {
                    String prevContent = segments.get(segments.size() - 1).getContent();
                    String overlap = prevContent.length() > config.getOverlap()
                            ? prevContent.substring(prevContent.length() - config.getOverlap())
                            : prevContent;
                    current.append("[overlap]").append(overlap).append("\n");
                }
            }

            current.append(trimmed).append("\n");

            if (current.length() >= config.getSize()) {
                String content = current.toString();
                int splitAt = findSentenceBoundary(content, config.getSize());
                segments.add(buildSegment(content.substring(0, splitAt), chunkIndex++, currentSpeaker, sectionType));
                current = new StringBuilder(content.substring(splitAt));
            }
        }

        if (current.length() > 0) {
            segments.add(buildSegment(current.toString(), chunkIndex, currentSpeaker, lastSectionType));
        }

        return segments;
    }

    private ChunkSegment buildSegment(String content, int index, String speaker, String sectionType) {
        return ChunkSegment.builder()
                .content(content.trim())
                .index(index)
                .speaker(speaker)
                .sectionType(sectionType)
                .build();
    }

    private int findSentenceBoundary(String text, int near) {
        if (near >= text.length()) return text.length();
        int pos = Math.min(near, text.length() - 1);
        for (int i = pos; i > Math.max(0, pos - 200); i--) {
            char c = text.charAt(i);
            if (c == '。' || c == '！' || c == '？' || c == '\n') {
                return i + 1;
            }
        }
        return near;
    }
}
