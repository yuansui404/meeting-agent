import React, { useState, useEffect, useRef } from 'react';
import { Typography, Button, List, Tag, Spin, message as antMsg, Tooltip, Space } from 'antd';
import {
  FileTextOutlined,
  DatabaseOutlined,
  ReloadOutlined,
  DeleteOutlined,
  UploadOutlined,
  CalendarOutlined,
  LinkOutlined,
  ClockCircleOutlined,
  CloseOutlined,
  DownloadOutlined,
} from '@ant-design/icons';
import { Meeting, listKnowledgeBase, deleteMeeting, uploadKnowledgeBaseFile, getFileUrl } from '../services/api';

const { Text } = Typography;

interface Props {
  visible: boolean;
  onClose: () => void;
}

const KnowledgeBase: React.FC<Props> = ({ visible, onClose }) => {
  const [meetings, setMeetings] = useState<Meeting[]>([]);
  const [loading, setLoading] = useState(false);
  const [uploading, setUploading] = useState(false);
  const fileInputRef = useRef<HTMLInputElement>(null);

  useEffect(() => {
    if (visible) loadMeetings();
  }, [visible]);

  const loadMeetings = async () => {
    setLoading(true);
    try {
      const res = await listKnowledgeBase();
      setMeetings(res.data || []);
    } catch {
      setMeetings([]);
    }
    setLoading(false);
  };

  const handleUpload = async (file: File) => {
    const validTypes = ['.txt', '.md', '.pdf', '.doc', '.docx'];
    const ext = '.' + file.name.split('.').pop()?.toLowerCase();
    if (!validTypes.includes(ext)) {
      antMsg.error('仅支持 txt、md、pdf、doc、docx 格式');
      return;
    }
    setUploading(true);
    try {
      await uploadKnowledgeBaseFile(file);
      antMsg.success(`"${file.name}" 已导入知识库并完成向量化`);
      loadMeetings();
    } catch (err: any) {
      antMsg.error('导入失败: ' + (err.response?.data?.message || err.message));
    }
    setUploading(false);
  };

  const handleDelete = (meeting: Meeting) => {
    if (!window.confirm(`确认从知识库删除「${meeting.title}」？\n向量数据将被一并删除。`)) return;
    deleteMeeting(meeting.id)
      .then(() => {
        antMsg.success('已删除');
        setMeetings(prev => prev.filter(m => m.id !== meeting.id));
      })
      .catch(() => antMsg.error('删除失败'));
  };

  const formatDateTime = (dateStr?: string) => {
    if (!dateStr) return '-';
    return new Date(dateStr).toLocaleString('zh-CN');
  };

  const formatFileSize = (bytes: number | null | undefined) => {
    if (bytes == null) return '-';
    if (bytes < 1024) return bytes + ' B';
    if (bytes < 1024 * 1024) return (bytes / 1024).toFixed(1) + ' KB';
    return (bytes / (1024 * 1024)).toFixed(1) + ' MB';
  };

  const getFileIcon = (title: string) => {
    const ext = '.' + title.split('.').pop()?.toLowerCase();
    if (ext === '.pdf') return <FileTextOutlined style={{ color: '#f5222d' }} />;
    if (['.doc', '.docx'].includes(ext)) return <FileTextOutlined style={{ color: '#1677ff' }} />;
    return <FileTextOutlined style={{ color: '#52c41a' }} />;
  };

  const renderDetailRow = (label: string, value: string, icon?: React.ReactNode) => (
    <div style={{ display: 'flex', alignItems: 'flex-start', gap: 4, marginBottom: 1 }}>
      {icon && <span style={{ fontSize: 10, color: '#999', marginTop: 2, flexShrink: 0 }}>{icon}</span>}
      <Text type="secondary" style={{ fontSize: 10, lineHeight: '16px', flexShrink: 0, minWidth: 46 }}>
        {label}
      </Text>
      <Text style={{ fontSize: 10, lineHeight: '16px', color: '#666', wordBreak: 'break-all' }}>
        {value}
      </Text>
    </div>
  );

  return (
    <div style={{
      height: '100%', display: 'flex', flexDirection: 'column',
      background: '#fafafa', width: 280,
    }}>
      {/* Header */}
      <div style={{
        padding: '12px 12px 8px',
        borderBottom: '1px solid #f0f0f0',
        display: 'flex', alignItems: 'center', justifyContent: 'space-between',
        background: '#fff',
      }}>
        <Space size={6}>
          <DatabaseOutlined style={{ color: '#1677ff', fontSize: 16 }} />
          <Text strong style={{ fontSize: 13 }}>知识库</Text>
        </Space>
        <Space size={2}>
          <Tooltip title="刷新">
            <Button type="text" size="small" icon={<ReloadOutlined />} onClick={loadMeetings} />
          </Tooltip>
          <Tooltip title="关闭">
            <Button type="text" size="small" icon={<CloseOutlined />} onClick={onClose} />
          </Tooltip>
        </Space>
      </div>

      {/* Upload area */}
      <div style={{ padding: '8px 12px', borderBottom: '1px solid #f0f0f0', background: '#fff' }}>
        <input
          ref={fileInputRef}
          type="file"
          accept=".txt,.md,.pdf,.doc,.docx"
          onChange={(e) => {
            const file = e.target.files?.[0];
            if (file) handleUpload(file);
            e.target.value = '';
          }}
          style={{ display: 'none' }}
        />
        <Button
          type="dashed"
          block
          icon={<UploadOutlined />}
          onClick={() => fileInputRef.current?.click()}
          loading={uploading}
          size="small"
          style={{ borderRadius: 6, fontSize: 12, height: 32 }}
        >
          上传到知识库
        </Button>
        <Text type="secondary" style={{ fontSize: 10, display: 'block', marginTop: 4, textAlign: 'center' }}>
          支持 txt、md、pdf、doc、docx（自动向量化）
        </Text>
      </div>

      {/* Meeting list */}
      <div style={{ flex: 1, overflow: 'auto', padding: '4px 8px' }}>
        {loading ? (
          <div style={{ textAlign: 'center', paddingTop: 40 }}>
            <Spin size="small" />
          </div>
        ) : meetings.length === 0 ? (
          <div style={{ textAlign: 'center', paddingTop: 40, color: '#999', fontSize: 12 }}>
            <DatabaseOutlined style={{ fontSize: 28, display: 'block', marginBottom: 8, opacity: 0.3 }} />
            知识库暂无内容
          </div>
        ) : (
          <List
            dataSource={meetings}
            split={false}
            renderItem={(meeting) => {
              const fileName = meeting.title || '';
              const fileUrl = getFileUrl(meeting.id);
              return (
                <List.Item
                  style={{
                    padding: '8px 10px',
                    borderRadius: 8,
                    marginBottom: 4,
                    background: '#fff',
                    border: '1px solid #f0f0f0',
                    display: 'block',
                  }}
                >
                  {/* Title row */}
                  <div style={{ display: 'flex', alignItems: 'flex-start', gap: 6, marginBottom: 6 }}>
                    <div style={{ fontSize: 16, marginTop: 1, flexShrink: 0 }}>
                      {getFileIcon(fileName)}
                    </div>
                    <div style={{ flex: 1, minWidth: 0 }}>
                      <Text style={{ fontSize: 12, fontWeight: 500, lineHeight: '18px' }} ellipsis={{ tooltip: fileName }}>
                        {fileName}
                      </Text>
                    </div>
                    <Tooltip title="删除">
                      <Button
                        type="text"
                        size="small"
                        danger
                        icon={<DeleteOutlined />}
                        onClick={() => handleDelete(meeting)}
                        style={{ flexShrink: 0, width: 22, height: 22, minWidth: 22, padding: 0, fontSize: 11 }}
                      />
                    </Tooltip>
                  </div>

                  {/* Detail fields */}
                  <div style={{ paddingLeft: 22 }}>
                    {renderDetailRow('日期', formatDateTime(meeting.createdAt), <CalendarOutlined />)}
                    {renderDetailRow('导入', formatDateTime(meeting.updatedAt), <ClockCircleOutlined />)}
                    {renderDetailRow('大小', formatFileSize(meeting.fileSize))}
                    {renderDetailRow('路径', meeting.filePath || '-', <LinkOutlined />)}
                  </div>

                  {/* Actions */}
                  <div style={{ marginTop: 6, paddingLeft: 22, display: 'flex', gap: 8 }}>
                    <Button
                      type="link"
                      size="small"
                      icon={<DownloadOutlined />}
                      href={fileUrl}
                      target="_blank"
                      style={{ fontSize: 11, padding: 0, height: 20 }}
                    >
                      下载原文
                    </Button>
                  </div>
                </List.Item>
              );
            }}
          />
        )}
      </div>
    </div>
  );
};

export default KnowledgeBase;
