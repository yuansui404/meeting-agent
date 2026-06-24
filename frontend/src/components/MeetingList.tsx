import React, { useState } from 'react';
import { List, Tag, Typography, Modal, Button, message as antMsg, Space } from 'antd';
import { FileTextOutlined, BookOutlined } from '@ant-design/icons';
import { getMeeting, Meeting, toggleKnowledgeBase } from '../services/api';

const { Text } = Typography;

interface Props {
  meetings: Meeting[];
  compact?: boolean;
}

const statusConfig: Record<string, { color: string; text: string }> = {
  processing: { color: 'processing', text: '转写中' },
  completed: { color: 'success', text: '已完成' },
  failed: { color: 'error', text: '失败' },
};

const MeetingList: React.FC<Props> = ({ meetings, compact }) => {
  const [detailVisible, setDetailVisible] = useState(false);
  const [detail, setDetail] = useState<Meeting | null>(null);

  const showDetail = async (id: number) => {
    try {
      const res = await getMeeting(id);
      setDetail(res.data);
      setDetailVisible(true);
    } catch { /* ignore */ }
  };

  const formatDate = (dateStr: string) => {
    return new Date(dateStr).toLocaleString('zh-CN');
  };

  const formatSize = (bytes: number | null) => {
    if (!bytes) return '未知';
    const mb = bytes / (1024 * 1024);
    return mb > 1 ? `${mb.toFixed(1)} MB` : `${(bytes / 1024).toFixed(0)} KB`;
  };

  return (
    <>
      <List
        dataSource={meetings}
        locale={{ emptyText: compact ? '暂无会议' : '暂无会议' }}
        renderItem={(item) => {
          const cfg = statusConfig[item.status] || { color: 'default', text: item.status };
          return (
            <List.Item
              onClick={() => showDetail(item.id)}
              style={{
                cursor: 'pointer',
                padding: compact ? '8px 16px' : '12px 24px',
                border: 'none',
              }}
            >
              <List.Item.Meta
                avatar={<FileTextOutlined style={{ color: '#1677ff', fontSize: 16 }} />}
                title={
                  <span>
                    <Text style={{ fontSize: compact ? 13 : 14 }} ellipsis>
                      {item.title}
                    </Text>
                    <Tag color={cfg.color} style={{ marginLeft: 6, fontSize: compact ? 10 : 11 }}>
                      {cfg.text}
                    </Tag>
                  </span>
                }
                description={
                  <Text type="secondary" style={{ fontSize: compact ? 11 : 12 }}>
                    {formatDate(item.createdAt)} | {formatSize(item.fileSize)}
                  </Text>
                }
              />
            </List.Item>
          );
        }}
      />
      <Modal
        title={detail?.title || '会议详情'}
        open={detailVisible}
        onCancel={() => setDetailVisible(false)}
        footer={null}
        width={800}
      >
        {detail && (
          <div>
            <Space style={{ marginBottom: 12 }} wrap>
              <Text strong>状态：</Text>
              <Tag color={statusConfig[detail.status]?.color}>{statusConfig[detail.status]?.text}</Tag>
              {detail.knowledgeBase && <Tag color="purple" icon={<BookOutlined />}>知识库</Tag>}
            </Space>
            <p><Text strong>文件大小：</Text>{formatSize(detail.fileSize)}</p>
            <p><Text strong>创建时间：</Text>{formatDate(detail.createdAt)}</p>
            <div style={{ marginBottom: 12 }}>
              <Button
                type={detail.knowledgeBase ? 'primary' : 'default'}
                icon={<BookOutlined />}
                onClick={async () => {
                  try {
                    const res = await toggleKnowledgeBase(detail.id);
                    setDetail({ ...detail, knowledgeBase: res.data.knowledgeBase });
                    antMsg.success(res.data.knowledgeBase ? '已加入知识库' : '已移出知识库');
                  } catch {
                    antMsg.error('操作失败');
                  }
                }}
              >
                {detail.knowledgeBase ? '已在知识库中' : '加入知识库'}
              </Button>
            </div>
            <div style={{ marginTop: 16 }}>
              <Text strong>转写内容：</Text>
              <div style={{
                marginTop: 8,
                padding: 12,
                background: '#fafafa',
                borderRadius: 4,
                whiteSpace: 'pre-wrap',
                maxHeight: 400,
                overflow: 'auto',
                fontSize: 13,
              }}>
                {detail.transcription || '暂无转写内容'}
              </div>
            </div>
          </div>
        )}
      </Modal>
    </>
  );
};

export default MeetingList;
