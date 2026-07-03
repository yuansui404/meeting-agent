package com.meeting.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.tool.AgentTool;
import io.agentscope.core.tool.ToolCallParam;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class CallMiMoAsrTool implements AgentTool {

    private final ObjectMapper objectMapper;

    @Value("${mimo.api-key:}") private String mimoApiKey = "";
    @Value("${mimo.url:https://token-plan-cn.xiaomimimo.com}") private String mimoUrl = "https://token-plan-cn.xiaomimimo.com";

    @Override
    public String getName() {
        return "call_mimo_asr";
    }

    @Override
    public String getDescription() {
        return "将音频或视频文件转写为文字。支持 mp3、wav、mp4 等格式。当用户上传音频或视频文件需要转写时使用。输入文件的绝对路径，返回转写后的文字内容。";
    }

    @Override
    public Map<String, Object> getParameters() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("filePath", Map.of(
                "type", "string",
                "description", "音频或视频文件的绝对路径"
        ));
        return Map.of(
                "type", "object",
                "properties", properties,
                "required", List.of("filePath")
        );
    }

    @Override
    public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
        return Mono.fromCallable(() -> {
            Map<String, Object> input = param.getInput();
            String filePath = (String) input.get("filePath");
            if (filePath == null || filePath.isBlank()) {
                return ToolResultBlock.error("缺少 filePath 参数");
            }

            Path path = Path.of(filePath);
            if (!Files.exists(path)) {
                return ToolResultBlock.error("文件不存在: " + filePath);
            }

            try {
                long fileSize = Files.size(path);
                if (fileSize > 200 * 1024 * 1024) {
                    log.warn("Audio file too large ({}MB): {}", fileSize / 1024 / 1024, filePath);
                }

                byte[] audioBytes = Files.readAllBytes(path);
                String base64Audio = java.util.Base64.getEncoder().encodeToString(audioBytes);
                String ext = filePath.toLowerCase().endsWith(".wav") ? "wav" : "mp3";

                Map<String, Object> requestBody = new LinkedHashMap<>();
                requestBody.put("model", "MiMo-V2.5-ASR");
                requestBody.put("messages", List.of(Map.of(
                        "role", "user",
                        "content", List.of(Map.of(
                                "type", "audio",
                                "audio", Map.of("data", base64Audio, "format", ext)
                        ))
                )));

                String json = objectMapper.writeValueAsString(requestBody);
                String url = mimoUrl + "/v1/chat/completions";

                HttpURLConnection conn = (HttpURLConnection) URI.create(url).toURL().openConnection();
                conn.setRequestMethod("POST");
                conn.setRequestProperty("Content-Type", "application/json");
                conn.setRequestProperty("api-key", mimoApiKey);
                conn.setConnectTimeout(60000);
                conn.setReadTimeout(300000);
                conn.setDoOutput(true);

                try (OutputStream os = conn.getOutputStream()) {
                    os.write(json.getBytes(StandardCharsets.UTF_8));
                }

                int code = conn.getResponseCode();
                InputStream is = code >= 400 ? conn.getErrorStream() : conn.getInputStream();
                String body = new String(is.readAllBytes(), StandardCharsets.UTF_8);

                if (code >= 400) {
                    log.error("MiMo ASR API error {}: {}", code, body);
                    return ToolResultBlock.error("ASR API 错误: HTTP " + code);
                }

                var root = objectMapper.readTree(body);
                String text = root.path("choices").path(0).path("message").path("content").asText(null);

                if (text == null || text.isBlank()) {
                    return ToolResultBlock.error("ASR 返回为空");
                }

                return ToolResultBlock.text(text);
            } catch (Exception e) {
                log.error("MiMo ASR call failed: {}", e.getMessage());
                return ToolResultBlock.error("ASR 调用失败: " + e.getMessage());
            }
        });
    }
}
