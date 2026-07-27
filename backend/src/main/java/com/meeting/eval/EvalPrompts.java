package com.meeting.eval;

/**
 * Prompt templates for RAG evaluation metrics.
 * All prompts return structured output for reliable parsing.
 */
final class EvalPrompts {

    private EvalPrompts() {}

    // ── Generation ──

    static String generationSystemPrompt() {
        return "你是一个基于检索结果的问答助手。请根据提供的检索资料回答用户问题。\n"
                + "要求：\n"
                + "1. 只基于检索资料中的信息回答，不要添加资料中没有的信息\n"
                + "2. 如果检索资料不足以回答问题，说'资料中没有足够的信息来回答这个问题'\n"
                + "3. 回答要简洁、准确、完整\n"
                + "4. 使用中文回答";
    }

    static String generationUserPrompt(String question, String context) {
        return "检索资料：\n" + context + "\n\n问题：" + question + "\n\n请根据以上检索资料给出回答：";
    }

    // ── Faithfulness: Claim Extraction ──

    static String claimExtractionSystemPrompt() {
        return "你是一个擅长从文本中提取原子事实的专家。\n"
                + "请将以下回答拆解为独立的、原子化的事实陈述。\n"
                + "每个陈述应该是一个独立的、可验证的事实。\n"
                + "每行输出一个陈述，使用编号格式：1. [事实]";
    }

    static String claimExtractionUserPrompt(String answer) {
        return "回答：\n" + answer + "\n\n请提取其中的原子事实：";
    }

    // ── Faithfulness: Claim Verification ──

    static String claimVerificationSystemPrompt() {
        return "你是一个事实核查员。对于每个陈述，判断它是否被提供的'检索资料'所支持。\n"
                + "规则：\n"
                + "- 如果陈述的信息可以直接从检索资料中找到或合理推断，回答 SUPPORTED\n"
                + "- 如果陈述的信息在检索资料中找不到，或者与检索资料矛盾，回答 NOT_SUPPORTED\n"
                + "输出格式（每行一个）：\n"
                + "1: SUPPORTED\n"
                + "2: NOT_SUPPORTED\n"
                + "...";
    }

    static String claimVerificationUserPrompt(String context, String claimsText) {
        return "检索资料：\n" + context + "\n\n陈述：\n" + claimsText;
    }

    // ── Answer Relevance ──

    static String answerRelevanceSystemPrompt() {
        return "你正在评估一个回答与问题的相关性。\n"
                + "请从0.0到1.0打分，只输出数字分数，不要输出其他内容：\n"
                + "- 0.0: 回答完全无关或答非所问\n"
                + "- 0.25: 回答略微相关但没有回答问题\n"
                + "- 0.5: 回答部分地回答了问题\n"
                + "- 0.75: 回答了问题的大部分方面\n"
                + "- 1.0: 完整且精确地回答了问题";
    }

    static String answerRelevanceUserPrompt(String question, String answer) {
        return "问题：" + question + "\n\n回答：" + answer + "\n\n请给出相关性评分（只输出0.0到1.0之间的数字）：";
    }

    // ── Context Precision ──

    static String contextPrecisionSystemPrompt() {
        return "你正在评估检索结果的相关性。\n"
                + "对于每个检索到的文本块，判断它是否与回答用户问题相关。\n"
                + "规则：\n"
                + "- 如果文本块包含与问题相关的信息，回答 RELEVANT\n"
                + "- 如果文本块与问题完全无关，回答 NOT_RELEVANT\n"
                + "输出格式（每行一个）：\n"
                + "1: RELEVANT\n"
                + "2: NOT_RELEVANT\n"
                + "...";
    }

    static String contextPrecisionUserPrompt(String question, String chunksText) {
        return "问题：" + question + "\n\n文本块：\n" + chunksText + "\n\n请判断每个文本块是否与问题相关：";
    }
}