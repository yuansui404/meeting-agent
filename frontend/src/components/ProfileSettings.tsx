import React, { useState, useEffect } from 'react';
import { Typography, Button, List, Input, message as antMsg, Space, Modal, Tabs, Drawer } from 'antd';
import {
  UserOutlined,
  PlusOutlined,
  DeleteOutlined,
  SaveOutlined,
  FileTextOutlined,
  CloseOutlined,
} from '@ant-design/icons';
import {
  listProfileFiles,
  getProfileFile,
  saveProfileFile,
  createProfileFile,
  deleteProfileFile,
} from '../services/api';

const { Text } = Typography;
const { TextArea } = Input;

interface Props {
  visible: boolean;
  onClose: () => void;
}

const ProfileSettings: React.FC<Props> = ({ visible, onClose }) => {
  const [files, setFiles] = useState<string[]>([]);
  const [activeFile, setActiveFile] = useState<string | null>(null);
  const [content, setContent] = useState('');
  const [loading, setLoading] = useState(false);
  const [saving, setSaving] = useState(false);
  const [newFileModal, setNewFileModal] = useState(false);
  const [newFileName, setNewFileName] = useState('');
  const [dirty, setDirty] = useState(false);

  useEffect(() => {
    if (visible) loadFiles();
  }, [visible]);

  const loadFiles = async () => {
    setLoading(true);
    try {
      const res = await listProfileFiles();
      const fileList = res.data.data || [];
      setFiles(fileList);
      // Select first file if none selected
      if (fileList.length > 0) {
        const target = activeFile && fileList.includes(activeFile) ? activeFile : fileList[0];
        loadContent(target);
      } else {
        setActiveFile(null);
        setContent('');
      }
    } catch {
      antMsg.error('加载用户画像列表失败');
    } finally {
      setLoading(false);
    }
  };

  const loadContent = async (filename: string) => {
    try {
      const res = await getProfileFile(filename);
      setContent(res.data.data.content || '');
      setActiveFile(filename);
      setDirty(false);
    } catch {
      antMsg.error('加载文件内容失败');
    }
  };

  const handleTabChange = (key: string) => {
    if (dirty) {
      Modal.confirm({
        title: '有未保存的更改',
        content: '切换文件前是否保存当前更改？',
        okText: '保存',
        cancelText: '不保存',
        onOk: async () => {
          if (activeFile) {
            await handleSave(activeFile, content);
          }
          loadContent(key);
        },
        onCancel: () => {
          loadContent(key);
        },
      });
    } else {
      loadContent(key);
    }
  };

  const handleSave = async (filename: string, fileContent: string) => {
    setSaving(true);
    try {
      await saveProfileFile(filename, fileContent);
      antMsg.success('已保存');
      setDirty(false);
    } catch {
      antMsg.error('保存失败');
    } finally {
      setSaving(false);
    }
  };

  const handleNewFile = async () => {
    const name = newFileName.trim();
    if (!name) return;
    const filename = name.endsWith('.md') ? name : name + '.md';
    if (files.includes(filename)) {
      antMsg.warning('文件已存在');
      return;
    }
    try {
      await createProfileFile(filename);
      antMsg.success('已创建');
      setNewFileModal(false);
      setNewFileName('');
      loadFiles();
    } catch {
      antMsg.error('创建失败');
    }
  };

  const handleDelete = (filename: string) => {
    Modal.confirm({
      title: `删除「${filename}」？`,
      content: '删除后无法恢复',
      okText: '删除',
      okType: 'danger',
      cancelText: '取消',
      onOk: async () => {
        try {
          await deleteProfileFile(filename);
          antMsg.success('已删除');
          if (activeFile === filename) {
            setActiveFile(null);
            setContent('');
          }
          loadFiles();
        } catch {
          antMsg.error('删除失败');
        }
      },
    });
  };

  const contentChanged = (e: React.ChangeEvent<HTMLTextAreaElement>) => {
    setContent(e.target.value);
    setDirty(true);
  };

  return (
    <Drawer
      title={
        <Space>
          <UserOutlined />
          <span>个人设置</span>
        </Space>
      }
      placement="right"
      width={500}
      open={visible}
      onClose={onClose}
      extra={
        <Button type="text" icon={<CloseOutlined />} onClick={onClose} />
      }
    >
      {files.length === 0 && !loading ? (
        <div style={{ textAlign: 'center', padding: '40px 0', color: '#999' }}>
          <UserOutlined style={{ fontSize: 48, marginBottom: 12, color: '#d9d9d9' }} />
          <div>暂无用户画像文件</div>
          <div style={{ fontSize: 12, marginTop: 4 }}>
            新建文件或等待 agent 自动记录信息
          </div>
          <Button
            type="primary"
            icon={<PlusOutlined />}
            style={{ marginTop: 16 }}
            onClick={() => setNewFileModal(true)}
          >
            新建文件
          </Button>
        </div>
      ) : (
        <>
          <div style={{ marginBottom: 12, display: 'flex', gap: 8 }}>
            <Button
              icon={<PlusOutlined />}
              size="small"
              onClick={() => setNewFileModal(true)}
            >
              新建
            </Button>
            <Button
              icon={<SaveOutlined />}
              size="small"
              type="primary"
              loading={saving}
              disabled={!dirty || !activeFile}
              onClick={() => activeFile && handleSave(activeFile, content)}
            >
              保存
            </Button>
          </div>

          <Tabs
            tabPosition="left"
            activeKey={activeFile || undefined}
            onChange={handleTabChange}
            style={{ minHeight: 300 }}
            items={files.map(f => ({
              key: f,
              label: (
                <Space style={{ display: 'flex', justifyContent: 'space-between', width: '100%' }}>
                  <FileTextOutlined />
                  <Text ellipsis style={{ maxWidth: 80, fontSize: 13 }}>
                    {f.replace('.md', '')}
                  </Text>
                  <DeleteOutlined
                    style={{ color: '#ff4d4f', fontSize: 11, opacity: 0.6 }}
                    onClick={(e) => {
                      e.stopPropagation();
                      handleDelete(f);
                    }}
                  />
                </Space>
              ),
              children: (
                <TextArea
                  value={content}
                  onChange={contentChanged}
                  rows={20}
                  style={{ fontFamily: 'monospace', fontSize: 13 }}
                  placeholder="在此编辑 Markdown 内容..."
                />
              ),
            }))}
          />
        </>
      )}

      {/* New File Modal */}
      <Modal
        title="新建用户画像文件"
        open={newFileModal}
        onOk={handleNewFile}
        onCancel={() => {
          setNewFileModal(false);
          setNewFileName('');
        }}
        okText="创建"
        cancelText="取消"
      >
        <Input
          value={newFileName}
          onChange={(e) => setNewFileName(e.target.value)}
          placeholder="文件名（如：我的偏好）"
          onPressEnter={handleNewFile}
          suffix=".md"
          autoFocus
        />
      </Modal>
    </Drawer>
  );
};

export default ProfileSettings;

