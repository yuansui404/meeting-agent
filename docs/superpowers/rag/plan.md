# Agent RAG 系统实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 从零搭建一个 Spring Boot + pgvector + DeepSeek-V4-Flash + AgentScope Java 的 Agent RAG 系统，覆盖文档 ETL、混合检索、Agent 问答、会话管理完整链路。

**Architecture:** 分层模块化架构，每层通过接口隔离。API 层 → Agent 层 → 检索层 → ETL 层 → 存储层，各层职责单一可独立测试。

**Tech Stack:** Spring Boot 3.x, PostgreSQL + pgvector, DeepSeek-V4 API, AgentScope Harness, Spring Data JPA, Nacos, Redis, Docker, SLF4J + Logback

## Global Constraints

- 所有 rag.* 配置参数外置到 application.yml（RAG 专属配置），通过 `@ConfigurationProperties` 注入
- 数据库 DDL 通过 `init-db.sql` 管理（沿用现有项目方式），不使用 Flyway
- ORM 使用 Spring Data JPA（`@Entity` + `Repository`），不使用 MyBatis-Plus
- 所有 API 返回值统一使用 `ApiResponse<T>` 包装
- 所有异常经过 `GlobalExceptionHandler` 统一处理
- traceId 通过 MDC 贯穿全链路，日志格式包含 `[traceId]` `[layer]`
- 检索算法（RRF、时间衰减、MMR、证据评估）封装为无状态工具类，不依赖 Spring Bean
- DeepSeek API 调用包装为单独 client 类，统一处理超时、重试、限速
- Agent 使用 AgentScope HarnessAgent（流式），不使用 @AgentTool 注解方式
- FunASR 语音识别管线独立于 RAG，互不依赖，并行演进

---

## 项目目录结构

```
{project-root}/src/main/java/com/meeting/        (沿用当前包名)
├── MeetingAgentApplication.java                  (已存在)
├── config/
│   ├── RagProperties.java          # @ConfigurationProperties(prefix="rag")
│   └── DeepSeekClient.java         # DeepSeek API HTTP 客户端
├── common/
│   ├── ApiResponse.java            # 统一 API 响应
│   ├── BusinessException.java      # 业务异常
│   └── GlobalExceptionHandler.java # 全局异常处理
├── document/
│   ├── DocumentController.java     # /api/document 接口
│   ├── service/
│   │   ├── DocumentUploadService.java  # 上传 + 本地存储
│   │   ├── DocumentParserService.java  # 智谱多模态解析
│   │   ├── ChunkService.java           # 入库 + 向量化
│   │   └── ChunkStrategy.java          # 切分策略接口
│   ├── model/
│   │   ├── entity/DocumentEntity.java         # @Entity JPA
│   │   ├── entity/DocumentChunkEntity.java    # @Entity JPA
│   │   └── dto/UploadResponse.java
│   └── repository/DocumentRepository.java     # JPA Repository
├── retrieval/
│   ├── service/
│   │   ├── HybridSearchService.java      # 主入口，编排全流程
│   │   ├── VectorSearchService.java      # pgvector 向量检索
│   │   ├── FullTextSearchService.java    # tsvector 全文检索
│   │   ├── QueryPlanningService.java     # DIRECT/REWRITE/DECOMPOSE
|   |   ├── Reranker.java                 # Rerank 接口
│   │   └── NoOpReranker.java             # 默认不执行
│   ├── algorithm/                         # ★ 算法工具类（无状态，不依赖 Spring）
│   │   ├── RrfMerger.java                # RRF 融合排序
│   │   ├── TimeDecayScorer.java          # 时间衰减加权
│   │   ├── MmrDeduplicator.java          # MMR 多样性去重
│   │   ├── EvidenceEvaluator.java        # 四级证据评估
│   │   └── CitationBuilder.java          # 引用去重汇聚
│   └── model/
│       ├── ChunkResult.java              # 检索结果（含分数、排名）
│       ├── EvidenceLevel.java            # NONE/WEAK/PARTIAL/SUFFICIENT
│       └── Citation.java                 # 引用来源
├── agent/
│   ├── SearchGuard.java                  # 防重复检索（ThreadLocal）
│   └── ChatService.java                  # HarnessAgent 流式对话（已存在，增强）
├── conversation/
│   ├── ConversationController.java       # /api/conversation CRUD
│   ├── ChatController.java               # POST /api/chat SSE（已存在）
│   ├── FeedbackController.java           # POST /api/feedback
│   ├── service/
│   │   ├── ConversationService.java      # 会话 CRUD
│   │   └── SummaryService.java           # Token 感知滚动摘要
│   └── model/
│       ├── entity/ConversationEntity.java   # @Entity JPA
│       ├── entity/MessageEntity.java        # @Entity JPA
│       └── dto/ChatRequest.java
└── common/
    └── filter/TraceIdFilter.java          # MDC traceId 过滤器
```

**约定：** 每个任务中涉及的文件路径如未标注 `{project-root}`，均指上述包结构中的对应文件。

---

## Phase 1: Foundation（基础设施）

### Task 1: 项目脚手架 + 依赖配置

**Files:**
- Create: `pom.xml`
- Create: `src/main/java/com/ys/agentrag/AgentRagApplication.java`
- Create: `src/main/resources/application.yml`
- Create: `src/main/resources/logback-spring.xml`

**Interfaces:**
- Consumes: (none)
- Produces: Spring Boot 可启动项目

- [ ] **Step 1: 创建 pom.xml**

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0
         https://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>
    <parent>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-parent</artifactId>
        <version>3.4.4</version>
    </parent>
    <groupId>com.ys</groupId>
    <artifactId>agent-rag</artifactId>
    <version>1.0.0</version>
    <name>Agent RAG</name>

    <properties>
        <java.version>21</java.version>
        <!-- JPA + PostgreSQL, 无 MyBatis-Plus -->
    </properties>

    <dependencies>
        <!-- Spring Boot -->
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-web</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-validation</artifactId>
        </dependency>

        <!-- Database -->
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-data-jpa</artifactId>
        </dependency>
        <dependency>
            <groupId>org.postgresql</groupId>
            <artifactId>postgresql</artifactId>
            <scope>runtime</scope>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-jdbc</artifactId>
        </dependency>

        <!-- Nacos Config -->
        <dependency>
            <groupId>com.alibaba.cloud</groupId>
            <artifactId>spring-cloud-starter-alibaba-nacos-config</artifactId>
            <version>2023.0.1.0</version>
        </dependency>

        <!-- HTTP Client (for DeepSeek / Zhipu API) -->
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-webflux</artifactId>
        </dependency>

        <!-- AgentScope Harness -->
        <dependency>
            <groupId>io.agentscope</groupId>
            <artifactId>agentscope-harness</artifactId>
            <version>2.0.0-RC3</version>
        </dependency>

        <!-- Utilities -->
        <dependency>
            <groupId>org.projectlombok</groupId>
            <artifactId>lombok</artifactId>
            <optional>true</optional>
        </dependency>
        <dependency>
            <groupId>com.fasterxml.jackson.core</groupId>
            <artifactId>jackson-databind</artifactId>
        </dependency>
        <dependency>
            <groupId>com.fasterxml.jackson.datatype</groupId>
            <artifactId>jackson-datatype-jsr310</artifactId>
        </dependency>

        <!-- Test -->
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-test</artifactId>
            <scope>test</scope>
        </dependency>
    </dependencies>

    <build>
        <plugins>
            <plugin>
                <groupId>org.springframework.boot</groupId>
                <artifactId>spring-boot-maven-plugin</artifactId>
            </plugin>
        </plugins>
    </build>
</project>
```

- [ ] **Step 2: 创建主启动类**

```java
package com.ys.agentrag;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class AgentRagApplication {
    public static void main(String[] args) {
        SpringApplication.run(AgentRagApplication.class, args);
    }
}
```

- [ ] **Step 3: 创建 application.yml**

```yaml
server:
  port: 8080

spring:
  nacos:  # Nacos 配置中心（可选，无 Nacos 时走本地配置）
    config:
      server-addr: ${NACOS_ADDR:localhost:8848}
      group: DEFAULT_GROUP
      file-extension: yaml
      refresh-enabled: true
  config:
    import: optional:nacos:meeting-agent.yaml
  datasource:
    url: jdbc:postgresql://localhost:5432/meeting_agent
    username: user
    password: password
    driver-class-name: org.postgresql.Driver
  jpa:
    hibernate:
      ddl-auto: update
    show-sql: true
    properties:
      hibernate:
        dialect: org.hibernate.dialect.PostgreSQLDialect
  servlet:
    multipart:
      max-file-size: 100MB
      max-request-size: 500MB
  jackson:
    serialization:
      write-dates-as-timestamps: false
    date-format: yyyy-MM-dd HH:mm:ss

# 应用配置（占位，后续 Task 细化）
rag:
  chunk:
    strategy: structural
    size: 512
    overlap: 128
  retrieval:
    vector_topk: 20
    fts_topk: 20
    rrf_k: 60
    rerank_enabled: false
    rerank_topk: 5
  evidence:
    threshold: 0.7
    adaptive: true
    adaptive_step: 0.05
    adaptive_max: 0.85
  time_decay:
    enabled: true
    recent_days: 30
    recent_weight: 1.2
    normal_weight: 1.0
    old_weight: 0.8
    archive_weight: 0.5
  conversation:
    summary_trigger: 3000
    max_visible_messages: 10
    max_context_tokens: 32000
  deepseek:
    api-key: ${DEEPSEEK_API_KEY:}      # Nacos 中配置，本地留空
    model: deepseek-chat
    base-url: https://api.deepseek.com
    embedding-model: deepseek-embedding
    embedding-url: https://api.deepseek.com/v1/embeddings
    max-tokens: 2048
    temperature: 0.1
  zhipu:
    api-key: ${ZHIPU_API_KEY:}         # Nacos 中配置，本地留空
    model: glm-4v

# 日志
logging:
  level:
    com.ys.agentrag: INFO
  pattern:
    console: "%d{yyyy-MM-dd HH:mm:ss} [%X{traceId:-no-trace}] [%X{layer:-NONE}] [%thread] %-5level %logger{36} - %msg%n"
```

- [ ] **Step 4: 创建 logback-spring.xml**

```xml
<?xml version="1.0" encoding="UTF-8"?>
<configuration>
    <appender name="FILE" class="ch.qos.logback.core.rolling.RollingFileAppender">
        <file>logs/agent-rag.log</file>
        <rollingPolicy class="ch.qos.logback.core.rolling.TimeBasedRollingPolicy">
            <fileNamePattern>logs/agent-rag.%d{yyyy-MM-dd}.log</fileNamePattern>
            <maxHistory>30</maxHistory>
        </rollingPolicy>
        <encoder>
            <pattern>%d{yyyy-MM-dd HH:mm:ss} [%X{traceId:-no-trace}] [%X{layer:-NONE}] [%thread] %-5level %logger{36} - %msg%n</pattern>
        </encoder>
    </appender>

    <root level="INFO">
        <appender-ref ref="FILE"/>
        <appender-ref ref="CONSOLE"/>
    </root>
</configuration>
```

- [ ] **Step 5: 验证项目启动**

Run: `mvn compile -q`
Expected: BUILD SUCCESS

- [ ] **Step 6: Commit**

```bash
git init
git add pom.xml src/ gitignore
git commit -m "chore: scaffold Spring Boot project with dependencies"
```

---

### Task 2: 通用基础设施

**Files:**
- Create: `src/main/java/com/ys/agentrag/common/ApiResponse.java`
- Create: `src/main/java/com/ys/agentrag/common/BusinessException.java`
- Create: `src/main/java/com/ys/agentrag/common/GlobalExceptionHandler.java`

**Interfaces:**
- Consumes: (none)
- Produces: `ApiResponse<T>`, `BusinessException`, `GlobalExceptionHandler`

- [ ] **Step 1: 创建 ApiResponse 统一响应**

```java
package com.ys.agentrag.common;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApiResponse<T>(boolean success, T data, String message, String traceId) {

    public static <T> ApiResponse<T> ok(T data) {
        return new ApiResponse<>(true, data, null, null);
    }

    public static <T> ApiResponse<T> ok(T data, String message) {
        return new ApiResponse<>(true, data, message, null);
    }

    public static <T> ApiResponse<T> error(String message) {
        return new ApiResponse<>(false, null, message, null);
    }

    public ApiResponse<T> withTraceId(String traceId) {
        return new ApiResponse<>(success, data, message, traceId);
    }
}
```

- [ ] **Step 2: 创建 BusinessException**

```java
package com.ys.agentrag.common;

import lombok.Getter;

@Getter
public class BusinessException extends RuntimeException {
    private final int code;

    public BusinessException(int code, String message) {
        super(message);
        this.code = code;
    }

    public BusinessException(String message) {
        this(400, message);
    }

    public static BusinessException notFound(String msg) {
        return new BusinessException(404, msg);
    }

    public static BusinessException timeout(String msg) {
        return new BusinessException(408, msg);
    }

    public static BusinessException rateLimited(String msg) {
        return new BusinessException(429, msg);
    }
}
```

- [ ] **Step 3: 创建 GlobalExceptionHandler**

```java
package com.ys.agentrag.common;

import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(BusinessException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ApiResponse<Void> handleBusiness(BusinessException e, HttpServletRequest request) {
        log.warn("Business exception: {}", e.getMessage());
        return ApiResponse.error(e.getMessage()).withTraceId(MDC.get("traceId"));
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    @ResponseStatus(HttpStatus.PAYLOAD_TOO_LARGE)
    public ApiResponse<Void> handleUploadSize() {
        return ApiResponse.error("文件大小超过限制");
    }

    @ExceptionHandler(Exception.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public ApiResponse<Void> handleUnknown(Exception e, HttpServletRequest request) {
        log.error("Unexpected error: ", e);
        return ApiResponse.error("服务器内部错误").withTraceId(MDC.get("traceId"));
    }
}
```

- [ ] **Step 4: Compile check**

Run: `mvn compile -q`
Expected: BUILD SUCCESS

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/ys/agentrag/common/
git commit -m "feat: add common infrastructure (ApiResponse, BusinessException, GlobalExceptionHandler)"
```

---

### Task 3: 配置映射 + DeepSeek Client

**Files:**
- Create: `src/main/java/com/ys/agentrag/config/RagProperties.java`
- Create: `src/main/java/com/ys/agentrag/config/DeepSeekClient.java`
- Create: `src/main/java/com/ys/agentrag/config/ZhipuClient.java`

**Interfaces:**
- Consumes: `application.yml` 中的 `rag.*` 配置
- Produces: `RagProperties` bean, `DeepSeekClient`, `ZhipuClient`

- [ ] **Step 1: 创建 RagProperties**

```java
package com.ys.agentrag.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "rag")
public class RagProperties {
    private Chunk chunk = new Chunk();
    private Retrieval retrieval = new Retrieval();
    private Evidence evidence = new Evidence();
    private TimeDecay timeDecay = new TimeDecay();
    private Conversation conversation = new Conversation();
    private Deepseek deepseek = new Deepseek();
    private Zhipu zhipu = new Zhipu();

    @Data public static class Chunk {
        private String strategy = "structural";
        private int size = 512;
        private int overlap = 128;
    }

    @Data public static class Retrieval {
        private int vectorTopk = 20;
        private int ftsTopk = 20;
        private int rrfK = 60;
        private boolean rerankEnabled = false;
        private int rerankTopk = 5;
    }

    @Data public static class Evidence {
        private double threshold = 0.7;
        private boolean adaptive = true;
        private double adaptiveStep = 0.05;
        private double adaptiveMax = 0.85;
    }

    @Data public static class TimeDecay {
        private boolean enabled = true;
        private int recentDays = 30;
        private double recentWeight = 1.2;
        private double normalWeight = 1.0;
        private double oldWeight = 0.8;
        private double archiveWeight = 0.5;
    }

    @Data public static class Conversation {
        private int summaryTrigger = 3000;
        private int maxVisibleMessages = 10;
        private int maxContextTokens = 32000;
    }

    @Data public static class Deepseek {
        private String apiKey;
        private String model = "deepseek-v4-flash";
        private String baseUrl = "https://api.deepseek.com";
        private int maxTokens = 2048;
        private double temperature = 0.1;
    }

    @Data public static class Zhipu {
        private String apiKey;
        private String model = "glm-4v";
    }
}
```

- [ ] **Step 2: 创建 DeepSeekClient**

```java
package com.ys.agentrag.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.ys.agentrag.common.BusinessException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.util.retry.Retry;

import java.time.Duration;
import java.util.List;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class DeepSeekClient {

    private final RagProperties properties;
    private final ObjectMapper objectMapper;
    private final WebClient webClient = WebClient.builder().build();

    /**
     * 聊天补全（流式、非流式通用）
     */
    public JsonNode chat(List<Map<String, String>> messages, boolean stream) {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("model", properties.getDeepseek().getModel());
        body.put("temperature", properties.getDeepseek().getTemperature());
        body.put("max_tokens", properties.getDeepseek().getMaxTokens());
        body.put("stream", stream);

        ArrayNode msgArray = body.putArray("messages");
        messages.forEach(m -> {
            ObjectNode msg = msgArray.addObject();
            msg.put("role", m.get("role"));
            msg.put("content", m.get("content"));
        });

        try {
            String json = webClient.post()
                .uri(properties.getDeepseek().getBaseUrl() + "/v1/chat/completions")
                .header("Authorization", "Bearer " + properties.getDeepseek().getApiKey())
                .header("Content-Type", "application/json")
                .bodyValue(body)
                .retrieve()
                .bodyToMono(String.class)
                .retryWhen(Retry.backoff(3, Duration.ofSeconds(1))
                    .filter(e -> e.getMessage() != null && e.getMessage().contains("429")))
                .timeout(Duration.ofSeconds(30))
                .block();

            return objectMapper.readTree(json);
        } catch (Exception e) {
            if (e.getMessage() != null && e.getMessage().contains("429")) {
                throw BusinessException.rateLimited("当前处理繁忙，请稍后再试");
            }
            if (e.getMessage() != null && e.getMessage().contains("timeout")) {
                throw BusinessException.timeout("处理超时，请重新发送");
            }
            log.error("DeepSeek API call failed", e);
            throw new BusinessException("AI 服务调用失败");
        }
    }

    /**
     * 获取 Embedding
     */
    public List<Float> embedding(String text) {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("model", "deepseek-embedding");
        body.put("input", text);

        try {
            String json = webClient.post()
                .uri(properties.getDeepseek().getBaseUrl() + "/v1/embeddings")
                .header("Authorization", "Bearer " + properties.getDeepseek().getApiKey())
                .header("Content-Type", "application/json")
                .bodyValue(body)
                .retrieve()
                .bodyToMono(String.class)
                .timeout(Duration.ofSeconds(10))
                .block();

            JsonNode data = objectMapper.readTree(json).get("data").get(0).get("embedding");
            List<Float> result = new java.util.ArrayList<>();
            data.forEach(n -> result.add(n.floatValue()));
            return result;
        } catch (Exception e) {
            log.error("Embedding API call failed", e);
            throw new BusinessException("向量化服务调用失败");
        }
    }
}
```

- [ ] **Step 3: 创建 ZhipuClient**

```java
package com.ys.agentrag.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.ys.agentrag.common.BusinessException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.util.Base64;

@Slf4j
@Component
@RequiredArgsConstructor
public class ZhipuClient {

    private final RagProperties properties;
    private final ObjectMapper objectMapper;
    private final WebClient webClient = WebClient.builder().build();

    /**
     * 用智谱多模态模型解析文档图片，提取文本内容
     */
    public String parseDocumentImage(byte[] imageBytes) {
        String base64 = Base64.getEncoder().encodeToString(imageBytes);
        String dataUrl = "data:image/png;base64," + base64;

        ObjectNode body = objectMapper.createObjectNode();
        body.put("model", properties.getZhipu().getModel());

        ArrayNode messages = body.putArray("messages");
        ObjectNode msg = messages.addObject();
        msg.put("role", "user");

        ArrayNode content = msg.putArray("content");
        ObjectNode textPart = content.addObject();
        textPart.put("type", "text");
        textPart.put("text", "请提取这份文档中的所有文字内容，保留原有的格式和段落结构");

        ObjectNode imagePart = content.addObject();
        imagePart.put("type", "image_url");
        imagePart.put("image_url", dataUrl);

        try {
            String json = webClient.post()
                .uri("https://open.bigmodel.cn/api/paas/v4/chat/completions")
                .header("Authorization", "Bearer " + properties.getZhipu().getApiKey())
                .header("Content-Type", "application/json")
                .bodyValue(body)
                .retrieve()
                .bodyToMono(String.class)
                .timeout(Duration.ofSeconds(60))
                .block();

            return objectMapper.readTree(json)
                .path("choices").get(0)
                .path("message").path("content")
                .asText();
        } catch (Exception e) {
            log.error("Zhipu API call failed", e);
            throw new BusinessException("文档解析失败");
        }
    }
}
```

- [ ] **Step 4: Compile**

Run: `mvn compile -q`
Expected: BUILD SUCCESS

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/ys/agentrag/config/
git commit -m "feat: add RagProperties, DeepSeekClient, ZhipuClient"
```

---

### Task 4: TraceId 全链路日志

**Files:**
- Create: `src/main/java/com/ys/agentrag/common/filter/TraceIdFilter.java`

**Interfaces:**
- Consumes: (none)
- Produces: 所有请求自动携带 traceId，写入 MDC

- [ ] **Step 1: 创建 TraceIdFilter**

```java
package com.ys.agentrag.common.filter;

import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.UUID;

@Component
@Order(Integer.MIN_VALUE)
public class TraceIdFilter implements Filter {

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {

        String traceId = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        MDC.put("traceId", traceId);
        MDC.put("layer", "API");

        if (response instanceof HttpServletResponse httpResponse) {
            httpResponse.setHeader("X-Trace-Id", traceId);
        }

        try {
            chain.doFilter(request, response);
        } finally {
            MDC.clear();
        }
    }
}
```

- [ ] **Step 2: 编译验证**

Run: `mvn compile -q`
Expected: BUILD SUCCESS

- [ ] **Step 3: Commit**

```bash
git add src/main/java/com/ys/agentrag/common/filter/
git commit -m "feat: add MDC traceId filter for full-request logging"
```

---

## Phase 2: Data Layer（数据层）

### Task 5: init-db.sql — 文档表

**Files:**
- Modify: `docker/init-db.sql`（在现有文件基础上追加 RAG 表）

**Interfaces:**
- Produces: `document`, `document_chunk` 两张表

> **说明**：不使用 Flyway。项目已有 `docker/init-db.sql` 在 PostgreSQL 首次初始化时执行。
> 在现有 DDL 文件末尾追加 RAG 表的 CREATE TABLE 语句。

- [ ] **Step 1: 创建 V1 迁移文件**

```sql
-- 追加到 docker/init-db.sql 末尾

注意：
- init-db.sql 已存在 meeting_minutes / meeting_vectors 表的 DDL
- 以下内容追加在已有表定义之后
- 新旧表共存：旧表用于音频转写管线，新表用于 RAG 文档管理
- 如果决定统一使用新表，后续可从 init-db.sql 中移除旧表定义

CREATE TABLE IF NOT EXISTS document (
    id BIGSERIAL PRIMARY KEY,
    title VARCHAR(255) NOT NULL,
    file_type VARCHAR(20) NOT NULL,
    file_path VARCHAR(500) NOT NULL,
    file_size BIGINT NOT NULL DEFAULT 0,
    meeting_date DATE,
    status VARCHAR(20) NOT NULL DEFAULT 'UPLOADED',
    chunk_count INT NOT NULL DEFAULT 0,
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE TABLE document_chunk (
    id BIGSERIAL PRIMARY KEY,
    document_id BIGINT NOT NULL REFERENCES document(id) ON DELETE CASCADE,
    content TEXT NOT NULL,
    embedding VECTOR(1536),
    chunk_index INT NOT NULL,
    speaker VARCHAR(100),
    section_type VARCHAR(50),
    metadata JSONB DEFAULT '{}',
    created_at TIMESTAMP NOT NULL DEFAULT NOW()
);

-- 索引
CREATE INDEX idx_document_status ON document(status);
CREATE INDEX idx_document_meeting_date ON document(meeting_date);
CREATE INDEX idx_chunk_document_id ON document_chunk(document_id);
CREATE INDEX idx_chunk_section_type ON document_chunk(section_type);

-- pgvector HNSW 索引（需要 pgvector 0.5.0+）
CREATE INDEX idx_chunk_embedding ON document_chunk USING hnsw (embedding vector_cosine_ops)
    WITH (m = 16, ef_construction = 200);

-- tsvector 全文索引
ALTER TABLE document_chunk ADD COLUMN content_tsv tsvector
    GENERATED ALWAYS AS (to_tsvector('simple', coalesce(content, ''))) STORED;
CREATE INDEX idx_chunk_content_tsv ON document_chunk USING gin (content_tsv);
```

- [ ] **Step 2: Commit**

```bash
# init-db.sql 路径不同，且是修改已有文件
git add docker/init-db.sql
git commit -m "feat: add RAG document and document_chunk tables to init-db.sql"
```

---

### Task 6: init-db.sql — 会话表

**Files:**
- Modify: `docker/init-db.sql`（追加会话表）

**Interfaces:**
- Produces: `conversation`, `message` 两张表

- [ ] **Step 1: 创建 V2 迁移文件**

```sql
-- 追加到 docker/init-db.sql 末尾（在 document 表定义之后）

CREATE TABLE IF NOT EXISTS conversation (
    id BIGSERIAL PRIMARY KEY,
    title VARCHAR(200) NOT NULL DEFAULT '新对话',
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    context_summary TEXT,
    compression_history JSONB DEFAULT '[]',
    message_count INT NOT NULL DEFAULT 0,
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE TABLE message (
    id BIGSERIAL PRIMARY KEY,
    conversation_id BIGINT NOT NULL REFERENCES conversation(id) ON DELETE CASCADE,
    role VARCHAR(20) NOT NULL,
    content TEXT NOT NULL,
    trace_id VARCHAR(64),
    metadata JSONB DEFAULT '{}',
    created_at TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_message_conversation_id ON message(conversation_id);
CREATE INDEX idx_message_created_at ON message(created_at);
CREATE INDEX idx_conversation_updated_at ON conversation(updated_at);
CREATE INDEX idx_conversation_status ON conversation(status);
```

- [ ] **Step 2: Commit**

```bash
git add docker/init-db.sql
git commit -m "feat: add RAG conversation and message tables to init-db.sql"
```

---

### Task 7: 实体类 + MyBatis-Plus Mappers

**Files:**
- Create: `src/main/java/com/ys/agentrag/document/model/entity/DocumentEntity.java`
- Create: `src/main/java/com/ys/agentrag/document/model/entity/DocumentChunkEntity.java`
- Create: `src/main/java/com/ys/agentrag/document/mapper/DocumentMapper.java`
- Create: `src/main/java/com/ys/agentrag/document/mapper/DocumentChunkMapper.java`
- Create: `src/main/java/com/ys/agentrag/conversation/model/entity/ConversationEntity.java`
- Create: `src/main/java/com/ys/agentrag/conversation/model/entity/MessageEntity.java`
- Create: `src/main/java/com/ys/agentrag/conversation/mapper/ConversationMapper.java`
- Create: `src/main/java/com/ys/agentrag/conversation/mapper/MessageMapper.java`

**Interfaces:**
- Consumes: V1, V2 迁移后的表结构
- Produces: Entity + Mapper 可以 CRUD

- [ ] **Step 1: 创建 DocumentEntity（JPA）**

```java
package com.meeting.document.model.entity;

import jakarta.persistence.*;
import lombok.Data;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "document")
public class DocumentEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String title;

    @Column(name = "file_type", length = 20)
    private String fileType;

    @Column(name = "file_path", length = 500)
    private String filePath;

    @Column(name = "file_size")
    private Long fileSize;

    @Column(name = "meeting_date")
    private LocalDate meetingDate;

    @Column(length = 20)
    private String status;

    @Column(name = "chunk_count")
    private Integer chunkCount;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
        if (status == null) status = "UPLOADED";
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
```

- [ ] **Step 2: 创建 DocumentChunkEntity**

```java
package com.meeting.document.model.entity;

import jakarta.persistence.*;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "document_chunk")
public class DocumentChunkEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "document_id", nullable = false)
    private Long documentId;

    @Column(columnDefinition = "TEXT", nullable = false)
    private String content;

    @Column(columnDefinition = "VECTOR(1536)")
    private float[] embedding;   // pgvector 向量，JPA 通过原生查询读写

    @Column(name = "chunk_index")
    private Integer chunkIndex;

    @Column(length = 100)
    private String speaker;

    @Column(name = "section_type", length = 50)
    private String sectionType;

    @Column(columnDefinition = "JSONB DEFAULT '{}'")
    private String metadata;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }
}
```

- [ ] **Step 3: 创建 DocumentRepository（JPA）**

```java
package com.meeting.document.repository;

import com.meeting.document.model.entity.DocumentEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface DocumentRepository extends JpaRepository<DocumentEntity, Long> {
    List<DocumentEntity> findByStatusOrderByCreatedAtDesc(String status);
    List<DocumentEntity> findAllByOrderByCreatedAtDesc();
}
```

- [ ] **Step 4: 创建 DocumentChunkMapper（含向量检索）**

```java
package com.meeting.document.repository;

import com.meeting.document.model.entity.DocumentChunkEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Map;

@Repository
public interface DocumentChunkRepository extends JpaRepository<DocumentChunkEntity, Long> {

    List<DocumentChunkEntity> findByDocumentIdOrderByChunkIndex(Long documentId);

    void deleteByDocumentId(Long documentId);

    /**
     * 向量检索：cosine 距离，返回 TopK
     * embedding 参数需要先转为 pgvector 格式字符串
     */
    @Query(value = """
        SELECT id, document_id, content, chunk_index, speaker, section_type, metadata,
               1 - (embedding <=> CAST(:embedding AS vector)) AS similarity
        FROM document_chunk
        WHERE embedding IS NOT NULL
        ORDER BY embedding <=> CAST(:embedding AS vector)
        LIMIT :topK
        """, nativeQuery = true)
    List<Map<String, Object>> vectorSearch(@Param("embedding") String embedding, @Param("topK") int topK);
}
```

- [ ] **Step 5: 创建 ConversationEntity**

```java
package com.meeting.conversation.model.entity;

import jakarta.persistence.*;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "conversation")
public class ConversationEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String title;

    @Column(length = 20)
    private String status;

    @Column(name = "context_summary", columnDefinition = "TEXT")
    private String contextSummary;

    @Column(name = "compression_history", columnDefinition = "JSONB DEFAULT '[]'")
    private String compressionHistory;

    @Column(name = "message_count")
    private Integer messageCount;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
        if (title == null) title = "新对话";
        if (status == null) status = "ACTIVE";
        if (messageCount == null) messageCount = 0;
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
```

- [ ] **Step 6: 创建 MessageEntity**

```java
package com.meeting.conversation.model.entity;

import jakarta.persistence.*;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "message")
public class MessageEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "conversation_id", nullable = false)
    private Long conversationId;

    @Column(length = 20)
    private String role;

    @Column(columnDefinition = "TEXT", nullable = false)
    private String content;

    @Column(name = "trace_id", length = 64)
    private String traceId;

    @Column(columnDefinition = "JSONB DEFAULT '{}'")
    private String metadata;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }
}
```

- [ ] **Step 7: 创建 ConversationRepository + MessageRepository（JPA）**

```java
package com.meeting.conversation.repository;

import com.meeting.conversation.model.entity.ConversationEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ConversationRepository extends JpaRepository<ConversationEntity, Long> {
    List<ConversationEntity> findByStatusOrderByUpdatedAtDesc(String status);
}
```

```java
package com.meeting.conversation.repository;

import com.meeting.conversation.model.entity.MessageEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface MessageRepository extends JpaRepository<MessageEntity, Long> {
    List<MessageEntity> findByConversationIdOrderByCreatedAtAsc(Long conversationId);
}
```

- [ ] **Step 8: Compile**

Run: `mvn compile -q`
Expected: BUILD SUCCESS

- [ ] **Step 9: Commit**

```bash
git add src/main/java/com/ys/agentrag/document/ src/main/java/com/ys/agentrag/conversation/
git commit -m "feat: add entity classes and MyBatis-Plus mappers"
```

---

## Phase 3: Document ETL（文档处理）

### Task 8: 文档上传 + 本地存储服务

**Files:**
- Create: `src/main/java/com/meeting/document/service/DocumentUploadService.java`

**Interfaces:**
- Consumes: `DocumentRepository`（JPA）
- Produces: `upload(file) -> DocumentEntity`，自动提取会议日期、创建本地文件

- [ ] **Step 1: 创建 DocumentUploadService**

```java
package com.meeting.document.service;

import com.meeting.common.BusinessException;
import com.meeting.document.model.entity.DocumentEntity;
import com.meeting.document.repository.DocumentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;

@Slf4j
@Service
@RequiredArgsConstructor
public class DocumentUploadService {

    private final DocumentRepository documentRepository;  // JPA Repository
    private final Path uploadDir = Paths.get("data/documents");

    public DocumentEntity upload(MultipartFile file) {
        String originalName = file.getOriginalFilename();
        if (originalName == null || originalName.isBlank()) {
            throw new BusinessException("文件名不能为空");
        }

        String ext = getExtension(originalName).toLowerCase();
        if (!List.of("pdf", "doc", "docx").contains(ext)) {
            throw new BusinessException("仅支持 PDF 和 Doc 格式");
        }

        try {
            Files.createDirectories(uploadDir);
            String storedName = UUID.randomUUID() + "." + ext;
            Path targetPath = uploadDir.resolve(storedName);
            file.transferTo(targetPath.toFile());

            DocumentEntity entity = new DocumentEntity();
            entity.setTitle(originalName);
            entity.setFileType(ext);
            entity.setFilePath(targetPath.toString());
            entity.setFileSize(file.getSize());
            entity.setMeetingDate(extractMeetingDate(originalName));
            entity.setStatus("UPLOADED");
            documentRepository.save(entity);  // JPA save

            log.info("Document uploaded: id={}, name={}, size={}", entity.getId(), originalName, file.getSize());
            return entity;
        } catch (IOException e) {
            log.error("File upload failed", e);
            throw new BusinessException("文件上传失败");
        }
    }

    private String getExtension(String filename) {
        int idx = filename.lastIndexOf('.');
        return idx == -1 ? "" : filename.substring(idx + 1);
    }

    /**
     * 从文件名提取会议日期：如 "2025-Q2-周会纪要.pdf" -> 2025-04-01
     * 也支持 "2025-06-15-周会.pdf" -> 2025-06-15
     */
    LocalDate extractMeetingDate(String filename) {
        // 先尝试 yyyy-MM-dd
        var matcher = Pattern.compile("(\\d{4})-(\\d{2})-(\\d{2})").matcher(filename);
        if (matcher.find()) {
            return LocalDate.of(
                Integer.parseInt(matcher.group(1)),
                Integer.parseInt(matcher.group(2)),
                Integer.parseInt(matcher.group(3))
            );
        }
        // 再尝试 Q1/Q2/Q3/Q4 -> 季度首月
        var qMatcher = Pattern.compile("(20\\d{2})-Q([1-4])").matcher(filename);
        if (qMatcher.find()) {
            int year = Integer.parseInt(qMatcher.group(1));
            int quarter = Integer.parseInt(qMatcher.group(2));
            return LocalDate.of(year, quarter * 3 - 2, 1);
        }
        return null;
    }
}
```

- [ ] **Step 2: Commit**

```bash
git add src/main/java/com/ys/agentrag/document/service/DocumentUploadService.java
git commit -m "feat: add document upload and local storage service"
```

---

### Task 9: 结构化感知切分器

**Files:**
- Create: `src/main/java/com/ys/agentrag/document/service/ChunkStrategy.java`（接口）
- Create: `src/main/java/com/ys/agentrag/document/service/StructuralChunkStrategy.java`

**Interfaces:**
- Produces: `ChunkStrategy.chunk(text, RagProperties.Chunk) -> List<ChunkSegment>`

- [ ] **Step 1: 创建 ChunkSegment 模型**

```java
// 在 document/model/ 下创建
package com.ys.agentrag.document.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
@AllArgsConstructor
public class ChunkSegment {
    private String content;
    private int index;
    private String speaker;
    private String sectionType;  // STATEMENT | SUMMARY | DECISION
}
```

- [ ] **Step 2: 创建 ChunkStrategy 接口**

```java
package com.ys.agentrag.document.service;

import com.ys.agentrag.config.RagProperties;
import com.ys.agentrag.document.model.ChunkSegment;

import java.util.List;

public interface ChunkStrategy {
    List<ChunkSegment> chunk(String text, RagProperties.Chunk config);
}
```

- [ ] **Step 3: 创建 StructuralChunkStrategy**

```java
package com.ys.agentrag.document.service;

import com.ys.agentrag.config.RagProperties;
import com.ys.agentrag.document.model.ChunkSegment;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Component
@ConditionalOnProperty(value = "rag.chunk.strategy", havingValue = "structural", matchIfMissing = true)
public class StructuralChunkStrategy implements ChunkStrategy {

    // 识别发言人模式：中文人名（2-4字）+ 冒号
    private static final Pattern SPEAKER_PATTERN = Pattern.compile(
        "^([\\u4e00-\\u9fa5]{2,4}[：:])\\s*(.*)", Pattern.MULTILINE
    );

    // 识别总结/决策标记
    private static final Pattern SECTION_PATTERN = Pattern.compile(
        "^\\[?(总结|决策|会议结论|决议|下一步)]?[:：]?\\s*(.*)", Pattern.MULTILINE
    );

    @Override
    public List<ChunkSegment> chunk(String text, RagProperties.Chunk config) {
        List<ChunkSegment> segments = new ArrayList<>();
        String[] lines = text.split("\n");
        StringBuilder current = new StringBuilder();
        String currentSpeaker = null;
        int chunkIndex = 0;

        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.isBlank()) continue;

            // 检测是否为新的分段开始
            boolean isNewSegment = false;

            // 检查发言人切换
            Matcher speakerMatcher = SPEAKER_PATTERN.matcher(trimmed);
            // 检查总结/决策标记
            String sectionType = null;
            Matcher sectionMatcher = SECTION_PATTERN.matcher(trimmed);

            if (sectionMatcher.find()) {
                sectionType = switch (sectionMatcher.group(1)) {
                    case "总结", "会议结论" -> "SUMMARY";
                    case "决策", "决议", "下一步" -> "DECISION";
                    default -> "STATEMENT";
                };
                isNewSegment = true;
            } else if (speakerMatcher.find()) {
                String newSpeaker = speakerMatcher.group(1).replace("：", "").replace(":", "");
                if (currentSpeaker != null && !newSpeaker.equals(currentSpeaker)) {
                    isNewSegment = true;
                }
                currentSpeaker = newSpeaker;
                sectionType = "STATEMENT";
            }

            // 检查是否需要切分当前段
            if (isNewSegment && current.length() > 0) {
                segments.add(buildSegment(current.toString(), chunkIndex++, currentSpeaker, sectionType));
                current = new StringBuilder();

                // 重叠窗口：保留上一段末尾内容
                if (!segments.isEmpty() && config.getOverlap() > 0) {
                    String prevContent = segments.get(segments.size() - 1).getContent();
                    String overlap = prevContent.length() > config.getOverlap()
                        ? prevContent.substring(prevContent.length() - config.getOverlap())
                        : prevContent;
                    current.append("[overlap]").append(overlap).append("\n");
                }
            }

            current.append(trimmed).append("\n");

            // 检查超过 maxSize 是否需要强制切分
            if (current.length() >= config.getSize()) {
                String content = current.toString();
                // 在最近的句子边界切分
                int splitAt = findSentenceBoundary(content, config.getSize());
                segments.add(buildSegment(content.substring(0, splitAt), chunkIndex++, currentSpeaker, sectionType));
                current = new StringBuilder(content.substring(splitAt));
            }
        }

        // 处理剩余内容
        if (current.length() > 0) {
            segments.add(buildSegment(current.toString(), chunkIndex, currentSpeaker, null));
        }

        return segments;
    }

    private ChunkSegment buildSegment(String content, int index, String speaker, String sectionType) {
        return ChunkSegment.builder()
            .content(content.trim())
            .index(index)
            .speaker(speaker)
            .sectionType(sectionType)
            .build();
    }

    private int findSentenceBoundary(String text, int near) {
        if (near >= text.length()) return text.length();
        // 在 near 位置向前找句子结束符
        int pos = Math.min(near, text.length() - 1);
        for (int i = pos; i > Math.max(0, pos - 200); i--) {
            char c = text.charAt(i);
            if (c == '。' || c == '！' || c == '？' || c == '\n') {
                return i + 1;
            }
        }
        return near;
    }
}
```

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/ys/agentrag/document/service/ChunkStrategy.java
git add src/main/java/com/ys/agentrag/document/service/StructuralChunkStrategy.java
git add src/main/java/com/ys/agentrag/document/model/ChunkSegment.java
git commit -m "feat: add structured-aware chunk strategy for meeting minutes"
```

---

### Task 10: Document ETL Service（文档解析 + 切分 + 入向量）

**Files:**
- Create: `src/main/java/com/ys/agentrag/document/service/DocumentParserService.java`
- Create: `src/main/java/com/ys/agentrag/document/service/ChunkService.java`

**Interfaces:**
- Produces: `DocumentParserService.parse(filePath) -> String`; `ChunkService.storeChunks(docId, chunks)`

- [ ] **Step 1: 创建 DocumentParserService**

```java
package com.ys.agentrag.document.service;

import com.ys.agentrag.common.BusinessException;
import com.ys.agentrag.config.ZhipuClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

@Slf4j
@Service
@RequiredArgsConstructor
public class DocumentParserService {

    private final ZhipuClient zhipuClient;

    /**
     * 解析文档为纯文本
     * PDF -> 逐页转图片 -> 智谱多模态提取文本
     * Doc -> 简化方案：后续增强
     */
    public String parse(String filePath) {
        // V1 简化实现：读取文件内容，按页发送到智谱解析
        // 需要引入 PDFBox 等库做 PDF 转图片，或直接用智谱的文件 API
        // 这里标注为 TODO，实际集成时按 Zhipu 的文档实现

        // 伪代码：
        // 1. 根据文件类型选择合适的解析策略
        // 2. PDF: 用 PDFBox 每页渲染为图片，调 ZhipuClient.parseDocumentImage()
        // 3. 汇总所有页面的文本
        // 4. 返回完整文本

        throw new BusinessException("文档解析暂未实现，需集成 PDFBox/Doc 解析库");
    }
}
```

- [ ] **Step 2: 创建 ChunkService**

```java
package com.ys.agentrag.document.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.meeting.common.BusinessException;
import com.meeting.config.DeepSeekClient;
import com.meeting.config.RagProperties;
import com.meeting.document.model.ChunkSegment;
import com.meeting.document.model.entity.DocumentChunkEntity;
import com.meeting.document.model.entity.DocumentEntity;
import com.meeting.document.repository.DocumentChunkRepository;
import com.meeting.document.repository.DocumentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class ChunkService {

    private final DocumentRepository documentRepository;        // JPA
    private final DocumentChunkRepository chunkRepository;      // JPA
    private final DeepSeekClient deepSeekClient;
    private final ChunkStrategy chunkStrategy;
    private final RagProperties ragProperties;
    private final ObjectMapper objectMapper;

    /**
     * 对文档执行 ETL：切分 → 向量化 → 入库
     */
    @Transactional
    public void processDocument(Long documentId, String text) {
        DocumentEntity doc = documentRepository.findById(documentId)
            .orElseThrow(() -> BusinessException.notFound("文档不存在"));

        // 1. 状态置 PROCESSING
        doc.setStatus("PROCESSING");
        documentRepository.save(doc);

        try {
            // 2. 结构化切分
            List<ChunkSegment> segments = chunkStrategy.chunk(text, ragProperties.getChunk());
            log.info("Document {} chunked into {} segments", documentId, segments.size());

            // 3. 逐段向量化 + 入库
            for (ChunkSegment segment : segments) {
                DocumentChunkEntity chunk = new DocumentChunkEntity();
                chunk.setDocumentId(documentId);
                chunk.setContent(segment.getContent());
                chunk.setChunkIndex(segment.getIndex());
                chunk.setSpeaker(segment.getSpeaker());
                chunk.setSectionType(segment.getSectionType());

                // 向量化
                float[] embedding = deepSeekClient.embedding(segment.getContent())
                    .stream().mapToDouble(Float::floatValue).toArray();
                // 转为 float[]
                // 注意：DeepSeekClient 返回 List<Float>，需转换
                chunk.setEmbedding(embedding);

                // 元数据
                chunk.setMetadata(objectMapper.writeValueAsString(
                    java.util.Map.of("length", segment.getContent().length())
                ));

                chunkRepository.save(chunk);
            }

            // 4. 更新文档状态
            doc.setStatus("COMPLETED");
            doc.setChunkCount(segments.size());
            documentRepository.save(doc);

            log.info("Document {} ETL completed, {} chunks", documentId, segments.size());

        } catch (Exception e) {
            log.error("Document {} ETL failed", documentId, e);
            doc.setStatus("FAILED");
            documentRepository.save(doc);
            throw new BusinessException("文档处理失败: " + e.getMessage());
        }
    }
}
```

- [ ] **Step 3: Commit**

```bash
git add src/main/java/com/ys/agentrag/document/service/DocumentParserService.java
git add src/main/java/com/ys/agentrag/document/service/ChunkService.java
git commit -m "feat: add document ETL services (parse, chunk, vectorize, store)"
```

---

### Task 11: Document Controller（上传 + 列表 + 详情）

**Files:**
- Create: `src/main/java/com/ys/agentrag/document/DocumentController.java`

**Interfaces:**
- Produces: `POST /api/document/upload`, `GET /api/document`, `GET /api/document/{id}`, `DELETE /api/document/{id}`

- [ ] **Step 1: 创建 DocumentController**

```java
package com.meeting.document;

import com.meeting.common.ApiResponse;
import com.meeting.document.model.entity.DocumentEntity;
import com.meeting.document.repository.DocumentRepository;
import com.meeting.document.service.ChunkService;
import com.meeting.document.service.DocumentUploadService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

@RestController
@RequestMapping("/api/document")
@RequiredArgsConstructor
public class DocumentController {

    private final DocumentUploadService uploadService;
    private final DocumentRepository documentRepository;  // JPA Repository
    private final ChunkService chunkService;

    @PostMapping("/upload")
    public ApiResponse<DocumentEntity> upload(@RequestParam("file") MultipartFile file) {
        DocumentEntity doc = uploadService.upload(file);
        // 异步 ETL（简化：同步处理）
        chunkService.processDocument(doc.getId(), "");
        DocumentEntity updated = documentRepository.findById(doc.getId()).orElse(null);
        return ApiResponse.ok(updated);
    }

    @GetMapping
    public ApiResponse<List<DocumentEntity>> list() {
        List<DocumentEntity> docs = documentRepository.findAllByOrderByCreatedAtDesc();
        return ApiResponse.ok(docs);
    }

    @GetMapping("/{id}")
    public ApiResponse<DocumentEntity> get(@PathVariable Long id) {
        DocumentEntity doc = documentRepository.findById(id).orElse(null);
        if (doc == null) {
            return ApiResponse.error("文档不存在");
        }
        return ApiResponse.ok(doc);
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable Long id) {
        documentRepository.deleteById(id);
        return ApiResponse.ok(null, "删除成功");
    }
}
```

- [ ] **Step 2: Compile**

Run: `mvn compile -q`
Expected: BUILD SUCCESS

- [ ] **Step 3: Commit**

```bash
git add src/main/java/com/ys/agentrag/document/DocumentController.java
git commit -m "feat: add document upload and management API"
```

---

## Phase 4: Retrieval Layer（检索层）

### Task 12: 检索结果模型 + Evidence 枚举 + 算法工具类

**Files:**
- Create: `src/main/java/com/meeting/retrieval/model/ChunkResult.java`
- Create: `src/main/java/com/meeting/retrieval/model/EvidenceLevel.java`
- Create: `src/main/java/com/meeting/retrieval/model/Citation.java`
- Create: `src/main/java/com/meeting/retrieval/algorithm/RrfMerger.java`     (新增)
- Create: `src/main/java/com/meeting/retrieval/algorithm/TimeDecayScorer.java` (新增)
- Create: `src/main/java/com/meeting/retrieval/algorithm/MmrDeduplicator.java` (新增)
- Create: `src/main/java/com/meeting/retrieval/algorithm/EvidenceEvaluator.java`(新增)
- Create: `src/main/java/com/meeting/retrieval/algorithm/CitationBuilder.java` (新增)

**Interfaces:**
- Produces: 检索层各模块共用的数据模型

- [ ] **Step 1: 创建 ChunkResult**

```java
package com.meeting.retrieval.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
@AllArgsConstructor
public class ChunkResult {
    private Long chunkId;
    private Long documentId;
    private String content;
    private int chunkIndex;
    private String speaker;
    private String sectionType;
    private String fileName;
    private double vectorScore;        // cosine 相似度
    private double ftsScore;           // BM25-like 分数
    private double rrfScore;           // RRF 融合后分数
    private double finalScore;         // 经过时间衰减+MMR 后最终分
    private int vectorRank;
    private int ftsRank;
}
```

- [ ] **Step 2: 创建 EvidenceLevel**

```java
package com.ys.agentrag.retrieval.model;

public enum EvidenceLevel {
    NONE,       // 未检索到任何文档
    WEAK,       // 仅单通道命中
    PARTIAL,    // 单通道 + 多文档
    SUFFICIENT  // 双通道命中 + 多文档
}
```

- [ ] **Step 3: 创建 Citation**

```java
package com.ys.agentrag.retrieval.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
@AllArgsConstructor
public class Citation {
    private int sourceId;
    private String fileName;
    private String content;
    private Integer chunkIndex;
}
```

- [ ] **Step 4: 创建算法工具类**

**RrfMerger.java**（RRF 融合排序，无状态工具类）

```java
package com.meeting.retrieval.algorithm;

import com.meeting.retrieval.model.ChunkResult;
import java.util.*;
import java.util.stream.Collectors;

/**
 * RRF (Reciprocal Rank Fusion) 融合排序算法
 * Score(d) = 1/(k + rank_vector) + 1/(k + rank_fts)
 */
public class RrfMerger {

    public static List<ChunkResult> merge(List<ChunkResult> vector, List<ChunkResult> fts, int k) {
        Map<Long, ChunkResult> merged = new LinkedHashMap<>();

        for (ChunkResult r : vector) {
            r.setRrfScore(1.0 / (k + r.getVectorRank()));
            merged.put(r.getChunkId(), r);
        }

        for (ChunkResult r : fts) {
            merged.computeIfAbsent(r.getChunkId(), id -> r);
            merged.get(r.getChunkId()).setFtsScore(r.getFtsScore());
            merged.get(r.getChunkId()).setRrfScore(
                merged.get(r.getChunkId()).getRrfScore() + 1.0 / (k + r.getFtsRank())
            );
        }

        return merged.values().stream()
            .sorted(Comparator.comparingDouble(ChunkResult::getRrfScore).reversed())
            .collect(Collectors.toList());
    }
}
```

**TimeDecayScorer.java**（时间衰减加权）

```java
package com.meeting.retrieval.algorithm;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

/**
 * 按会议日期对检索结果做时间衰减加权
 */
public class TimeDecayScorer {

    public record TimeDecayConfig(
        boolean enabled, int recentDays,
        double recentWeight, double normalWeight,
        double oldWeight, double archiveWeight
    ) {}

    public static double apply(double rrfScore, LocalDate meetingDate, TimeDecayConfig config) {
        if (!config.enabled() || meetingDate == null) return rrfScore;

        long daysOld = ChronoUnit.DAYS.between(meetingDate, LocalDate.now());
        double factor;
        if (daysOld <= config.recentDays()) factor = config.recentWeight();
        else if (daysOld <= 90)               factor = config.normalWeight();
        else if (daysOld <= 365)              factor = config.oldWeight();
        else                                  factor = config.archiveWeight();

        return rrfScore * factor;
    }
}
```

**MmrDeduplicator.java**（MMR 多样性去重）

```java
package com.meeting.retrieval.algorithm;

import com.meeting.retrieval.model.ChunkResult;
import java.util.*;

/**
 * 最大边际相关性去重，避免同文档相邻段落垄断 TopN
 */
public class MmrDeduplicator {

    public static List<ChunkResult> deduplicate(List<ChunkResult> results, int topN) {
        if (results.size() <= topN) return results;

        List<ChunkResult> selected = new ArrayList<>();
        Set<Long> selectedDocs = new HashSet<>();

        for (ChunkResult r : results) {
            if (selected.size() >= topN) break;
            if (selectedDocs.contains(r.getDocumentId())) continue;
            selected.add(r);
            selectedDocs.add(r.getDocumentId());
        }

        return selected;
    }
}
```

**EvidenceEvaluator.java**（四级证据评估）

```java
package com.meeting.retrieval.algorithm;

import com.meeting.retrieval.model.ChunkResult;
import com.meeting.retrieval.model.EvidenceLevel;
import java.util.List;

/**
 * 四级证据等级评估：NONE / WEAK / PARTIAL / SUFFICIENT
 */
public class EvidenceEvaluator {

    public static EvidenceLevel evaluate(List<ChunkResult> topChunks, int vectorCount, int ftsCount) {
        boolean hasVector = vectorCount > 0;
        boolean hasFts = ftsCount > 0;
        long distinctDocs = topChunks.stream()
            .map(ChunkResult::getDocumentId).distinct().count();

        if (!hasVector && !hasFts) return EvidenceLevel.NONE;
        if (!hasVector || !hasFts) {
            return distinctDocs >= 2 ? EvidenceLevel.PARTIAL : EvidenceLevel.WEAK;
        }
        if (hasVector && hasFts && distinctDocs >= 2) return EvidenceLevel.SUFFICIENT;
        return EvidenceLevel.PARTIAL;
    }
}
```

**CitationBuilder.java**（引用去重汇聚）

```java
package com.meeting.retrieval.algorithm;

import com.meeting.retrieval.model.ChunkResult;
import java.util.*;

/**
 * 引用去重汇聚：同一文件只显示一条引用
 */
public class CitationBuilder {

    public static List<Map<String, String>> build(List<ChunkResult> chunks) {
        Map<String, Map<String, String>> citationMap = new LinkedHashMap<>();
        int sourceId = 0;
        for (ChunkResult r : chunks) {
            citationMap.putIfAbsent(r.getFileName(), Map.of(
                "source_id", String.valueOf(++sourceId),
                "file_name", r.getFileName(),
                "content", r.getContent().length() > 100
                    ? r.getContent().substring(0, 100) + "..."
                    : r.getContent()
            ));
        }
        return new ArrayList<>(citationMap.values());
    }
}
```

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/meeting/retrieval/model/ src/main/java/com/meeting/retrieval/algorithm/
git commit -m "feat: add retrieval data models and algorithm utility classes"
```

---

### Task 13: 向量检索 + 全文检索 Service

**Files:**
- Create: `src/main/java/com/meeting/retrieval/service/VectorSearchService.java`
- Create: `src/main/java/com/meeting/retrieval/service/FullTextSearchService.java`

**Interfaces:**
- Produces: `VectorSearchService.search(query, topK) -> List<ChunkResult>`
- Produces: `FullTextSearchService.search(query, topK) -> List<ChunkResult>`

- [ ] **Step 1: 创建 VectorSearchService**

```java
package com.meeting.retrieval.service;

import com.meeting.config.DeepSeekClient;
import com.meeting.config.RagProperties;
import com.meeting.document.model.entity.DocumentEntity;
import com.meeting.document.repository.DocumentChunkRepository;
import com.meeting.document.repository.DocumentRepository;
import com.meeting.retrieval.model.ChunkResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class VectorSearchService {

    private final DocumentChunkRepository chunkRepository;  // JPA Repository
    private final DocumentRepository documentRepository;    // JPA Repository
    private final DeepSeekClient deepSeekClient;

    public List<ChunkResult> search(String query, int topK) {
        // 1. 获取 query 的向量
        List<Float> queryVector = deepSeekClient.embedding(query);

        // 2. 构建 pgvector 检索参数
        String embeddingStr = queryVector.stream()
            .map(String::valueOf)
            .collect(Collectors.joining(","));
        String vectorStr = "[" + embeddingStr + "]";

        // 3. 执行向量检索
        List<Map<String, Object>> rawResults = chunkRepository.vectorSearch(vectorStr, topK);

        // 4. 转换为 ChunkResult
        List<ChunkResult> results = new ArrayList<>();
        Map<Long, DocumentEntity> docCache = new ConcurrentHashMap<>();

        for (int i = 0; i < rawResults.size(); i++) {
            Map<String, Object> row = rawResults.get(i);
            Long docId = ((Number) row.get("document_id")).longValue();

            DocumentEntity doc = docCache.computeIfAbsent(docId,
                id -> documentRepository.findById(id).orElse(null));

            ChunkResult result = ChunkResult.builder()
                .chunkId(((Number) row.get("id")).longValue())
                .documentId(docId)
                .content((String) row.get("content"))
                .chunkIndex(((Number) row.get("chunk_index")).intValue())
                .speaker((String) row.get("speaker"))
                .sectionType((String) row.get("section_type"))
                .fileName(doc != null ? doc.getTitle() : "")
                .vectorScore(((Number) row.get("similarity")).doubleValue())
                .vectorRank(i + 1)
                .build();

            results.add(result);
        }

        log.debug("Vector search: query={}, topK={}, found={}", query, topK, results.size());
        return results;
    }
}
```

- [ ] **Step 2: 创建 FullTextSearchService**

```java
package com.ys.agentrag.retrieval.service;

import com.ys.agentrag.retrieval.model.ChunkResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class FullTextSearchService {

    private final JdbcTemplate jdbcTemplate;

    public List<ChunkResult> search(String query, int topK) {
        String sql = """
            SELECT c.id, c.document_id, c.content, c.chunk_index, c.speaker, c.section_type,
                   d.title AS file_name,
                   ts_rank(c.content_tsv, plainto_tsquery('simple', ?)) AS score
            FROM document_chunk c
            JOIN document d ON d.id = c.document_id
            WHERE c.content_tsv @@ plainto_tsquery('simple', ?)
            ORDER BY score DESC
            LIMIT ?
            """;

        List<ChunkResult> results = jdbcTemplate.query(sql,
            new Object[]{query, query, topK},
            (rs, rowNum) -> ChunkResult.builder()
                .chunkId(rs.getLong("id"))
                .documentId(rs.getLong("document_id"))
                .content(rs.getString("content"))
                .chunkIndex(rs.getInt("chunk_index"))
                .speaker(rs.getString("speaker"))
                .sectionType(rs.getString("section_type"))
                .fileName(rs.getString("file_name"))
                .ftsScore(rs.getDouble("score"))
                .ftsRank(rowNum + 1)
                .build()
        );

        log.debug("Full-text search: query={}, topK={}, found={}", query, topK, results.size());
        return results;
    }
}
```

- [ ] **Step 3: Commit**

```bash
git add src/main/java/com/ys/agentrag/retrieval/service/VectorSearchService.java
git add src/main/java/com/ys/agentrag/retrieval/service/FullTextSearchService.java
git commit -m "feat: add vector search and full-text search services"
```

---

### Task 14: Query Planning Service

**Files:**
- Create: `src/main/java/com/ys/agentrag/retrieval/service/QueryPlanningService.java`

**Interfaces:**
- Produces: `QueryPlanningService.plan(query, history) -> QueryPlan`

- [ ] **Step 1: 创建 QueryPlanningService**

```java
package com.meeting.retrieval.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.meeting.config.DeepSeekClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class QueryPlanningService {

    private final DeepSeekClient deepSeekClient;

    public record QueryPlan(String strategy, String rewrittenQuery, List<String> subQueries) {
        public static QueryPlan direct(String query) {
            return new QueryPlan("DIRECT", query, List.of(query));
        }
    }

    public QueryPlan plan(String originalQuery) {
        try {
            List<Map<String, String>> messages = List.of(
                Map.of("role", "system", "content",
                    "你是一个查询规划器。分析用户问题，输出 JSON 格式的规划结果。\n" +
                    "- DIRECT: 原样检索\n" +
                    "- REWRITE: 改写 query 使其更适合检索\n" +
                    "- DECOMPOSE: 拆解为多个子问题分别检索\n\n" +
                    "输出格式: {\"strategy\": \"DIRECT|REWRITE|DECOMPOSE\", " +
                    "\"rewritten_query\": \"...\", \"sub_queries\": [\"...\"]}"),
                Map.of("role", "user", "content", originalQuery)
            );

            JsonNode result = deepSeekClient.chat(messages, false);
            String content = result.path("choices").get(0)
                .path("message").path("content").asText();

            // 简单解析 JSON
            if (content.contains("\"REWRITE\"")) {
                return new QueryPlan("REWRITE", extractFromJson(content, "rewritten_query"),
                    List.of(extractFromJson(content, "rewritten_query")));
            } else if (content.contains("\"DECOMPOSE\"")) {
                return new QueryPlan("DECOMPOSE", originalQuery, List.of(originalQuery));
            }
            return QueryPlan.direct(originalQuery);

        } catch (Exception e) {
            log.warn("Query planning failed, fallback to DIRECT: {}", e.getMessage());
            return QueryPlan.direct(originalQuery);
        }
    }

    private String extractFromJson(String json, String key) {
        int start = json.indexOf("\"" + key + "\"");
        if (start == -1) return "";
        start = json.indexOf(":", start) + 1;
        start = json.indexOf("\"", start) + 1;
        int end = json.indexOf("\"", start);
        return json.substring(start, end);
    }
}
```

- [ ] **Step 2: Commit**

```bash
git add src/main/java/com/ys/agentrag/retrieval/service/QueryPlanningService.java
git commit -m "feat: add query planning service (DIRECT/REWRITE/DECOMPOSE)"
```

---

### Task 15: RRF 融合 + Rerank 接口 + 时间衰减 + MMR + 重查 + 扩窗

**Files:**
- Create: `src/main/java/com/ys/agentrag/retrieval/service/Reranker.java`
- Create: `src/main/java/com/ys/agentrag/retrieval/service/NoOpReranker.java`
- Create: `src/main/java/com/ys/agentrag/retrieval/service/HybridSearchService.java`

**Interfaces:**
- Consumes: VectorSearchService, FullTextSearchService, QueryPlanningService
- Produces: `HybridSearchService.search(query, timeRange) -> SearchResult`

- [ ] **Step 1: 创建 Reranker 接口**

```java
package com.meeting.retrieval.service;

import com.meeting.retrieval.model.ChunkResult;
import java.util.List;

public interface Reranker {
    List<ChunkResult> reRank(String query, List<ChunkResult> candidates, int topN);
}
```

- [ ] **Step 2: 创建 NoOpReranker**

```java
package com.meeting.retrieval.service;

import com.meeting.retrieval.model.ChunkResult;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.stream.Collectors;

@Component
@ConditionalOnProperty(value = "rag.retrieval.rerank-enabled", havingValue = "false", matchIfMissing = true)
public class NoOpReranker implements Reranker {
    @Override
    public List<ChunkResult> reRank(String query, List<ChunkResult> candidates, int topN) {
        return candidates.stream().limit(topN).collect(Collectors.toList());
    }
}
```

- [ ] **Step 3: 创建 HybridSearchService（核心编排）**

```java
package com.meeting.retrieval.service;

import com.meeting.config.RagProperties;
import com.meeting.document.repository.DocumentChunkRepository;
import com.meeting.retrieval.algorithm.*;
import com.meeting.retrieval.model.ChunkResult;
import com.meeting.retrieval.model.EvidenceLevel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class HybridSearchService {

    private final VectorSearchService vectorSearchService;
    private final FullTextSearchService fullTextSearchService;
    private final QueryPlanningService queryPlanningService;
    private final Reranker reranker;
    private final RagProperties ragProperties;
    private final DocumentChunkRepository chunkRepository;  // JPA

    public record SearchResult(
        List<ChunkResult> chunks,
        String evidenceLevel,
        List<Map<String, String>> citations,
        String queryUsed,
        boolean retried
    ) {}

    public SearchResult search(String query, String timeRange) {
        MDC.put("layer", "RETRIEVAL");

        // 1. Query 规划
        QueryPlanningService.QueryPlan plan = queryPlanningService.plan(query);
        log.info("Query plan: strategy={}, rewritten={}", plan.strategy(), plan.rewrittenQuery());

        String actualQuery = plan.rewrittenQuery();
        boolean retried = false;

        // 2. 并行检索
        List<ChunkResult> vectorResults = vectorSearchService.search(actualQuery, ragProperties.getRetrieval().getVectorTopk());
        List<ChunkResult> ftsResults = fullTextSearchService.search(actualQuery, ragProperties.getRetrieval().getFtsTopk());

        // 3. RRF 融合（委托工具类）
        List<ChunkResult> merged = RrfMerger.merge(vectorResults, ftsResults, ragProperties.getRetrieval().getRrfK());

        // 4. Rerank
        if (ragProperties.getRetrieval().isRerankEnabled()) {
            merged = reranker.reRank(actualQuery, merged, ragProperties.getRetrieval().getRerankTopk());
        }

        // 5. 时间衰减加权（委托工具类）
        if (ragProperties.getTimeDecay().isEnabled()) {
            var config = ragProperties.getTimeDecay();
            for (ChunkResult r : merged) {
                // 查询文档的 meeting_date（简化实现，实际需从 document 表加载）
                var doc = chunkRepository.findById(r.getChunkId()).orElse(null);
                LocalDate meetingDate = null; // todo: 从 doc 获取
                r.setFinalScore(TimeDecayScorer.apply(r.getRrfScore(), meetingDate,
                    new TimeDecayScorer.TimeDecayConfig(
                        true, config.getRecentDays(), config.getRecentWeight(),
                        config.getNormalWeight(), config.getOldWeight(), config.getArchiveWeight()
                    )));
            }
        }

        // 6. MMR 多样性去重（委托工具类）
        merged = MmrDeduplicator.deduplicate(merged, 5);

        // 7. 低置信度自动重查
        if (!merged.isEmpty() && merged.get(0).getFinalScore() < ragProperties.getEvidence().getThreshold()) {
            log.info("Low confidence, triggering retry");
            String retryQuery = generateRetryQuery(actualQuery);
            List<ChunkResult> retryVector = vectorSearchService.search(retryQuery, ragProperties.getRetrieval().getVectorTopk());
            List<ChunkResult> retryFts = fullTextSearchService.search(retryQuery, ragProperties.getRetrieval().getFtsTopk());
            List<ChunkResult> retryMerged = RrfMerger.merge(retryVector, retryFts, ragProperties.getRetrieval().getRrfK());
            if (!retryMerged.isEmpty() && retryMerged.get(0).getFinalScore() > merged.get(0).getFinalScore()) {
                merged = retryMerged;
            }
            retried = true;
            actualQuery = retryQuery;
        }

        // 8. 邻居扩窗
        merged = expandNeighbors(merged);

        // 9. 证据等级 + 引用（委托工具类）
        EvidenceLevel evidenceLevel = EvidenceEvaluator.evaluate(merged, vectorResults.size(), ftsResults.size());
        List<Map<String, String>> citations = CitationBuilder.build(merged);

        log.info("Hybrid search complete: query={}, chunks={}, evidence={}",
            actualQuery, merged.size(), evidenceLevel);

        return new SearchResult(merged, evidenceLevel.name(), citations, actualQuery, retried);
    }

    // ---- 低置信度重查 ----
    String generateRetryQuery(String originalQuery) {
        return originalQuery + " 内容 详情 决定";
    }

    // ---- 邻居扩窗 ----
    List<ChunkResult> expandNeighbors(List<ChunkResult> results) {
        List<ChunkResult> expanded = new ArrayList<>();
        for (ChunkResult r : results) {
            expanded.add(r);
            var neighbors = chunkRepository.findByDocumentIdOrderByChunkIndex(r.getDocumentId());
            for (var n : neighbors) {
                if (Math.abs(n.getChunkIndex() - r.getChunkIndex()) <= 1
                    && !n.getId().equals(r.getChunkId())) {
                    expanded.add(ChunkResult.builder()
                        .chunkId(n.getId())
                        .documentId(n.getDocumentId())
                        .content(n.getContent())
                        .chunkIndex(n.getChunkIndex())
                        .speaker(n.getSpeaker())
                        .sectionType(n.getSectionType())
                        .build());
                }
            }
        }
        return expanded.stream().distinct().collect(Collectors.toList());
    }
}
```

- [ ] **Step 2: Commit**

```bash
git add src/main/java/com/ys/agentrag/retrieval/service/Reranker.java
git add src/main/java/com/ys/agentrag/retrieval/service/NoOpReranker.java
git add src/main/java/com/ys/agentrag/retrieval/service/HybridSearchService.java
git commit -m "feat: add hybrid search service with RRF fusion, time decay, MMR, retry, neighbor expansion"
```

---

## Phase 5: Session + Chat（会话管理）

### Task 16: Conversation 和 Chat Service

**Files:**
- Create: `src/main/java/com/ys/agentrag/conversation/service/ConversationService.java`
- Create: `src/main/java/com/ys/agentrag/conversation/service/SummaryService.java`

**Interfaces:**
- Produces: CRUD 会话、Token 感知摘要压缩

- [ ] **Step 1: 创建 ConversationService**

```java
package com.meeting.conversation.service;

import com.meeting.common.BusinessException;
import com.meeting.conversation.model.entity.ConversationEntity;
import com.meeting.conversation.model.entity.MessageEntity;
import com.meeting.conversation.repository.ConversationRepository;
import com.meeting.conversation.repository.MessageRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
public class ConversationService {

    private final ConversationRepository conversationRepository;  // JPA
    private final MessageRepository messageRepository;            // JPA

    public ConversationEntity create(String title) {
        ConversationEntity conv = new ConversationEntity();
        conv.setTitle(title != null ? title : "新对话");
        conv.setStatus("ACTIVE");
        conv.setCompressionHistory("[]");
        conversationRepository.save(conv);
        return conv;
    }

    public List<ConversationEntity> list() {
        return conversationRepository.findByStatusOrderByUpdatedAtDesc("ACTIVE");
    }

    public ConversationEntity getById(Long id) {
        return conversationRepository.findById(id)
            .orElseThrow(() -> BusinessException.notFound("会话不存在"));
    }

    public void delete(Long id) {
        conversationRepository.deleteById(id);
    }

    @Transactional
    public void addMessage(Long conversationId, String role, String content, String traceId, String metadata) {
        MessageEntity msg = new MessageEntity();
        msg.setConversationId(conversationId);
        msg.setRole(role);
        msg.setContent(content);
        msg.setTraceId(traceId);
        msg.setMetadata(metadata);
        messageRepository.save(msg);

        ConversationEntity conv = getById(conversationId);
        conv.setMessageCount(conv.getMessageCount() + 1);
        conv.setUpdatedAt(LocalDateTime.now());
        conversationRepository.save(conv);
    }

    public List<MessageEntity> getMessages(Long conversationId) {
        return messageRepository.findByConversationIdOrderByCreatedAtAsc(conversationId);
    }
}
```

- [ ] **Step 2: 创建 SummaryService**

```java
package com.ys.agentrag.conversation.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ys.agentrag.config.DeepSeekClient;
import com.ys.agentrag.config.RagProperties;
import com.ys.agentrag.conversation.mapper.ConversationMapper;
import com.ys.agentrag.conversation.model.entity.ConversationEntity;
import com.ys.agentrag.conversation.model.entity.MessageEntity;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class SummaryService {

    private final DeepSeekClient deepSeekClient;
    private final ConversationMapper conversationMapper;
    private final RagProperties ragProperties;
    private final ObjectMapper objectMapper;

    /**
     * 检查是否需要压缩，如果 token 量超过阈值则触发压缩
     */
    public boolean checkAndCompress(Long conversationId, List<MessageEntity> messages) {
        int estimatedTokens = estimateTokens(messages);
        if (estimatedTokens <= ragProperties.getConversation().getSummaryTrigger()) {
            return false;
        }

        ConversationEntity conv = conversationMapper.selectById(conversationId);
        String oldSummary = conv.getContextSummary();

        // 构建压缩 prompt 的输入
        StringBuilder sb = new StringBuilder();
        if (oldSummary != null && !oldSummary.isBlank()) {
            sb.append("历史摘要：").append(oldSummary).append("\n\n");
        }
        for (MessageEntity msg : messages) {
            sb.append(msg.getRole()).append(": ").append(msg.getContent()).append("\n");
        }

        // 调用 DeepSeek 压缩
        String newSummary = compress(sb.toString());

        // 更新
        conv.setContextSummary(newSummary);

        // 追加压缩历史
        try {
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> history = objectMapper.readValue(
                conv.getCompressionHistory() != null ? conv.getCompressionHistory() : "[]",
                List.class
            );
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("version", history.size() + 1);
            entry.put("triggered_at", new java.util.Date().toString());
            entry.put("tokens_before", estimatedTokens);
            entry.put("tokens_after", estimateTokens(List.of()));
            history.add(entry);
            conv.setCompressionHistory(objectMapper.writeValueAsString(history));
        } catch (Exception e) {
            log.warn("Failed to update compression history", e);
        }

        conversationMapper.updateById(conv);
        log.info("Conversation {} compressed: {} tokens -> summary", conversationId, estimatedTokens);
        return true;
    }

    /**
     * 粗略估算 token 数（中文≈0.5 token/字，英文≈1 token/词）
     */
    public int estimateTokens(List<MessageEntity> messages) {
        int total = 0;
        for (MessageEntity msg : messages) {
            if (msg.getContent() != null) {
                total += msg.getContent().length() / 2;
            }
        }
        return total;
    }

    private String compress(String text) {
        List<Map<String, String>> messages = List.of(
            Map.of("role", "system", "content",
                "请将以下对话压缩为简洁的摘要，保留关键讨论要点、决策和结论。"),
            Map.of("role", "user", "content", text)
        );
        var result = deepSeekClient.chat(messages, false);
        return result.path("choices").get(0).path("message").path("content").asText();
    }
}
```

- [ ] **Step 3: Commit**

```bash
git add src/main/java/com/ys/agentrag/conversation/service/
git commit -m "feat: add conversation management and token-aware summary compression"
```

---

## Phase 6: Agent + Chat API

### Task 17: Agent Tool 集成

**Files:**
- Create: `src/main/java/com/meeting/agent/SearchGuard.java`
- Modify: `src/main/java/com/meeting/service/ChatService.java`（已有，增强 RAG 注入能力）

**Interfaces:**
- Produces: SearchGuard (防重复检索), ChatService 增强（预检索 + HarnessAgent 流式对话）

- [ ] **Step 1: 创建 SearchGuard（防重复检索）**

```java
package com.meeting.agent;

/**
 * 防重复检索标记：同一轮对话中只检索一次知识库
 */
public class SearchGuard {
    private static final ThreadLocal<Boolean> SEARCHED = ThreadLocal.withInitial(() -> false);

    public static boolean hasSearched() { return SEARCHED.get(); }
    public static void markSearched() { SEARCHED.set(true); }
    public static void reset() { SEARCHED.remove(); }
}
```

- [ ] **Step 2: 增强 ChatService（已有，注入 RAG 上下文到 HarnessAgent）**

现有 ChatService 已使用 AgentScope HarnessAgent（`HarnessAgent.builder().streamEvents()`）做流式对话。
核心变更：在调用 `agent.streamEvents()` 之前，先调用 HybridSearchService 检索知识库，
将检索结果拼入 UserMessage 的上下文中。

```java
// ChatService 中增强 buildRagContext 方法
private String buildRagContext(Long dialogueId, String userMessage) {
    // 调用 HybridSearchService 检索
    HybridSearchService.SearchResult result =
        hybridSearchService.search(userMessage, null);

    if (result.chunks().isEmpty()) return "";

    return result.chunks().stream()
        .map(c -> "[会议片段] " + c.getContent())
        .collect(Collectors.joining("\n\n"));
}
```

完整流程（复用现有 ChatService 架构）：

```
用户消息 → ChatController (SSE)
    ↓
ChatService
    ├── 1. 保存用户消息到 DB
    ├── 2. 调用 HybridSearchService 检索知识库
    ├── 3. 将检索结果 + 用户问题拼入 UserMessage
    ├── 4. HarnessAgent.streamEvents(msg, ctx) 流式生成回答
    ├── 5. SSE 推送逐 token 到前端
    └── 6. 保存助手消息到 DB
```

> **注意**：现有 `ChatService.java`（`com.meeting.service.ChatService`）中 `buildRagContext()` 方法已经实现了类似的预检索逻辑，使用 `VectorizationService.searchSimilar()`。
> 增强时只需将内部实现替换为 HybridSearchService，保持接口不变。
```

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/ys/agentrag/agent/
git commit -m "feat: add Agent tool integration and chat orchestrator"
```

---

### Task 18: Chat Controller + SSE 流式

**Files:**
- Create: `src/main/java/com/ys/agentrag/conversation/ConversationController.java`
- Create: `src/main/java/com/ys/agentrag/conversation/ChatController.java`
- Create: `src/main/java/com/ys/agentrag/conversation/FeedbackController.java`
- Create: `src/main/java/com/ys/agentrag/conversation/model/dto/ChatRequest.java`

**Interfaces:**
- Produces: 完整 REST API

- [ ] **Step 1: 创建 ConversationController**

```java
package com.ys.agentrag.conversation;

import com.ys.agentrag.common.ApiResponse;
import com.ys.agentrag.conversation.model.entity.ConversationEntity;
import com.ys.agentrag.conversation.model.entity.MessageEntity;
import com.ys.agentrag.conversation.service.ConversationService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/conversation")
@RequiredArgsConstructor
public class ConversationController {

    private final ConversationService conversationService;

    @PostMapping
    public ApiResponse<ConversationEntity> create(@RequestParam(required = false) String title) {
        return ApiResponse.ok(conversationService.create(title));
    }

    @GetMapping
    public ApiResponse<List<ConversationEntity>> list() {
        return ApiResponse.ok(conversationService.list());
    }

    @GetMapping("/{id}")
    public ApiResponse<ConversationEntity> get(@PathVariable Long id) {
        return ApiResponse.ok(conversationService.getById(id));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable Long id) {
        conversationService.delete(id);
        return ApiResponse.ok(null, "删除成功");
    }

    @GetMapping("/{id}/messages")
    public ApiResponse<List<MessageEntity>> messages(@PathVariable Long id) {
        return ApiResponse.ok(conversationService.getMessages(id));
    }
}
```

- [ ] **Step 2: 创建 ChatRequest**

```java
package com.ys.agentrag.conversation.model.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class ChatRequest {
    @NotNull
    private Long conversationId;
    @NotBlank
    private String content;
}
```

- [ ] **Step 3: 创建/修改 ChatController（SSE 流式，复用现有实现）**

ChatController 已在现有项目中实现（`POST /api/dialogue/{id}/chat`，SSE 流式），无需重新创建。
核心端点保持不变：

```java
// 现有实现（无需修改）
@PostMapping(value = "/dialogue/{id}/chat", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
public SseEmitter streamChat(@PathVariable Long id, @RequestBody Map<String, String> request) {
    SseEmitter emitter = new SseEmitter(300_000L);
    chatService.streamChat(id, message.trim(), emitter);
    return emitter;
}
```

新增端点（统一使用新表 conversation）：

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/api/conversation` | 创建新会话 |
| GET | `/api/conversation` | 会话列表 |
| GET | `/api/conversation/{id}` | 会话详情 |
| DELETE | `/api/conversation/{id}` | 删除会话 |
| GET | `/api/conversation/{id}/messages` | 查看历史消息 |
| POST | `/api/conversation/{id}/chat` | 流式对话（SSE） |
```

- [ ] **Step 4: 创建 FeedbackController**

```java
package com.meeting.conversation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.meeting.common.ApiResponse;
import com.meeting.conversation.model.entity.MessageEntity;
import com.meeting.conversation.repository.MessageRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/feedback")
@RequiredArgsConstructor
public class FeedbackController {

    private final MessageRepository messageRepository;  // JPA
    private final ObjectMapper objectMapper;

    @PostMapping("/{messageId}")
    public ApiResponse<Void> feedback(
            @PathVariable Long messageId,
            @RequestParam String type) {

        MessageEntity msg = messageRepository.findById(messageId).orElse(null);
        if (msg == null) {
            return ApiResponse.error("消息不存在");
        }

        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> meta = objectMapper.readValue(msg.getMetadata(), Map.class);
            meta.put("feedback", type);
            msg.setMetadata(objectMapper.writeValueAsString(meta));
            messageRepository.save(msg);
            log.info("Feedback recorded: message={}, type={}", messageId, type);
        } catch (Exception e) {
            log.warn("Failed to record feedback", e);
        }

        return ApiResponse.ok(null, "反馈已记录");
    }
}
```

- [ ] **Step 5: Compile**

Run: `mvn compile -q`
Expected: BUILD SUCCESS

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/ys/agentrag/conversation/ConversationController.java
git add src/main/java/com/ys/agentrag/conversation/ChatController.java
git add src/main/java/com/ys/agentrag/conversation/FeedbackController.java
git commit -m "feat: add conversation, chat and feedback APIs"
```

---

## Phase 7: Quality + Operations

### Task 19: 离线评估脚本 + Docker 部署

**Files:**
- Create: `src/test/java/com/ys/agentrag/evaluation/EvalRunner.java`
- Create: `Dockerfile`
- Create: `docker-compose.yml`

**Interfaces:**
- Produces: 可运行的评估脚本 + Docker 部署配置

- [ ] **Step 1: 创建 EvalRunner（离线评估）**

```java
package com.ys.agentrag.evaluation;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ys.agentrag.retrieval.model.ChunkResult;
import com.ys.agentrag.retrieval.service.HybridSearchService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 离线检索评估：从 JSON 文件加载测试集，输出 Hit Rate 和 MRR
 * 运行：mvn spring-boot:run -Dspring-boot.run.arguments="--eval"
 */
// @Component  // 默认不启用，通过配置开关激活
@RequiredArgsConstructor
@Slf4j
public class EvalRunner implements CommandLineRunner {

    private final HybridSearchService hybridSearchService;
    private final ObjectMapper objectMapper;

    @Override
    public void run(String... args) throws Exception {
        if (args.length == 0 || !"--eval".equals(args[0])) return;

        // 加载测试集
        String json = """
            [
                {"query": "服务器采购预算批了吗", "expected_doc": "周会纪要.pdf"},
                {"query": "物流方案谁负责", "expected_doc": "周会纪要.pdf"},
                {"query": "Q3营收目标多少", "expected_doc": "经营分析会.docx"}
            ]
            """;
        List<Map<String, String>> testCases = objectMapper.readValue(json,
            new TypeReference<List<Map<String, String>>>() {});

        int hits = 0;
        double reciprocalRankSum = 0.0;

        for (Map<String, String> tc : testCases) {
            String query = tc.get("query");
            String expected = tc.get("expected_doc");

            var result = hybridSearchService.search(query, null);
            boolean hit = false;
            for (int i = 0; i < result.chunks().size(); i++) {
                if (result.chunks().get(i).getFileName().contains(expected)) {
                    hits++;
                    reciprocalRankSum += 1.0 / (i + 1);
                    hit = true;
                    break;
                }
            }

            log.info("Query: {}, Hit={}, Top1={}",
                query, hit,
                result.chunks().isEmpty() ? "none" : result.chunks().get(0).getFileName());
        }

        double hitRate = (double) hits / testCases.size();
        double mrr = reciprocalRankSum / testCases.size();

        // 标准输出，方便脚本解析
        System.out.println("=== EVAL RESULTS ===");
        System.out.println("Total queries: " + testCases.size());
        System.out.println("Hit Rate: " + String.format("%.2f", hitRate));
        System.out.println("MRR: " + String.format("%.2f", mrr));
        System.out.println("=====================");

        System.exit(0);
    }
}
```

- [ ] **Step 2: 创建 Dockerfile**

```dockerfile
FROM eclipse-temurin:21-jre
WORKDIR /app
COPY target/agent-rag-1.0.0.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
```

- [ ] **Step 3: 更新/创建 docker-compose.yml（含 Nacos + Redis）**

```yaml
version: '3.8'

services:
  postgres:
    image: pgvector/pgvector:pg16
    environment:
      POSTGRES_DB: meeting_agent
      POSTGRES_USER: user
      POSTGRES_PASSWORD: password
    ports:
      - "5432:5432"
    volumes:
      - pgdata:/var/lib/postgresql/data
      - ./init-db.sql:/docker-entrypoint-initdb.d/init-db.sql
    healthcheck:
      test: ["CMD-SHELL", "pg_isready -U user -d meeting_agent"]
      interval: 5s
      timeout: 3s
      retries: 5

  redis:                          # 缓存基础服务
    image: redis:7-alpine
    ports:
      - "6379:6379"
    volumes:
      - redis_data:/data
    healthcheck:
      test: ["CMD", "redis-cli", "ping"]
      interval: 10s
      timeout: 3s
      retries: 5

  nacos:                          # 配置中心
    image: nacos/nacos-server:v2.3.0
    ports:
      - "8848:8848"
    environment:
      MODE: standalone
      NACOS_AUTH_ENABLE: "false"

  app:
    build: .
    ports:
      - "8080:8080"
    environment:
      SPRING_PROFILES_ACTIVE: docker
      NACOS_ADDR: nacos:8848
      DEEPSEEK_API_KEY: ${DEEPSEEK_API_KEY}
      ZHIPU_API_KEY: ${ZHIPU_API_KEY}
    volumes:
      - ./data:/app/data
    depends_on:
      postgres:
        condition: service_healthy
      nacos:
        condition: service_started

volumes:
  pgdata:
  redis_data:
```

- [ ] **Step 4: Commit**

```bash
git add Dockerfile docker-compose.yml src/test/java/com/ys/agentrag/evaluation/
git commit -m "feat: add offline evaluation runner and Docker deployment config"
```

---

## Implementation Order Summary

```
Phase 1: Foundation
  Task 1  →  Scaffold + dependencies (JPA + Nacos + AgentScope Harness)
  Task 2  →  Common infrastructure (ApiResponse, Exception)
  Task 3  →  Config properties + DeepSeek/Zhipu clients
  Task 4  →  TraceId logging filter

Phase 2: Data Layer (JPA Repositories, not MyBatis-Plus)
  Task 5  →  init-db.sql DDL (document + chunk)
  Task 6  →  init-db.sql DDL (conversation + message)
  Task 7  →  JPA Entity + Repository (not Mapper)

Phase 3: Document ETL
  Task 8  →  Upload service (JPA Repository)
  Task 9  →  Structural chunk strategy
  Task 10 →  Parser service + ChunkService (JPA)
  Task 11 →  Document Controller (JPA Repository)

Phase 4: Retrieval
  Task 12 →  Retrieval models + Algorithm utility classes (RrfMerger, TimeDecayScorer, etc.)
  Task 13 →  VectorSearch + FullTextSearch (JPA Repository)
  Task 14 →  QueryPlanning service
  Task 15 →  HybridSearch (delegates to algorithm utility classes)

Phase 5: Session
  Task 16 →  ConversationService + SummaryService (JPA Repository)

Phase 6: Agent + Chat
  Task 17 →  SearchGuard + ChatService enhancement (HarnessAgent)
  Task 18 →  Chat/Conversation/Feedback Controllers

Phase 7: Quality + Ops
  Task 19 →  Evaluation runner + Docker (Nacos + Redis + PostgreSQL)
```

每个 Phase 可独立验证：Phase 1 完成后项目可启动，Phase 3 完成后可上传文档，Phase 4 后可检索，Phase 6 后可对话。

### 与旧有语音管线的关系

```
meeting_minutes + meeting_vectors  ←  FunASR 语音转写管线（独立，不受 RAG 影响）
document + document_chunk          ←  RAG 文档管线（新表，与旧表共存）
dialogues + dialogue_messages      ←  ChatService 旧会话表（逐步迁移到 conversation + message）
conversation + message             ←  RAG 新会话表
```

FunASR 语音转写继续使用 `meeting_minutes` / `meeting_vectors` / `dialogues` 旧表。
RAG 功能使用 `document` / `document_chunk` / `conversation` / `message` 新表。
两条管线通过 `ChatService`（已存在）中的上下文注入统一呈现给用户。
