package com.meeting.common;

/**
 * Enriched message 构建与清洗共享的常量。
 * FileContextBuilder 构建 enriched prompt 时使用，
 * CleanablePgAgentStateStore 持久化前清洗时使用。
 */
public final class EnrichedMessageConstants {

    public static final String PREFIX = "请参考以下资料来回答问题。";
    public static final String QUESTION_DELIMITER = "\n\n问题：";

    private EnrichedMessageConstants() {}
}
