-- ============================================================
-- RAG 评估测试集初始数据
-- 注：ground_truth_doc_ids 基于当前知识库实际文档ID
-- 新文档入库后需更新此文件
-- ============================================================

TRUNCATE eval_test_case CASCADE;

-- 工业互联网业务规划（根据当前文档内容调整）
INSERT INTO eval_test_case (question, ground_truth_doc_ids, ground_truth_answer, topic, query_type, difficulty) VALUES
('工业互联网的业务规划是什么？', '{12}', '工业互联网业务规划内容', '战略', 'fact', 'Easy');

INSERT INTO eval_test_case (question, ground_truth_doc_ids, ground_truth_answer, topic, query_type, difficulty) VALUES
('2026年工业互联网有哪些重点方向？', '{12}', '2026年工业互联网重点方向', '战略', 'summary', 'Medium');

INSERT INTO eval_test_case (question, ground_truth_doc_ids, ground_truth_answer, topic, query_type, difficulty) VALUES
('工业互联网的目标市场是什么？', '{12}', '工业互联网目标市场', '战略', 'fact', 'Easy');

INSERT INTO eval_test_case (question, ground_truth_doc_ids, ground_truth_answer, topic, query_type, difficulty) VALUES
('工业互联网业务的竞争优势是什么？', '{12}', '工业互联网竞争优势', '战略', 'fact', 'Medium');

INSERT INTO eval_test_case (question, ground_truth_doc_ids, ground_truth_answer, topic, query_type, difficulty) VALUES
('工业互联网的预期收益和投入成本是多少？', '{12}', '工业互联网投入产出分析', '战略', 'fact', 'Medium');

INSERT INTO eval_test_case (question, ground_truth_doc_ids, ground_truth_answer, topic, query_type, difficulty) VALUES
('谁负责工业互联网项目的推进？', '{12}', '项目负责人及团队', '战略', 'fact', 'Easy');

INSERT INTO eval_test_case (question, ground_truth_doc_ids, ground_truth_answer, topic, query_type, difficulty) VALUES
('工业互联网项目的时间规划是怎样的？', '{12}', '项目时间线及里程碑', '战略', 'summary', 'Medium');

INSERT INTO eval_test_case (question, ground_truth_doc_ids, ground_truth_answer, topic, query_type, difficulty) VALUES
('工业互联网业务汇报中提到了哪些技术方案？', '{12}', '技术方案选型', '技术', 'fact', 'Medium');

INSERT INTO eval_test_case (question, ground_truth_doc_ids, ground_truth_answer, topic, query_type, difficulty) VALUES
('工业互联网的市场规模预测是多少？', '{12}', '市场规模预测数据', '战略', 'fact', 'Hard');

INSERT INTO eval_test_case (question, ground_truth_doc_ids, ground_truth_answer, topic, query_type, difficulty) VALUES
('工业互联网汇报会议有哪些人参加？', '{12}', '参会人员名单', '战略', 'fact', 'Easy');