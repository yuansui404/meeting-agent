import React, { useState } from 'react';
import { Card, List, Tag, Typography, Modal } from 'antd';
import { getMeeting, Meeting } from '../services/api';

const { Text } = Typography;

interface Props {
  meetings: Meeting[];
}

const statusConfig: Record<string, { color: string; text: string }> = {
  processing: { color: 'processing', text: '转写中' },
  completed: { color: 'success', text: '已完成' },
  failed: { color: 'error', text: '失败' },
};

const MeetingList: React.FC<Props> = ({ meetings }) => {
  const [detailVisible, setDetailVisible] = useState(false);
  const [detail, setDetail] = useState<Meeting | null>(null);

  const showDetail = async (id: number) => {
    try {
      const res = await getMeeting(id);
      setDetail(res.data);
      setDetailVisible(true);
    } catch {
      // ignore
    }
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
      <Card title="会议列表" size="small">
        <List
          dataSource={meetings}
          locale={{ emptyText: '暂无会议' }}
          renderItem={(item) => {
            const cfg = statusConfig[item.status] || { color: 'default', text: item.status };
            return (
              <List.Item
                onClick={() => showDetail(item.id)}
                style={{ cursor: 'pointer' }}
              >
                <List.Item.Meta
                  title={
                    <span>
                      {item.title}
                      <Tag color={cfg.color} style={{ marginLeft: 8 }}>{cfg.text}</Tag>
                    </span>
                  }
                  description={
                    <Text type="secondary" style={{ fontSize: 12 }}>
                      {formatDate(item.createdAt)} | {formatSize(item.fileSize)}
                    </Text>
                  }
                />
              </List.Item>
            );
          }}
        />
      </Card>
      <Modal
        title={detail?.title || '会议详情'}
        open={detailVisible}
        onCancel={() => setDetailVisible(false)}
        footer={null}
        width={800}
      >
        {detail && (
          <div>
            <p><Text strong>状态：</Text>
              <Tag color={statusConfig[detail.status]?.color}>{statusConfig[detail.status]?.text}</Tag>
            </p>
            <p><Text strong>文件大小：</Text>{formatSize(detail.fileSize)}</p>
            <p><Text strong>创建时间：</Text>{formatDate(detail.createdAt)}</p>
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
