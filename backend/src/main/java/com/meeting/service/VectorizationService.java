package com.meeting.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.meeting.config.DeepSeekChatClient;
import com.meeting.entity.MeetingMinutes;
import com.meeting.entity.MeetingVector;
import com.meeting.repository.MeetingMinutesRepository;
import com.meeting.repository.MeetingVectorRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class VectorizationService {

    private static final Logger log = LoggerFactory.getLogger(VectorizationService.class);
    private static final int MAX_CHUNK_SIZE = 1500;
    private static final int CHUNK_OVERLAP = 50;

    /**
     * Result of vector search with cosine similarity score.
     * similarity ranges from 0 (completely dissimilar) to 1 (identical).
     */
    public record ScoredVector(Long id, Long meetingId, String content, Integer chunkIndex, double similarity) {}

    /**
     * Search vectors weighted by priority_score for style example retrieval.
     * Result ordered by: similarity * (1 + 0.2 * COALESCE(priority_score, 0)) DESC
     *
     * @param excludeFileIds  if non-empty, exclude vectors belonging to these meeting_ids
     */
    public List<ScoredVector> searchStyleExamples(String query, int limit, List<Long> excludeFileIds) {
        try {
            float[] queryEmbedding = embeddingService.generateEmbedding(query);
            String embeddingStr = formatVector(queryEmbedding);

            // Query limit * 3 to expand candidate pool for randomization
            int queryLimit = limit * 3;

            StringBuilder sql = new StringBuilder();
            List<Object> params = new ArrayList<>();
            params.add(embeddingStr);

            sql.append("SELECT mv.id, mv.meeting_id, mv.content, mv.chunk_index, ")
                    .append("  1 - (mv.embedding <=> CAST(? AS vector)) AS similarity, ")
                    .append("  mm.style_exemplar ")
                    .append("FROM meeting_vectors mv ")
                    .append("JOIN meeting_minutes mm ON mv.meeting_id = mm.id ");

            if (excludeFileIds != null && !excludeFileIds.isEmpty()) {
                String placeholders = excludeFileIds.stream().map(id -> "?").collect(Collectors.joining(","));
                sql.append("WHERE mv.meeting_id NOT IN (").append(placeholders).append(") ");
                params.addAll(excludeFileIds);
            }

            sql.append("ORDER BY mm.style_exemplar DESC, ")
                    .append("  (1 - (mv.embedding <=> CAST(? AS vector))) * (1 + 0.2 * COALESCE(mv.priority_score, 0)) DESC ")
                    .append("LIMIT ?");
            params.add(embeddingStr);
            params.add(queryLimit);

            List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql.toString(), params.toArray());
            log.info("searchStyleExamples returned {} rows for query '{}'", rows.size(), query.length() > 50 ? query.substring(0, 50) + "..." : query);

            // Separate starred and non-starred for randomization
            List<ScoredVector> starred = new ArrayList<>();
            List<ScoredVector> nonStarred = new ArrayList<>();

            for (Map<String, Object> r : rows) {
                ScoredVector sv = new ScoredVector(
                        ((Number) r.get("id")).longValue(),
                        ((Number) r.get("meeting_id")).longValue(),
                        (String) r.get("content"),
                        r.get("chunk_index") != null ? ((Number) r.get("chunk_index")).intValue() : null,
                        ((Number) r.get("similarity")).doubleValue()
                );
                boolean isStarred = r.get("style_exemplar") != null && Boolean.TRUE.equals(r.get("style_exemplar"));
                if (isStarred) {
                    starred.add(sv);
                } else {
                    nonStarred.add(sv);
                }
            }

            // Shuffle both groups for randomness
            Collections.shuffle(starred);
            Collections.shuffle(nonStarred);

            // Combine: starred first, then non-starred, take top limit
            List<ScoredVector> result = new ArrayList<>();
            result.addAll(starred);
            result.addAll(nonStarred);
            if (result.size() > limit) {
                result = result.subList(0, limit);
            }

            return result;
        } catch (Exception e) {
            log.error("Style example search failed for query '{}': {}", query, e.getMessage(), e);
            return List.of();
        }
    }

    private final MeetingMinutesRepository meetingRepository;
    private final MeetingVectorRepository vectorRepository;
    private final EmbeddingService embeddingService;
    private final JdbcTemplate jdbcTemplate;
    private final DeepSeekChatClient deepSeekChatClient;
    private final String uploadDir;

    public VectorizationService(MeetingMinutesRepository meetingRepository,
                                MeetingVectorRepository vectorRepository,
                                EmbeddingService embeddingService,
                                JdbcTemplate jdbcTemplate,
                                DeepSeekChatClient deepSeekChatClient,
                                @Value("${file.upload-dir:/app/data/uploads}") String uploadDir) {
        this.meetingRepository = meetingRepository;
        this.vectorRepository = vectorRepository;
        this.embeddingService = embeddingService;
        this.jdbcTemplate = jdbcTemplate;
        this.deepSeekChatClient = deepSeekChatClient;
        this.uploadDir = uploadDir;
    }

    @Transactional
    public void vectorizeMeeting(Long meetingId) {
        MeetingMinutes meeting = meetingRepository.findById(meetingId)
                .orElseThrow(() -> new IllegalArgumentException("Meeting not found: " + meetingId));

        String transcription = meeting.getTranscription();
        if (transcription == null || transcription.isBlank()) {
            throw new IllegalStateException("Meeting has no transcription: " + meetingId);
        }

        // Remove old vectors for this meeting (JdbcTemplate, since Hibernate cannot handle VECTOR columns)
        jdbcTemplate.update("DELETE FROM meeting_vectors WHERE meeting_id = ?", meetingId);

        // Chunk and vectorize
        List<String> chunks = chunkText(transcription);
        List<MeetingVector> vectors = new ArrayList<>();

        for (int i = 0; i < chunks.size(); i++) {
            String chunk = chunks.get(i);
            float[] embedding = embeddingService.generateEmbedding(chunk);

            MeetingVector mv = new MeetingVector();
            mv.setMeetingId(meetingId);
            mv.setContent(chunk);
            mv.setEmbedding(embedding);
            mv.setChunkIndex(i);
            vectors.add(mv);
        }

        vectorRepository.saveAll(vectors);

        // Extract participants and create dedicated vector chunk (chunk_index = -1)
        try {
            String participants = extractParticipantsFromTranscription(transcription);
            if (participants != null && !participants.isBlank()) {
                String participantContent = "与会人: " + participants;
                float[] pEmbedding = embeddingService.generateEmbedding(participantContent);

                MeetingVector pv = new MeetingVector();
                pv.setMeetingId(meetingId);
                pv.setContent(participantContent);
                pv.setEmbedding(pEmbedding);
                pv.setChunkIndex(-1);
                vectorRepository.save(pv);

                meeting.setParticipants(participants);
            }
        } catch (Exception e) {
            log.warn("Failed to create participant vector for meeting {}: {}", meetingId, e.getMessage());
        }

        // Collect participants into global 与会人.md
        collectParticipants(meeting);
    }

    /**
     * Extract participant names from meeting transcription via LLM,
     * merge into the global 与会人.md file (deduplicated).
     */
    private void collectParticipants(MeetingMinutes meeting) {
        String transcription = meeting.getTranscription();
        if (transcription == null || transcription.isBlank()) return;

        try {
            String sample = transcription.length() > 3000 ? transcription.substring(0, 3000) : transcription;

            List<Map<String, String>> messages = List.of(
                    Map.of("role", "system", "content",
                            "你是一个会议助手。从以下会议纪要文本中提取'与会人'栏目的名单。"
                                    + "返回逗号分隔的姓名列表，不要其他任何内容。"
                                    + "如果找不到与会人列表，返回空字符串。"),
                    Map.of("role", "user", "content", sample)
            );

            JsonNode response = deepSeekChatClient.chat(messages, false);
            String extracted = extractContentText(response);
            if (extracted == null || extracted.isBlank() || "null".equals(extracted.trim())) {
                log.info("collectParticipants: no participants found for meeting {}", meeting.getId());
                return;
            }

            // Parse names (separated by Chinese/English commas or 、)
            String[] newNames = extracted.split("[，,、]");
            Set<String> newNameSet = new LinkedHashSet<>();
            for (String name : newNames) {
                String trimmed = name.trim();
                if (trimmed.length() >= 2 && trimmed.length() <= 4) {
                    newNameSet.add(trimmed);
                }
            }
            if (newNameSet.isEmpty()) {
                log.info("collectParticipants: extracted empty name list for meeting {}", meeting.getId());
                return;
            }

            // Persist to DB (this meeting's participants only)
            String participantsStr = String.join("、", newNameSet);
            meeting.setParticipants(participantsStr);
            meetingRepository.save(meeting);

            // Read existing 与会人.md
            Path participantsFile = Path.of(uploadDir, "profile", "与会人.md");
            Set<String> existingNames = new LinkedHashSet<>();
            if (Files.exists(participantsFile)) {
                List<String> lines = Files.readAllLines(participantsFile, StandardCharsets.UTF_8);
                for (String line : lines) {
                    String trimmed = line.trim();
                    if (trimmed.startsWith("- ")) {
                        existingNames.add(trimmed.substring(2).trim());
                    }
                }
            }

            // Merge new names
            int addedCount = 0;
            for (String name : newNameSet) {
                if (!existingNames.contains(name)) {
                    existingNames.add(name);
                    addedCount++;
                }
            }
            if (addedCount == 0) {
                log.info("collectParticipants: no new names to add for meeting {}", meeting.getId());
                return;
            }

            // Write back
            StringBuilder content = new StringBuilder();
            content.append("# 公司常与会人名单\n\n");
            content.append("以下名单从历史会议纪要中提取，用于校对时验证人名准确性。\n\n");
            for (String name : existingNames) {
                content.append("- ").append(name).append("\n");
            }

            Files.createDirectories(participantsFile.getParent());
            Files.writeString(participantsFile, content.toString(), StandardCharsets.UTF_8);
            log.info("collectParticipants: updated 与会人.md with {} names (+{} new) from meeting {}",
                    existingNames.size(), addedCount, meeting.getId());

        } catch (Exception e) {
            log.warn("collectParticipants failed for meeting {}: {}", meeting.getId(), e.getMessage());
        }
    }

    /**
     * Backfill participants for existing knowledge-base meetings:
     * extract from transcription, create/update chunk_index=-1 vector, persist to DB.
     */
    @Transactional
    public void backfillParticipants(Long meetingId) {
        MeetingMinutes meeting = meetingRepository.findById(meetingId)
                .orElseThrow(() -> new IllegalArgumentException("Meeting not found: " + meetingId));

        String transcription = meeting.getTranscription();
        if (transcription == null || transcription.isBlank()) {
            log.info("backfillParticipants: meeting {} has no transcription, skipping", meetingId);
            return;
        }

        // Extract via regex
        String participants = extractParticipantsFromTranscription(transcription);
        if (participants == null || participants.isBlank()) {
            log.info("backfillParticipants: no participants found in transcription for meeting {}", meetingId);
            return;
        }

        // Persist to DB
        meeting.setParticipants(participants);
        meetingRepository.save(meeting);

        // Remove old chunk_index=-1 vector if exists
        jdbcTemplate.update("DELETE FROM meeting_vectors WHERE meeting_id = ? AND chunk_index = -1", meetingId);

        // Create dedicated vector chunk
        String participantContent = "与会人: " + participants;
        float[] embedding = embeddingService.generateEmbedding(participantContent);
        MeetingVector mv = new MeetingVector();
        mv.setMeetingId(meetingId);
        mv.setContent(participantContent);
        mv.setEmbedding(embedding);
        mv.setChunkIndex(-1);
        vectorRepository.save(mv);

        log.info("backfillParticipants: updated meeting {} with participants '{}'", meetingId, participants);
    }

    private String extractContentText(JsonNode response) {
        try {
            return response.get("choices").get(0).get("message").get("content").asText();
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Extract participant names from transcription text using regex.
     * Looks for patterns like "与会人  张力、杜伟、高玉坤" or "与会人：张三、李四".
     */
    static String extractParticipantsFromTranscription(String transcription) {
        if (transcription == null || transcription.isBlank()) return null;
        java.util.regex.Pattern pattern = java.util.regex.Pattern.compile(
                "与会人[：: \\t]+([^\\n]+)", java.util.regex.Pattern.CASE_INSENSITIVE);
        java.util.regex.Matcher matcher = pattern.matcher(transcription);
        if (matcher.find()) {
            String result = matcher.group(1).trim();
            return result.isBlank() ? null : result;
        }
        return null;
    }

    public List<MeetingVector> searchSimilar(String query, int limit) {
        try {
            float[] queryEmbedding = embeddingService.generateEmbedding(query);
            String embeddingStr = formatVector(queryEmbedding);
            List<Long> ids = vectorSearchIds(embeddingStr, limit);
            if (ids.isEmpty()) return List.of();
            return findVectorsByIdsPreservingOrder(ids);
        } catch (Exception e) {
            log.error("Vector search failed for query '{}': {}", query, e.getMessage(), e);
            return List.of();
        }
    }

    /**
     * Fetch MeetingVector rows by ID without the embedding column,
     * preserving the order from the input list.
     *
     * JPA findAllById fails because Hibernate cannot map VECTOR -> float[]
     * without a custom type registration, so we use JdbcTemplate instead.
     */
    private List<MeetingVector> findVectorsByIdsPreservingOrder(List<Long> ids) {
        if (ids.isEmpty()) return List.of();
        String placeholders = ids.stream().map(id -> "?").collect(Collectors.joining(","));
        String sql = "SELECT id, meeting_id, content, chunk_index, created_at FROM meeting_vectors WHERE id IN (" + placeholders + ")";
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql, ids.toArray());
        // index by id for ordering
        java.util.Map<Long, MeetingVector> byId = new java.util.HashMap<>();
        for (Map<String, Object> row : rows) {
            MeetingVector mv = new MeetingVector();
            mv.setId(((Number) row.get("id")).longValue());
            mv.setMeetingId(((Number) row.get("meeting_id")).longValue());
            mv.setContent((String) row.get("content"));
            mv.setChunkIndex(row.get("chunk_index") != null ? ((Number) row.get("chunk_index")).intValue() : null);
            // embedding intentionally omitted — VECTOR column cannot be read by JdbcTemplate without pgvector JDBC type
            byId.put(mv.getId(), mv);
        }
        List<MeetingVector> ordered = new ArrayList<>();
        for (Long id : ids) {
            MeetingVector mv = byId.get(id);
            if (mv != null) ordered.add(mv);
        }
        return ordered;
    }

    public List<MeetingVector> searchSimilarByMeeting(Long meetingId, String query, int limit) {
        try {
            float[] queryEmbedding = embeddingService.generateEmbedding(query);
            String embeddingStr = formatVector(queryEmbedding);
            List<Long> ids = vectorSearchIds(embeddingStr, limit * 3);
            if (ids.isEmpty()) return List.of();
            return findVectorsByIdsPreservingOrder(ids).stream()
                    .filter(v -> v.getMeetingId().equals(meetingId))
                    .limit(limit)
                    .toList();
        } catch (Exception e) {
            log.error("Vector search by meeting failed for query '{}', meeting {}: {}", query, meetingId, e.getMessage(), e);
            return List.of();
        }
    }

    /**
     * Search vectors with cosine similarity scores.
     * Returns records with similarity score (0-1), ordered by best match first.
     */
    public List<ScoredVector> searchSimilarWithScores(String query, int limit) {
        try {
            float[] queryEmbedding = embeddingService.generateEmbedding(query);
            String embeddingStr = formatVector(queryEmbedding);
            return searchVectorsWithScores(embeddingStr, null, limit);
        } catch (Exception e) {
            log.error("Vector search with scores failed for query '{}': {}", query, e.getMessage(), e);
            return List.of();
        }
    }

    /**
     * Search vectors within a specific meeting with similarity scores.
     */
    public List<ScoredVector> searchSimilarByMeetingWithScores(Long meetingId, String query, int limit) {
        try {
            float[] queryEmbedding = embeddingService.generateEmbedding(query);
            String embeddingStr = formatVector(queryEmbedding);
            return searchVectorsWithScores(embeddingStr, meetingId, limit);
        } catch (Exception e) {
            log.error("Vector search by meeting with scores failed for query '{}', meeting {}: {}", query, meetingId, e.getMessage(), e);
            return List.of();
        }
    }

    private List<ScoredVector> searchVectorsWithScores(String embeddingStr, Long meetingId, int limit) {
        String sql;
        Object[] params;
        if (meetingId != null) {
            sql = "SELECT id, meeting_id, content, chunk_index, "
                    + "1 - (embedding <=> CAST(? AS vector)) AS similarity "
                    + "FROM meeting_vectors WHERE meeting_id = ? ORDER BY similarity DESC LIMIT ?";
            params = new Object[]{embeddingStr, meetingId, limit};
        } else {
            sql = "SELECT id, meeting_id, content, chunk_index, "
                    + "1 - (embedding <=> CAST(? AS vector)) AS similarity "
                    + "FROM meeting_vectors ORDER BY similarity DESC LIMIT ?";
            params = new Object[]{embeddingStr, limit};
        }
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql, params);
        return rows.stream().map(r -> new ScoredVector(
                ((Number) r.get("id")).longValue(),
                ((Number) r.get("meeting_id")).longValue(),
                (String) r.get("content"),
                r.get("chunk_index") != null ? ((Number) r.get("chunk_index")).intValue() : null,
                ((Number) r.get("similarity")).doubleValue()
        )).toList();
    }

    private List<Long> vectorSearchIds(String embeddingStr, int limit) {
        String sql = "SELECT id FROM meeting_vectors ORDER BY 1 - (embedding <=> CAST(? AS vector)) DESC LIMIT ?";
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql, embeddingStr, limit);
        return rows.stream().map(r -> ((Number) r.get("id")).longValue()).toList();
    }

    private String formatVector(float[] embedding) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < embedding.length; i++) {
            if (i > 0) sb.append(",");
            sb.append(embedding[i]);
        }
        sb.append("]");
        return sb.toString();
    }

    /**
     * Chunk text into semantic sections. First attempts to split by meeting agenda
     * headings (关于/会议决策), then falls back to line-by-line section detection.
     * Within each section, splits by sentences if content exceeds MAX_CHUNK_SIZE.
     */
    static List<String> chunkText(String text) {
        List<String> chunks = new ArrayList<>();
        if (text == null || text.isBlank()) return chunks;

        // Step 1: collect raw sections via two strategies
        List<String> rawSections;

        // Strategy A: split by semantic headings (关于/会议决策)
        String[] byHeadings = text.split("\\n(?=关于|会议决策)");
        if (byHeadings.length >= 3) {
            // Good split detected — use heading-based sections
            rawSections = Arrays.asList(byHeadings);
        } else {
            // Strategy B: split body by lines, each substantive line is a section
            int bodyStart = text.indexOf("\n\n");
            String body = (bodyStart > 0) ? text.substring(bodyStart) : text;
            rawSections = new ArrayList<>();

            // Add header separately if present
            if (bodyStart > 0) {
                String header = text.substring(0, bodyStart).trim();
                if (!header.isEmpty()) rawSections.add(header);
            }

            for (String line : body.split("\n")) {
                line = line.trim();
                if (line.isBlank()) continue;
                // Skip structural lines (会议讨论, 议题...)
                if (line.startsWith("会议讨论") || line.startsWith("议题")) continue;
                rawSections.add(line);
            }
        }

        // Step 2: process each section — keep as is, or split by sentences
        for (String section : rawSections) {
            section = section.trim();
            if (section.isEmpty()) continue;
            appendChunk(chunks, section, MAX_CHUNK_SIZE);
        }

        return chunks;
    }

    private static void appendChunk(List<String> chunks, String text, int maxSize) {
        if (text.length() <= maxSize) {
            chunks.add(text);
            return;
        }
        String[] sentences = text.split("(?<=[。！？\\n])");
        StringBuilder buf = new StringBuilder();
        for (String s : sentences) {
            s = s.trim();
            if (s.isEmpty()) continue;
            if (buf.length() + s.length() <= maxSize) {
                if (buf.length() > 0) buf.append("\n");
                buf.append(s);
            } else {
                chunks.add(buf.toString());
                buf = new StringBuilder(s);
            }
        }
        if (buf.length() > 0) chunks.add(buf.toString());
    }
}
