package com.meeting.document;

import com.meeting.config.RagProperties;
import com.meeting.document.model.ChunkSegment;
import com.meeting.document.service.GenericChunkStrategy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class GenericChunkStrategyTest {

    private GenericChunkStrategy strategy;
    private RagProperties.Chunk config;

    @BeforeEach
    void setUp() {
        strategy = new GenericChunkStrategy();
        config = new RagProperties.Chunk();
        config.setSize(512);
        config.setOverlap(0);
    }

    @Test
    void shouldSplitAtH2Boundary() {
        var markdown = """
                ## 第一节

                第一段内容。第一段内容。

                ## 第二节

                第二段内容。第二段内容。
                """;

        var chunks = strategy.chunk(markdown, config, "test-doc");
        assertEquals(2, chunks.size(), "两个 h2 应产生两个 chunk");
        assertTrue(chunks.get(0).getContent().contains("第一节"));
        assertTrue(chunks.get(1).getContent().contains("第二节"));
    }

    @Test
    void shouldTrackSectionHeading() {
        var markdown = """
                ## 会议讨论

                一些讨论内容。

                ## 会议决策

                决策内容。
                """;

        var chunks = strategy.chunk(markdown, config, "test-doc");
        assertEquals(2, chunks.size());
        assertEquals("## 会议讨论", chunks.get(0).getSectionHeading());
        assertEquals("## 会议决策", chunks.get(1).getSectionHeading());
    }

    @Test
    void shouldTrackH3Topic() {
        var markdown = """
                ## 会议讨论

                ### 议题一

                议题一的内容。这里是一些讨论。

                ### 议题二

                议题二的内容。更多讨论。
                """;

        var chunks = strategy.chunk(markdown, config, "test-doc");
        assertEquals(2, chunks.size(), "两个 h3 应产生两个 chunk");
        assertTrue(chunks.get(0).getTopic().contains("议题一"));
        assertTrue(chunks.get(1).getTopic().contains("议题二"));
    }

    @Test
    void shouldSplitLongContentAtSentenceBoundary() {
        var markdown = """
                ## 章节

                第一句。第二句。第三句。第四句。第五句。第六句。第七句。第八句。第九句。第十句。
                第十一句。第十二句。第十三句。第十四句。第十五句。第十六句。第十七句。第十八句。第十九句。第二十句。
                第二十一句。第二十二句。第二十三句。第二十四句。第二十五句。第二十六句。第二十七句。第二十八句。第二十九句。第三十句。
                第三十一句。第三十二句。第三十三句。第三十四句。第三十五句。第三十六句。第三十七句。第三十八句。第三十九句。第四十句。
                第四十一句。第四十二句。第四十三句。第四十四句。第四十五句。第四十六句。第四十七句。第四十八句。第四十九句。第五十句。
                第五十一句。第五十二句。第五十三句。第五十四句。第五十五句。第五十六句。第五十七句。第五十八句。第五十九句。第六十句。
                第六十一句。第六十二句。第六十三句。第六十四句。第六十五句。第六十六句。第六十七句。第六十八句。第六十九句。第七十句。
                第七十一句。第七十二句。第七十三句。第七十四句。第七十五句。第七十六句。第七十七句。第七十八句。第七十九句。第八十句。
                """;

        config.setSize(200);
        var chunks = strategy.chunk(markdown, config, "test-doc");

        assertTrue(chunks.size() >= 2, "长内容应被切成多段");
        for (var c : chunks) {
            assertTrue(c.getContent().contains("章节"),
                    "每个子 chunk 都应包含章节标题上下文");
        }
    }

    @Test
    void shouldNotSplitTable() {
        var markdown = """
                ## 数据表格

                | 姓名 | 年龄 | 城市 |
                |------|------|------|
                | 张三 | 28 | 北京 |
                | 李四 | 32 | 上海 |
                | 王五 | 25 | 广州 |
                """;

        var chunks = strategy.chunk(markdown, config, "test-doc");
        assertEquals(1, chunks.size());
        assertTrue(chunks.get(0).getContent().contains("张三"));
        assertTrue(chunks.get(0).getContent().contains("王五"));
    }

    @Test
    void shouldHandleNonMeetingDocument() {
        // 一篇普通的技术文章，没有会议相关结构
        var markdown = """
                # 技术博客

                这是一篇关于微服务架构的文章。

                ## 背景

                微服务架构是一种将应用拆分为多个独立服务的架构风格。

                ## 优势

                ### 可扩展性

                每个服务可以独立扩展，无需整体扩容。

                ### 容错性

                单个服务的故障不会影响整个系统。
                """;

        var chunks = strategy.chunk(markdown, config, "test-doc");
        assertTrue(chunks.size() >= 3, "普通文章应正常分块");
        assertTrue(chunks.stream().anyMatch(c -> c.getContent().contains("技术博客")),
                "h1 标题应被正常分块");
        assertTrue(chunks.stream().anyMatch(c -> c.getContent().contains("可扩展性")),
                "h3 子标题应被正常分块");
    }

    @Test
    void shouldPreserveDocumentTitleInMetadata() {
        var markdown = """
                ## 章节

                内容。
                """;

        var chunks = strategy.chunk(markdown, config, "my-document-title.docx");
        assertEquals("my-document-title.docx", chunks.get(0).getDocumentTitle());
    }

    @Test
    void shouldHandleEmptyContent() {
        var chunks = strategy.chunk("", config, "test-doc");
        assertTrue(chunks.isEmpty(), "空内容应产生空结果");
    }

    // --- Speaker boundary tests ---

    @Test
    void shouldSplitBySpeakerBoundary() {
        var markdown = """
                ## 会议讨论

                ### 关于TP政策

                **刘赞总意见：**
                同意TP政策。

                **张力总意见：**
                需要确认细节。

                **于总意见：**
                按流程推进。
                """;

        var chunks = strategy.chunk(markdown, config, "test-doc");
        // 应产生3个chunk，每人一个
        assertEquals(3, chunks.size(), "三个发言人应产生三个各自独立的chunk");
        assertEquals("刘赞", chunks.get(0).getSpeaker());
        assertEquals("张力", chunks.get(1).getSpeaker());
        assertEquals("于", chunks.get(2).getSpeaker()); // "于总意见" → 正则提取"于"，"总"为标题
    }

    @Test
    void shouldPreserveTopicContextAcrossSpeakerChunks() {
        var markdown = """
                ## 会议讨论

                ### 关于TP政策

                **刘赞总意见：**
                同意TP政策。

                **张力总意见：**
                需要确认细节。
                """;

        var chunks = strategy.chunk(markdown, config, "test-doc");
        // 每个chunk都应包含议题标题作为上下文
        for (var c : chunks) {
            assertTrue(c.getContent().contains("关于TP政策"),
                    "每个speaker chunk都应包含议题标题上下文: " + c.getSpeaker());
            assertEquals("关于TP政策", c.getTopic().replace("### ", ""));
        }
    }

    @Test
    void shouldHandleSingleSpeakerWithMultipleParagraphs() {
        var markdown = """
                ## 会议讨论

                ### 议题一

                **刘赞总意见：**
                第一点内容。第一点内容。

                第二点内容。第二点内容。

                **张力总意见：**
                张力观点。
                """;

        var chunks = strategy.chunk(markdown, config, "test-doc");
        assertEquals(2, chunks.size(), "两个发言人各一个chunk");
        assertEquals("刘赞", chunks.get(0).getSpeaker());
        assertTrue(chunks.get(0).getContent().contains("第一点内容"));
        assertTrue(chunks.get(0).getContent().contains("第二点内容"),
                "刘赞的多段内容应合并到同一个chunk");
    }

    @Test
    void shouldResetSpeakerAtH2Boundary() {
        var markdown = """
                ## 会议讨论

                **刘赞总意见：**
                讨论内容。

                ## 会议决策

                决策结论。
                """;

        var chunks = strategy.chunk(markdown, config, "test-doc");
        assertEquals(2, chunks.size());
        assertEquals("刘赞", chunks.get(0).getSpeaker());
        assertNull(chunks.get(1).getSpeaker(), "h2章节边界应重置speaker");
    }

    @Test
    void shouldKeepSpeakerOnOverflowSplit() {
        StringBuilder longContent = new StringBuilder();
        for (int i = 0; i < 50; i++) {
            longContent.append("刘赞的观点内容。");
        }

        var markdown = """
                ## 会议讨论

                ### 议题一

                **刘赞总意见：**
                """ + longContent + "\n\n**张力总意见：**\n张力观点。";

        config.setSize(200);
        var chunks = strategy.chunk(markdown, config, "test-doc");
        // 刘赞的内容应该被切分成多个chunk，但speaker都应为刘赞
        var liuZanChunks = chunks.stream()
                .filter(c -> "刘赞".equals(c.getSpeaker()))
                .toList();
        assertTrue(liuZanChunks.size() >= 2, "刘赞的长内容应被切分成多个chunk");
        assertTrue(liuZanChunks.stream().allMatch(c -> "刘赞".equals(c.getSpeaker())),
                "内容溢出分割后speaker应保持一致");
    }

    @Test
    void shouldNotMatchNonSpeakerBoldText() {
        var markdown = """
                ## 会议讨论

                这是一段**重要**的内容，不是发言人。

                **张力总意见：**
                这是发言内容。
                """;

        var chunks = strategy.chunk(markdown, config, "test-doc");
        assertEquals(2, chunks.size(), "非发言人标记不触发分块，但非发言内容+发言内容应为两个chunk");
        assertNull(chunks.get(0).getSpeaker(), "非发言人内容chunk的speaker应为null");
        assertEquals("张力", chunks.get(1).getSpeaker());
    }

    @Test
    void shouldMatchSpeakerVariations() {
        var markdown = """
                ## 会议讨论

                **刘赞作了相关汇报：**
                汇报内容。

                **张力总意见：**
                意见内容。
                """;

        var chunks = strategy.chunk(markdown, config, "test-doc");
        assertEquals(2, chunks.size());
        assertEquals("刘赞", chunks.get(0).getSpeaker());
        assertEquals("张力", chunks.get(1).getSpeaker());
    }
}