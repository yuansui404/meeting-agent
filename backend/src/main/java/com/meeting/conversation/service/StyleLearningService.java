package com.meeting.conversation.service;

import com.meeting.document.model.VectorSearchHit;
import com.meeting.document.model.entity.DocumentEntity;
import com.meeting.document.repository.DocumentChunkRepository;
import com.meeting.document.repository.DocumentRepository;
import com.meeting.llm.service.EmbeddingService;
import com.meeting.transcription.service.FileProcessingService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class StyleLearningService {
    private static final int MAX_EXAMPLES = 5;
    private static final double SIMILARITY_THRESHOLD = 0.3;
    private static final int MAX_DOCUMENT_PREVIEW = 4000;

    private static final java.util.Set<String> TEXT_EXTENSIONS = java.util.Set.of(
            ".txt", ".md", ".csv", ".json", ".xml", ".html", ".yaml", ".yml", ".properties");
    private static final java.util.Set<String> DOC_EXTENSIONS = java.util.Set.of(".pdf", ".doc", ".docx");

    private final EmbeddingService embeddingService;
    private final DocumentChunkRepository chunkRepository;
    private final DocumentRepository documentRepository;

    public String buildStyleExamples(String query, List<Long> excludeFileIds) {
        float[] queryEmbedding = embeddingService.generateEmbedding(query);
        String embeddingStr = "[" + floatArrayToString(queryEmbedding) + "]";

        List<VectorSearchHit> examples = chunkRepository.styleExemplarSearch(embeddingStr, MAX_EXAMPLES * 3);

        // Filter out excluded document IDs
        if (excludeFileIds != null && !excludeFileIds.isEmpty()) {
            examples = examples.stream()
                    .filter(h -> !excludeFileIds.contains(h.documentId()))
                    .toList();
        }

        if (examples.isEmpty()) {
            log.info("buildStyleExamples: no examples found from styleExemplarSearch");
            return "";
        }
        if (examples.get(0).similarityScore() < SIMILARITY_THRESHOLD) {
            log.info("buildStyleExamples: best similarity {} < threshold {}, skipping",
                    String.format("%.4f", examples.get(0).similarityScore()), SIMILARITY_THRESHOLD);
            return "";
        }

        List<VectorSearchHit> relevant = examples.stream()
                .filter(v -> v.similarityScore() >= SIMILARITY_THRESHOLD)
                .limit(MAX_EXAMPLES)
                .toList();
        if (relevant.isEmpty()) {
            log.info("buildStyleExamples: {} initial examples all below threshold {}", examples.size(), SIMILARITY_THRESHOLD);
            return "";
        }
        log.info("buildStyleExamples: {} examples passed threshold, top similarity={}",
                relevant.size(), String.format("%.4f", relevant.get(0).similarityScore()));

        // Batch fetch document titles
        java.util.Set<Long> docIds = relevant.stream().map(VectorSearchHit::documentId).collect(java.util.stream.Collectors.toSet());
        java.util.Map<Long, String> titleCache = new java.util.HashMap<>();
        documentRepository.findAllById(docIds).forEach(d -> titleCache.put(d.getId(), d.getTitle()));

        StringBuilder sb = new StringBuilder();
        sb.append("以下历史优秀纪要是您的风格参照，请严格模仿其写作风格、语气和结构进行改写。\n\n");
        sb.append("风格参考示例：\n");

        for (int i = 0; i < relevant.size(); i++) {
            VectorSearchHit v = relevant.get(i);
            String source = titleCache.getOrDefault(v.documentId(), "未知文档");
            if (i > 0) sb.append("\n---\n");
            sb.append("【示例 ").append(i + 1).append(" 来自：").append(source).append("】\n");
            sb.append(cleanExampleContent(v.content()));
        }

        sb.append("\n\n以上示例的写作风格具有最高优先级，请严格遵循其风格进行改写，确保行文风格高度一致。");
        return sb.toString();
    }

    static String cleanExampleContent(String content) {
        if (content == null || content.isBlank()) return content;
        String cleaned = content.replace('\t', ' ');
        cleaned = cleaned.replaceAll(" +", " ");
        cleaned = cleaned.lines()
                .map(String::trim)
                .collect(java.util.stream.Collectors.joining("\n"));
        return cleaned;
    }

    public String buildFullDocumentReferences(String query, List<Long> excludeFileIds, List<Long> manualReferenceIds) {
        java.util.Set<Long> documentIds = new java.util.LinkedHashSet<>();

        if (manualReferenceIds != null) {
            documentIds.addAll(manualReferenceIds);
        }

        // RAG search for additional style examples
        float[] queryEmbedding = embeddingService.generateEmbedding(query);
        String embeddingStr = "[" + floatArrayToString(queryEmbedding) + "]";
        List<VectorSearchHit> examples = chunkRepository.styleExemplarSearch(embeddingStr, MAX_EXAMPLES * 3);

        if (excludeFileIds != null && !excludeFileIds.isEmpty()) {
            examples = examples.stream()
                    .filter(h -> !excludeFileIds.contains(h.documentId()))
                    .toList();
        }

        if (!examples.isEmpty() && examples.get(0).similarityScore() >= SIMILARITY_THRESHOLD) {
            for (VectorSearchHit v : examples) {
                if (v.similarityScore() >= SIMILARITY_THRESHOLD) {
                    documentIds.add(v.documentId());
                }
            }
        }

        if (documentIds.isEmpty()) {
            log.info("buildFullDocumentReferences: no documents found (manual={}, rag={})",
                    manualReferenceIds != null ? manualReferenceIds.size() : 0, examples.size());
            return "";
        }

        log.info("buildFullDocumentReferences: {} unique documents to load", documentIds.size());

        StringBuilder sb = new StringBuilder();
        sb.append("以下历史会议记录是您的格式和风格参照，请严格按照其格式进行输出。\n\n");
        sb.append("输出格式示例（请严格按照此格式输出）：\n");

        int idx = 0;
        for (Long docId : documentIds) {
            String fullContent = loadFullDocumentContent(docId);
            if (fullContent == null || fullContent.isBlank()) {
                log.warn("buildFullDocumentReferences: document {} has no readable content, skipping", docId);
                continue;
            }

            if (fullContent.length() > MAX_DOCUMENT_PREVIEW) {
                fullContent = fullContent.substring(0, MAX_DOCUMENT_PREVIEW)
                        + "\n...（内容较长，仅展示前" + MAX_DOCUMENT_PREVIEW + "字符）";
            }

            if (idx > 0) sb.append("\n---\n");
            String title = documentRepository.findById(docId)
                    .map(DocumentEntity::getTitle)
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

    private String loadFullDocumentContent(Long documentId) {
        return documentRepository.findById(documentId)
                .map(doc -> {
                    // Priority 1: transcription field (stored full text)
                    if (doc.getTranscription() != null && !doc.getTranscription().isBlank()
                            && !"{}".equals(doc.getTranscription().trim())) {
                        return doc.getTranscription();
                    }
                    // Priority 2: read from file on disk
                    if (doc.getFilePath() != null) {
                        java.nio.file.Path filePath = java.nio.file.Path.of(doc.getFilePath());
                        if (java.nio.file.Files.exists(filePath)) {
                            String ext = getExtension(doc.getTitle());
                            try {
                                if (TEXT_EXTENSIONS.contains(ext)) {
                                    return java.nio.file.Files.readString(filePath, java.nio.charset.StandardCharsets.UTF_8);
                                }
                                if (DOC_EXTENSIONS.contains(ext)) {
                                    return com.meeting.document.service.DocumentTextExtractor.extractText(filePath, ext);
                                }
                            } catch (Exception e) {
                                log.warn("Failed to read file for document {}: {}", documentId, e.getMessage());
                            }
                        }
                    }
                    return null;
                })
                .orElse(null);
    }

    private String getExtension(String filename) {
        int dot = filename.lastIndexOf('.');
        return dot >= 0 ? filename.substring(dot).toLowerCase() : "";
    }

    private String floatArrayToString(float[] arr) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < arr.length; i++) {
            if (i > 0) sb.append(",");
            sb.append(arr[i]);
        }
        return sb.toString();
    }
}
