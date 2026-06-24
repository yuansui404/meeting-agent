package com.meeting.service;

import com.meeting.entity.MeetingMinutes;
import com.meeting.entity.MeetingVector;
import com.meeting.repository.MeetingMinutesRepository;
import com.meeting.repository.MeetingVectorRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SearchServiceTest {

    @Mock
    private MeetingMinutesRepository meetingRepository;

    @Mock
    private MeetingVectorRepository vectorRepository;

    @Mock
    private VectorizationService vectorizationService;

    private SearchService searchService;

    @BeforeEach
    void setUp() {
        searchService = new SearchService(meetingRepository, vectorRepository, vectorizationService);
    }

    @Test
    void search_ShouldReturnFullTextResults() {
        MeetingMinutes mm = new MeetingMinutes();
        mm.setId(1L);
        mm.setTitle("测试会议");
        mm.setTranscription("这是会议内容");

        when(meetingRepository.fullTextSearch("测试")).thenReturn(new ArrayList<>(List.of(mm)));
        when(meetingRepository.trigramSearch("测试")).thenReturn(new ArrayList<>());
        when(vectorizationService.searchSimilar(eq("测试"), anyInt())).thenReturn(new ArrayList<>());

        List<Map<String, Object>> results = searchService.search("测试", 20);

        assertEquals(1, results.size());
        assertEquals("测试会议", results.get(0).get("title"));
        assertEquals("fulltext", results.get(0).get("type"));
    }

    @Test
    void search_ShouldIncludeVectorResults() {
        MeetingMinutes mm = new MeetingMinutes();
        mm.setId(1L);
        mm.setTitle("测试会议");
        mm.setTranscription("这是会议内容");

        MeetingVector mv = new MeetingVector();
        mv.setId(1L);
        mv.setMeetingId(2L);
        mv.setContent("匹配的向量片段");

        MeetingMinutes mm2 = new MeetingMinutes();
        mm2.setId(2L);
        mm2.setTitle("向量匹配会议");
        mm2.setTranscription("向量搜索匹配的内容");

        when(meetingRepository.fullTextSearch("查询")).thenReturn(new ArrayList<>(List.of(mm)));
        when(meetingRepository.trigramSearch("查询")).thenReturn(new ArrayList<>());
        when(vectorizationService.searchSimilar(eq("查询"), anyInt())).thenReturn(new ArrayList<>(List.of(mv)));
        when(meetingRepository.findById(2L)).thenReturn(Optional.of(mm2));

        List<Map<String, Object>> results = searchService.search("查询", 20);

        assertEquals(2, results.size());
        Map<String, Object> vectorResult = results.get(1);
        assertEquals("向量匹配会议", vectorResult.get("title"));
        assertEquals("vector", vectorResult.get("type"));
    }

    @Test
    void search_ShouldHandleVectorSearchError() {
        MeetingMinutes mm = new MeetingMinutes();
        mm.setId(1L);
        mm.setTitle("测试会议");

        when(meetingRepository.fullTextSearch("查询")).thenReturn(new ArrayList<>(List.of(mm)));
        when(meetingRepository.trigramSearch("查询")).thenReturn(new ArrayList<>());
        when(vectorizationService.searchSimilar(eq("查询"), anyInt()))
                .thenThrow(new RuntimeException("API error"));

        List<Map<String, Object>> results = searchService.search("查询", 20);

        assertEquals(1, results.size());
        assertEquals("fulltext", results.get(0).get("type"));
    }

    @Test
    void search_ShouldDeduplicateResults() {
        MeetingMinutes mm = new MeetingMinutes();
        mm.setId(1L);
        mm.setTitle("测试会议");
        mm.setTranscription("相同会议");

        MeetingVector mv = new MeetingVector();
        mv.setMeetingId(1L);
        mv.setContent("片段");

        when(meetingRepository.fullTextSearch("测试")).thenReturn(new ArrayList<>(List.of(mm)));
        when(meetingRepository.trigramSearch("测试")).thenReturn(new ArrayList<>());
        when(vectorizationService.searchSimilar(eq("测试"), anyInt())).thenReturn(new ArrayList<>(List.of(mv)));

        List<Map<String, Object>> results = searchService.search("测试", 20);

        assertEquals(1, results.size());
        verify(meetingRepository, never()).findById(1L);
    }

    @Test
    void search_ShouldRespectLimit() {
        MeetingMinutes mm1 = new MeetingMinutes();
        mm1.setId(1L);
        mm1.setTitle("会议1");
        MeetingMinutes mm2 = new MeetingMinutes();
        mm2.setId(2L);
        mm2.setTitle("会议2");
        MeetingMinutes mm3 = new MeetingMinutes();
        mm3.setId(3L);
        mm3.setTitle("会议3");

        when(meetingRepository.fullTextSearch("会议")).thenReturn(new ArrayList<>(List.of(mm1)));
        when(meetingRepository.trigramSearch("会议")).thenReturn(new ArrayList<>(List.of(mm2, mm3)));
        when(vectorizationService.searchSimilar(eq("会议"), anyInt())).thenReturn(new ArrayList<>());

        List<Map<String, Object>> results = searchService.search("会议", 2);

        assertEquals(2, results.size());
    }
}
