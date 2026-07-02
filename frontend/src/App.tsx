import React, { useState, useEffect, useCallback } from 'react';
import { Layout, Typography, Button, List, message as antMsg, Space, Modal, Input, ConfigProvider, theme } from 'antd';
import {
  PlusOutlined,
  MessageOutlined,
  DatabaseOutlined,
  ClockCircleOutlined,
  EllipsisOutlined,
  UnorderedListOutlined,
  DeleteOutlined,
  EditOutlined,
  RobotOutlined,
  BulbOutlined,
  SunOutlined,
  MoonOutlined,
} from '@ant-design/icons';
import DialoguePanel from './components/DialoguePanel';
import KnowledgeBase from './components/KnowledgeBase';
import MemoryEditor from './components/MemoryEditor';
import { listDialogues, createDialogue, deleteDialogue, renameDialogue, Dialogue } from './services/api';

const { Sider, Content } = Layout;
const { Text } = Typography;

const App: React.FC = () => {
  const [kbVisible, setKbVisible] = useState(false);
  const [memoryVisible, setMemoryVisible] = useState(false);
  const [dialogues, setDialogues] = useState<Dialogue[]>([]);
  const [activeDialogue, setActiveDialogue] = useState<Dialogue | null>(null);
  const [creating, setCreating] = useState(false);
  const [renameModal, setRenameModal] = useState<{ visible: boolean; dialogue: Dialogue | null; value: string }>({
    visible: false, dialogue: null, value: '',
  });

  const [isDark, setIsDark] = useState(() => {
    return localStorage.getItem('theme-dark') === 'true';
  });

  const toggleTheme = useCallback(() => {
    setIsDark(prev => {
      const next = !prev;
      localStorage.setItem('theme-dark', String(next));
      return next;
    });
  }, []);

  useEffect(() => {
    document.documentElement.dataset.theme = isDark ? 'dark' : 'light';
  }, [isDark]);

  const refreshDialogues = useCallback(async () => {
    try {
      const res = await listDialogues();
      setDialogues(res.data);
    } catch { /* ignore */ }
  }, []);

  useEffect(() => {
    refreshDialogues();
  }, [refreshDialogues]);

  const handleNewDialogue = async () => {
    if (creating) return;
    setCreating(true);
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
      setActiveDialogue(newDialogue);
      refreshDialogues();
    } catch {
      antMsg.error('创建对话失败');
    } finally {
      setCreating(false);
    }
  };

  const handleSelectDialogue = (d: Dialogue) => {
    setActiveDialogue(d);
  };

  const handleStartChat = async (message: string): Promise<Dialogue | null> => {
    if (creating) return null;
    setCreating(true);
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
      setActiveDialogue(newDialogue);
      refreshDialogues();
      return newDialogue;
    } catch {
      antMsg.error('创建对话失败');
      return null;
    } finally {
      setCreating(false);
    }
  };

  const handleDialogueUpdated = () => {
    refreshDialogues();
  };

  const handleCreateDialogue = async (): Promise<Dialogue | null> => {
    if (creating) return null;
    setCreating(true);
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
      setActiveDialogue(newDialogue);
      refreshDialogues();
      return newDialogue;
    } catch {
      antMsg.error('创建对话失败');
      return null;
    } finally {
      setCreating(false);
    }
  };

  const handleDelete = async (id: number) => {
    try {
      await deleteDialogue(id);
      if (activeDialogue?.id === id) setActiveDialogue(null);
      refreshDialogues();
      antMsg.success('已删除');
    } catch {
      antMsg.error('删除失败');
    }
  };

  const openRename = (d: Dialogue) => {
    setRenameModal({ visible: true, dialogue: d, value: d.title });
  };

  const handleRename = async () => {
    const d = renameModal.dialogue;
    const title = renameModal.value.trim();
    if (!d || !title) return;
    try {
      await renameDialogue(d.id, title);
      if (activeDialogue?.id === d.id) {
        setActiveDialogue({ ...d, title });
      }
      refreshDialogues();
      setRenameModal({ visible: false, dialogue: null, value: '' });
      antMsg.success('已重命名');
    } catch {
      antMsg.error('重命名失败');
    }
  };

  const formatDate = (dateStr: string) => {
    const d = new Date(dateStr);
    const now = new Date();
    const diffMs = now.getTime() - d.getTime();
    const diffDays = Math.floor(diffMs / (1000 * 60 * 60 * 24));
    if (diffDays === 0) return d.toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit' });
    if (diffDays === 1) return '昨天';
    if (diffDays < 7) return `${diffDays}天前`;
    return d.toLocaleDateString('zh-CN', { month: 'short', day: 'numeric' });
  };

  return (
    <ConfigProvider theme={{ algorithm: isDark ? theme.darkAlgorithm : theme.defaultAlgorithm }}>
    <Layout style={{ minHeight: '100vh', height: '100vh', background: 'var(--app-bg)' }}>
      {/* Left Sidebar */}
      <Sider
        width={280}
        theme="light"
        style={{
          borderRight: '1px solid var(--sider-border)',
          background: 'var(--sider-bg)',
          display: 'flex',
          flexDirection: 'column',
          overflow: 'hidden',
        }}
      >
        {/* Brand */}
        <div style={{
          padding: '20px 20px 16px',
          borderBottom: '1px solid var(--sider-border)',
        }}>
          <div style={{ display: 'flex', alignItems: 'center', gap: 10 }}>
            <div style={{
              width: 32, height: 32, borderRadius: 8,
              background: 'linear-gradient(135deg, #1677ff, #7c3aed)',
              display: 'flex', alignItems: 'center', justifyContent: 'center',
              flexShrink: 0,
            }}>
              <RobotOutlined style={{ fontSize: 18, color: '#fff' }} />
            </div>
            <Text strong style={{ fontSize: 18, color: 'var(--text-color)', letterSpacing: 1 }}>
              你问呗
            </Text>
          </div>
        </div>

        {/* Action Buttons */}
        <div style={{ padding: '12px 16px', display: 'flex', gap: 8 }}>
          <Button
            type="primary"
            icon={<PlusOutlined />}
            onClick={handleNewDialogue}
            loading={creating}
            style={{
              flex: 1, borderRadius: 8, height: 38,
              fontWeight: 500, fontSize: 14,
            }}
          >
            新建对话
          </Button>
          <Button
            icon={<UnorderedListOutlined />}
            style={{ borderRadius: 8, height: 38, width: 38 }}
          />
        </div>

        {/* Sidebar content (scrollable) */}
        <div style={{ flex: 1, overflow: 'auto', padding: '0 8px' }}>
          {/* My Knowledge Base */}
          <div style={{ marginBottom: 8, marginTop: 4 }}>
            <div
              onClick={() => setKbVisible(!kbVisible)}
              style={{
                display: 'flex', alignItems: 'center', gap: 10,
                padding: '10px 12px', borderRadius: 8, cursor: 'pointer',
                background: kbVisible ? 'var(--active-bg)' : 'transparent',
                color: kbVisible ? '#1677ff' : 'var(--text-color)',
                transition: 'all 0.2s',
              }}
              onMouseEnter={(e) => {
                if (!kbVisible) (e.currentTarget as HTMLElement).style.background = 'var(--hover-bg)';
              }}
              onMouseLeave={(e) => {
                if (!kbVisible) (e.currentTarget as HTMLElement).style.background = 'transparent';
              }}
            >
              <DatabaseOutlined style={{ fontSize: 16, color: kbVisible ? '#1677ff' : 'var(--text-tertiary)' }} />
              <Text style={{ fontSize: 14, fontWeight: 500, color: kbVisible ? '#1677ff' : 'var(--text-color)' }}>
                我的知识库
              </Text>
            </div>
          </div>

          {/* Agent Memory */}
          <div style={{ marginBottom: 8 }}>
            <div
              onClick={() => setMemoryVisible(true)}
              style={{
                display: 'flex', alignItems: 'center', gap: 10,
                padding: '10px 12px', borderRadius: 8, cursor: 'pointer',
                color: 'var(--text-color)',
                transition: 'all 0.2s',
              }}
              onMouseEnter={(e) => {
                (e.currentTarget as HTMLElement).style.background = 'var(--hover-bg)';
              }}
              onMouseLeave={(e) => {
                (e.currentTarget as HTMLElement).style.background = 'transparent';
              }}
            >
              <BulbOutlined style={{ fontSize: 16, color: 'var(--text-tertiary)' }} />
              <Text style={{ fontSize: 14, fontWeight: 500, color: 'var(--text-color)' }}>
                Agent 记忆
              </Text>
            </div>
          </div>

          {/* Recent Conversations */}
          <div style={{ marginTop: 12 }}>
            <div style={{
              padding: '8px 12px 6px',
              fontSize: 12, fontWeight: 600, color: 'var(--text-secondary)',
              letterSpacing: 0.5,
            }}>
              最近对话
            </div>
            <List
              dataSource={dialogues}
              locale={{ emptyText: '暂无对话' }}
              split={false}
              renderItem={(item, idx) => (
                <List.Item
                  onClick={() => handleSelectDialogue(item)}
                  style={{
                    cursor: 'pointer',
                    padding: '8px 12px',
                    borderRadius: 8,
                    marginBottom: 1,
                    background: activeDialogue?.id === item.id ? 'var(--active-bg)' : 'transparent',
                    border: 'none',
                    display: 'flex',
                    alignItems: 'center',
                    gap: 10,
                    transition: 'all 0.15s',
                  }}
                  onMouseEnter={(e) => {
                    if (activeDialogue?.id !== item.id) {
                      (e.currentTarget as HTMLElement).style.background = 'var(--hover-bg)';
                    }
                  }}
                  onMouseLeave={(e) => {
                    if (activeDialogue?.id !== item.id) {
                      (e.currentTarget as HTMLElement).style.background = 'transparent';
                    }
                  }}
                >
                  {/* Blue dot indicator (first 3 items or active item) */}
                  {(idx < 3 || activeDialogue?.id === item.id) ? (
                    <div style={{
                      width: 8, height: 8, borderRadius: '50%',
                      background: '#1677ff',
                      opacity: activeDialogue?.id === item.id ? 1 : 0.5,
                      flexShrink: 0,
                    }} />
                  ) : (
                    <div style={{ width: 8, flexShrink: 0 }} />
                  )}

                  {/* Icon */}
                  <MessageOutlined style={{
                    fontSize: 14,
                    color: activeDialogue?.id === item.id ? '#1677ff' : 'var(--text-secondary)',
                    flexShrink: 0,
                  }} />

                  {/* Content */}
                  <div style={{ flex: 1, minWidth: 0, display: 'flex', flexDirection: 'column', gap: 2 }}>
                    <Text
                      style={{
                        fontSize: 13,
                        color: activeDialogue?.id === item.id ? '#1677ff' : 'var(--text-color)',
                        fontWeight: activeDialogue?.id === item.id ? 500 : 400,
                      }}
                      ellipsis
                    >
                      {item.title}
                    </Text>
                    <Text type="secondary" style={{ fontSize: 11 }}>
                      {formatDate(item.updatedAt)}
                    </Text>
                  </div>

                  {/* Actions */}
                  <Button
                    type="text"
                    size="small"
                    icon={<EllipsisOutlined style={{ fontSize: 14 }} />}
                    onClick={(e) => {
                      e.stopPropagation();
                      Modal.confirm({
                        title: `操作「${item.title}」`,
                        content: (
                          <div style={{ display: 'flex', flexDirection: 'column', gap: 8, marginTop: 12 }}>
                            <Button
                              block
                              icon={<EditOutlined />}
                              onClick={() => { Modal.destroyAll(); openRename(item); }}
                            >
                              重命名
                            </Button>
                            <Button
                              block
                              danger
                              icon={<DeleteOutlined />}
                              onClick={() => {
                                Modal.destroyAll();
                                Modal.confirm({
                                  title: '确认删除？',
                                  content: '删除后不可恢复',
                                  okText: '删除',
                                  okType: 'danger',
                                  cancelText: '取消',
                                  onOk: () => handleDelete(item.id),
                                });
                              }}
                            >
                              删除
                            </Button>
                          </div>
                        ),
                        footer: null,
                        closable: true,
                      });
                    }}
                    style={{ color: 'var(--text-secondary)', flexShrink: 0 }}
                  />
                </List.Item>
              )}
            />
          </div>
        </div>

        {/* Theme Toggle */}
        <div style={{
          padding: '12px 16px',
          borderTop: '1px solid var(--sider-border)',
        }}>
          <Button
            type="text"
            icon={isDark ? <SunOutlined /> : <MoonOutlined />}
            onClick={toggleTheme}
            style={{ width: '100%', borderRadius: 8, color: 'var(--text-tertiary)', fontSize: 13 }}
          >
            {isDark ? '浅色模式' : '深色模式'}
          </Button>
        </div>
      </Sider>

      {/* Right Main Content */}
      <Content style={{ display: 'flex', flexDirection: 'column', overflow: 'hidden' }}>
        <DialoguePanel
          activeDialogue={activeDialogue}
          onDialogueUpdated={handleDialogueUpdated}
          onStartChat={handleStartChat}
          onCreateDialogue={handleCreateDialogue}
        />
      </Content>

      {/* Memory Editor Drawer */}
      <MemoryEditor visible={memoryVisible} onClose={() => setMemoryVisible(false)} />

      {/* Knowledge Base Right Panel */}
      {kbVisible && (
        <Sider
          width={320}
          theme="light"
          style={{
            borderLeft: '1px solid var(--sider-border)',
            overflow: 'auto',
            background: 'var(--sider-bg)',
          }}
        >
          <KnowledgeBase visible={kbVisible} onClose={() => setKbVisible(false)} />
        </Sider>
      )}

      {/* Rename Modal */}
      <Modal
        title="重命名对话"
        open={renameModal.visible}
        onOk={handleRename}
        onCancel={() => setRenameModal({ visible: false, dialogue: null, value: '' })}
        okText="确认"
        cancelText="取消"
      >
        <Input
          value={renameModal.value}
          onChange={(e) => setRenameModal(prev => ({ ...prev, value: e.target.value }))}
          onPressEnter={handleRename}
          placeholder="输入新名称"
          autoFocus
        />
      </Modal>
    </Layout>
    </ConfigProvider>
  );
};

export default App;
