import React, { useEffect, useState } from 'react';
import { Drawer, Input, Button, message, Spin } from 'antd';
import { getMemory, saveMemory } from '../services/api';

interface Props {
  visible: boolean;
  onClose: () => void;
}

const MemoryEditor: React.FC<Props> = ({ visible, onClose }) => {
  const [content, setContent] = useState('');
  const [loading, setLoading] = useState(false);
  const [saving, setSaving] = useState(false);

  useEffect(() => {
    if (visible) {
      setLoading(true);
      getMemory()
        .then(res => setContent(res.data.data?.content || ''))
        .catch(() => message.error('加载记忆失败'))
        .finally(() => setLoading(false));
    }
  }, [visible]);

  const handleSave = async () => {
    setSaving(true);
    try {
      await saveMemory(content);
      message.success('保存成功');
      onClose();
    } catch {
      message.error('保存失败');
    } finally {
      setSaving(false);
    }
  };

  return (
    <Drawer
      title="Agent 记忆"
      open={visible}
      onClose={onClose}
      width={480}
      extra={
        <Button type="primary" loading={saving} onClick={handleSave}>
          保存
        </Button>
      }
    >
      {loading ? (
        <Spin style={{ display: 'block', marginTop: 80, textAlign: 'center' }} />
      ) : (
        <Input.TextArea
          value={content}
          onChange={e => setContent(e.target.value)}
          rows={20}
          placeholder="输入 agent 应记住的信息..."
        />
      )}
    </Drawer>
  );
};

export default MemoryEditor;
