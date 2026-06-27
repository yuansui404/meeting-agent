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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
    private final String uploadDir;
    private final ObjectMapper objectMapper;

    public RewriteService(@Value("${deepseek.api-key:}") String apiKey,
                          @Value("${deepseek.model:deepseek-chat}") String modelName,
                          @Value("${deepseek.url:https://api.deepseek.com}") String apiUrl,
                          @Value("${file.upload-dir:/app/data/uploads}") String uploadDir,
                          MeetingMinutesRepository meetingRepository,
                          RewriteResultRepository rewriteResultRepository,
                          DialogueService dialogueService,
                          StyleLearningService styleLearningService,
                          ObjectMapper objectMapper) {
        this.meetingRepository = meetingRepository;
        this.rewriteResultRepository = rewriteResultRepository;
        this.dialogueService = dialogueService;
        this.styleLearningService = styleLearningService;
        this.deepseekApiKey = apiKey;
        this.deepseekModel = modelName;
        this.deepseekUrl = apiUrl;
        this.uploadDir = uploadDir;
        this.objectMapper = objectMapper;
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

                String styleExamples = styleLearningService.buildFullDocumentReferences(sourceContent, excludeIds, manualReferenceIds);
                log.info("Full document references for dialogue {}: {} chars, excludeIds={}, manualRefs={}",
                        dialogueId, styleExamples != null ? styleExamples.length() : 0, excludeIds, manualReferenceIds);

                // 3. Build prompt
                String prompt = buildRewritePrompt(sourceContent, styleExamples);

                // 4. Call DeepSeek API (SSE streaming)
                String apiResponse = streamDeepSeek(prompt, emitter, fullResponse);

                // 4.5 Proofreading pass (non-streaming)
                if (fullResponse.length() > 0) {
                    try {
                        String proofreadResult = proofreadContent(
                                fullResponse.toString(), sourceContent);
                        if (proofreadResult != null && !proofreadResult.equals(fullResponse.toString())) {
                            log.info("Proofreading applied corrections for dialogue {}, original={} chars, corrected={} chars",
                                    dialogueId, fullResponse.length(), proofreadResult.length());
                            fullResponse = new StringBuilder(proofreadResult);
                            // Notify client with corrected content
                            try {
                                emitter.send(SseEmitter.event().name("corrected").data(proofreadResult));
                            } catch (IOException ignored) {
                                // Client may have disconnected
                            }
                        } else {
                            log.info("Proofreading: no corrections needed for dialogue {}", dialogueId);
                        }
                    } catch (Exception e) {
                        log.warn("Proofreading failed for dialogue {}, continuing with original: {}",
                                dialogueId, e.getMessage());
                    }
                }

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

    private String buildRewritePrompt(String sourceContent, String documentReferences) {
        StringBuilder sb = new StringBuilder();
        sb.append("请根据以下原始内容，润色改写为正式的会议纪要。\n\n");

        if (documentReferences != null && !documentReferences.isEmpty()) {
            sb.append(documentReferences).append("\n\n");
        } else {
            sb.append("基本格式要求：\n");
            sb.append("1. 保持事实准确，不添加原文没有的信息\n");
            sb.append("2. 按主题/议程分段组织\n");
            sb.append("3. 保留关键数据和决策结论\n\n");
            sb.append("注意：没有可参考的历史记录，请使用通用的正式会议纪要格式。\n\n");
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
        Path outputDir = Path.of(uploadDir, "rewrite");
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

            // Parse rewritten content: render tab-separated lines as real Word tables
            renderRichContent(doc, rewrittenContent);

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

    /**
     * Render rewritten content with table-aware line parsing.
     * Tab-separated consecutive lines become real Word tables;
     * plain text lines become paragraphs; "---" separators add spacing.
     */
    private void renderRichContent(XWPFDocument doc, String content) {
        String[] lines = content.split("\\n", -1);
        List<String> currentTable = null;

        for (String line : lines) {
            String trimmed = line.trim();

            if (trimmed.equals("---")) {
                flushTable(doc, currentTable);
                currentTable = null;
                // Add spacing paragraph
                doc.createParagraph();
                continue;
            }

            if (trimmed.contains("\t")) {
                if (currentTable == null) {
                    currentTable = new ArrayList<>();
                }
                currentTable.add(trimmed);
            } else {
                flushTable(doc, currentTable);
                currentTable = null;
                if (!trimmed.isEmpty()) {
                    XWPFParagraph p = doc.createParagraph();
                    XWPFRun r = p.createRun();
                    r.setFontSize(11);
                    r.setText(trimmed);
                }
            }
        }
        flushTable(doc, currentTable);
    }

    private void flushTable(XWPFDocument doc, List<String> rows) {
        if (rows == null || rows.isEmpty()) return;

        // Determine column count from the row with the most tabs
        int cols = rows.stream()
                .mapToInt(row -> row.split("\t", -1).length)
                .max().orElse(1);

        XWPFTable table = doc.createTable(rows.size(), cols);
        table.setWidth("100%");

        for (int i = 0; i < rows.size(); i++) {
            String[] cells = rows.get(i).split("\t", -1);
            XWPFTableRow row = table.getRow(i);
            for (int j = 0; j < cols; j++) {
                XWPFTableCell cell = j < cells.length ? row.getCell(j) : row.getCell(cols - 1);
                if (cell == null) continue;
                String cellText = j < cells.length ? cells[j] : "";
                // Clear default paragraph and set text
                cell.removeParagraph(0);
                XWPFParagraph cp = cell.addParagraph();
                cp.setAlignment(ParagraphAlignment.LEFT);
                XWPFRun cr = cp.createRun();
                cr.setFontSize(10);
                cr.setText(cellText);
            }
        }

        // Add spacing after table
        doc.createParagraph();
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

    /**
     * Proofread the rewritten content:
     * 1. Validate participant names against 与会人.md
     * 2. Check ICT terminology and logic consistency
     * 3. Return corrected content (or original if no issues)
     */
    private String proofreadContent(String draftContent, String sourceContent) {
        // Read global 与会人.md
        String participantsContext = "";
        Path participantsFile = Path.of(uploadDir, "knowledge-base", "与会人.md");
        if (Files.exists(participantsFile)) {
            try {
                participantsContext = Files.readString(participantsFile, StandardCharsets.UTF_8);
            } catch (IOException e) {
                log.warn("Failed to read 与会人.md: {}", e.getMessage());
            }
        }

        // Build proofreading prompt
        StringBuilder sb = new StringBuilder();
        sb.append("你是专业的文档校对专家，请对以下会议纪要改写结果进行严格校对。\n\n");
        sb.append("## 校对要求（按优先级排序）\n\n");
        sb.append("### 1. 人名准确性（最高优先级）\n");
        sb.append("逐一核对改写结果中出现的每个姓名是否在公司常与会人名单中。\n");
        sb.append("如果某个姓名不在名单位中，但名单中存在读音相似或字形近似的正确写法（如名单有\"张弢\"但文中写\"张涛\"），必须更正为名单中的正确姓名。\n");
        sb.append("人名更正没有例外——即使原始内容中使用了错误姓名，也必须按名单修正。\n\n");
        sb.append("### 2. 部门/机构名称准确性\n");
        sb.append("逐一核对改写结果中出现的所有部门名、机构名是否与原始会议内容完全一致。\n");
        sb.append("特别注意：不要随意增减或改变部门名称（如\"运营管理部\"不要写成\"一营管理部\"或\"运营部\"）。\n");
        sb.append("如果改写结果改变了原始内容中的部门名称，必须更正回原始名称。\n\n");
        sb.append("### 3. ICT 行业术语\n");
        sb.append("确保使用的术语符合 ICT/通信行业规范（如\"TP价\"、\"代表处\"、\"BG\"、\"OP\"、\"毛利率\"、\"虚拟毛利\"等）。\n\n");
        sb.append("### 4. 逻辑一致性\n");
        sb.append("内容连贯、逻辑通顺、事实准确。\n\n");
        sb.append("### 5. 错别字与语法\n");
        sb.append("修正错别字、用词不当、语病。\n\n");

        if (!participantsContext.isBlank()) {
            sb.append("## 参考文献\n");
            sb.append("公司常与会人名单（以此为准核对所有人名）：\n");
            sb.append(participantsContext).append("\n\n");
        }

        sb.append("## 输出要求\n");
        sb.append("- 必须逐字检查每个人名，确保无一遗漏\n");
        sb.append("- 如果内容没有问题，原样返回\n");
        sb.append("- 如果发现问题，修正后**返回完整的修正版全文**\n");
        sb.append("- 保持原文的改写风格和格式\n");
        sb.append("- 不要添加原文没有的信息\n");
        sb.append("- 直接输出会议纪要全文，不要额外解释\n\n");

        sb.append("原始会议内容：\n");
        sb.append("====================\n");
        sb.append(sourceContent);
        sb.append("\n====================\n\n");

        sb.append("待校对的改写结果：\n");
        sb.append("====================\n");
        sb.append(draftContent);
        sb.append("\n====================\n");

        try {
            return callDeepSeekNonStreaming("你是专业的文档校对专家，擅长校对会议纪要。", sb.toString(), 8192);
        } catch (Exception e) {
            log.warn("Proofreading DeepSeek call failed: {}", e.getMessage());
            return draftContent;
        }
    }

    /**
     * Non-streaming DeepSeek API call with configurable max_tokens.
     */
    private String callDeepSeekNonStreaming(String systemMessage, String userMessage, int maxTokens) throws IOException {
        Map<String, Object> requestBody = new LinkedHashMap<>();
        requestBody.put("model", deepseekModel);
        requestBody.put("stream", false);
        requestBody.put("max_tokens", maxTokens);

        List<Map<String, String>> messages = new ArrayList<>();
        messages.add(Map.of("role", "system", "content", systemMessage));
        messages.add(Map.of("role", "user", "content", userMessage));
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

        String responseBody = new String(conn.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        try {
            JsonNode root = objectMapper.readTree(responseBody);
            return root.get("choices").get(0).get("message").get("content").asText();
        } catch (Exception e) {
            throw new IOException("Failed to parse DeepSeek response: " + e.getMessage());
        }
    }
}
