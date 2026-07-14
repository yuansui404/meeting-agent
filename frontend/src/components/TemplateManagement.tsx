import React, { useState, useEffect, useRef } from 'react';
import { Typography, Button, Modal, message as antMsg, Tag, Space, List, Spin, Input, Empty } from 'antd';
import {
  FileTextOutlined,
  PlusOutlined,
  UploadOutlined,
  DeleteOutlined,
  EyeOutlined,
  DownloadOutlined,
  CloseOutlined,
} from '@ant-design/icons';
import {
  Template,
  uploadTemplate,
  listTemplates,
  deleteTemplate,
  getTemplateDownloadUrl,
  getTemplatePreview,
} from '../services/template';

const { Text } = Typography;

interface Props {
  visible: boolean;
  onClose: () => void;
}

const TemplateManagement: React.FC<Props> = ({ visible, onClose }) => {
  const [templates, setTemplates] = useState<Template[]>([]);
  const [loading, setLoading] = useState(false);
  const [uploadModalOpen, setUploadModalOpen] = useState(false);
  const [uploading, setUploading] = useState(false);
  const [selectedFile, setSelectedFile] = useState<File | null>(null);
  const [templateName, setTemplateName] = useState('');
  const [styleTags, setStyleTags] = useState('');
  const [previewModalOpen, setPreviewModalOpen] = useState(false);
  const [previewTitle, setPreviewTitle] = useState('');
  const [previewContent, setPreviewContent] = useState<string | null>(null);
  const [previewLoading, setPreviewLoading] = useState(false);
  const fileInputRef = useRef<HTMLInputElement>(null);

  useEffect(() => {
    if (visible) loadTemplates();
  }, [visible]);

  const loadTemplates = async () => {
    setLoading(true);
    try {
      const res = await listTemplates();
      const data = res.data?.data;
      setTemplates(Array.isArray(data) ? data : []);
    } catch {
      setTemplates([]);
    }
    setLoading(false);
  };

  const resetUploadForm = () => {
    setSelectedFile(null);
    setTemplateName('');
    setStyleTags('');
  };

  const handleUpload = async () => {
    if (!selectedFile) {
      antMsg.warning('请选择文件');
      return;
    }
    if (!templateName.trim()) {
      antMsg.warning('请输入模板名称');
      return;
    }
    setUploading(true);
    try {
      await uploadTemplate(selectedFile, templateName.trim(), styleTags.trim() || undefined);
      antMsg.success('模板上传成功');
      setUploadModalOpen(false);
      resetUploadForm();
      await loadTemplates();
    } catch (err: any) {
      const msg = err?.response?.data?.message || err?.message || '未知错误';
      antMsg.error('上传失败: ' + msg);
    }
    setUploading(false);
  };

  const handleDelete = (tpl: Template) => {
    Modal.confirm({
      title: '确认删除',
      content: `确定要删除模板「${tpl.name}」吗？`,
      okText: '删除',
      okType: 'danger',
      cancelText: '取消',
      onOk: async () => {
        try {
          await deleteTemplate(tpl.id);
          antMsg.success('已删除');
          setTemplates(prev => prev.filter(t => t.id !== tpl.id));
        } catch {
          antMsg.error('删除失败');
        }
      },
    });
  };

  const handlePreview = async (tpl: Template) => {
    setPreviewTitle(tpl.name);
    setPreviewContent(null);
    setPreviewModalOpen(true);
    setPreviewLoading(true);
    try {
      const res = await getTemplatePreview(tpl.id);
      setPreviewContent(res.data?.data || null);
    } catch {
      setPreviewContent(null);
    }
    setPreviewLoading(false);
  };

  const formatDateTime = (dateStr?: string) => {
    if (!dateStr) return '-';
    return new Date(dateStr).toLocaleString('zh-CN');
  };

  return (
    <div style={{
      height: '100%', display: 'flex', flexDirection: 'column',
      background: '#fafafa',
    }}>
      {/* Header */}
      <div style={{
        padding: '12px 12px 8px',
        borderBottom: '1px solid #f0f0f0',
        display: 'flex', alignItems: 'center', justifyContent: 'space-between',
        background: '#fff',
      }}>
        <Space size={6}>
          <FileTextOutlined style={{ color: '#1677ff', fontSize: 16 }} />
          <Text strong style={{ fontSize: 13 }}>模板管理</Text>
          {!loading && <Text type="secondary" style={{ fontSize: 11 }}>({templates.length})</Text>}
        </Space>
        <Space size={2}>
          <Button
            type="text"
            size="small"
            icon={<PlusOutlined />}
            onClick={() => setUploadModalOpen(true)}
          />
          <Button type="text" size="small" icon={<CloseOutlined />} onClick={onClose} />
        </Space>
      </div>

      {/* Upload area */}
      <div style={{ padding: '8px 12px', borderBottom: '1px solid #f0f0f0', background: '#fff' }}>
        <Button
          type="dashed"
          block
          icon={<PlusOutlined />}
          onClick={() => setUploadModalOpen(true)}
          size="small"
          style={{ borderRadius: 6, fontSize: 12, height: 32 }}
        >
          上传模板
        </Button>
        <Text type="secondary" style={{ fontSize: 10, display: 'block', marginTop: 4, textAlign: 'center' }}>
          支持 .docx 格式的模板文档
        </Text>
      </div>

      {/* Template list */}
      <div style={{ flex: 1, overflow: 'auto', padding: '4px 8px' }}>
        {loading ? (
          <div style={{ textAlign: 'center', paddingTop: 40 }}>
            <Spin size="small" />
          </div>
        ) : templates.length === 0 ? (
          <div style={{ textAlign: 'center', paddingTop: 40, color: '#999', fontSize: 12 }}>
            <FileTextOutlined style={{ fontSize: 28, display: 'block', marginBottom: 8, opacity: 0.3 }} />
            暂无模板
          </div>
        ) : (
          <List
            dataSource={templates}
            split={false}
            renderItem={(tpl) => {
              const downloadUrl = getTemplateDownloadUrl(tpl.id);
              const styleTagList = tpl.styleTags
                ? tpl.styleTags.split(',').map(s => s.trim()).filter(Boolean)
                : [];
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
                      <FileTextOutlined style={{ color: '#1677ff' }} />
                    </div>
                    <div style={{ flex: 1, minWidth: 0 }}>
                      <Text style={{ fontSize: 12, fontWeight: 500, lineHeight: '18px' }} ellipsis={{ tooltip: tpl.name }}>
                        {tpl.name}
                      </Text>
                    </div>
                    <Button
                      type="text"
                      size="small"
                      danger
                      icon={<DeleteOutlined />}
                      onClick={() => handleDelete(tpl)}
                      style={{ flexShrink: 0, width: 22, height: 22, minWidth: 22, padding: 0, fontSize: 11 }}
                    />
                  </div>

                  {/* Style tags */}
                  {styleTagList.length > 0 && (
                    <div style={{ paddingLeft: 22, marginBottom: 6 }}>
                      <Space size={4} wrap>
                        {styleTagList.map((tag, i) => (
                          <Tag key={i} color="blue" style={{ fontSize: 10, lineHeight: '16px', margin: 0, borderRadius: 4 }}>
                            {tag}
                          </Tag>
                        ))}
                      </Space>
                    </div>
                  )}

                  {/* Created time */}
                  <div style={{ paddingLeft: 22, marginBottom: 6 }}>
                    <Text type="secondary" style={{ fontSize: 10 }}>
                      创建时间: {formatDateTime(tpl.createdAt)}
                    </Text>
                  </div>

                  {/* Actions */}
                  <div style={{ paddingLeft: 22, display: 'flex', gap: 8 }}>
                    <Button
                      type="link"
                      size="small"
                      icon={<EyeOutlined />}
                      onClick={() => handlePreview(tpl)}
                      style={{ fontSize: 11, padding: 0, height: 20 }}
                    >
                      预览
                    </Button>
                    <Button
                      type="link"
                      size="small"
                      icon={<DownloadOutlined />}
                      href={downloadUrl}
                      target="_blank"
                      style={{ fontSize: 11, padding: 0, height: 20 }}
                    >
                      下载
                    </Button>
                  </div>
                </List.Item>
              );
            }}
          />
        )}
      </div>

      {/* Upload Modal */}
      <Modal
        title="上传模板"
        open={uploadModalOpen}
        onCancel={() => { setUploadModalOpen(false); resetUploadForm(); }}
        footer={null}
        destroyOnClose
        width={420}
      >
        <div style={{ display: 'flex', flexDirection: 'column', gap: 12 }}>
          {/* File selection */}
          <div>
            <Text style={{ fontSize: 12, display: 'block', marginBottom: 4 }}>模板文件 *</Text>
            <input
              ref={fileInputRef}
              type="file"
              accept=".docx"
              onChange={(e) => {
                const file = e.target.files?.[0];
                if (file) {
                  setSelectedFile(file);
                  // Auto-fill name from filename if not already set
                  if (!templateName.trim()) {
                    setTemplateName(file.name.replace(/\.docx$/i, ''));
                  }
                }
                e.target.value = '';
              }}
              style={{ display: 'none' }}
            />
            <Button
              icon={<UploadOutlined />}
              onClick={() => fileInputRef.current?.click()}
              size="small"
            >
              {selectedFile ? selectedFile.name : '选择文件'}
            </Button>
          </div>

          {/* Template name */}
          <div>
            <Text style={{ fontSize: 12, display: 'block', marginBottom: 4 }}>模板名称 *</Text>
            <Input
              size="small"
              placeholder="输入模板名称"
              value={templateName}
              onChange={e => setTemplateName(e.target.value)}
            />
          </div>

          {/* Style tags */}
          <div>
            <Text style={{ fontSize: 12, display: 'block', marginBottom: 4 }}>
              风格标签 <Text type="secondary" style={{ fontSize: 10 }}>（可选，逗号分隔）</Text>
            </Text>
            <Input
              size="small"
              placeholder="例如: 正式, 商务, 技术"
              value={styleTags}
              onChange={e => setStyleTags(e.target.value)}
            />
          </div>

          {/* Submit */}
          <div style={{ textAlign: 'right', marginTop: 8 }}>
            <Button
              type="primary"
              size="small"
              loading={uploading}
              onClick={handleUpload}
            >
              提交
            </Button>
          </div>
        </div>
      </Modal>

      {/* Preview Modal */}
      <Modal
        title={previewTitle}
        open={previewModalOpen}
        onCancel={() => { setPreviewModalOpen(false); setPreviewContent(null); }}
        footer={null}
        width={640}
        destroyOnClose
      >
        {previewLoading ? (
          <div style={{ textAlign: 'center', padding: '40px 0' }}>
            <Spin size="small" />
          </div>
        ) : previewContent ? (
          <div style={{
            maxHeight: 480,
            overflow: 'auto',
            background: '#f5f5f5',
            padding: 16,
            borderRadius: 6,
            fontSize: 13,
            lineHeight: 1.7,
            whiteSpace: 'pre-wrap',
            wordBreak: 'break-word',
          }}>
            {previewContent}
          </div>
        ) : (
          <Empty description="无法加载预览内容" />
        )}
      </Modal>
    </div>
  );
};

export default TemplateManagement;