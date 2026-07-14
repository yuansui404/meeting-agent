import React, { useState } from 'react';
import { Input, List, Typography, Space } from 'antd';
import { SearchOutlined, FileTextOutlined } from '@ant-design/icons';
import { searchDocumentsByTitle, RagDocument } from '../services/api';

const { Text } = Typography;

interface Props {
  dialogues: never[];
  compact?: boolean;
}

const SearchPanel: React.FC<Props> = ({ compact }) => {
  const [query, setQuery] = useState('');
  const [results, setResults] = useState<RagDocument[]>([]);
  const [searched, setSearched] = useState(false);
  const [loading, setLoading] = useState(false);

  const handleSearch = async (value: string) => {
    if (!value.trim()) return;
    setQuery(value);
    setSearched(true);
    setLoading(true);
    try {
      const res = await searchDocumentsByTitle(value);
      setResults(res.data?.data || []);
    } catch {
      setResults([]);
    } finally {
      setLoading(false);
    }
  };

  const highlight = (text: string, keyword: string) => {
    if (!keyword.trim()) return text;
    const parts = text.split(new RegExp(`(${keyword.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')})`, 'gi'));
    return parts.map((part, i) =>
      part.toLowerCase() === keyword.toLowerCase()
        ? <Text type="warning" key={i}>{part}</Text>
        : part
    );
  };

  return (
    <div style={{ padding: compact ? '8px 16px' : 0 }}>
      <Space.Compact style={{ width: '100%', marginBottom: 12 }}>
        <Input
          placeholder="搜索文档标题..."
          prefix={<SearchOutlined />}
          onPressEnter={(e) => handleSearch((e.target as HTMLInputElement).value)}
          style={{ flex: 1, fontSize: compact ? 13 : 14 }}
        />
      </Space.Compact>

      {searched && (
        <List
          dataSource={results}
          loading={loading}
          locale={{ emptyText: `未找到相关文档` }}
          renderItem={(item: RagDocument) => (
            <List.Item style={{ padding: '6px 0', border: 'none' }}>
              <List.Item.Meta
                avatar={<FileTextOutlined style={{ fontSize: 18, color: '#999' }} />}
                title={
                  <Text style={{ fontSize: 13 }} strong>
                    {highlight(item.title, query)}
                  </Text>
                }
                description={
                  item.meetingDate && (
                    <Text style={{ fontSize: 11 }} type="secondary">
                      {item.meetingDate}
                    </Text>
                  )
                }
              />
            </List.Item>
          )}
        />
      )}
    </div>
  );
};

export default SearchPanel;