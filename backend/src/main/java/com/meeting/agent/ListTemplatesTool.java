package com.meeting.agent;

import com.meeting.common.JsonUtil;
import com.meeting.template.model.entity.TemplateEntity;
import com.meeting.template.service.TemplateService;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.tool.AgentTool;
import io.agentscope.core.tool.ToolCallParam;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class ListTemplatesTool implements AgentTool {

    private final TemplateService templateService;

    @Override
    public String getName() {
        return "list_templates";
    }

    @Override
    public String getDescription() {
        return "获取可用的 docx 排版模板列表，每个模板有不同的风格标签";
    }

    @Override
    public Map<String, Object> getParameters() {
        return Map.of(
                "type", "object",
                "properties", Map.of(),
                "required", List.of()
        );
    }

    @Override
    public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
        return Mono.fromCallable(() -> {
            List<TemplateEntity> templates = templateService.list();
            if (templates.isEmpty()) {
                return ToolResultBlock.text("暂无可用模板");
            }

            Map<String, Object> result = new LinkedHashMap<>();
            List<Map<String, Object>> items = templates.stream()
                    .map(t -> {
                        Map<String, Object> item = new LinkedHashMap<>();
                        item.put("id", t.getId());
                        item.put("name", t.getName());
                        item.put("styleTags", t.getStyleTags() != null ? t.getStyleTags() : "通用");
                        return item;
                    })
                    .toList();
            result.put("items", items);
            result.put("total", items.size());

            return ToolResultBlock.text(JsonUtil.toJson(result));
        });
    }
}