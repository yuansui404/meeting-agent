-- ============================================================
-- RAG 评估测试集初始数据
-- 注意：请根据实际知识库内容调整 ground_truth_doc_ids
-- 先用 docker exec 查看文档列表：psql -U user -d meeting_agent -c "SELECT id, title FROM document"
-- 然后替换下方的文档ID
-- ============================================================

-- 预算/财务
INSERT INTO eval_test_case (question, ground_truth_doc_ids, ground_truth_answer, topic, query_type, difficulty) VALUES
('预算审批流程是什么？', '{1}', '预算审批流程说明', '预算', 'fact', 'Easy');

-- 技术
INSERT INTO eval_test_case (question, ground_truth_doc_ids, ground_truth_answer, topic, query_type, difficulty) VALUES
('技术架构升级方案是什么？', '{2}', '架构升级方案', '技术', 'fact', 'Easy');

-- 战略
INSERT INTO eval_test_case (question, ground_truth_doc_ids, ground_truth_answer, topic, query_type, difficulty) VALUES
('今年下半年的战略重点是什么？', '{3}', '下半年战略方向', '战略', 'summary', 'Medium');

-- 人事
INSERT INTO eval_test_case (question, ground_truth_doc_ids, ground_truth_answer, topic, query_type, difficulty) VALUES
('人事调整方案的内容是什么？', '{4}', '人事调整方案', '人事', 'fact', 'Easy');

-- 对比
INSERT INTO eval_test_case (question, ground_truth_doc_ids, ground_truth_answer, topic, query_type, difficulty) VALUES
('对比Q1和Q2的预算方案差异', '{1,5}', '两个季度的预算对比', '预算', 'comparison', 'Hard');

-- 跨文档推理
INSERT INTO eval_test_case (question, ground_truth_doc_ids, ground_truth_answer, topic, query_type, difficulty) VALUES
('技术架构升级对预算有什么影响？', '{1,2}', '技术投入对预算的影响', '预算', 'reasoning', 'Hard');

-- 时间敏感
INSERT INTO eval_test_case (question, ground_truth_doc_ids, ground_truth_answer, topic, query_type, difficulty) VALUES
('最近一次技术评审会讨论了什么？', '{2}', '最近的技术评审议题', '技术', 'summary', 'Medium');

-- 发言人
INSERT INTO eval_test_case (question, ground_truth_doc_ids, ground_truth_answer, topic, query_type, difficulty) VALUES
('张弢在会议上提了什么建议？', '{2,3}', '张弢的建议内容', '技术', 'fact', 'Easy');

-- 模糊匹配
INSERT INTO eval_test_case (question, ground_truth_doc_ids, ground_truth_answer, topic, query_type, difficulty) VALUES
('那笔500万的拨款是给哪个项目的？', '{5}', 'A项目拨款500万', '预算', 'fact', 'Medium');

-- 多跳推理
INSERT INTO eval_test_case (question, ground_truth_doc_ids, ground_truth_answer, topic, query_type, difficulty) VALUES
('哪些部门参与了今年的预算审批？', '{1,5}', '参与预算审批的部门', '预算', 'reasoning', 'Hard');