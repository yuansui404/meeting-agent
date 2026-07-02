package com.meeting.service;

import com.meeting.entity.RewriteResult;
import com.meeting.repository.RewriteResultRepository;
import io.agentscope.core.formatter.openai.dto.OpenAIMessage;
import io.agentscope.core.formatter.openai.dto.OpenAIRequest;
import io.agentscope.core.formatter.openai.dto.OpenAIResponse;
import io.agentscope.core.model.OpenAIClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

@Service
public class RewriteService {

    private static final Logger log = LoggerFactory.getLogger(RewriteService.class);
    private static final int MAX_TOKENS = 8000;

    private final RewriteResultRepository rewriteResultRepository;
    private final SessionService sessionService;
    private final StyleLearningService styleLearningService;
    private final ExecutorService executor = Executors.newCachedThreadPool();

    private final String deepseekApiKey;
    private final String deepseekModel;
    private final String deepseekUrl;
    private final String uploadDir;
    private final OpenAIClient openAIClient = new OpenAIClient();

    public RewriteService(@Value("${deepseek.api-key:}") String apiKey,
                          @Value("${deepseek.model:deepseek-chat}") String modelName,
                          @Value("${deepseek.url:https://api.deepseek.com}") String apiUrl,
                          @Value("${file.upload-dir:/app/data/uploads}") String uploadDir,
                          RewriteResultRepository rewriteResultRepository,
                          SessionService sessionService,
                          StyleLearningService styleLearningService) {
        this.rewriteResultRepository = rewriteResultRepository;
        this.sessionService = sessionService;
        this.styleLearningService = styleLearningService;
        this.deepseekApiKey = apiKey;
        this.deepseekModel = modelName;
        this.deepseekUrl = apiUrl;
        this.uploadDir = uploadDir;
    }

    /**
     * Rewrite with pre-loaded source content (used by multi-intent flow).
     * Skips file loading — uses the provided content directly.
     */
    public void streamRewriteWithContent(Long dialogueId, String sourceContent, SseEmitter emitter) {
        executor.submit(() -> {
            StringBuilder fullResponse = new StringBuilder();

            try {
                if (sourceContent == null || sourceContent.isBlank()) {
                    emitter.send(SseEmitter.event().name("error").data("没有找到需要改写的内容"));
                    emitter.complete();
                    return;
                }

                String content = sourceContent;
                if (content.length() > MAX_TOKENS) {
                    content = content.substring(0, MAX_TOKENS) + "\n...（内容过长，已截断至 " + MAX_TOKENS + " 字符）";
                }

                // Get style examples
                String styleExamples = styleLearningService.buildFullDocumentReferences(content, List.of(), null);
                String prompt = buildRewritePrompt(content, styleExamples);

                // Call DeepSeek (streaming)
                streamDeepSeek(prompt, emitter, fullResponse);

                // Proofreading
                if (fullResponse.length() > 0) {
                    try {
                        String proofreadResult = proofreadContent(fullResponse.toString(), content);
                        if (proofreadResult != null && !proofreadResult.equals(fullResponse.toString())) {
                            log.info("Proofreading applied corrections for dialogue {} (with-content)", dialogueId);
                            fullResponse = new StringBuilder(proofreadResult);
                            try {
                                emitter.send(SseEmitter.event().name("corrected").data(proofreadResult));
                            } catch (IOException ignored) {}
                        }
                    } catch (Exception e) {
                        log.warn("Proofreading failed for dialogue {} (with-content): {}", dialogueId, e.getMessage());
                    }
                }

                // Save (no file IDs, no DOCX)
                if (fullResponse.length() > 0) {
                    RewriteResult result = saveRewriteResult(dialogueId, List.of(), List.of(), fullResponse.toString());
                    String meta = "{\"type\":\"rewrite\",\"rewriteResultId\":" + result.getId() + "}";
                    sessionService.addMessage(dialogueId, "assistant", fullResponse.toString(), "text", meta);
                }

                emitter.send(SseEmitter.event().name("done").data(""));
                emitter.complete();
            } catch (Exception e) {
                log.error("Rewrite with content failed for dialogue {}: {}", dialogueId, e.getMessage(), e);
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

        sb.append("【重要】以下原始内容来自语音转文字（ASR），");
        sb.append("可能存在同音字错误或专业术语识别错误（例如“G2网”应为标准ICT术语）。");
        sb.append("请根据上下文和ICT行业知识进行甄别和更正，不要照搬原文中不规范的术语。\n\n");

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

    /**
     * Streaming DeepSeek API call using OpenAIClient. Sends SSE events to emitter.
     */
    private String streamDeepSeek(String prompt, SseEmitter emitter, StringBuilder fullResponse) throws IOException {
        try {
            OpenAIRequest request = OpenAIRequest.builder()
                    .model(deepseekModel)
                    .messages(List.of(
                            OpenAIMessage.builder().role("system")
                                    .content("你是专业的会议纪要撰写助手，擅长润色和改写会议记录。").build(),
                            OpenAIMessage.builder().role("user").content(prompt).build()
                    ))
                    .stream(true)
                    .build();

            final boolean[] clientDisconnected = {false};
            openAIClient.stream(deepseekApiKey, deepseekUrl, request, null)
                    .doOnNext(response -> {
                        if (response.isChunk()) {
                            OpenAIMessage delta = response.getFirstChoice().getDelta();
                            if (delta != null) {
                                String content = delta.getContentAsString();
                                if (content != null && !content.isEmpty()) {
                                    fullResponse.append(content);
                                    if (!clientDisconnected[0]) {
                                        try {
                                            emitter.send(SseEmitter.event().data(content));
                                        } catch (IOException ex) {
                                            clientDisconnected[0] = true;
                                            log.info("Client disconnected during rewrite stream, continuing to accumulate");
                                        }
                                    }
                                }
                            }
                        }
                    })
                    .blockLast();
        } catch (Exception e) {
            throw new IOException("DeepSeek streaming failed: " + e.getMessage());
        }
        return fullResponse.toString();
    }

    private RewriteResult saveRewriteResult(Long dialogueId, List<Long> sourceFileIds,
                                             List<Long> referenceIds, String content) {
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

    private String toJsonIdList(List<Long> ids) {
        return ids.stream().map(String::valueOf)
                .collect(Collectors.joining(",", "[", "]"));
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
        Path participantsFile = Path.of(uploadDir, "profile", "与会人.md");
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
        sb.append("### 3. ICT 行业术语（关键）\n");
        sb.append("确保使用的术语符合 ICT/通信行业规范（如\"TP价\"、\"代表处\"、\"BG\"、\"OP\"、\"毛利率\"、\"虚拟毛利\"等）。\n");
        sb.append("特别注意：原始内容来自语音转文字，可能存在专业术语识别错误。\n");
        sb.append("如果你发现某个技术名词不是标准 ICT 术语（例如\"G2网\"应纠正为标准术语），\n");
        sb.append("请根据上下文推断正确的术语并更正。不要照搬原文中不规范的术语。\n\n");
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
        try {
            OpenAIRequest request = OpenAIRequest.builder()
                    .model(deepseekModel)
                    .messages(List.of(
                            OpenAIMessage.builder().role("system").content(systemMessage).build(),
                            OpenAIMessage.builder().role("user").content(userMessage).build()
                    ))
                    .maxTokens(maxTokens)
                    .build();

            OpenAIResponse response = openAIClient.call(deepseekApiKey, deepseekUrl, request);
            return response.getFirstChoice().getMessage().getContentAsString();
        } catch (Exception e) {
            throw new IOException("DeepSeek non-streaming call failed: " + e.getMessage());
        }
    }
}
