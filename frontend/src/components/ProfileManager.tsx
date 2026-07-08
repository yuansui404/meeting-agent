import React, { useEffect, useState } from 'react';
import { Drawer, Table, Switch, Button, Input, message, Popconfirm, Space, Modal, Form } from 'antd';
import { PlusOutlined, DeleteOutlined } from '@ant-design/icons';
import {
  getProfileFiles, getProfileFile, saveProfileFile,
  createProfileFile, deleteProfileFile, toggleProfileFile,
  ProfileFileItem,
} from '../services/api';

interface Props {
  visible: boolean;
  onClose: () => void;
}

const ProfileManager: React.FC<Props> = ({ visible, onClose }) => {
  const [files, setFiles] = useState<ProfileFileItem[]>([]);
  const [loading, setLoading] = useState(false);
  const [editing, setEditing] = useState<{ filename: string; content: string; description: string } | null>(null);
  const [saving, setSaving] = useState(false);
  const [createModalOpen, setCreateModalOpen] = useState(false);
  const [form] = Form.useForm();

  const loadFiles = async () => {
    setLoading(true);
    try {
      const res = await getProfileFiles();
      setFiles(res.data.data || []);
    } catch {
      message.error('加载画像文件失败');
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    if (visible) loadFiles();
  }, [visible]);

  const handleEdit = async (filename: string) => {
    try {
      const res = await getProfileFile(filename);
      const item = files.find(f => f.filename === filename);
      setEditing({
        filename,
        content: res.data.data?.content || '',
        description: item?.description || '',
      });
    } catch {
      message.error('加载文件内容失败');
    }
  };

  const handleSave = async () => {
    if (!editing) return;
    setSaving(true);
    try {
      await saveProfileFile(editing.filename, {
        content: editing.content,
        description: editing.description,
      });
      message.success('保存成功');
      setEditing(null);
      loadFiles();
    } catch {
      message.error('保存失败');
    } finally {
      setSaving(false);
    }
  };

  const handleToggle = async (filename: string, enabled: boolean) => {
    try {
      await toggleProfileFile(filename, enabled);
      loadFiles();
    } catch {
      message.error('操作失败');
    }
  };

  const handleDelete = async (filename: string) => {
    try {
      await deleteProfileFile(filename);
      message.success('已删除');
      loadFiles();
    } catch {
      message.error('删除失败');
    }
  };

  const handleCreate = async () => {
    try {
      const values = await form.validateFields();
      const filename = values.filename.endsWith('.md') ? values.filename : values.filename + '.md';
      await createProfileFile(filename, { description: values.description });
      message.success('创建成功');
      setCreateModalOpen(false);
      form.resetFields();
      loadFiles();
    } catch {
      // validation failed or API error
    }
  };

  const columns = [
    {
      title: '文件名',
      dataIndex: 'filename',
      key: 'filename',
      render: (filename: string) => (
        <a onClick={() => handleEdit(filename)}>{filename}</a>
      ),
    },
    {
      title: '描述',
      dataIndex: 'description',
      key: 'description',
      ellipsis: true,
      render: (desc: string) => desc || '-',
    },
    {
      title: '启用',
      dataIndex: 'enabled',
      key: 'enabled',
      width: 70,
      render: (enabled: boolean, record: ProfileFileItem) => (
        <Switch
          size="small"
          checked={enabled !== false}
          onChange={(val) => handleToggle(record.filename, val)}
        />
      ),
    },
    {
      title: '',
      key: 'action',
      width: 40,
      render: (_: unknown, record: ProfileFileItem) => (
        <Popconfirm title="确定删除？" onConfirm={() => handleDelete(record.filename)}>
          <DeleteOutlined style={{ color: 'var(--text-tertiary)', cursor: 'pointer' }} />
        </Popconfirm>
      ),
    },
  ];

  return (
    <>
      <Drawer
        title="用户画像"
        open={visible}
        onClose={editing ? () => setEditing(null) : onClose}
        width={editing ? 560 : 480}
        extra={
          editing ? (
            <Space>
              <Button onClick={() => setEditing(null)}>返回</Button>
              <Button type="primary" loading={saving} onClick={handleSave}>保存</Button>
            </Space>
          ) : (
            <Button icon={<PlusOutlined />} onClick={() => setCreateModalOpen(true)}>新建</Button>
          )
        }
      >
        {editing ? (
          <div>
            <div style={{ marginBottom: 12 }}>
              <div style={{ fontSize: 12, color: 'var(--text-secondary)', marginBottom: 4 }}>描述</div>
              <Input
                value={editing.description}
                onChange={e => setEditing({ ...editing, description: e.target.value })}
                placeholder="文件描述，用于索引展示"
              />
            </div>
            <div>
              <div style={{ fontSize: 12, color: 'var(--text-secondary)', marginBottom: 4 }}>内容</div>
              <Input.TextArea
                value={editing.content}
                onChange={e => setEditing({ ...editing, content: e.target.value })}
                rows={18}
              />
            </div>
          </div>
        ) : (
          <Table
            dataSource={files}
            columns={columns}
            rowKey="filename"
            loading={loading}
            size="small"
            pagination={false}
          />
        )}
      </Drawer>

      <Modal
        title="新建画像文件"
        open={createModalOpen}
        onOk={handleCreate}
        onCancel={() => { setCreateModalOpen(false); form.resetFields(); }}
        okText="创建"
      >
        <Form form={form} layout="vertical">
          <Form.Item name="filename" label="文件名" rules={[{ required: true, message: '请输入文件名' }]}>
            <Input placeholder="如 改写模板.md" />
          </Form.Item>
          <Form.Item name="description" label="描述">
            <Input placeholder="文件描述，用于索引展示" />
          </Form.Item>
        </Form>
      </Modal>
    </>
  );
};

export default ProfileManager;
