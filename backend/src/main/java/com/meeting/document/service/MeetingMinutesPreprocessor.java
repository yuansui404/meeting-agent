package com.meeting.document.service;

import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 会议纪要 markdown 预处理：提取元数据（日期/与会人/时长/地点）+ 剥离元数据章节，
 * 返回干净正文用于分块。
 * <p>
 * 所有会议格式相关的定制化逻辑集中在这里，
 * 一次扫描同时完成提取和剥离，下游 {@link ChunkStrategy} 保持通用。
 */
@Component
public class MeetingMinutesPreprocessor {

    private static final Pattern MD_HEADING = Pattern.compile("^#{1,6}\\s+");

    /**
     * 预处理结果：清洗后的正文文本 + 提取到的元数据
     */
    public record PreprocessResult(String cleanedText, Map<String, Object> metadata) {}

    /**
     * 预处理 markdown 文本：
     * - 提取会信息息到 metadata（日期/与会人/时长/地点）
     * - 剥离标题行和 ## 会议信息 章节
     *
     * @return 清洗后的文本 + 结构化元数据
     */
    public PreprocessResult preprocess(String markdown) {
        String[] lines = markdown.split("\n");
        StringBuilder cleaned = new StringBuilder();
        Map<String, Object> metadata = new HashMap<>();
        boolean titleSkipped = false;
        boolean inMeetingInfo = false;

        for (String line : lines) {
            String trimmed = line.trim();

            // Skip title: non-empty lines before first ## heading
            if (!titleSkipped && !trimmed.isEmpty() && !MD_HEADING.matcher(trimmed).find()) {
                // Capture the first line as meeting title, then continue skipping
                if (!metadata.containsKey("meeting_title")) {
                    metadata.put("meeting_title", trimmed);
                }
                continue;
            }
            if (!titleSkipped && MD_HEADING.matcher(trimmed).find()) {
                titleSkipped = true;
            }

            // Detect ## 会议信息 section
            if (MD_HEADING.matcher(trimmed).find() && trimmed.contains("会议信息")) {
                inMeetingInfo = true;
                continue;
            }

            // Inside ## 会议信息: extract key-value pairs
            if (inMeetingInfo) {
                // Check if this is the end of the section (next ## heading)
                if (MD_HEADING.matcher(trimmed).find()) {
                    inMeetingInfo = false;
                    // Fall through to add this line
                } else {
                    extractKeyValue(trimmed, metadata);
                    continue;
                }
            }

            cleaned.append(line).append("\n");
        }

        return new PreprocessResult(cleaned.toString().strip(), metadata);
    }

    private void extractKeyValue(String line, Map<String, Object> metadata) {
        // **日期**：2026/03/02
        if (line.contains("**日期**")) {
            String val = line.replaceAll("\\*\\*日期\\*\\*[：:]\\s*", "").strip();
            try {
                metadata.put("meeting_date", LocalDate.parse(val, DateTimeFormatter.ofPattern("yyyy/MM/dd")));
            } catch (Exception ignored) {}
            return;
        }
        // **与会人** / **参会人**：张力、杜伟、...
        if (line.contains("**与会人**") || line.contains("**参会人**")) {
            String val = line.replaceAll("\\*\\*[^*]+\\*\\*[：:]\\s*", "").strip();
            metadata.put("participants", val);
            return;
        }
        // **会议时长** / **时长**：1小时30分钟
        if (line.contains("**会议时长**") || line.contains("**时长**")) {
            String val = line.replaceAll("\\*\\*[^*]+\\*\\*[：:]\\s*", "").strip();
            int minutes = 0;
            var m = Pattern.compile("(\\d+)\\s*小时").matcher(val);
            if (m.find()) minutes += Integer.parseInt(m.group(1)) * 60;
            var mm = Pattern.compile("(\\d+)\\s*分钟").matcher(val);
            if (mm.find()) minutes += Integer.parseInt(mm.group(1));
            if (minutes > 0) metadata.put("duration", minutes);
            return;
        }
        // **地点** / **地址**：杭州2002会议室+腾讯会议
        if (line.contains("**地点**") || line.contains("**地址**")) {
            String val = line.replaceAll("\\*\\*[^*]+\\*\\*[：:]\\s*", "").strip();
            metadata.put("location", val);
        }
    }
}