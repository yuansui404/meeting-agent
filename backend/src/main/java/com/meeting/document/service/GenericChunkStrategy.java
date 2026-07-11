package com.meeting.document.service;

import com.meeting.config.RagProperties;
import com.meeting.document.model.ChunkSegment;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 通用 markdown 分块策略。
 * 只理解 markdown 结构（标题、表格、段落），不包含任何文档格式定制逻辑。
 * <p>
 * 文档格式相关的预处理由 {@link MeetingMinutesPreprocessor} 在分块前完成。
 */
@Component
public class GenericChunkStrategy implements ChunkStrategy {

    private static final Pattern MD_HEADING_PATTERN = Pattern.compile("^(#{1,6})\\s+(.+)");

    /**
     * 发言人标记检测：匹配 {@code **刘赞总意见：**} 等格式。
     * <p>
     * 匹配模式：
     * <ul>
     *   <li>{@code **刘赞总意见：**} — 常见格式</li>
     *   <li>{@code **刘赞作了相关汇报：**} — 开场汇报格式</li>
     *   <li>{@code **于总：**} — 简写格式</li>
     * </ul>
     */
    private static final Pattern SPEAKER_LINE_PATTERN = Pattern.compile(
            "^\\*\\*([\\u4e00-\\u9fff]{1,6}?)(?:总意见|作了.{0,20}?汇报|总)[：:].*\\*\\*$");

    @Override
    public List<ChunkSegment> chunk(String text, RagProperties.Chunk config, String documentTitle) {
        List<ChunkSegment> segments = new ArrayList<>();
        if (text == null || text.isBlank()) return segments;
        String[] lines = text.split("\n");
        StringBuilder current = new StringBuilder();
        int chunkIndex = 0;
        boolean inTable = false;
        StringBuilder tableBuffer = new StringBuilder();
        String lastHeading = null;
        String currentTopic = null; // h3 议题标题，如 "### 关于工业互联网..."
        String lastSectionHeading = null; // h2 章节标题，如 "## 会议讨论"
        String currentSpeaker = null; // 当前发言人，如 "刘赞"

        int triggerSize = config.getSize() + 100;

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
                    inTable = true;
                    tableBuffer.setLength(0);
                }
                tableBuffer.append(trimmed).append("\n");
                continue;
            } else if (inTable) {
                inTable = false;
                current.append(tableBuffer);
                tableBuffer.setLength(0);
                if (current.length() >= triggerSize) {
                    flushCurrentBuffer(current, config, triggerSize, currentTopic,
                            lastSectionHeading, documentTitle, segments, chunkIndex, currentSpeaker);
                    chunkIndex = segments.size();
                }
            }

            // --- Markdown heading detection ---
            Matcher headingMatcher = MD_HEADING_PATTERN.matcher(trimmed);
            if (headingMatcher.find()) {
                String headingMarkers = headingMatcher.group(1);

                // Flush current buffer before heading boundary
                if (current.length() > 0) {
                    if (!hasSubstantialContent(current.toString())) {
                        current.setLength(0);
                    } else {
                        segments.add(buildSegment(current.toString(), chunkIndex++, currentSpeaker,
                                currentTopic, lastSectionHeading, documentTitle));
                        current = new StringBuilder();
                        if (config.getOverlap() > 0 && !segments.isEmpty()) {
                            String prevContent = segments.get(segments.size() - 1).getContent();
                            int overlapStart = findOverlapStart(prevContent, config.getOverlap());
                            current.append("[overlap]").append(prevContent.substring(overlapStart)).append("\n");
                        }
                    }
                }

                if (headingMarkers.equals("###")) {
                    // h3 = topic bookmark
                    currentTopic = trimmed;
                    current.append(trimmed).append("\n");
                } else {
                    // h2 or h1 = major section boundary — reset speaker
                    currentTopic = null;
                    currentSpeaker = null;
                    lastSectionHeading = trimmed;
                    current.append(trimmed).append("\n");
                }
                lastHeading = trimmed;
                continue;
            }

            // --- Speaker boundary detection ---
            Matcher speakerMatcher = SPEAKER_LINE_PATTERN.matcher(trimmed);
            if (speakerMatcher.find()) {
                String speaker = speakerMatcher.group(1);
                boolean speakerChanged = !Objects.equals(speaker, currentSpeaker);

                // Flush previous speaker's content
                if (speakerChanged && current.length() > 0) {
                    if (!hasSubstantialContent(current.toString())) {
                        current.setLength(0);
                    } else {
                        segments.add(buildSegment(current.toString(), chunkIndex++, currentSpeaker,
                                currentTopic, lastSectionHeading, documentTitle));
                        current = new StringBuilder();
                    }
                }

                currentSpeaker = speaker;

                // Ensure topic context at start of new speaker's buffer
                if (speakerChanged && current.length() == 0 && currentTopic != null) {
                    current.append(currentTopic).append("\n\n");
                }

                current.append(trimmed).append("\n");
                continue;
            }

            current.append(trimmed).append("\n");

            // --- Size overflow: cascade split at sentence boundary ---
            while (current.length() >= triggerSize) {
                String content = current.toString();
                int splitAt = findSentenceBoundary(content, triggerSize);
                if (splitAt <= 0) break;

                String chunkContent = content.substring(0, splitAt);
                segments.add(buildSegment(chunkContent, chunkIndex++, currentSpeaker,
                        currentTopic, lastSectionHeading, documentTitle));

                // Build context prefix for new chunk (topic + speaker)
                StringBuilder contextPrefix = new StringBuilder();
                if (currentTopic != null) {
                    contextPrefix.append(currentTopic).append("\n\n");
                }
                if (currentSpeaker != null) {
                    contextPrefix.append("**").append(currentSpeaker).append("总意见：**\n\n");
                }
                if (currentTopic == null && currentSpeaker == null && lastHeading != null) {
                    contextPrefix.append("[").append(lastHeading.replaceAll("^#+\\s*", "")).append("] ");
                }
                current = new StringBuilder(contextPrefix.toString() + content.substring(splitAt));
            }
        }

        // Flush remaining content
        if (inTable && tableBuffer.length() > 0) {
            current.append(tableBuffer);
        }
        if (current.length() > 0) {
            segments.add(buildSegment(current.toString(), chunkIndex, currentSpeaker,
                    currentTopic, lastSectionHeading, documentTitle));
        }

        return segments;
    }

    private boolean isTableLine(String line) {
        return line.startsWith("|") && line.endsWith("|");
    }

    private boolean hasSubstantialContent(String text) {
        for (String line : text.split("\n")) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) continue;
            if (trimmed.startsWith("#")) continue;
            if (trimmed.startsWith("[overlap]")) continue;
            return true;
        }
        return false;
    }

    private ChunkSegment buildSegment(String content, int index, String speaker,
                                       String topic, String sectionHeading, String documentTitle) {
        return ChunkSegment.builder()
                .content(content.trim())
                .index(index)
                .speaker(speaker)
                .topic(topic)
                .sectionHeading(sectionHeading)
                .documentTitle(documentTitle)
                .build();
    }

    private int findSentenceBoundary(String text, int near) {
        if (near >= text.length()) return text.length();
        int pos = Math.min(near, text.length() - 1);
        for (int i = pos; i > Math.max(0, pos - 200); i--) {
            char c = text.charAt(i);
            if (c == '。' || c == '！' || c == '？') {
                return i + 1;
            }
            if (c == '\n' && i + 1 < text.length() && text.charAt(i + 1) == '#') {
                return i + 1;
            }
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

    private void flushCurrentBuffer(StringBuilder current, RagProperties.Chunk config, int triggerSize,
                                     String currentTopic, String lastSectionHeading, String documentTitle,
                                     List<ChunkSegment> segments, int chunkIndex, String currentSpeaker) {
        String content = current.toString();
        int splitAt = findSentenceBoundary(content, triggerSize);
        if (splitAt > 0) {
            segments.add(buildSegment(content.substring(0, splitAt), chunkIndex, currentSpeaker,
                    currentTopic, lastSectionHeading, documentTitle));
            current.setLength(0);
            if (config.getOverlap() > 0 && !segments.isEmpty()) {
                String prevContent = segments.get(segments.size() - 1).getContent();
                int overlapStart = findOverlapStart(prevContent, config.getOverlap());
                current.append("[overlap]").append(prevContent.substring(overlapStart)).append("\n");
            }
        }
    }
}