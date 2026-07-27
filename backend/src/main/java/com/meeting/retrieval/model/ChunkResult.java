package com.meeting.retrieval.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDate;

/**
 * RAG 检索管线核心数据对象，在管线各阶段间传递。
 *
 * <p>每个 ChunkResult 对应一个文档分块，携带多路检索的分数槽
 * （vector / fts / rrf），供管线各阶段写入和消费。</p>
 */
@Data
@Builder(toBuilder = true)
@AllArgsConstructor
public class ChunkResult {

    // ── 数据库字段（来自 document_chunk_v2 表） ──

    /** 分块主键 ID */
    private Long chunkId;

    /** 所属文档 ID（即 meeting_id，用于证据收敛判定） */
    private Long documentId;

    /** 分块文本内容 */
    private String content;

    /** 分块在文档内的序号（从 0 开始） */
    private int chunkIndex;

    /** 发言人（来自 speaker 列，精确匹配用） */
    private String speaker;

    // ── 元数据字段（从 metadata JSON 列解析） ──

    /** 文件名 / 文档标题 */
    private String fileName;

    /** 会议日期（用于时间衰减加权） */
    private LocalDate meetingDate;

    /** 与会人列表（逗号分隔的字符串） */
    private String participants;

    /** 段落主题（h3 标题，如 "### 关于工业互联网..."，用于 topic 级邻居扩展） */
    private String topic;

    /** 段落所属章节标题（h2 标题，如 "## 会议讨论"） */
    private String sectionHeading;

    // ── 分数槽（各阶段写入） ──

    /** 向量检索的相似度分数（cosine distance，值域 [0,1]） */
    private double vectorScore;

    /** 全文检索的 ts_rank 分数 */
    private double ftsScore;

    /** RRF 融合后的倒数排名分数 {@code 1/(k+rank)} */
    private double rrfScore;

    /** 向量检索中的排名位次（从 1 开始，用于 RRF 计算） */
    private Integer vectorRank;

    /** 全文检索中的排名位次（从 1 开始，用于 RRF 计算） */
    private Integer ftsRank;
}
