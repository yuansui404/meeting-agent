package com.meeting.service;

import com.meeting.entity.MeetingMinutes;
import com.meeting.entity.MeetingVector;
import com.meeting.repository.MeetingMinutesRepository;
import com.meeting.repository.MeetingVectorRepository;
import org.springframework.stereotype.Service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class SearchService {

    private static final Logger log = LoggerFactory.getLogger(SearchService.class);

    private final MeetingMinutesRepository meetingRepository;
    private final MeetingVectorRepository vectorRepository;
    private final VectorizationService vectorizationService;

    public SearchService(MeetingMinutesRepository meetingRepository,
                         MeetingVectorRepository vectorRepository,
                         VectorizationService vectorizationService) {
        this.meetingRepository = meetingRepository;
        this.vectorRepository = vectorRepository;
        this.vectorizationService = vectorizationService;
    }

    public List<Map<String, Object>> search(String query, int limit) {
        return search(query, limit, false);
    }

    public List<Map<String, Object>> search(String query, int limit, boolean kbOnly) {
        // 1. 全文搜索（适用于英文）
        List<MeetingMinutes> textResults = meetingRepository.fullTextSearch(query);

        // 2. 补充 trigram 模糊搜索（适用于中文）
        if (textResults.size() < limit) {
            List<MeetingMinutes> trigramResults = meetingRepository.trigramSearch(query);
            Set<Long> existingIds = textResults.stream().map(MeetingMinutes::getId).collect(Collectors.toSet());
            for (MeetingMinutes mm : trigramResults) {
                if (!existingIds.contains(mm.getId())) {
                    textResults.add(mm);
                }
            }
        }

        // 3. 向量搜索
        List<MeetingVector> vectorResults;
        try {
            vectorResults = vectorizationService.searchSimilar(query, limit);
        } catch (Exception e) {
            log.warn("Vector search failed for query '{}': {}", query, e.getMessage());
            vectorResults = Collections.emptyList();
        }

        // 4. 合并结果去重
        Set<Long> seen = new HashSet<>();
        List<Map<String, Object>> results = new ArrayList<>();

        for (MeetingMinutes mm : textResults) {
            if (seen.add(mm.getId())) {
                Map<String, Object> item = new HashMap<>();
                item.put("id", mm.getId());
                item.put("title", mm.getTitle());
                item.put("transcription", mm.getTranscription());
                item.put("duration", mm.getDuration());
                item.put("createdAt", mm.getCreatedAt());
                item.put("type", "fulltext");
                results.add(item);
            }
        }

        for (MeetingVector mv : vectorResults) {
            if (seen.add(mv.getMeetingId())) {
                meetingRepository.findById(mv.getMeetingId()).ifPresent(mm -> {
                    Map<String, Object> item = new HashMap<>();
                    item.put("id", mm.getId());
                    item.put("title", mm.getTitle());
                    item.put("transcription", mm.getTranscription());
                    item.put("duration", mm.getDuration());
                    item.put("createdAt", mm.getCreatedAt());
                    item.put("type", "vector");
                    item.put("matchedContent", mv.getContent());
                    results.add(item);
                });
            }
        }

        return results.stream().limit(limit).collect(Collectors.toList());
    }
}
