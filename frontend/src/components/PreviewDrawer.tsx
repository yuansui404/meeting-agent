import React, { useState, useEffect } from 'react';
import { Drawer, Spin, Button, Typography, Space } from 'antd';
import { DownloadOutlined, FileTextOutlined, FullscreenOutlined, FullscreenExitOutlined } from '@ant-design/icons';
import { getFileBlob } from '../services/api';
import ReactMarkdown from 'react-markdown';

const { Text } = Typography;

const DOCX_PREVIEW_STYLE = `
  .docx-preview table {
    border-collapse: collapse;
    width: 100%;
    margin: 12px 0;
    font-size: 13px;
  }
  .docx-preview table td, .docx-preview table th {
    border: 1px solid #d0d0d0;
    padding: 8px 10px;
    text-align: left;
    vertical-align: top;
  }
  .docx-preview table th {
    background: #f0f0f0;
    font-weight: 600;
  }
  .docx-preview p {
    margin: 0 0 8px 0;
  }
  .docx-preview h1, .docx-preview h2, .docx-preview h3,
  .docx-preview h4, .docx-preview h5, .docx-preview h6 {
    margin: 16px 0 8px 0;
  }
`;

const IMAGE_EXTS = ['.jpg', '.jpeg', '.png', '.gif', '.bmp', '.webp', '.svg'];
const DOCX_EXTS = ['.docx'];
const PDF_EXTS = ['.pdf'];

/** Normalize file extension to always include leading dot (e.g. "docx" → ".docx") */
const normalizeExt = (ext: string) => ext.startsWith('.') ? ext : `.${ext}`;

interface PreviewDrawerProps {
  open: boolean;
  onClose: () => void;
  title: string;
  fileUrl: string;
  fileType: string; // ext with dot, e.g. ".pdf", ".docx"
  textContent?: string; // fallback: extracted text content
  downloadUrl?: string; // download button URL, defaults to fileUrl
  width?: number;
}

const PreviewDrawer: React.FC<PreviewDrawerProps> = ({
  open,
  onClose,
  title,
  fileUrl,
  fileType,
  textContent,
  downloadUrl,
  width = 720,
}) => {
  const [loading, setLoading] = useState(false);
  const [htmlContent, setHtmlContent] = useState<string | null>(null);
  const [blobUrl, setBlobUrl] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [fullScreen, setFullScreen] = useState(false);

  const ext = normalizeExt(fileType?.toLowerCase() || '');
  const isImage = IMAGE_EXTS.includes(ext);
  const isPdf = PDF_EXTS.includes(ext);
  const isDocx = DOCX_EXTS.includes(ext);

  useEffect(() => {
    if (!open) {
      // Cleanup blob URLs
      if (blobUrl) {
        URL.revokeObjectURL(blobUrl);
        setBlobUrl(null);
      }
      setHtmlContent(null);
      setError(null);
      return;
    }

    // PDF: fetch blob and create blob URL for iframe
    if (isPdf) {
      setLoading(true);
      setError(null);
      getFileBlob(fileUrl)
        .then((blob) => {
          const url = URL.createObjectURL(blob);
          setBlobUrl(url);
        })
        .catch(() => setError('无法加载 PDF 文件'))
        .finally(() => setLoading(false));
      return;
    }

    // DOCX: fetch and convert to HTML via mammoth
    if (isDocx) {
      setLoading(true);
      setError(null);
      (async () => {
        try {
          const blob = await getFileBlob(fileUrl);
          const arrayBuffer = await blob.arrayBuffer();
          const mammoth = await import('mammoth');
          const result = await mammoth.convertToHtml({ arrayBuffer });
          setHtmlContent(result.value);
        } catch {
          setError('无法解析 DOCX 文件');
        } finally {
          setLoading(false);
        }
      })();
      return;
    }

    // Images and others: no extra loading needed
  }, [open, fileUrl, isPdf, isDocx]);

  const renderContent = () => {
    if (loading) {
      return (
        <div style={{ textAlign: 'center', paddingTop: 60 }}>
          <Spin />
          <Text type="secondary" style={{ display: 'block', marginTop: 12 }}>加载中...</Text>
        </div>
      );
    }

    if (error) {
      return (
        <div style={{ textAlign: 'center', paddingTop: 60 }}>
          <FileTextOutlined style={{ fontSize: 48, color: '#ccc' }} />
          <Text type="secondary" style={{ display: 'block', marginTop: 16 }}>{error}</Text>
          <Button
            type="link"
            icon={<DownloadOutlined />}
            href={downloadUrl || fileUrl}
            target="_blank"
            style={{ marginTop: 8 }}
          >
            下载文件查看
          </Button>
        </div>
      );
    }

    // PDF: iframe
    if (isPdf && blobUrl) {
      return (
        <iframe
          src={blobUrl}
          style={{ width: '100%', height: 'calc(100vh - 120px)', border: 'none', borderRadius: 8 }}
          title={title}
        />
      );
    }

    // DOCX: rendered HTML
    if (isDocx && htmlContent !== null) {
      return (
        <>
          <style>{DOCX_PREVIEW_STYLE}</style>
          <div
            className="docx-preview"
            style={{ fontSize: 14, lineHeight: 1.8, wordBreak: 'break-word' }}
            dangerouslySetInnerHTML={{ __html: htmlContent }}
          />
        </>
      );
    }

    // Image
    if (isImage) {
      return (
        <div style={{ textAlign: 'center' }}>
          <img
            src={fileUrl}
            alt={title}
            style={{ maxWidth: '100%', borderRadius: 8, boxShadow: '0 2px 8px rgba(0,0,0,0.1)' }}
          />
        </div>
      );
    }

    // Text content fallback
    if (textContent) {
      return (
        <div style={{
          background: 'var(--preview-bg, #f5f5f5)',
          borderRadius: 8,
          padding: 16,
          maxHeight: 'calc(100vh - 220px)',
          overflow: 'auto',
          fontSize: 13,
          lineHeight: 1.7,
          whiteSpace: 'pre-wrap',
          fontFamily: "'SF Mono', 'Menlo', 'Monaco', 'Consolas', monospace",
        }}>
          <ReactMarkdown>{textContent}</ReactMarkdown>
        </div>
      );
    }

    // No preview available
    return (
      <div style={{ textAlign: 'center', paddingTop: 60 }}>
        <FileTextOutlined style={{ fontSize: 48, color: '#ccc' }} />
        <Text type="secondary" style={{ display: 'block', marginTop: 16 }}>
          暂无内容预览
        </Text>
        <Button
          type="link"
          icon={<DownloadOutlined />}
          href={downloadUrl || fileUrl}
          target="_blank"
          style={{ marginTop: 8 }}
        >
          下载文件查看
        </Button>
      </div>
    );
  };

  const finalWidth = fullScreen ? '90vw' : width;

  return (
    <Drawer
      title={title || '文件预览'}
      placement="right"
      width={finalWidth}
      onClose={onClose}
      open={open}
      extra={
        <Space>
          <Button
            size="small"
            icon={fullScreen ? <FullscreenExitOutlined /> : <FullscreenOutlined />}
            onClick={() => setFullScreen(!fullScreen)}
          >
            {fullScreen ? '退出全屏' : '全屏'}
          </Button>
          <Button
            type="primary"
            size="small"
            icon={<DownloadOutlined />}
            href={downloadUrl || fileUrl}
            target="_blank"
          >
            下载
          </Button>
        </Space>
      }
    >
      {renderContent()}
    </Drawer>
  );
};

export default PreviewDrawer;
