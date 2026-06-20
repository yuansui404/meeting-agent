package com.meeting.service;

import com.meeting.entity.MeetingMinutes;
import com.meeting.entity.MeetingVector;
import com.meeting.repository.MeetingMinutesRepository;
import com.meeting.repository.MeetingVectorRepository;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

@Service
public class SearchService {

    private final MeetingMinutesRepository meetingRepository;
    private final MeetingVectorRepository vectorRepository;

    public SearchService(MeetingMinutesRepository meetingRepository,
                         MeetingVectorRepository vectorRepository) {
        this.meetingRepository = meetingRepository;
        this.vectorRepository = vectorRepository;
    }

    public List<Map<String, Object>> search(String query, int limit) {
        // 1. 全文搜索
        List<MeetingMinutes> textResults = meetingRepository.fullTextSearch(query);

        // 2. 向量搜索（如果有向量）
        List<MeetingVector> vectorResults = Collections.emptyList();

        // 3. 合并结果去重
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
