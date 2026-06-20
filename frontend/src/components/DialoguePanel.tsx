import React, { useState, useEffect } from 'react';
import { Card, List, Button, Typography, Input, Space, Popconfirm, Tag, message } from 'antd';
import {
  PlusOutlined,
  MessageOutlined,
  ImportOutlined,
} from '@ant-design/icons';
import {
  Dialogue,
  DialogueMessage,
  createDialogue,
  getDialogue,
  addMessage,
  archiveDialogue,
  importDialogue,
} from '../services/api';

const { Text, Paragraph } = Typography;
const { TextArea } = Input;

interface Props {
  dialogues: Dialogue[];
  activeDialogue: Dialogue | null;
  onSelect: (d: Dialogue | null) => void;
  onRefresh: () => void;
}

const DialoguePanel: React.FC<Props> = ({ dialogues, activeDialogue, onSelect, onRefresh }) => {
  const [messages, setMessages] = useState<DialogueMessage[]>([]);
  const [input, setInput] = useState('');

  useEffect(() => {
    if (activeDialogue) {
      loadMessages(activeDialogue.id);
    } else {
      setMessages([]);
    }
  }, [activeDialogue]);

  const loadMessages = async (id: number) => {
    try {
      const res = await getDialogue(id);
      setMessages(res.data.messages || []);
    } catch {
      setMessages([]);
    }
  };

  const handleNewDialogue = async () => {
    try {
      const res = await createDialogue('新对话');
      const newDialogue: Dialogue = {
        id: res.data.dialogueId,
        title: '新对话',
        status: 'active',
        updatedAt: new Date().toISOString(),
        meetingId: null,
        imported: false,
      };
      onSelect(newDialogue);
      onRefresh();
    } catch {
      // ignore
    }
  };

  const handleSend = async () => {
    if (!input.trim() || !activeDialogue) return;
    const content = input.trim();
    setInput('');

    try {
      await addMessage(activeDialogue.id, 'user', content);
      const res = await getDialogue(activeDialogue.id);
      setMessages(res.data.messages || []);
      onRefresh();
    } catch {
      // ignore
    }
  };

  const handleImport = async (id: number) => {
    try {
      await importDialogue(id);
      message.success('已导入知识库');
      onRefresh();
    } catch {
      message.error('导入失败');
    }
  };

  const handleArchive = async (id: number) => {
    try {
      await archiveDialogue(id);
      if (activeDialogue?.id === id) onSelect(null);
      onRefresh();
    } catch {
      // ignore
    }
  };

  const formatDate = (dateStr: string) => {
    return new Date(dateStr).toLocaleString('zh-CN');
  };

  const getTypeTag = (type: string) => {
    const config: Record<string, { color: string; text: string }> = {
      text: { color: 'default', text: '文本' },
      search: { color: 'blue', text: '搜索' },
      note: { color: 'orange', text: '备注' },
      transcription: { color: 'green', text: '转写' },
    };
    const c = config[type] || { color: 'default', text: type };
    return <Tag color={c.color}>{c.text}</Tag>;
  };

  if (activeDialogue) {
    return (
      <Card
        title={
          <Space>
            <MessageOutlined />
            <span>{activeDialogue.title}</span>
          </Space>
        }
        size="small"
        extra={
          <Space>
            <Button size="small" onClick={() => onSelect(null)}>返回列表</Button>
            <Popconfirm title="确认归档？" onConfirm={() => handleArchive(activeDialogue.id)}>
              <Button size="small" danger>归档</Button>
            </Popconfirm>
          </Space>
        }
      >
        <div style={{ maxHeight: 400, overflow: 'auto', marginBottom: 12 }}>
          {messages.length === 0 ? (
            <Text type="secondary">暂无消息，开始对话吧</Text>
          ) : (
            messages.map((msg) => (
              <div
                key={msg.id}
                style={{
                  marginBottom: 8,
                  padding: '8px 12px',
                  background: msg.role === 'user' ? '#e6f7ff' : '#f6f6f6',
                  borderRadius: 4,
                }}
              >
                <div style={{ marginBottom: 4 }}>
                  <Text strong style={{ fontSize: 13 }}>
                    {msg.role === 'user' ? '你' : '助手'}
                  </Text>
                  {' '}
                  {getTypeTag(msg.messageType)}
                  <Text type="secondary" style={{ fontSize: 11, marginLeft: 8 }}>
                    {formatDate(msg.timestamp)}
                  </Text>
                </div>
                <Paragraph style={{ margin: 0, whiteSpace: 'pre-wrap', fontSize: 13 }}>
                  {msg.content}
                </Paragraph>
              </div>
            ))
          )}
        </div>
        <Space.Compact style={{ width: '100%' }}>
          <TextArea
            value={input}
            onChange={(e) => setInput(e.target.value)}
            onPressEnter={handleSend}
            placeholder="输入消息..."
            rows={2}
            style={{ flex: 1 }}
          />
          <Button type="primary" onClick={handleSend}>发送</Button>
        </Space.Compact>
      </Card>
    );
  }

  return (
    <Card
      title="对话列表"
      size="small"
      extra={
        <Button type="primary" size="small" icon={<PlusOutlined />} onClick={handleNewDialogue}>
          新建对话
        </Button>
      }
    >
      <List
        dataSource={dialogues}
        locale={{ emptyText: '暂无对话' }}
        renderItem={(item) => (
          <List.Item
            onClick={() => onSelect(item)}
            style={{ cursor: 'pointer' }}
            actions={[
              <Button
                type="link"
                size="small"
                icon={<ImportOutlined />}
                onClick={(e) => {
                  e.stopPropagation();
                  handleImport(item.id);
                }}
              >
                导入
              </Button>,
            ]}
          >
            <List.Item.Meta
              avatar={<MessageOutlined />}
              title={item.title}
              description={
                <Space size={4}>
                  <Text type="secondary" style={{ fontSize: 12 }}>{formatDate(item.updatedAt)}</Text>
                  {item.imported && <Tag color="green" style={{ fontSize: 10 }}>已导入</Tag>}
                </Space>
              }
            />
          </List.Item>
        )}
      />
    </Card>
  );
};

export default DialoguePanel;
