package com.meeting.agent;

import com.meeting.service.TranscriptionService;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.tool.AgentTool;
import io.agentscope.core.tool.ToolCallParam;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class CallMiMoAsrTool implements AgentTool {

    private final TranscriptionService transcriptionService;

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

            long fileSize = Files.size(path);
            if (fileSize > 200 * 1024 * 1024) {
                log.warn("Audio file too large ({}MB): {}", fileSize / 1024 / 1024, filePath);
            }

            String result = transcriptionService.callMiMoASR(filePath);
            if (result.startsWith("转写失败")) {
                return ToolResultBlock.error(result);
            }
            return ToolResultBlock.text(result);
        });
    }
}
