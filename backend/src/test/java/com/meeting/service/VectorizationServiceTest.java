package com.meeting.service;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class VectorizationServiceTest {

    @Test
    void chunkText_ShouldHandleNullInput() {
        List<String> result = VectorizationService.chunkText(null);
        assertTrue(result.isEmpty());
    }

    @Test
    void chunkText_ShouldHandleBlankInput() {
        List<String> result = VectorizationService.chunkText("   ");
        assertTrue(result.isEmpty());
    }

    @Test
    void chunkText_ShouldReturnSingleChunkForShortText() {
        String text = "这是一段简短的会议记录。讨论了项目进度。";
        List<String> result = VectorizationService.chunkText(text);
        assertEquals(1, result.size());
        assertEquals(text, result.get(0));
    }

    @Test
    void chunkText_ShouldSplitOnParagraphs() {
        // Each paragraph is ~250 chars, total > 500, so should split
        String para1 = "第一段关于项目进度。".repeat(30);
        String para2 = "第二段关于技术方案。".repeat(30);
        String para3 = "第三段关于下一步计划。".repeat(30);
        String text = para1 + "\n\n" + para2 + "\n\n" + para3;
        List<String> result = VectorizationService.chunkText(text);
        assertTrue(result.size() >= 2);
        assertTrue(result.get(0).contains("第一段"));
        assertTrue(result.get(1).contains("第二段"));
    }

    @Test
    void chunkText_ShouldHandleMixedParagraphs() {
        String shortPara = "短段落。";
        String longPara = "这是长段落内容的详细描述部分。".repeat(40);
        String text = shortPara + "\n\n" + longPara;

        List<String> result = VectorizationService.chunkText(text);
        assertTrue(result.size() >= 2, "Mixed paragraphs should be split when exceeding chunk size");
        assertTrue(result.get(0).contains("短段落"));
    }

    @Test
    void chunkText_ShouldPreserveAllContent() {
        String text = "第一段内容。\n\n第二段内容。\n\n第三段内容。";
        List<String> result = VectorizationService.chunkText(text);
        String joined = String.join("", result);
        assertTrue(joined.contains("第一段内容"));
        assertTrue(joined.contains("第二段内容"));
        assertTrue(joined.contains("第三段内容"));
    }
}
