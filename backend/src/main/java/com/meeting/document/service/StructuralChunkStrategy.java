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

    // Markdown heading: ## 会议决策, ### 议题标题, etc.
    private static final Pattern MD_HEADING_PATTERN = Pattern.compile("^(#{1,6})\\s+(.+)");

    // Bold speaker from cleaned markdown: **刘赞作了相关汇报：**
    private static final Pattern MD_SPEAKER_PATTERN = Pattern.compile(
            "^\\*\\*(.{1,10}(?:总意见|作了.*汇报|总))[:：]\\*\\*$");

    // Legacy speaker: 张三：
    private static final Pattern SPEAKER_PATTERN = Pattern.compile(
            "^([\\u4e00-\\u9fa5]{2,4}[：:])\\s*(.*)");

    // Section keywords (for plain text fallback)
    private static final Pattern SECTION_PATTERN = Pattern.compile(
            "^\\[?(总结|决策|会议结论|决议|下一步|决定)]?[:：]?\\s*(.*)");

    // Section type mapping from heading text
    private static final Map<String, String> HEADING_SECTION_MAP = Map.of(
            "会议决策", "DECISION",
            "决策", "DECISION",
            "会议信息", "SUMMARY",
            "总结", "SUMMARY",
            "会议结论", "SUMMARY"
    );

    @Override
    public List<ChunkSegment> chunk(String text, RagProperties.Chunk config) {
        List<ChunkSegment> segments = new ArrayList<>();
        String[] lines = text.split("\n");
        StringBuilder current = new StringBuilder();
        String currentSpeaker = null;
        int chunkIndex = 0;
        String lastSectionType = "STATEMENT";
        boolean inTable = false;
        StringBuilder tableBuffer = new StringBuilder();

        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.isBlank()) {
                if (inTable) tableBuffer.append("\n");
                else current.append("\n");
                continue;
            }

            // --- Table handling: accumulate entire table atomically ---
            if (isTableLine(trimmed)) {
                if (!inTable) {
                    // Entering table mode: flush any pending non-table content
                    inTable = true;
                    tableBuffer.setLength(0);
                }
                tableBuffer.append(trimmed).append("\n");
                continue;
            } else if (inTable) {
                // Exiting table mode: flush table as one chunk
                inTable = false;
                String tableContent = tableBuffer.toString();
                current.append(tableContent);
                tableBuffer.setLength(0);
                // Check size after table flush
                if (current.length() >= config.getSize()) {
                    segments.add(buildSegment(current.toString(), chunkIndex++, currentSpeaker, lastSectionType));
                    current = new StringBuilder();
                }
            }

            // --- Markdown heading detection ---
            Matcher headingMatcher = MD_HEADING_PATTERN.matcher(trimmed);
            if (headingMatcher.find()) {
                // Flush current buffer before starting new section
                if (current.length() > 0) {
                    segments.add(buildSegment(current.toString(), chunkIndex++, currentSpeaker, lastSectionType));
                    current = new StringBuilder();
                    // Overlap
                    if (config.getOverlap() > 0 && !segments.isEmpty()) {
                        String prevContent = segments.get(segments.size() - 1).getContent();
                        int overlapStart = findOverlapStart(prevContent, config.getOverlap());
                        current.append("[overlap]").append(prevContent.substring(overlapStart)).append("\n");
                    }
                }
                String headingText = headingMatcher.group(2);
                lastSectionType = HEADING_SECTION_MAP.getOrDefault(headingText, "STATEMENT");
                current.append(trimmed).append("\n");
                currentSpeaker = null; // Reset speaker on section change
                continue;
            }

            // --- Bold speaker detection (cleaned markdown) ---
            Matcher mdSpeakerMatcher = MD_SPEAKER_PATTERN.matcher(trimmed);
            if (mdSpeakerMatcher.find()) {
                String newSpeaker = mdSpeakerMatcher.group(1);
                if (currentSpeaker != null && !newSpeaker.equals(currentSpeaker) && current.length() > 0) {
                    segments.add(buildSegment(current.toString(), chunkIndex++, currentSpeaker, lastSectionType));
                    current = new StringBuilder();
                    if (config.getOverlap() > 0 && !segments.isEmpty()) {
                        String prevContent = segments.get(segments.size() - 1).getContent();
                        int overlapStart = findOverlapStart(prevContent, config.getOverlap());
                        current.append("[overlap]").append(prevContent.substring(overlapStart)).append("\n");
                    }
                }
                currentSpeaker = newSpeaker;
                lastSectionType = "STATEMENT";
                current.append(trimmed).append("\n");
                continue;
            }

            // --- Legacy speaker detection ---
            Matcher speakerMatcher = SPEAKER_PATTERN.matcher(trimmed);
            if (speakerMatcher.find()) {
                String newSpeaker = speakerMatcher.group(1).replace("：", "").replace(":", "");
                if (currentSpeaker != null && !newSpeaker.equals(currentSpeaker) && current.length() > 0) {
                    segments.add(buildSegment(current.toString(), chunkIndex++, currentSpeaker, lastSectionType));
                    current = new StringBuilder();
                    if (config.getOverlap() > 0 && !segments.isEmpty()) {
                        String prevContent = segments.get(segments.size() - 1).getContent();
                        int overlapStart = findOverlapStart(prevContent, config.getOverlap());
                        current.append("[overlap]").append(prevContent.substring(overlapStart)).append("\n");
                    }
                }
                currentSpeaker = newSpeaker;
                lastSectionType = "STATEMENT";
                current.append(trimmed).append("\n");
                continue;
            }

            // --- Section keyword detection (plain text fallback) ---
            Matcher sectionMatcher = SECTION_PATTERN.matcher(trimmed);
            if (sectionMatcher.find()) {
                String sectionType = switch (sectionMatcher.group(1)) {
                    case "总结", "会议结论", "决定" -> "SUMMARY";
                    case "决策", "决议", "下一步" -> "DECISION";
                    default -> "STATEMENT";
                };
                if (current.length() > 0) {
                    segments.add(buildSegment(current.toString(), chunkIndex++, currentSpeaker, lastSectionType));
                    current = new StringBuilder();
                    if (config.getOverlap() > 0 && !segments.isEmpty()) {
                        String prevContent = segments.get(segments.size() - 1).getContent();
                        int overlapStart = findOverlapStart(prevContent, config.getOverlap());
                        current.append("[overlap]").append(prevContent.substring(overlapStart)).append("\n");
                    }
                }
                lastSectionType = sectionType;
            }

            current.append(trimmed).append("\n");

            // --- Size overflow ---
            if (current.length() >= config.getSize()) {
                String content = current.toString();
                int splitAt = findSentenceBoundary(content, config.getSize());
                segments.add(buildSegment(content.substring(0, splitAt), chunkIndex++, currentSpeaker, lastSectionType));
                current = new StringBuilder(content.substring(splitAt));
            }
        }

        // Flush remaining content
        if (inTable && tableBuffer.length() > 0) {
            current.append(tableBuffer);
        }
        if (current.length() > 0) {
            segments.add(buildSegment(current.toString(), chunkIndex, currentSpeaker, lastSectionType));
        }

        return segments;
    }

    private boolean isTableLine(String line) {
        return line.startsWith("|") && line.endsWith("|");
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
            // Chinese sentence endings
            if (c == '。' || c == '！' || c == '？') {
                return i + 1;
            }
            // Newline followed by heading → split before heading
            if (c == '\n' && i + 1 < text.length() && text.charAt(i + 1) == '#') {
                return i + 1;
            }
            // Generic newline as last resort
            if (c == '\n') {
                return i + 1;
            }
        }
        return near;
    }

    private int findOverlapStart(String text, int overlapLength) {
        int desired = text.length() - overlapLength;
        if (desired <= 0) return 0;
        int start = Math.max(0, desired);
        for (int i = start; i < Math.min(text.length(), desired + 200); i++) {
            char c = text.charAt(i);
            if (c == '。' || c == '！' || c == '？' || c == '\n') {
                return i + 1;
            }
        }
        return start;
    }
}
