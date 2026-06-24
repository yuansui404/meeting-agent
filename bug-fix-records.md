# 项目 Bug 修复记录

> 本文档记录了会议纪要智能体项目开发过程中遇到的典型技术问题，
> 包含现象、根因分析和解决方案，可用于技术面试中的项目经验描述。

---

## 目录

1. [Hibernate 与 pgvector VECTOR 列不兼容](#1-hibernate-与-pgvector-vector-列不兼容)
2. [JPA 派生 delete 方法因 VECTOR 列执行失败](#2-jpa-派生-delete-方法因-vector-列执行失败)
3. [@Modifying 原生 DELETE 缺少事务上下文](#3-modifying-原生-delete-缺少事务上下文)
4. [大模型幻觉：输出完整简历而非回答问题](#4-大模型幻觉输出完整简历而非回答问题)
5. [RAG 上下文注入导致模型无法回答通用问题](#5-rag-上下文注入导致模型无法回答通用问题)
6. [向量检索返回空结果的陷阱](#6-向量检索返回空结果的陷阱)
7. [智谱 GLM-4V 忽略系统指令](#7-智谱-glm-4v-忽略系统指令)
8. [经验总结](#8-经验总结)

---

## 1. Hibernate 与 pgvector VECTOR 列不兼容

### 现象

调用 `meetingVectorRepository.findAllById(ids)` 时抛出异常：

```
org.springframework.dao.IncorrectResultSetColumnCountException:
No results were returned by the query
```

### 触发场景

在 `VectorizationService` 中，向量检索（余弦相似度排序）拿到匹配的 ID 列表后，
需要回查 `meeting_vectors` 表获取完整记录（含 content、chunk_index 等字段）。

### 根因分析

数据库表定义：

```sql
CREATE TABLE meeting_vectors (
    id BIGSERIAL PRIMARY KEY,
    meeting_id BIGINT NOT NULL,
    content TEXT NOT NULL,
    embedding VECTOR(1024),   -- pgvector 扩展类型
    chunk_index INT
);
```

Entity 映射：

```java
@Entity
@Table(name = "meeting_vectors")
public class MeetingVector {
    // ...
    private float[] embedding;  // Java float[]
    // ...
}
```

**问题本质**：Spring Data JPA 底层使用 Hibernate 6.3.1 完成 ORM 映射。当调用
`findAllById()` 时，Hibernate 会生成 `SELECT * FROM meeting_vectors WHERE id IN (...)`，
尝试将 VECTOR 类型的列映射为 Java `float[]`。然而，**标准 Hibernate 类型系统
不识别 pgvector 的 VECTOR 类型**，也没有注册对应的自定义类型（CustomType）。

Hibernate 执行查询后发现结果集的列类型无法映射到实体字段，直接返回空结果，
而不是抛出一个明确的类型转换异常。

具体来说：PostgreSQL JDBC 驱动（`org.postgresql:postgresql:42.6.0`）的
`getObject()` 方法无法将 VECTOR 列解析为 Hibernate 期望的类型，导致
`ResultSet` 的行数被视为 0。

### 解决方案

**方案**：绕过 JPA，直接使用 `JdbcTemplate` 查询非 VECTOR 列。

```java
private List<MeetingVector> findVectorsByIdsPreservingOrder(List<Long> ids) {
    String placeholders = ids.stream().map(id -> "?").collect(Collectors.joining(","));
    // 只查询非 VECTOR 列，手动构建实体
    String sql = "SELECT id, meeting_id, content, chunk_index, created_at " +
                 "FROM meeting_vectors WHERE id IN (" + placeholders + ")";
    List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql, ids.toArray());
    // 手动映射 ...
}
```

**为什么 JdbcTemplate 可以？** JdbcTemplate 返回 `List<Map<String, Object>>`，
每一行以 Map 形式返回。对于 VECTOR 列，如果不 SELECT 它，JDBC 驱动根本不需要
去解析它。手动构建 Entity 对象时，只设置可以解析的字段（id, meeting_id, content 等），
embeding 字段保持 null。

### 技术要点

- JPA/Hibernate 的 ORM 自动映射对 PostgreSQL 扩展类型（pgvector、PostGIS 等）支持有限
- 解决方案模式：**JPA 用于常规 CRUD，JdbcTemplate 用于涉及扩展类型的操作**
- 这不是 Hibernate 的 bug，而是 pgvector 的 VECTOR 不是 SQL 标准类型，
  Hibernate 6.x 需要通过 `@JdbcTypeCode` 或自定义 `UserType` 来支持

---

## 2. JPA 派生 delete 方法因 VECTOR 列执行失败

### 现象

调用 `meetingVectorRepository.deleteByMeetingId(meetingId)` 时抛出：

```
PSQLException: ERROR: column "embedding" is of type vector but expression is of type bytea
```

### 触发场景

删除会议记录时，需要同时删除关联的向量数据：
- 会议删除 API：`DELETE /api/meeting/{id}`
- 重新向量化时先清空旧数据

### 根因分析

**Spring Data JPA 的派生 delete 方法执行机制**：

1. 先执行 `SELECT * FROM meeting_vectors WHERE meeting_id = ?` 加载实体到 Persistence Context
2. 对每个实体调用 `EntityManager.remove()`
3. Hibernate 在 flush 时生成 `DELETE` 语句

关键在第 1 步：JPA 会 SELECT 所有列，包括了 VECTOR 类型的 `embedding` 列。
这触发了与 Bug #1 相同的 Hibernate VECTOR 类型映射失败问题。

更隐蔽的问题：即使第 1 步没有报错（比如某些场景下 SELECT 可以返回结果），
第 3 步生成 DELETE 时，Hibernate 可能会尝试将实体中的 `float[]` 值绑定为
`bytea` 参数，导致类型不匹配。

### 解决方案

**方案**：移除 JPA 派生 delete 方法，改用 JdbcTemplate 直接执行 DELETE。

```java
// 删除 MeetingVectorRepository 中的：
// @Modifying
// @Query("DELETE FROM MeetingVector v WHERE v.meetingId = :meetingId")
// void deleteByMeetingId(@Param("meetingId") Long meetingId);

// 改为在 Service/Controller 中直接使用：
jdbcTemplate.update("DELETE FROM meeting_vectors WHERE meeting_id = ?", meetingId);
```

### 技术要点

- JPA 派生删除（Derived Delete）会先查询实体再逐个删除，性能差且容易引发副作用
- 批量删除建议直接用 `JdbcTemplate` 或 `EntityManager.createQuery()` 执行 JPQL DELETE
- **经验法则**：涉及 PostgreSQL 扩展列的表，所有写操作（DELETE、UPDATE）都通过
  JdbcTemplate 执行，读操作如果不需要扩展列也走 JdbcTemplate

---

## 3. @Modifying 原生 DELETE 缺少事务上下文

### 现象

```java
@Modifying
@Query(value = "DELETE FROM meeting_vectors WHERE meeting_id = ?1", nativeQuery = true)
void deleteByMeetingId(Long meetingId);
```

抛出：

```
javax.persistence.TransactionRequiredException:
Executing an update/delete query
```

### 根因分析

`@Modifying` 注解的查询需要在事务上下文中执行。Spring Data JPA 的 Repository
方法默认没有事务（单纯查询有 `@Transactional(readOnly = true)` 隐式事务，
但修改操作没有）。

当在 Controller 层直接调用 `repository.deleteByMeetingId()` 时，没有事务边界，
JPA 的 `EntityManager` 无法执行 DML 操作。

### 解决方案

**方案 A**：在 Repository 接口上添加 `@Transactional`：

```java
@Modifying
@Transactional
@Query(value = "DELETE FROM meeting_vectors WHERE meeting_id = ?1", nativeQuery = true)
void deleteByMeetingId(Long meetingId);
```

**方案 B**（本项目采用）：改用 JdbcTemplate，不需要事务注解：

```java
jdbcTemplate.update("DELETE FROM meeting_vectors WHERE meeting_id = ?", meetingId);
```

### 技术要点

- `@Modifying` 必须配合 `@Transactional` 使用，或由调用方提供事务上下文
- `JdbcTemplate` 的 `update()` 方法自带事务管理，不需要显式注解
- 这个 Bug 也是促使我们全面迁移到 JdbcTemplate 处理 VECTOR 表的触发点

---

## 4. 大模型幻觉：输出完整简历而非回答问题

### 现象

用户上传简历后提问"花小猪砍车费是什么"，AI 模型不是针对性地回答问题，
而是将**整份简历内容全部输出**，夹杂了大量不相关的个人信息和幻觉内容。

### 触发场景

使用智谱 GLM-4V 模型进行对话，用户上传了 markdown 格式的简历文件，
提问简历中提到的某个具体项目。

### 根因分析

**双重问题**：

1. **智谱 GLM-4V 的系统指令跟随能力弱**：
   - 初始系统提示词为 "答案必须来自资料原文，禁止添加资料中没有的信息"
   - GLM-4V 对系统消息（System Message）的响应程度远低于用户消息（User Message）
   - 模型倾向于忽略系统层的约束，优先遵循用户消息中的指令

2. **文件内容直接拼接到用户消息中**：
   - 用户上传的文件内容直接拼接在用户消息后面
   - 模型将大段文件内容理解为"需要处理的输入"，而不是"可以参考的资料"
   - 导致模型认为用户要求它复述或处理整份文件

3. **GLM-4V 的预训练知识覆盖广**：
   - 针对"花小猪"这类知名度高的业务，模型有很强的预训练记忆
   - 这些记忆与用户的简历内容混合后，模型产生了幻觉，补充了简历中没有的信息

### 解决方案

**方案**：多模型路由 + 指令内嵌

```java
// 1. 路由策略：文本用 DeepSeek，图片用智谱 GLM-4V
if (hasImages) {
    streamChatWithZhipu(...);  // 仅当用户上传了图片才走智谱
} else {
    streamChatWithDeepSeek(...); // 纯文本一律走 DeepSeek
}

// 2. 指令内嵌到用户消息中（对两种模型都有效）
String enriched = buildEnrichedMessage(ragContext, fileContext, userMessage);
// 输出：
// "请分析以下资料来回答问题。
//  要求：答案必须直接引用资料中的原文，不得添加资料中没有的信息。
//
//  文件内容：<用户上传的文件>
//  知识库内容：<RAG 检索结果>
//
//  问题：花小猪砍车费是什么？"
```

**为什么 DeepSeek 表现更好**：
- DeepSeek 对系统指令和用户消息中的约束都能严格遵守
- 引用格式更规范，会显式标注 `【来源：xxx】`
- 不会在无相关信息时强行编造

### 技术要点

- **不同模型的指令跟随能力差异巨大**，不能假设所有模型都遵守 System Prompt
- 关键约束建议同时写入 System Prompt 和 User Message（双重保险）
- 多模态模型（GLM-4V）和纯文本模型（DeepSeek）的使用场景应分离
- 文件内容应**结构化呈现**（标注来源、文件名），而非简单拼接

---

## 5. RAG 上下文注入导致模型无法回答通用问题

### 现象

用户提问"你好"或"Python requests 库怎么用"等与知识库无关的问题时，
模型回答"没有找到相关信息"，拒绝回答本可以回答的通用问题。

### 触发场景

每次对话都执行 RAG 检索，只要有知识库内容，就把检索结果注入到 Prompt 中，
同时系统提示词要求"答案必须来自资料原文"。

### 根因分析

**RAG 架构设计缺陷**：

原来的流程是：
```
用户提问 → 向量检索 → 注入上下文 → 强制引用 → 输出
```

问题是：
1. **没有相关性判断**：无论检索结果和问题是否相关，都注入到 Prompt
2. **系统提示词过于严格**："禁止添加资料中没有的信息" 导致模型拒绝使用自身知识
3. **检索即假设**：假设所有检索到的内容都是相关的，实际上语义搜索 100% 准确的
   情况很少

### 解决方案

**方案**：基于余弦相似度的相关性阈值过滤 + 双模式提示

```java
// 1. 相关性阈值 0.55（经过校准）
private static final double RELEVANCE_THRESHOLD = 0.55;

// 2. 阈值过滤逻辑
List<ScoredVector> scoredVectors = searchSimilarWithScores(query, limit);
double bestScore = scoredVectors.get(0).similarity();
if (bestScore < RELEVANCE_THRESHOLD) {
    // 不注入 RAG 上下文，走普通对话模式
    return "";
}

// 3. 双模式提示
// 有上下文时：
"请分析以下资料来回答问题。答案必须直接引用资料中的原文..."

// 无上下文时：
// 直接使用用户消息，系统提示词为"你是智能助手，回答简洁准确"
```

**阈值校准过程**：
- 使用 ZhiPu embedding-2 模型，对中文的语义相似度分数偏低
- 相关查询的相似度：0.58 ~ 0.62
- 无关查询的相似度：0.25 ~ 0.33
- 最终校准为 0.55，平衡召回率和精确率

### 技术要点

- RAG 不是"检索了就一定要用"，需要**相关性判断层**
- 余弦相似度是天然的**相关性信号**，不需要额外 API 调用
- 阈值需要根据 embedding 模型**实际校准**，不能拍脑袋设 0.7 或 0.8
- **双模式 Prompt**：有上下文时严格引用，无上下文时自由回答
- 不同 embedding 模型的相似度分布不同（ZhiPu embedding-2 整体偏低，
  OpenAI ada-002 整体偏高），更换模型必须重新校准

---

## 6. 向量检索返回空结果的陷阱

### 现象

调用 `jdbcTemplate.queryForList(sql, Long.class, embeddingStr, limit)` 时，
明明数据库中有向量数据，但查询结果为空。

### 触发场景

向量检索的核心 SQL：

```sql
SELECT id FROM meeting_vectors
ORDER BY 1 - (embedding <=> CAST(? AS vector)) DESC
LIMIT ?
```

传入 embedding 向量字符串和 limit 参数。

### 根因分析

**Spring JdbcTemplate 的方法重载问题**：

```java
// 错误用法：
jdbcTemplate.queryForList(sql, Long.class, embeddingStr, limit);
```

`queryForList(String sql, Class<T> elementType, Object... args)` 是 JdbcTemplate
的一个重载方法，它期望查询结果中只包含**一列**，并将这一列映射为指定的类型。

但 Spring JDBC 的方法解析存在陷阱：当 `embeddingStr` 和 `limit` 被当成 `Object...`
参数传入时，`Long.class` 参数在某些版本中会被错误解析，导致 SQL 执行时参数
绑定出现问题。具体来说，`Long.class` 被当成了一个普通的 Object 参数，导致
参数数量不匹配或参数类型错误。

### 解决方案

**方案**：改为 `queryForList(String sql, Object... args)` 返回 `List<Map<String, Object>>`，
然后手动提取 ID。

```java
// 正确用法：
List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql, embeddingStr, limit);
List<Long> ids = rows.stream()
    .map(r -> ((Number) r.get("id")).longValue())
    .toList();
```

### 技术要点

- JdbcTemplate 的 `queryForList(sql, Class<T>, Object...)` 重载容易混淆，
  建议优先使用 `queryForList(sql, Object...)` + 手动映射
- 使用 `queryForList` 返回 `List<Map<String, Object>>` 是最安全的用法，
  可以获取任意列的组合
- 类型映射时注意 PostgreSQL 返回的数值类型是 `BigInteger` 或 `Long`，
  需要使用 `((Number) value).longValue()` 安全转换

---

## 7. 智谱 GLM-4V 忽略系统指令

### 现象

系统提示词设置为"答案必须来自资料原文"，但 GLM-4V 仍然基于自己的预训练知识
回答，甚至在资料中不包含相关信息时编造内容。

### 触发场景

用户上传简历后提问"谁组织了会议"，GLM-4V 根据自己的知识编造了回答，
而不是从资料中查找。

### 根因分析

**GLM-4V 的 API 设计特点**：

1. **System Message 权重低**：智谱 API 的设计中，系统消息的优先级低于
   用户消息。部分模型版本甚至不完全遵守系统消息中的约束。

2. **多模态模型的固有特性**：GLM-4V 是视觉-语言模型，主要优化方向是
   图片理解和多模态对话，对文本指令的精细控制不如纯文本模型。

3. **内容安全策略**：部分模型实现了内容安全过滤，当模型检测到"必须从
   资料中回答"这类限制性指令时，可能会绕过限制自由发挥。

### 解决方案

**方案**：指令内嵌 + DeepSeek 兜底

```java
// 1. 将约束指令嵌入到用户消息（而不是系统消息）
String enriched = "请分析以下资料来回答问题。\n"
    + "要求：答案必须直接引用资料中的原文，不得添加资料中没有的信息。\n"
    + "\n文件内容：\n" + fileContext
    + "\n知识库内容：\n" + ragContext
    + "\n\n问题：" + userMessage;

// 2. 纯文本问题走 DeepSeek，图片走智谱
if (hasImages) {
    // 走智谱 GLM-4V（用户消息中已含约束）
    streamChatWithZhipu(...);
} else {
    // 走 DeepSeek（指令跟随更好）
    streamChatWithDeepSeek(...);
}
```

### 技术要点

- **系统消息不可靠**：关键约束不能只放在 System Prompt 中
- **指令内嵌**（Instruction Injection）：将约束嵌入 User Message，对所有模型都有效
- **模型选择策略**：多模态任务用专用模型，文本任务用专用模型，不要用一个模型做所有事
- 任何新模型集成都需要测试其对 System Prompt 的遵循程度

---

## 8. 经验总结

### 架构决策经验

| 问题 | 教训 | 后续建议 |
|------|------|---------|
| Hibernate + pgvector | ORM 对数据库扩展类型支持有限 | VECTOR 列的操作统一用 JdbcTemplate |
| JPA 派生删除 | 派生方法会先查后删，效率低且有副作用 | 批量操作走 JdbcTemplate |
| @Modifying 事务 | DML 操作需要事务上下文 | 明确事务边界 |
| 大模型幻觉 | 不同模型能力差异大，不能混用 | 按场景选择专用模型 |

### RAG 系统设计经验

| 问题 | 教训 | 后续建议 |
|------|------|---------|
| 无条件注入 RAG | 需要相关性判断 | 余弦相似度阈值 + 双模式提示 |
| 系统提示词过于严格 | 限制了模型的通用能力 | 有上下文时引用，无上下文时自由回答 |
| 阈值拍脑袋 | 需要实际校准 | 基于 embedding 模型的分布设置阈值 |

### 面试话术建议

**当面试官问"你在项目中遇到的最有挑战的技术问题是什么？"**

> "我们在做 RAG 系统时发现 Hibernate 无法处理 pgvector 的 VECTOR 类型。
> 具体表现是 JPA 查询返回空结果、删除操作报类型错误。排查后发现是 ORM 框架
> 对 PostgreSQL 扩展类型支持不足。我们的解决方案是建立了一个**混合持久化层**：
> 常规操作继续用 JPA，涉及 VECTOR 列的操作全部走 JdbcTemplate。这个方案
> 既保留了 JPA 的开发效率，又避开了 ORM 的局限性。后来我们还把类似模式
> 用在了其他 PostgreSQL 扩展类型的处理上。"

**当面试官问"RAG 系统的意图识别你是怎么设计的？"**

> "我们没有用额外的意图分类器。核心思路是：**向量检索的余弦相似度本身就是
> 最好的相关性信号**。我们给检索结果设了一个 0.55 的相似度阈值，低于这个值
> 就不注入 RAG 上下文，让模型用自己的知识回答。同时实现了**双模式提示**：
> 有上下文时要求严格引用来源，无上下文时允许自由回答。这个方案的优点是
> 零额外成本（不需要 API 调用），用 embedding 模型的余弦距离做判断。
> 阈值是根据实际数据校准的——我们用的 ZhiPu embedding-2 模型对中文的
> 相似度分布偏低，相关查询只有 0.58-0.62，所以要设到 0.55。"

**当面试官问"你怎么解决大模型幻觉问题的？"**

> "我们遇到了两类幻觉：一是智谱 GLM-4V 模型无视系统指令自行编造，
> 二是 RAG 注入不相关内容后模型强行引用。解决方案分三层：
> 第一层，**多模型路由**——文本用 DeepSeek，多模态才用 GLM-4V，
> 因为 DeepSeek 的指令跟随能力明显更强。
> 第二层，**指令内嵌**——关键约束同时写在系统提示词和用户消息中，
> 因为智谱对系统消息的响应度很低。
> 第三层，**相关性过滤**——通过余弦相似度阈值只注入真正相关的内容，
> 避免模型被不相关的检索结果误导。"

---

*文档创建日期：2026-06-23*
*涵盖范围：Hibernate + pgvector 兼容性、RAG 架构设计、大模型幻觉治理*
