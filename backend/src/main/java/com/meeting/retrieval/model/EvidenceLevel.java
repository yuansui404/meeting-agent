package com.meeting.retrieval.model;

public enum EvidenceLevel {
    NONE,       // 向量和全文检索均无结果
    WEAK,       // 仅一种检索命中，且仅来自一份文档
    PARTIAL,    // 两种检索命中但指向不同文档 / 仅一种检索命中但来自多份文档
    SUFFICIENT  // 两种检索均命中并收敛到同一文档（强信号）
}
