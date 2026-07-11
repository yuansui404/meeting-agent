#!/bin/bash
# 快捷查看数据库内容
# 用法:
#   ./docker/db.sh              - 查看所有表
#   ./docker/db.sh docs         - 查看文档列表
#   ./docker/db.sh chunks       - 查看所有 chunk 概览
#   ./docker/db.sh dump 7       - 查看文档ID=7 的完整 chunk 内容
#   ./docker/db.sh sql "SELECT..." - 执行任意 SQL

CMD="docker exec -i docker-postgres-1 psql -U user -d meeting_agent"

case "${1:-}" in
  docs)
    $CMD -c "SELECT id, title, file_type, status, chunk_count, meeting_date, participants FROM document ORDER BY id;"
    ;;
  chunks)
    $CMD -c "
      SELECT c.id, c.document_id, c.chunk_index, c.speaker,
             length(c.content) AS char_len,
             left(c.content, 80) AS content_preview,
             c.metadata
      FROM document_chunk c
      ORDER BY c.document_id, c.chunk_index;
    "
    ;;
  dump)
    ID=${2:-1}
    $CMD -c "\x" -c "SELECT id, document_id, chunk_index, speaker, content, metadata FROM document_chunk WHERE document_id = $ID ORDER BY chunk_index;"
    ;;
  sql)
    shift
    $CMD -c "$*"
    ;;
  *)
    $CMD -c "\dt"
    $CMD -c "SELECT '---' AS info;"
    $CMD -c "SELECT id, title, file_type, status, chunk_count FROM document ORDER BY id;"
    $CMD -c "SELECT count(*) AS total_chunks FROM document_chunk;"
    ;;
esac