package com.meeting.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.meeting.config.ZhiPuProperties;
import io.agentscope.core.formatter.openai.dto.OpenAIMessage;
import io.agentscope.core.formatter.openai.dto.OpenAIRequest;
import io.agentscope.core.formatter.openai.dto.OpenAIResponse;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.OpenAIClient;
import io.agentscope.core.tool.AgentTool;
import io.agentscope.core.tool.ToolCallParam;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

@Slf4j
@Component
@RequiredArgsConstructor
public class UnderstandImageTool implements AgentTool {

    private final OpenAIClient openAIClient;
    private final ZhiPuProperties zhiPuProps;
    private final ObjectMapper objectMapper;

    @Override
    public String getName() {
        return "understand_image";
    }

    @Override
    public String getDescription() {
        return "理解图片内容并回答问题。当用户上传图片并需要分析图片内容时使用。输入图片的绝对路径和可选的问题，返回对图片的描述或回答。";
    }

    @Override
    public Map<String, Object> getParameters() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("filePath", Map.of(
                "type", "string",
                "description", "图片文件的绝对路径"
        ));
        properties.put("question", Map.of(
                "type", "string",
                "description", "可选，关于图片的问题。如果不提供，返回图片的整体描述"
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
            String question = (String) input.getOrDefault("question", "请描述这张图片的内容");

            if (filePath == null || filePath.isBlank()) {
                return ToolResultBlock.error("缺少 filePath 参数");
            }

            Path path = Path.of(filePath);
            if (!Files.exists(path)) {
                return ToolResultBlock.error("文件不存在: " + filePath);
            }

            try {
                long fileSize = Files.size(path);
                if (fileSize > 20 * 1024 * 1024) {
                    return ToolResultBlock.error("图片文件过大 (" + (fileSize / 1024 / 1024) + "MB)，最大支持 20MB");
                }

                String ext = filePath.toLowerCase();
                String mediaType = getMimeType(ext);

                BufferedImage image = ImageIO.read(path.toFile());
                if (image == null) {
                    return ToolResultBlock.error("不支持的图片格式: " + filePath);
                }

                // Resize if longest edge exceeds 2048px
                int maxDim = 2048;
                int w = image.getWidth(), h = image.getHeight();
                if (w > maxDim || h > maxDim) {
                    double scale = Math.min((double) maxDim / w, (double) maxDim / h);
                    int nw = (int) (w * scale);
                    int nh = (int) (h * scale);
                    BufferedImage resized = new BufferedImage(nw, nh, image.getType());
                    Graphics2D g = resized.createGraphics();
                    g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                    g.drawImage(image.getScaledInstance(nw, nh, Image.SCALE_SMOOTH), 0, 0, null);
                    g.dispose();
                    image = resized;
                    log.info("Resized image from {}x{} to {}x{}", w, h, nw, nh);
                }

                String format = mediaType.equals("image/png") ? "png" : "jpg";
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                ImageIO.write(image, format, baos);
                String base64 = Base64.getEncoder().encodeToString(baos.toByteArray());

                // Build multimodal content parts
                List<Object> contentParts = new ArrayList<>();
                contentParts.add(Map.of("type", "text", "text", question));
                contentParts.add(Map.of(
                        "type", "image_url",
                        "image_url", Map.of("url", "data:" + mediaType + ";base64," + base64)
                ));

                OpenAIMessage sysMsg = new OpenAIMessage();
                sysMsg.setRole("system");
                sysMsg.setContent("你是一个图片理解助手。根据用户提供的图片和问题，给出准确的描述或回答。");

                OpenAIMessage userMsg = new OpenAIMessage();
                userMsg.setRole("user");
                userMsg.setContent(contentParts);

                OpenAIRequest request = OpenAIRequest.builder()
                        .model(zhiPuProps.getModel())
                        .messages(List.of(sysMsg, userMsg))
                        .stream(false)
                        .build();

                GenerateOptions opts = GenerateOptions.builder().endpointPath("").build();
                String url = zhiPuProps.getUrl() + "/chat/completions";

                // Non-streaming call
                OpenAIResponse response = openAIClient.call(
                        zhiPuProps.getApiKey(), url, request, opts);

                String result = response.getFirstChoice().getMessage().getContentAsString();
                if (result == null || result.isBlank()) {
                    return ToolResultBlock.error("图片理解返回为空");
                }

                return ToolResultBlock.text(result);
            } catch (Exception e) {
                log.error("Image understanding failed: {}", e.getMessage());
                return ToolResultBlock.error("图片理解失败: " + e.getMessage());
            }
        });
    }

    private String getMimeType(String path) {
        if (path.endsWith(".jpg") || path.endsWith(".jpeg")) return "image/jpeg";
        if (path.endsWith(".png")) return "image/png";
        if (path.endsWith(".gif")) return "image/gif";
        if (path.endsWith(".webp")) return "image/webp";
        if (path.endsWith(".bmp")) return "image/bmp";
        if (path.endsWith(".svg")) return "image/svg+xml";
        return "image/png";
    }
}
