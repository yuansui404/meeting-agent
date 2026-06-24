package com.meeting.service;

import com.meeting.common.DocumentTextExtractor;
import com.meeting.entity.Dialogue;
import com.meeting.entity.MeetingMinutes;
import com.meeting.repository.DialogueRepository;
import com.meeting.repository.MeetingMinutesRepository;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.formatter.openai.DeepSeekFormatter;
import io.agentscope.core.message.*;
import io.agentscope.core.model.OpenAIChatModel;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.harness.agent.HarnessAgent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.context.config.annotation.RefreshScope;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

@Service
@RefreshScope
public class ChatService {

    private static final Logger log = LoggerFactory.getLogger(ChatService.class);
    private static final String SYSTEM_PROMPT = "你是智能助手，回答简洁准确。";
    private static final double RELEVANCE_THRESHOLD = 0.55;
    private static final long MAX_FILE_SIZE = 10 * 1024 * 1024; // 10MB limit for file reading

    private static final Set<String> IMAGE_FORMATS = Set.of(".jpg", ".jpeg", ".png", ".gif", ".bmp", ".webp", ".svg");
    private static final Set<String> TEXT_FORMATS = Set.of(".txt", ".md", ".csv", ".json", ".xml", ".html", ".yaml", ".yml", ".properties");
    private static final Set<String> DOC_FORMATS = Set.of(".pdf", ".doc", ".docx");

    private final HarnessAgent agent;
    private final DialogueService dialogueService;
    private final DialogueRepository dialogueRepository;
    private final MeetingMinutesRepository meetingRepository;
    private final VectorizationService vectorizationService;
    private final ExecutorService executor = Executors.newCachedThreadPool();

    private final String deepseekApiKey;
    private final String zhipuApiKey;
    private final String zhipuModel;
    private final String zhipuUrl;

    public ChatService(@Value("${deepseek.api-key:}") String apiKey,
                       @Value("${deepseek.model:deepseek-chat}") String modelName,
                       @Value("${deepseek.url:https://api.deepseek.com}") String apiUrl,
                       @Value("${zhipu.api-key:}") String zhipuApiKey,
                       @Value("${zhipu.model:glm-4v}") String zhipuModel,
                       @Value("${zhipu.url:https://open.bigmodel.cn/api/paas/v4}") String zhipuUrl,
                       DialogueService dialogueService,
                       DialogueRepository dialogueRepository,
                       MeetingMinutesRepository meetingRepository,
                       VectorizationService vectorizationService) {
        this.dialogueService = dialogueService;
        this.dialogueRepository = dialogueRepository;
        this.meetingRepository = meetingRepository;
        this.vectorizationService = vectorizationService;
        this.deepseekApiKey = apiKey;
        this.zhipuApiKey = zhipuApiKey;
        this.zhipuModel = zhipuModel;
        this.zhipuUrl = zhipuUrl;

        OpenAIChatModel model = OpenAIChatModel.builder()
                .apiKey(apiKey)
                .modelName(modelName)
                .baseUrl(apiUrl)
                .formatter(new DeepSeekFormatter())
                .stream(true)
                .build();

        this.agent = HarnessAgent.builder()
                .name("MeetingAssistant")
                .sysPrompt(SYSTEM_PROMPT)
                .model(model)
                .toolkit(new Toolkit())
                .disableMemoryHooks()
                .disableFilesystemTools()
                .build();
    }

    public void streamChat(Long dialogueId, String userMessage, String metadata, SseEmitter emitter) {
        executor.submit(() -> {
            try {
                // 1. Save user message to DB with file metadata
                dialogueService.addMessage(dialogueId, "user", userMessage, "text", metadata);

                // 2. Build file blocks (text + images) and RAG context
                List<ContentBlock> fileBlocks = buildFileBlocks(dialogueId);
                boolean hasImages = fileBlocks.stream().anyMatch(b -> b instanceof ImageBlock);
                String ragContext = buildRagContext(dialogueId, userMessage);

                // 3. ZhiPu only for multimodal (images), DeepSeek for all text
                if (hasImages) {
                    streamChatWithZhipu(dialogueId, userMessage, ragContext, fileBlocks, emitter);
                } else {
                    streamChatWithDeepSeek(dialogueId, userMessage, ragContext, fileBlocks, emitter);
                }
            } catch (Exception e) {
                handleStreamError(emitter, e);
            }
        });
    }

    private void streamChatWithDeepSeek(Long dialogueId, String userMessage,
                                        String ragContext, List<ContentBlock> fileBlocks,
                                        SseEmitter emitter) {
        StringBuilder fullResponse = new StringBuilder();
        try {
            UserMessage msg;
            String fileContext = buildFileTextContext(dialogueId);
            if (!fileBlocks.isEmpty()) {
                String enriched = buildEnrichedMessage(ragContext, null, userMessage);
                List<ContentBlock> blocks = new ArrayList<>();
                blocks.add(TextBlock.builder().text(enriched).build());
                blocks.addAll(fileBlocks);
                msg = new UserMessage(blocks);
            } else {
                String enriched = buildEnrichedMessage(ragContext, fileContext, userMessage);
                msg = new UserMessage(enriched);
            }

            RuntimeContext ctx = RuntimeContext.builder()
                    .sessionId("dialogue-" + dialogueId)
                    .build();

            final boolean[] clientDisconnected = {false};
            agent.streamEvents(msg, ctx)
                    .doOnNext(event -> {
                        if (event instanceof TextBlockDeltaEvent e) {
                            String delta = e.getDelta();
                            fullResponse.append(delta);
                            if (!clientDisconnected[0]) {
                                try {
                                    emitter.send(SseEmitter.event().data(delta));
                                } catch (IOException ex) {
                                    clientDisconnected[0] = true;
                                    log.info("Client disconnected during DeepSeek stream, continuing to accumulate response");
                                }
                            }
                        }
                    })
                    .blockLast();

            if (!clientDisconnected[0]) {
                emitter.send(SseEmitter.event().name("done").data(""));
                emitter.complete();
            }
        } catch (Exception e) {
            handleStreamError(emitter, e);
        } finally {
            if (fullResponse.length() > 0) {
                try {
                    dialogueService.addMessage(dialogueId, "assistant", fullResponse.toString(), "text");
                } catch (Exception ignored) {}
            }
        }
    }

    private void streamChatWithZhipu(Long dialogueId, String userMessage,
                                     String ragContext, List<ContentBlock> fileBlocks,
                                     SseEmitter emitter) {
        StringBuilder fullResponse = new StringBuilder();
        try {
            List<Map<String, Object>> messages = new ArrayList<>();

            String fileContext = buildFileTextContext(dialogueId);
            String enriched = buildEnrichedMessage(ragContext, fileContext, userMessage);

            List<Map<String, Object>> contentParts = new ArrayList<>();
            contentParts.add(Map.of("type", "text", "text", enriched));

            // Add images only (text file content is already in enriched)
            boolean hasImages = false;
            for (ContentBlock block : fileBlocks) {
                if (block instanceof ImageBlock ib) {
                    hasImages = true;
                    Source source = ib.getSource();
                    if (source instanceof Base64Source b64) {
                        contentParts.add(Map.of(
                                "type", "image_url",
                                "image_url", Map.of("url",
                                        "data:" + b64.getMediaType() + ";base64," + b64.getData())
                        ));
                    }
                }
            }

            messages.add(Map.of("role", "system", "content", SYSTEM_PROMPT));
            messages.add(Map.of("role", "user", "content", contentParts));

            // Build the request body
            Map<String, Object> requestBody = new LinkedHashMap<>();
            requestBody.put("model", zhipuModel);
            requestBody.put("messages", messages);
            requestBody.put("stream", true);

            String jsonBody = objectToJson(requestBody);

            // Make HTTP request to ZhiPu API
            HttpURLConnection conn = (HttpURLConnection) URI.create(zhipuUrl + "/chat/completions").toURL().openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setRequestProperty("Authorization", "Bearer " + zhipuApiKey);
            conn.setDoOutput(true);
            conn.setConnectTimeout(30000);
            conn.setReadTimeout(120000);

            try (OutputStream os = conn.getOutputStream()) {
                os.write(jsonBody.getBytes(StandardCharsets.UTF_8));
            }

            int responseCode = conn.getResponseCode();
            if (responseCode != 200) {
                String errorBody = new String(conn.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
                try {
                    emitter.send(SseEmitter.event().name("error")
                            .data("智谱API请求失败: HTTP " + responseCode + " - " + errorBody));
                } catch (IOException ignored) {}
                emitter.complete();
                return;
            }

            // Stream the SSE response, continue accumulating even if client disconnects
            boolean clientDisconnected = false;
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.startsWith("data: ")) {
                        String data = line.substring(6).trim();
                        if ("[DONE]".equals(data)) break;
                        // Parse delta content from the JSON
                        String delta = extractZhipuDelta(data);
                        if (delta != null && !delta.isEmpty()) {
                            fullResponse.append(delta);
                            if (!clientDisconnected) {
                                try {
                                    emitter.send(SseEmitter.event().data(delta));
                                } catch (IOException ex) {
                                    clientDisconnected = true;
                                    log.info("Client disconnected during ZhiPu stream, continuing to accumulate response");
                                }
                            }
                        }
                    }
                }
            }

            if (!clientDisconnected) {
                emitter.send(SseEmitter.event().name("done").data(""));
                emitter.complete();
            }

        } catch (Exception e) {
            try {
                emitter.send(SseEmitter.event().name("error").data("智谱调用失败: " + e.getMessage()));
            } catch (IOException ignored) {}
            emitter.completeWithError(e);
        } finally {
            if (fullResponse.length() > 0) {
                try {
                    dialogueService.addMessage(dialogueId, "assistant", fullResponse.toString(), "text");
                } catch (Exception ignored) {}
            }
        }
    }

    private String extractZhipuDelta(String jsonData) {
        // Parse ZhiPu/OpenAI SSE delta: {"choices":[{"delta":{"content":"..."}}]}
        try {
            int contentStart = jsonData.indexOf("\"content\":\"");
            if (contentStart < 0) return "";
            contentStart += 11; // length of "content":"
            int contentEnd = jsonData.indexOf("\"", contentStart);
            if (contentEnd < 0) return "";
            String content = jsonData.substring(contentStart, contentEnd);
            // Unescape JSON string
            return content.replace("\\n", "\n").replace("\\t", "\t")
                    .replace("\\\"", "\"").replace("\\\\", "\\");
        } catch (Exception e) {
            return "";
        }
    }

    private String objectToJson(Object obj) {
        // Simple JSON serialization for our use case
        if (obj instanceof Map map) {
            StringBuilder sb = new StringBuilder("{");
            boolean first = true;
            for (var entry : (Set<Map.Entry>) map.entrySet()) {
                if (!first) sb.append(",");
                first = false;
                sb.append("\"").append(escapeJson(entry.getKey().toString())).append("\":");
                sb.append(objectToJson(entry.getValue()));
            }
            sb.append("}");
            return sb.toString();
        } else if (obj instanceof List list) {
            StringBuilder sb = new StringBuilder("[");
            boolean first = true;
            for (var item : list) {
                if (!first) sb.append(",");
                first = false;
                sb.append(objectToJson(item));
            }
            sb.append("]");
            return sb.toString();
        } else if (obj instanceof String s) {
            return "\"" + escapeJson(s) + "\"";
        } else if (obj instanceof Boolean b) {
            return b.toString();
        } else if (obj instanceof Number n) {
            return n.toString();
        }
        return "null";
    }

    private String escapeJson(String s) {
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }

    private void handleStreamError(SseEmitter emitter, Exception e) {
        try {
            String msg = e instanceof RuntimeException && e.getCause() != null
                    ? e.getCause().getMessage()
                    : e.getMessage();
            emitter.send(SseEmitter.event().name("error").data(msg != null ? msg : "Unknown error"));
        } catch (IOException ignored) {
        }
        try {
            emitter.completeWithError(e);
        } catch (Exception ignored) {
        }
    }

    private String buildEnrichedMessage(String ragContext, String fileContext, String userMessage) {
        boolean hasContext = (fileContext != null && !fileContext.isEmpty())
                || (ragContext != null && !ragContext.isEmpty());
        if (!hasContext) return userMessage;

        StringBuilder sb = new StringBuilder();
        sb.append("请分析以下资料来回答问题。\n");
        sb.append("要求：答案必须直接引用资料中的原文，不得添加资料中没有的信息。\n");
        if (fileContext != null && !fileContext.isEmpty()) {
            sb.append("\n文件内容：\n").append(fileContext);
        }
        if (ragContext != null && !ragContext.isEmpty()) {
            sb.append("\n知识库内容：\n").append(ragContext);
        }
        sb.append("\n\n问题：").append(userMessage);
        return sb.toString();
    }

    private String buildRagContext(Long dialogueId, String userMessage) {
        try {
            Optional<Dialogue> dialogueOpt = dialogueRepository.findById(dialogueId);
            if (dialogueOpt.isEmpty()) return "";

            Dialogue dialogue = dialogueOpt.get();
            Long meetingId = dialogue.getMeetingId();
            boolean isImported = Boolean.TRUE.equals(dialogue.getImported());

            List<VectorizationService.ScoredVector> scoredVectors;
            if (meetingId != null) {
                scoredVectors = vectorizationService.searchSimilarByMeetingWithScores(meetingId, userMessage, 5);
            } else if (isImported) {
                scoredVectors = vectorizationService.searchSimilarWithScores(userMessage, 5);
            } else {
                scoredVectors = vectorizationService.searchSimilarWithScores(userMessage, 3);
            }

            if (scoredVectors.isEmpty()) return "";

            // Relevance filter: skip RAG if the best match is below threshold
            double bestScore = scoredVectors.get(0).similarity();
            log.info("RAG check: best similarity={}, threshold={}, query='{}'",
                    String.format("%.3f", bestScore), RELEVANCE_THRESHOLD, userMessage);
            if (bestScore < RELEVANCE_THRESHOLD) {
                return "";
            }

            log.info("RAG triggered: best similarity={}, chunks={}, query='{}'",
                    String.format("%.3f", bestScore), scoredVectors.size(), userMessage);

            // Only include vectors above threshold
            List<VectorizationService.ScoredVector> relevant = scoredVectors.stream()
                    .filter(v -> v.similarity() >= RELEVANCE_THRESHOLD)
                    .toList();

            if (relevant.isEmpty()) return "";

            // Build a title lookup cache for source attribution
            Map<Long, String> titleCache = new HashMap<>();
            for (VectorizationService.ScoredVector v : relevant) {
                titleCache.computeIfAbsent(v.meetingId(),
                        id -> meetingRepository.findById(id)
                                .map(MeetingMinutes::getTitle)
                                .orElse("未知文件"));
            }

            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < relevant.size(); i++) {
                VectorizationService.ScoredVector v = relevant.get(i);
                String source = titleCache.get(v.meetingId());
                if (i > 0) sb.append("\n---\n");
                sb.append("【来源：").append(source).append("】");
                if (v.chunkIndex() != null) {
                    sb.append("（片段 ").append(v.chunkIndex()).append("）");
                }
                sb.append("\n").append(v.content());
            }
            return sb.toString();
        } catch (Exception e) {
            log.warn("RAG context build failed: {}", e.getMessage());
            return "";
        }
    }

    private String buildFileTextContext(Long dialogueId) {
        try {
            List<MeetingMinutes> files = meetingRepository.findByDialogueId(dialogueId);
            if (files.isEmpty()) return "";

            StringBuilder sb = new StringBuilder();
            for (MeetingMinutes f : files) {
                if (!"completed".equals(f.getStatus())) continue;
                String ext = FileProcessingService.getExtension(f.getTitle()).toLowerCase();
                if (IMAGE_FORMATS.contains(ext)) continue;

                if (f.getFileSize() != null && f.getFileSize() > MAX_FILE_SIZE) {
                    sb.append("【来源：").append(f.getTitle()).append("】（文件过大，跳过内容提取）\n");
                    continue;
                }

                String content = extractFileContent(Path.of(f.getFilePath()), ext);
                if (content != null && !content.isBlank()) {
                    String preview = content.length() > 2000 ? content.substring(0, 2000) + "..." : content;
                    sb.append("【来源：").append(f.getTitle()).append("】\n").append(preview).append("\n\n");
                } else {
                    sb.append("【来源：").append(f.getTitle()).append("】（无法提取文字内容）\n");
                }
            }
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }

    private List<ContentBlock> buildFileBlocks(Long dialogueId) {
        try {
            List<MeetingMinutes> files = meetingRepository.findByDialogueId(dialogueId);
            if (files.isEmpty()) return List.of();

            List<ContentBlock> blocks = new ArrayList<>();
            StringBuilder textIntro = new StringBuilder("以下是用户上传的文件：\n");

            for (MeetingMinutes f : files) {
                if (!"completed".equals(f.getStatus())) continue;
                String ext = FileProcessingService.getExtension(f.getTitle()).toLowerCase();
                Path filePath = Path.of(f.getFilePath());
                if (!Files.exists(filePath)) continue;

                if (IMAGE_FORMATS.contains(ext)) {
                    if (!textIntro.isEmpty()) {
                        blocks.add(TextBlock.builder().text(textIntro.toString()).build());
                        textIntro.setLength(0);
                    }
                    String mediaType = getImageMimeType(ext);
                    String base64 = Base64.getEncoder().encodeToString(Files.readAllBytes(filePath));
                    blocks.add(new ImageBlock(new Base64Source(mediaType, base64)));
                    textIntro.append("- ").append(f.getTitle()).append("（图片）\n");
                } else {
                    if (f.getFileSize() != null && f.getFileSize() > MAX_FILE_SIZE) {
                        textIntro.append("- ").append(f.getTitle()).append("（文件过大，跳过内容提取）\n");
                        continue;
                    }
                    String content = extractFileContent(filePath, ext);
                    if (content != null && !content.isBlank()) {
                        String preview = content.length() > 2000 ? content.substring(0, 2000) + "..." : content;
                        textIntro.append("- ").append(f.getTitle()).append("：\n").append(preview).append("\n");
                    } else {
                        textIntro.append("- ").append(f.getTitle()).append("（无法提取文字内容）\n");
                    }
                }
            }

            if (!textIntro.isEmpty()) {
                blocks.add(TextBlock.builder().text(textIntro.toString()).build());
            }

            return blocks;
        } catch (Exception e) {
            return List.of();
        }
    }

    private String extractFileContent(Path filePath, String ext) {
        try {
            if (TEXT_FORMATS.contains(ext)) {
                return Files.readString(filePath, StandardCharsets.UTF_8);
            }
            if (DOC_FORMATS.contains(ext)) {
                return DocumentTextExtractor.extractText(filePath, ext);
            }
            return null;
        } catch (Exception e) {
            return null;
        }
    }

    private String getImageMimeType(String ext) {
        return switch (ext) {
            case ".jpg", ".jpeg" -> "image/jpeg";
            case ".png" -> "image/png";
            case ".gif" -> "image/gif";
            case ".webp" -> "image/webp";
            case ".bmp" -> "image/bmp";
            case ".svg" -> "image/svg+xml";
            default -> "image/png";
        };
    }
}
