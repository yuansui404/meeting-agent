package com.meeting.document.service;

import com.meeting.document.model.entity.DocumentEntity;
import com.meeting.user.service.ProfileService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 从会议纪要中提取与会人，增量更新到 {@code 与会人.md} 用户画像。
 * <p>
 * 提取策略（两路合并）：
 * <ol>
 *   <li>「## 会议信息」元数据中的 <b>与会人</b>/<b>参会人</b> 字段</li>
 *   <li>正文中的 speaker 标签，如 {@code **刘赞总意见：**}</li>
 * </ol>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AttendeeProfileUpdater {

    private static final String PROFILE_FILENAME = "与会人.md";
    /** 复用 GenericChunkStrategy 的 speaker 格式，提取发言人姓名 */
    private static final Pattern SPEAKER_PATTERN = Pattern.compile(
            "^\\*\\*([\\u4e00-\\u9fff]{1,6}?)(?:总意见|作了.{0,20}?汇报|总)[：:].*\\*\\*$",
            Pattern.MULTILINE);

    private final ProfileService profileService;

    /**
     * 从预处理结果中提取与会人，增量追加到 {@code 与会人.md}。
     *
     * @param result MeetingMinutesPreprocessor 预处理结果
     * @param doc    文档实体（仅用于日志）
     */
    public void updateFromDocument(MeetingMinutesPreprocessor.PreprocessResult result, DocumentEntity doc) {
        Set<String> attendees = new LinkedHashSet<>();

        // 来源 1：元数据 participants
        String participants = (String) result.metadata().get("participants");
        if (participants != null && !participants.isBlank()) {
            Arrays.stream(participants.split("[、,，]"))
                    .map(String::trim)
                    .filter(s -> !s.isEmpty())
                    .forEach(attendees::add);
        }

        // 来源 2：正文 speaker 标签
        String cleanedText = result.cleanedText();
        if (cleanedText != null && !cleanedText.isBlank()) {
            Matcher matcher = SPEAKER_PATTERN.matcher(cleanedText);
            while (matcher.find()) {
                String name = matcher.group(1).trim();
                // 过滤单字名（如 "于" 来自 "于总意见"），避免姓氏截断
                if (name.length() >= 2) {
                    attendees.add(name);
                }
            }
        }

        if (attendees.isEmpty()) {
            log.debug("No attendees extracted from document id={}", doc.getId());
            return;
        }

        // 读取与会人.md 已有名单，去重
        Set<String> existing = readExistingAttendees();
        Set<String> toAdd = new LinkedHashSet<>(attendees);
        toAdd.removeAll(existing);

        if (toAdd.isEmpty()) {
            log.debug("No new attendees to add for document id={}", doc.getId());
            return;
        }

        // 构建追加内容
        String newContent = toAdd.stream()
                .map(name -> "- " + name)
                .collect(Collectors.joining("\n"));
        profileService.appendFile(PROFILE_FILENAME, newContent);

        log.info("Appended {} new attendees to {}: {}", toAdd.size(), PROFILE_FILENAME, toAdd);
    }

    /** 读取与会人.md，返回已有名单集合 */
    private Set<String> readExistingAttendees() {
        try {
            String content = profileService.readFile(PROFILE_FILENAME);
            if (content == null || content.isBlank()) {
                return Set.of();
            }
            return Arrays.stream(content.split("\n"))
                    .map(String::trim)
                    .filter(line -> line.startsWith("- ") && !line.startsWith("--"))
                    .map(line -> line.substring(2).trim())
                    .filter(s -> !s.isEmpty())
                    .collect(Collectors.toSet());
        } catch (Exception e) {
            log.warn("Failed to read existing {}: {}", PROFILE_FILENAME, e.getMessage());
            return Set.of();
        }
    }
}