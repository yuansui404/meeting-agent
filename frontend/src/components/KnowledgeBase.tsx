import React, { useState, useEffect, useRef } from 'react';
import { Typography, Button, List, Tag, Spin, message as antMsg, Tooltip, Space, Pagination, Drawer } from 'antd';
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
  EyeOutlined,
  StarOutlined,
  StarFilled,
} from '@ant-design/icons';
import { Meeting, listMeetings, deleteMeeting, uploadKnowledgeBaseFile, getFileUrl, getMeeting, getMeetingTextContent, setStyleExemplar } from '../services/api';
import ReactMarkdown from 'react-markdown';

const { Text } = Typography;

interface Props {
  visible: boolean;
  onClose: () => void;
}

const PAGE_SIZE = 8;

const KnowledgeBase: React.FC<Props> = ({ visible, onClose }) => {
  const [meetings, setMeetings] = useState<Meeting[]>([]);
  const [loading, setLoading] = useState(false);
  const [uploading, setUploading] = useState(false);
  const [page, setPage] = useState(1);
  const [previewFile, setPreviewFile] = useState<Meeting | null>(null);
  const [previewContent, setPreviewContent] = useState<string | null>(null);
  const [previewLoading, setPreviewLoading] = useState(false);
  const fileInputRef = useRef<HTMLInputElement>(null);

  useEffect(() => {
    if (visible) loadMeetings();
  }, [visible]);

  useEffect(() => {
    setPage(1);
  }, [meetings.length]);

  const loadMeetings = async () => {
    setLoading(true);
    try {
      const res = await listMeetings();
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

  const handlePreview = async (meeting: Meeting) => {
    setPreviewFile(meeting);
    setPreviewContent(null);
    setPreviewLoading(true);
    try {
      // Try text-content endpoint first (supports all formats including PDF, doc, docx)
      const res = await getMeetingTextContent(meeting.id);
      if (res.data.content) {
        setPreviewContent(res.data.content);
      } else {
        // Fallback to transcription
        const detail = await getMeeting(meeting.id);
        setPreviewContent(detail.data.transcription || '（文件内容为空）');
      }
    } catch {
      // Fallback to transcription
      try {
        const detail = await getMeeting(meeting.id);
        setPreviewContent(detail.data.transcription || '（无法读取文件内容）');
      } catch {
        setPreviewContent('（无法读取文件内容）');
      }
    }
    setPreviewLoading(false);
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

  // Paginated items
  const startIndex = (page - 1) * PAGE_SIZE;
  const pageItems = meetings.slice(startIndex, startIndex + PAGE_SIZE);

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
          {!loading && <Text type="secondary" style={{ fontSize: 11 }}>({meetings.length})</Text>}
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
          <>
            <List
              dataSource={pageItems}
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
                      cursor: 'pointer',
                    }}
                    onClick={() => handlePreview(meeting)}
                  >
                    {/* Title row */}
                    <div style={{ display: 'flex', alignItems: 'flex-start', gap: 6, marginBottom: 6 }}>
                      <div style={{ fontSize: 16, marginTop: 1, flexShrink: 0 }}>
                        {getFileIcon(fileName)}
                      </div>
                      <div style={{ flexShrink: 0, marginTop: 2 }}>
                        <Tooltip title={meeting.styleExemplar ? '取消风格参考标记' : '标记为风格参考'}>
                          <span
                            onClick={(e) => {
                              e.stopPropagation();
                              const newVal = !meeting.styleExemplar;
                              setStyleExemplar(meeting.id, newVal).then(() => {
                                setMeetings(prev => prev.map(m =>
                                  m.id === meeting.id ? { ...m, styleExemplar: newVal } : m
                                ));
                              }).catch(() => antMsg.error('标记失败'));
                            }}
                            style={{ cursor: 'pointer', fontSize: 14, lineHeight: 1 }}
                          >
                            {meeting.styleExemplar ? (
                              <StarFilled style={{ color: '#fadb14' }} />
                            ) : (
                              <StarOutlined style={{ color: '#d9d9d9' }} />
                            )}
                          </span>
                        </Tooltip>
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
                          onClick={(e) => { e.stopPropagation(); handleDelete(meeting); }}
                          style={{ flexShrink: 0, width: 22, height: 22, minWidth: 22, padding: 0, fontSize: 11 }}
                        />
                      </Tooltip>
                    </div>

                    {/* Detail fields */}
                    <div style={{ paddingLeft: 22 }}>
                      {renderDetailRow('会议时间', formatDateTime(meeting.meetingDate || meeting.createdAt), <CalendarOutlined />)}
                      {renderDetailRow('导入', formatDateTime(meeting.updatedAt), <ClockCircleOutlined />)}
                      {renderDetailRow('大小', formatFileSize(meeting.fileSize))}
                      {renderDetailRow('路径', meeting.filePath || '-', <LinkOutlined />)}
                    </div>

                    {/* Actions */}
                    <div style={{ marginTop: 6, paddingLeft: 22, display: 'flex', gap: 8 }}>
                      <Button
                        type="link"
                        size="small"
                        icon={<EyeOutlined />}
                        onClick={(e) => { e.stopPropagation(); handlePreview(meeting); }}
                        style={{ fontSize: 11, padding: 0, height: 20 }}
                      >
                        预览
                      </Button>
                      <Button
                        type="link"
                        size="small"
                        icon={<DownloadOutlined />}
                        href={fileUrl}
                        target="_blank"
                        onClick={(e) => e.stopPropagation()}
                        style={{ fontSize: 11, padding: 0, height: 20 }}
                      >
                        下载原文
                      </Button>
                    </div>
                  </List.Item>
                );
              }}
            />
            {meetings.length > PAGE_SIZE && (
              <div style={{ textAlign: 'center', padding: '8px 0' }}>
                <Pagination
                  size="small"
                  current={page}
                  total={meetings.length}
                  pageSize={PAGE_SIZE}
                  onChange={setPage}
                  showSizeChanger={false}
                  showTotal={(total) => `共 ${total} 项`}
                />
              </div>
            )}
          </>
        )}
      </div>

      {/* Preview Drawer */}
      <Drawer
        title={previewFile ? previewFile.title : '文件预览'}
        placement="right"
        width={560}
        onClose={() => { setPreviewFile(null); setPreviewContent(null); }}
        open={!!previewFile}
        extra={
          previewFile && (
            <Button
              type="primary"
              size="small"
              icon={<DownloadOutlined />}
              href={getFileUrl(previewFile.id)}
              target="_blank"
            >
              下载原文
            </Button>
          )
        }
      >
        {previewLoading ? (
          <div style={{ textAlign: 'center', paddingTop: 60 }}>
            <Spin />
          </div>
        ) : previewContent ? (
          <div style={{
            fontSize: 14, lineHeight: 1.8,
            whiteSpace: 'pre-wrap', wordBreak: 'break-word',
          }}>
            <ReactMarkdown>{previewContent}</ReactMarkdown>
          </div>
        ) : (
          <div style={{ textAlign: 'center', paddingTop: 60, color: '#999' }}>
            无法读取文件内容
          </div>
        )}
      </Drawer>
    </div>
  );
};

export default KnowledgeBase;
