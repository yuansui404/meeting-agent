package com.meeting.service;

import com.meeting.entity.MeetingMinutes;
import com.meeting.entity.RewriteResult;
import com.meeting.repository.MeetingMinutesRepository;
import com.meeting.repository.RewriteResultRepository;
import org.apache.poi.xwpf.usermodel.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

@Service
public class RewriteService {

    private static final Logger log = LoggerFactory.getLogger(RewriteService.class);
    private static final long MAX_DRAFT_SIZE = 10 * 1024 * 1024;
    private static final int MAX_TOKENS = 8000;
    private static final int MAX_FILE_PREVIEW = 4000;

    private static final Set<String> TEXT_FORMATS = Set.of(".txt", ".md", ".csv", ".json", ".xml", ".html", ".yaml", ".yml", ".properties");
    private static final Set<String> DOC_FORMATS = Set.of(".pdf", ".doc", ".docx");

    private final MeetingMinutesRepository meetingRepository;
    private final RewriteResultRepository rewriteResultRepository;
    private final DialogueService dialogueService;
    private final StyleLearningService styleLearningService;
    private final ExecutorService executor = Executors.newCachedThreadPool();

    private final String deepseekApiKey;
    private final String deepseekModel;
    private final String deepseekUrl;

    public RewriteService(@Value("${deepseek.api-key:}") String apiKey,
                          @Value("${deepseek.model:deepseek-chat}") String modelName,
                          @Value("${deepseek.url:https://api.deepseek.com}") String apiUrl,
                          MeetingMinutesRepository meetingRepository,
                          RewriteResultRepository rewriteResultRepository,
                          DialogueService dialogueService,
                          StyleLearningService styleLearningService) {
        this.meetingRepository = meetingRepository;
        this.rewriteResultRepository = rewriteResultRepository;
        this.dialogueService = dialogueService;
        this.styleLearningService = styleLearningService;
        this.deepseekApiKey = apiKey;
        this.deepseekModel = modelName;
        this.deepseekUrl = apiUrl;
    }

    public void streamRewrite(Long dialogueId, List<Long> sourceFileIds, List<Long> manualReferenceIds, SseEmitter emitter) {
        executor.submit(() -> {
            StringBuilder fullResponse = new StringBuilder();
            List<Long> referenceIds = new ArrayList<>();

            try {
                // 1. Load source file contents
                List<MeetingMinutes> sourceFiles = loadSourceFiles(sourceFileIds);
                if (sourceFiles.isEmpty()) {
                    emitter.send(SseEmitter.event().name("error").data("请先上传需要改写的文件"));
                    emitter.complete();
                    return;
                }

                String sourceContent = buildSourceContent(sourceFiles);
                if (sourceContent.length() > MAX_TOKENS) {
                    sourceContent = sourceContent.substring(0, MAX_TOKENS) + "\n...（内容过长，已截断至 " + MAX_TOKENS + " 字符）";
                }

                // 2. Get style examples
                List<Long> excludeIds = new ArrayList<>();
                if (manualReferenceIds == null || manualReferenceIds.isEmpty()) {
                    // Automatic retrieval: check if this dialogue has previous rewrites
                    List<RewriteResult> previous = rewriteResultRepository.findByDialogueIdOrderByVersionDesc(dialogueId);
                    if (!previous.isEmpty()) {
                        // "换一种风格" mode — exclude previous references
                        String prevRefs = previous.get(0).getReferenceIds();
                        if (prevRefs != null && !prevRefs.isEmpty()) {
                            try {
                                if (prevRefs.startsWith("[")) {
                                    excludeIds.addAll(parseJsonIdList(prevRefs));
                                }
                            } catch (Exception ignored) {}
                        }
                    }
                } else {
                    // Manual reference specified
                    referenceIds.addAll(manualReferenceIds);
                    excludeIds.addAll(manualReferenceIds);
                }

                String styleExamples = styleLearningService.buildStyleExamples(sourceContent, excludeIds);

                // 3. Build prompt
                String prompt = buildRewritePrompt(sourceContent, styleExamples, referenceIds);

                // 4. Call DeepSeek API (SSE streaming)
                String apiResponse = streamDeepSeek(prompt, emitter, fullResponse);

                // 5. Save rewrite result
                if (fullResponse.length() > 0) {
                    RewriteResult result = saveRewriteResult(dialogueId, sourceFileIds, referenceIds, fullResponse.toString());
                    String docxPath = generateComparisonDocx(sourceFiles, fullResponse.toString(), dialogueId, result.getVersion());

                    if (docxPath != null) {
                        result.setDocxPath(docxPath);
                        rewriteResultRepository.save(result);
                    }

                    // 6. Save assistant message with metadata
                    String meta = "{\"type\":\"rewrite\",\"rewriteResultId\":" + result.getId() + "}";
                    dialogueService.addMessage(dialogueId, "assistant", fullResponse.toString(), "text", meta);
                }

                emitter.send(SseEmitter.event().name("done").data(""));
                emitter.complete();
            } catch (Exception e) {
                log.error("Rewrite failed for dialogue {}: {}", dialogueId, e.getMessage(), e);
                if (fullResponse.length() > 0) {
                    // Save partial response on error
                    try {
                        RewriteResult result = saveRewriteResult(dialogueId, sourceFileIds, referenceIds, fullResponse.toString());
                        String meta = "{\"type\":\"rewrite\",\"rewriteResultId\":" + result.getId() + "}";
                        dialogueService.addMessage(dialogueId, "assistant", fullResponse.toString(), "text", meta);
                    } catch (Exception ignored) {}
                }
                try {
                    emitter.send(SseEmitter.event().name("error").data("改写失败: " + e.getMessage()));
                } catch (IOException ignored) {}
                emitter.completeWithError(e);
            }
        });
    }

    private String buildRewritePrompt(String sourceContent, String styleExamples, List<Long> referenceIds) {
        StringBuilder sb = new StringBuilder();
        sb.append("你是一个专业的会议纪要撰写助手。请根据以下原始内容，润色改写为正式的会议纪要。\n\n");

        if (styleExamples != null && !styleExamples.isEmpty()) {
            sb.append(styleExamples).append("\n\n");
        }

        sb.append("请遵循以下要求：\n");
        sb.append("1. 保持事实准确，不添加原文没有的信息\n");
        sb.append("2. 使用正式、专业的书面语言\n");
        sb.append("3. 结构化组织：按主题/议程分段\n");
        sb.append("4. 每段包含明确的主题句\n");
        sb.append("5. 保留关键数据和决策结论\n");
        sb.append("6. 使用段落之间用空行分隔\n\n");

        if (styleExamples == null || styleExamples.isEmpty()) {
            sb.append("注意：没有可参考的历史风格示例，请使用通用的正式会议纪要风格。\n\n");
        }

        sb.append("以下是需要改写的原始内容：\n");
        sb.append("====================\n");
        sb.append(sourceContent);
        sb.append("\n====================\n");

        return sb.toString();
    }

    private List<MeetingMinutes> loadSourceFiles(List<Long> sourceFileIds) {
        if (sourceFileIds == null || sourceFileIds.isEmpty()) return List.of();
        return sourceFileIds.stream()
                .map(id -> meetingRepository.findById(id).orElse(null))
                .filter(Objects::nonNull)
                .filter(f -> "completed".equals(f.getStatus()))
                .collect(Collectors.toList());
    }

    private String buildSourceContent(List<MeetingMinutes> sourceFiles) {
        StringBuilder sb = new StringBuilder();
        for (MeetingMinutes f : sourceFiles) {
            if (sb.length() > 0) sb.append("\n\n===== 文件分割 =====\n\n");
            sb.append("文件：").append(f.getTitle()).append("\n\n");

            String content = null;
            // Prefer reading the actual file for text/document formats
            String ext = getExtension(f.getTitle());
            if (f.getFilePath() != null && (TEXT_FORMATS.contains(ext) || DOC_FORMATS.contains(ext))) {
                Path filePath = Path.of(f.getFilePath());
                if (Files.exists(filePath) && f.getFileSize() != null && f.getFileSize() <= MAX_DRAFT_SIZE) {
                    content = extractFileContent(filePath, ext);
                }
            }
            // Fallback to transcription (for audio/video files processed by FunASR)
            if (content == null && f.getTranscription() != null && !f.getTranscription().isBlank()
                    && !"{}".equals(f.getTranscription().trim())) {
                content = f.getTranscription();
            }

            if (content != null && !content.isBlank()) {
                if (content.length() > MAX_FILE_PREVIEW) {
                    sb.append(content, 0, MAX_FILE_PREVIEW).append("\n...（内容较长，仅展示前部分）");
                } else {
                    sb.append(content);
                }
            } else {
                sb.append("（无法读取文件内容）");
            }
        }
        return sb.toString();
    }

    private String extractFileContent(Path filePath, String ext) {
        try {
            if (TEXT_FORMATS.contains(ext)) {
                return Files.readString(filePath, StandardCharsets.UTF_8);
            }
            if (DOC_FORMATS.contains(ext)) {
                return com.meeting.common.DocumentTextExtractor.extractText(filePath, ext);
            }
            return null;
        } catch (Exception e) {
            return null;
        }
    }

    private String getExtension(String filename) {
        int dot = filename.lastIndexOf('.');
        return dot >= 0 ? filename.substring(dot).toLowerCase() : "";
    }

    private String streamDeepSeek(String prompt, SseEmitter emitter, StringBuilder fullResponse) throws IOException {
        Map<String, Object> requestBody = new LinkedHashMap<>();
        requestBody.put("model", deepseekModel);
        requestBody.put("stream", true);

        List<Map<String, String>> messages = new ArrayList<>();
        messages.add(Map.of("role", "system", "content", "你是专业的会议纪要撰写助手，擅长润色和改写会议记录。"));
        messages.add(Map.of("role", "user", "content", prompt));
        requestBody.put("messages", messages);

        String jsonBody = objectToJson(requestBody);

        HttpURLConnection conn = (HttpURLConnection) URI.create(deepseekUrl + "/chat/completions").toURL().openConnection();
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setRequestProperty("Authorization", "Bearer " + deepseekApiKey);
        conn.setDoOutput(true);
        conn.setConnectTimeout(30000);
        conn.setReadTimeout(120000);

        try (OutputStream os = conn.getOutputStream()) {
            os.write(jsonBody.getBytes(StandardCharsets.UTF_8));
        }

        int responseCode = conn.getResponseCode();
        if (responseCode != 200) {
            String errorBody = new String(conn.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
            throw new IOException("DeepSeek API error " + responseCode + ": " + errorBody);
        }

        boolean clientDisconnected = false;
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.startsWith("data: ")) {
                    String data = line.substring(6).trim();
                    if ("[DONE]".equals(data)) break;
                    String delta = extractDelta(data);
                    if (delta != null && !delta.isEmpty()) {
                        fullResponse.append(delta);
                        if (!clientDisconnected) {
                            try {
                                emitter.send(SseEmitter.event().data(delta));
                            } catch (IOException ex) {
                                clientDisconnected = true;
                                log.info("Client disconnected during rewrite stream, continuing to accumulate");
                            }
                        }
                    }
                }
            }
        }
        return fullResponse.toString();
    }

    private String extractDelta(String jsonData) {
        try {
            int contentStart = jsonData.indexOf("\"content\":\"");
            if (contentStart < 0) return "";
            contentStart += 11;
            int contentEnd = jsonData.indexOf("\"", contentStart);
            if (contentEnd < 0) return "";
            return jsonData.substring(contentStart, contentEnd)
                    .replace("\\n", "\n").replace("\\t", "\t")
                    .replace("\\\"", "\"").replace("\\\\", "\\");
        } catch (Exception e) {
            return "";
        }
    }

    private RewriteResult saveRewriteResult(Long dialogueId, List<Long> sourceFileIds,
                                             List<Long> referenceIds, String content) {
        // Determine next version
        List<RewriteResult> previous = rewriteResultRepository.findByDialogueIdOrderByVersionDesc(dialogueId);
        int nextVersion = previous.isEmpty() ? 1 : previous.get(0).getVersion() + 1;

        RewriteResult result = new RewriteResult();
        result.setDialogueId(dialogueId);
        result.setSourceFileIds(toJsonIdList(sourceFileIds));
        result.setReferenceIds(referenceIds != null && !referenceIds.isEmpty() ? toJsonIdList(referenceIds) : null);
        result.setContent(content);
        result.setVersion(nextVersion);
        result.setCreatedAt(LocalDateTime.now());
        return rewriteResultRepository.save(result);
    }

    private String generateComparisonDocx(List<MeetingMinutes> sourceFiles, String rewrittenContent,
                                           Long dialogueId, int version) {
        Path outputDir = Path.of("uploads", "rewrite");
        try {
            Files.createDirectories(outputDir);
            String filename = "rewrite_" + dialogueId + "_v" + version + "_"
                    + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss")) + ".docx";
            Path outputPath = outputDir.resolve(filename);

            XWPFDocument doc = new XWPFDocument();

            // Title
            XWPFParagraph titlePara = doc.createParagraph();
            titlePara.setAlignment(ParagraphAlignment.CENTER);
            XWPFRun titleRun = titlePara.createRun();
            titleRun.setBold(true);
            titleRun.setFontSize(16);
            titleRun.setText("会议纪要改写对照版");

            // Draft section
            XWPFParagraph draftTitle = doc.createParagraph();
            XWPFRun draftRun = draftTitle.createRun();
            draftRun.setBold(true);
            draftRun.setFontSize(14);
            draftRun.setText("原始内容");

            for (MeetingMinutes f : sourceFiles) {
                String sourceText = f.getTranscription() != null ? f.getTranscription() : "";
                if (sourceText.length() > 1000) sourceText = sourceText.substring(0, 1000) + "\n...（内容过长已截断）";
                XWPFParagraph srcPara = doc.createParagraph();
                XWPFRun srcRun = srcPara.createRun();
                srcRun.setFontSize(10);
                srcRun.setText("【" + f.getTitle() + "】\n" + sourceText);
            }

            // Separator
            doc.createParagraph();
            XWPFParagraph sep = doc.createParagraph();
            XWPFRun sepRun = sep.createRun();
            sepRun.setText("━".repeat(50));

            // Rewritten section
            XWPFParagraph rewTitle = doc.createParagraph();
            XWPFRun rewRun = rewTitle.createRun();
            rewRun.setBold(true);
            rewRun.setFontSize(14);
            rewRun.setText("改写后内容");

            String[] paragraphs = rewrittenContent.split("\\n\\n+");
            for (String para : paragraphs) {
                XWPFParagraph p = doc.createParagraph();
                XWPFRun r = p.createRun();
                r.setFontSize(11);
                r.setText(para.trim());
            }

            try (FileOutputStream fos = new FileOutputStream(outputPath.toFile())) {
                doc.write(fos);
            }
            doc.close();

            return outputPath.toAbsolutePath().toString();
        } catch (Exception e) {
            log.error("Failed to generate .docx for dialogue {}: {}", dialogueId, e.getMessage(), e);
            return null;
        }
    }

    private String objectToJson(Object obj) {
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

    private String toJsonIdList(List<Long> ids) {
        return ids.stream().map(String::valueOf)
                .collect(Collectors.joining(",", "[", "]"));
    }

    private List<Long> parseJsonIdList(String json) {
        String trimmed = json.trim();
        if (trimmed.startsWith("[") && trimmed.endsWith("]")) {
            trimmed = trimmed.substring(1, trimmed.length() - 1);
        }
        if (trimmed.isEmpty()) return List.of();
        return Arrays.stream(trimmed.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .map(Long::parseLong)
                .collect(Collectors.toList());
    }

    public List<RewriteResult> getRewriteHistory(Long dialogueId) {
        return rewriteResultRepository.findByDialogueIdOrderByVersionDesc(dialogueId);
    }

    public Optional<RewriteResult> getRewriteResult(Long resultId) {
        return rewriteResultRepository.findById(resultId);
    }
}
