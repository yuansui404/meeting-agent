package com.meeting.controller;

import com.meeting.common.ApiResponse;
import com.meeting.common.BusinessException;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.subagent.task.BackgroundTask;
import io.agentscope.harness.agent.subagent.task.TaskRepository;
import io.agentscope.harness.agent.subagent.task.TaskStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/admin")
@RequiredArgsConstructor
public class AdminController {

    private final HarnessAgent meetingAssistantAgent;
    private final TaskRepository taskRepository;

    // ============ Subagent 管理 ============

    @GetMapping("/subagents")
    public String listActiveSubAgents() {
        try {
            Object spawnTool = resolveAgentSpawnTool();
            Method agentList = spawnTool.getClass().getMethod("agentList");
            return (String) agentList.invoke(spawnTool);
        } catch (Exception e) {
            log.error("Failed to list sub-agents", e);
            return "Error: " + e.getMessage();
        }
    }

    @PostMapping("/subagents/kill")
    public ApiResponse<Map<String, Object>> killSubAgent(@RequestParam String agentKey) {
        if (agentKey == null || agentKey.isBlank()) {
            throw BusinessException.badRequest("agentKey is required");
        }
        try {
            Object spawnTool = resolveAgentSpawnTool();

            Field agentsByKeyField = spawnTool.getClass().getDeclaredField("agentsByKey");
            agentsByKeyField.setAccessible(true);
            Map<String, Object> agentsByKey = (Map<String, Object>) agentsByKeyField.get(spawnTool);

            Field labelToKeyField = spawnTool.getClass().getDeclaredField("labelToKey");
            labelToKeyField.setAccessible(true);
            Map<String, String> labelToKey = (Map<String, String>) labelToKeyField.get(spawnTool);

            Object spawnedAgent = agentsByKey.get(agentKey);
            if (spawnedAgent == null) {
                throw BusinessException.notFound("Sub-agent not found: " + agentKey);
            }

            // 关闭 Agent 实例
            String agentId = null;
            for (Method m : spawnedAgent.getClass().getMethods()) {
                switch (m.getName()) {
                    case "agent" -> {
                        Object agent = m.invoke(spawnedAgent);
                        if (agent != null) {
                            agent.getClass().getMethod("interrupt").invoke(agent);
                        }
                    }
                    case "agentId" -> agentId = (String) m.invoke(spawnedAgent);
                }
            }

            // 从 maps 中移除
            agentsByKey.remove(agentKey);
            labelToKey.values().remove(agentKey);

            Map<String, Object> data = new HashMap<>();
            data.put("agentKey", agentKey);
            data.put("agentId", agentId);
            data.put("interrupted", true);
            return ApiResponse.ok(data, "Sub-agent " + agentKey + " killed");
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.error("Failed to kill sub-agent", e);
            throw BusinessException.processingFailed("Failed to kill sub-agent", e);
        }
    }

    @PostMapping("/subagents/cleanup")
    public ApiResponse<Map<String, Object>> cleanupSubAgents(
            @RequestParam(required = false) String sessionId) {
        try {
            Object spawnTool = resolveAgentSpawnTool();

            Field agentsByKeyField = spawnTool.getClass().getDeclaredField("agentsByKey");
            agentsByKeyField.setAccessible(true);
            Map<String, Object> agentsByKey = (Map<String, Object>) agentsByKeyField.get(spawnTool);

            Field labelToKeyField = spawnTool.getClass().getDeclaredField("labelToKey");
            labelToKeyField.setAccessible(true);
            Map<String, String> labelToKey = (Map<String, String>) labelToKeyField.get(spawnTool);

            Map<String, Object> data = new HashMap<>();

            if (sessionId != null && !sessionId.isBlank()) {
                Method sessionIdMethod = null;
                List<String> keysToRemove = new ArrayList<>();
                for (Map.Entry<String, Object> entry : agentsByKey.entrySet()) {
                    Object spawnedAgent = entry.getValue();
                    if (sessionIdMethod == null) {
                        sessionIdMethod = spawnedAgent.getClass().getMethod("sessionId");
                    }
                    String agentSessionId = (String) sessionIdMethod.invoke(spawnedAgent);
                    if (sessionId.equals(agentSessionId)) {
                        keysToRemove.add(entry.getKey());
                    }
                }
                keysToRemove.forEach(agentsByKey::remove);
                labelToKey.values().removeAll(keysToRemove);

                data.put("removed", keysToRemove.size());
                data.put("sessionId", sessionId);
                return ApiResponse.ok(data, "Cleaned up " + keysToRemove.size() + " sub-agents for session " + sessionId);
            } else {
                int before = agentsByKey.size();
                agentsByKey.clear();
                labelToKey.clear();
                data.put("cleared", true);
                data.put("removed", before);
                return ApiResponse.ok(data, "All sub-agents cleared (removed " + before + " entries)");
            }
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.error("Sub-agent cleanup failed", e);
            throw BusinessException.processingFailed("Sub-agent cleanup failed", e);
        }
    }

    // ============ Task 管理 ============

    @GetMapping("/task/output")
    public ApiResponse<String> getTaskOutput(
            @RequestParam String taskId,
            @RequestParam(required = false, defaultValue = "false") Boolean markDelivered,
            @RequestParam(required = false) Long timeoutMs) {
        if (taskId == null || taskId.isBlank()) {
            throw BusinessException.badRequest("taskId is required");
        }
        try {
            Object subagentMiddleware = resolveSubagentsMiddleware();
            Object taskTool = resolveTaskTool(subagentMiddleware);
            RuntimeContext ctx = RuntimeContext.builder()
                    .sessionId("admin")
                    .userId("admin")
                    .build();
            Method taskOutput = taskTool.getClass().getMethod(
                    "taskOutput", RuntimeContext.class, String.class, Boolean.class, Long.class);
            String result = (String) taskOutput.invoke(taskTool, ctx, taskId, markDelivered, timeoutMs);
            return ApiResponse.ok(result);
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.error("Failed to get task output", e);
            throw BusinessException.processingFailed("Failed to get task output", e);
        }
    }

    @PostMapping("/task/cancel")
    public ApiResponse<String> cancelTask(@RequestParam String taskId) {
        if (taskId == null || taskId.isBlank()) {
            throw BusinessException.badRequest("taskId is required");
        }
        try {
            Object subagentMiddleware = resolveSubagentsMiddleware();
            Object taskTool = resolveTaskTool(subagentMiddleware);
            RuntimeContext ctx = RuntimeContext.builder()
                    .sessionId("admin")
                    .userId("admin")
                    .build();
            Method taskCancel = taskTool.getClass().getMethod(
                    "taskCancel", RuntimeContext.class, String.class);
            String result = (String) taskCancel.invoke(taskTool, ctx, taskId);
            return ApiResponse.ok(result);
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.error("Failed to cancel task", e);
            throw BusinessException.processingFailed("Failed to cancel task", e);
        }
    }

    @GetMapping("/task/list")
    public ApiResponse<List<Map<String, Object>>> listTasks(
            @RequestParam(required = false) String sessionId,
            @RequestParam(required = false) String status) {
        try {
            TaskStatus statusFilter = null;
            if (status != null && !status.isBlank()) {
                try {
                    statusFilter = TaskStatus.valueOf(status.toUpperCase());
                } catch (IllegalArgumentException e) {
                    throw BusinessException.badRequest(
                            "Invalid status: " + status + ". Valid values: PENDING, RUNNING, COMPLETED, FAILED, CANCELLED");
                }
            }

            RuntimeContext ctx = RuntimeContext.builder()
                    .userId("admin")
                    .sessionId(sessionId != null ? sessionId : "admin")
                    .build();

            Collection<BackgroundTask> tasks = taskRepository.listTasks(ctx, sessionId, statusFilter);
            List<Map<String, Object>> result = new ArrayList<>();
            for (BackgroundTask task : tasks) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("taskId", task.getTaskId());
                item.put("agentId", task.getAgentId());
                item.put("status", task.getStatus());
                item.put("completed", task.isCompleted());
                item.put("createdAt", task.getCreatedAt() != null ? task.getCreatedAt().toString() : null);
                item.put("result", task.getResult());
                Exception error = task.getError();
                item.put("error", error != null ? error.getMessage() : null);
                result.add(item);
            }
            return ApiResponse.ok(result);
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.error("Failed to list tasks", e);
            throw BusinessException.processingFailed("Failed to list tasks", e);
        }
    }

    // ============ 私有辅助方法 ============

    private Object resolveSubagentsMiddleware() throws Exception {
        var field = HarnessAgent.class.getDeclaredField("subagentMiddleware");
        field.setAccessible(true);
        return field.get(meetingAssistantAgent);
    }

    private Object resolveAgentSpawnTool() throws Exception {
        Object subagentMiddleware = resolveSubagentsMiddleware();
        Method getTools = subagentMiddleware.getClass().getMethod("getTools");
        List<Object> tools = (List<Object>) getTools.invoke(subagentMiddleware);
        for (Object tool : tools) {
            if (tool.getClass().getSimpleName().equals("AgentSpawnTool")) {
                return tool;
            }
        }
        throw BusinessException.notFound("AgentSpawnTool not found");
    }

    private Object resolveTaskTool(Object subagentMiddleware) throws Exception {
        Method getTools = subagentMiddleware.getClass().getMethod("getTools");
        List<Object> tools = (List<Object>) getTools.invoke(subagentMiddleware);
        for (Object tool : tools) {
            if (tool.getClass().getSimpleName().equals("TaskTool")) {
                return tool;
            }
        }
        throw BusinessException.notFound("TaskTool not found");
    }
}