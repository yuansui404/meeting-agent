import React, { useState } from 'react';
import { Upload, message, Card, Typography } from 'antd';
import { InboxOutlined } from '@ant-design/icons';
import { uploadFile } from '../services/api';

const { Dragger } = Upload;
const { Text } = Typography;

interface Props {
  onSuccess: () => void;
}

const FileUpload: React.FC<Props> = ({ onSuccess }) => {
  const [uploading, setUploading] = useState(false);

  const handleUpload = async (file: File) => {
    setUploading(true);
    try {
      const res = await uploadFile(file);
      if (res.data.meetingId) {
        message.success(`文件 "${file.name}" 上传成功，正在转写...`);
        onSuccess();
      }
    } catch (err: any) {
      const errorMsg = err.response?.data?.error || err.message;
      message.error(`上传失败: ${errorMsg}`);
    } finally {
      setUploading(false);
    }
    return false; // 阻止默认上传行为
  };

  return (
    <Card title="上传会议视频" size="small">
      <Dragger
        accept=".mp4"
        beforeUpload={handleUpload}
        showUploadList={false}
        disabled={uploading}
      >
        <p className="ant-upload-drag-icon">
          <InboxOutlined />
        </p>
        <p className="ant-upload-text">
          {uploading ? '上传处理中...' : '点击或拖拽 MP4 文件到此区域'}
        </p>
        <p className="ant-upload-hint">
          仅支持 MP4 格式，上传后将自动进行语音转写
        </p>
      </Dragger>
    </Card>
  );
};

export default FileUpload;
