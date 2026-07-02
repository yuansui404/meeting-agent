package com.meeting.service;

import com.meeting.entity.MeetingMinutes;
import com.meeting.repository.MeetingMinutesRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

@Service
public class StyleLearningService {

    private static final Logger log = LoggerFactory.getLogger(StyleLearningService.class);
    private static final int MAX_EXAMPLES = 5;
    private static final double SIMILARITY_THRESHOLD = 0.3;
    private static final int MAX_DOCUMENT_PREVIEW = 4000;

    private static final java.util.Set<String> TEXT_EXTENSIONS = java.util.Set.of(
            ".txt", ".md", ".csv", ".json", ".xml", ".html", ".yaml", ".yml", ".properties");
    private static final java.util.Set<String> DOC_EXTENSIONS = java.util.Set.of(".pdf", ".doc", ".docx");

    private final VectorizationService vectorizationService;
    private final MeetingMinutesRepository meetingRepository;

    public StyleLearningService(VectorizationService vectorizationService,
                                MeetingMinutesRepository meetingRepository) {
        this.vectorizationService = vectorizationService;
        this.meetingRepository = meetingRepository;
    }

    /**
     * Retrieve style examples from knowledge base, weighted by priority_score.
     *
     * @param query           the user's rewrite query or source file content
     * @param excludeFileIds  meetings to exclude (previously used style refs)
     * @return formatted style examples prompt section, or empty string if none
     */
    public String buildStyleExamples(String query, List<Long> excludeFileIds) {
        List<VectorizationService.ScoredVector> examples =
                vectorizationService.searchStyleExamples(query, MAX_EXAMPLES, excludeFileIds);

        if (examples.isEmpty()) {
            log.info("buildStyleExamples: no examples found from searchStyleExamples");
            return "";
        }
        if (examples.get(0).similarity() < SIMILARITY_THRESHOLD) {
            log.info("buildStyleExamples: best similarity {} < threshold {}, skipping",
                    String.format("%.4f", examples.get(0).similarity()), SIMILARITY_THRESHOLD);
            return "";
        }

        // Full threshold filter
        List<VectorizationService.ScoredVector> relevant = examples.stream()
                .filter(v -> v.similarity() >= SIMILARITY_THRESHOLD)
                .toList();
        if (relevant.isEmpty()) {
            log.info("buildStyleExamples: {} initial examples all below threshold {}", examples.size(), SIMILARITY_THRESHOLD);
            return "";
        }
        log.info("buildStyleExamples: {} examples passed threshold, top similarity={}",
                relevant.size(), String.format("%.4f", relevant.get(0).similarity()));

        // Build title cache
        java.util.Map<Long, String> titleCache = new java.util.HashMap<>();
        for (VectorizationService.ScoredVector v : relevant) {
            titleCache.computeIfAbsent(v.meetingId(),
                    id -> meetingRepository.findById(id)
                            .map(MeetingMinutes::getTitle)
                            .orElse("未知文档"));
        }

        StringBuilder sb = new StringBuilder();
        sb.append("以下历史优秀纪要是您的风格参照，请严格模仿其写作风格、语气和结构进行改写。\n\n");
        sb.append("风格参考示例：\n");

        for (int i = 0; i < relevant.size(); i++) {
            VectorizationService.ScoredVector v = relevant.get(i);
            String source = titleCache.get(v.meetingId());
            if (i > 0) sb.append("\n---\n");
            sb.append("【示例 ").append(i + 1).append(" 来自：").append(source).append("】\n");
            sb.append(cleanExampleContent(v.content()));
        }

        sb.append("\n\n以上示例的写作风格具有最高优先级，请严格遵循其风格进行改写，确保行文风格高度一致。");
        return sb.toString();
    }

    /**
     * Clean raw document text for use as style examples.
     * Removes tab characters, excessive whitespace, and renders table content
     * as readable text (tabs → single space, leading "机密" header cleaned).
     */
    static String cleanExampleContent(String content) {
        if (content == null || content.isBlank()) return content;
        // Replace tabs with single space
        String cleaned = content.replace('\t', ' ');
        // Collapse multiple spaces into one (but preserve single newlines)
        cleaned = cleaned.replaceAll(" +", " ");
        // Clean up leading/trailing whitespace on each line
        cleaned = cleaned.lines()
                .map(String::trim)
                .collect(java.util.stream.Collectors.joining("\n"));
        return cleaned;
    }

    /**
     * Build style references from COMPLETE documents (not chunks).
     * Includes both manually selected documents and RAG-ranked results.
     * Preserves original formatting including tab separators.
     *
     * @param query              the rewrite query / source content
     * @param excludeFileIds     meetings to exclude from RAG search (manual refs, previous refs)
     * @param manualReferenceIds user-selected style exemplar meeting IDs (always included)
     * @return formatted reference section with full document content, empty if none
     */
    public String buildFullDocumentReferences(String query, List<Long> excludeFileIds, List<Long> manualReferenceIds) {
        java.util.Set<Long> meetingIds = new java.util.LinkedHashSet<>();

        // 1. Manual references go first (highest priority)
        if (manualReferenceIds != null) {
            meetingIds.addAll(manualReferenceIds);
        }

        // 2. RAG search for additional style examples
        List<VectorizationService.ScoredVector> examples =
                vectorizationService.searchStyleExamples(query, MAX_EXAMPLES, excludeFileIds);

        if (!examples.isEmpty() && examples.get(0).similarity() >= SIMILARITY_THRESHOLD) {
            for (VectorizationService.ScoredVector v : examples) {
                if (v.similarity() >= SIMILARITY_THRESHOLD) {
                    meetingIds.add(v.meetingId());
                }
            }
        }

        if (meetingIds.isEmpty()) {
            log.info("buildFullDocumentReferences: no documents found (manual={}, rag={})",
                    manualReferenceIds != null ? manualReferenceIds.size() : 0, examples.size());
            return "";
        }

        log.info("buildFullDocumentReferences: {} unique documents to load", meetingIds.size());

        StringBuilder sb = new StringBuilder();
        sb.append("以下历史会议记录是您的格式和风格参照，请严格按照其格式进行输出。\n\n");
        sb.append("输出格式示例（请严格按照此格式输出）：\n");

        int idx = 0;
        for (Long meetingId : meetingIds) {
            String fullContent = loadFullDocumentContent(meetingId);
            if (fullContent == null || fullContent.isBlank()) {
                log.warn("buildFullDocumentReferences: meeting {} has no readable content, skipping", meetingId);
                continue;
            }

            if (fullContent.length() > MAX_DOCUMENT_PREVIEW) {
                fullContent = fullContent.substring(0, MAX_DOCUMENT_PREVIEW)
                        + "\n...（内容较长，仅展示前" + MAX_DOCUMENT_PREVIEW + "字符）";
            }

            if (idx > 0) sb.append("\n---\n");
            String title = meetingRepository.findById(meetingId)
                    .map(MeetingMinutes::getTitle)
                    .orElse("未知文档");
            sb.append("【参考文档 ").append(++idx).append("：").append(title).append("】\n");
            sb.append(fullContent);
        }

        if (idx == 0) {
            log.info("buildFullDocumentReferences: all documents had empty content");
            return "";
        }

        sb.append("\n\n以上参考文档的格式具有最高优先级，请严格遵循相同的格式进行输出。");
        log.info("buildFullDocumentReferences: returning {} chars across {} documents", sb.length(), idx);
        return sb.toString();
    }

    /**
     * Load complete document content, trying file first then transcription.
     * Preserves original formatting (tabs, etc.).
     */
    private String loadFullDocumentContent(Long meetingId) {
        return meetingRepository.findById(meetingId)
                .map(meeting -> {
                    String ext = getExtension(meeting.getTitle());
                    if (meeting.getFilePath() != null) {
                        java.nio.file.Path filePath = java.nio.file.Path.of(meeting.getFilePath());
                        if (java.nio.file.Files.exists(filePath)) {
                            try {
                                if (TEXT_EXTENSIONS.contains(ext)) {
                                    return java.nio.file.Files.readString(filePath, java.nio.charset.StandardCharsets.UTF_8);
                                }
                                if (DOC_EXTENSIONS.contains(ext)) {
                                    return com.meeting.common.DocumentTextExtractor.extractText(filePath, ext);
                                }
                            } catch (Exception e) {
                                log.warn("Failed to read file for meeting {}: {}", meetingId, e.getMessage());
                            }
                        }
                    }
                    // Check sidecar transcription file for audio/video
                    if (FileProcessingService.isTranscribable(ext)) {
                        java.nio.file.Path transcriptionPath = java.nio.file.Path.of(meeting.getFilePath() + ".transcription.md");
                        if (java.nio.file.Files.exists(transcriptionPath)) {
                            try {
                                return java.nio.file.Files.readString(transcriptionPath, java.nio.charset.StandardCharsets.UTF_8);
                            } catch (Exception e) {
                                log.warn("Failed to read sidecar transcription for meeting {}: {}", meetingId, e.getMessage());
                            }
                        }
                    }

                    // Fallback to transcription
                    String transcription = meeting.getTranscription();
                    if (transcription != null && !transcription.isBlank() && !"{}".equals(transcription.trim())) {
                        return transcription;
                    }
                    return null;
                })
                .orElse(null);
    }

    private String getExtension(String filename) {
        int dot = filename.lastIndexOf('.');
        return dot >= 0 ? filename.substring(dot).toLowerCase() : "";
    }
}
