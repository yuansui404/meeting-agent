import React, { useState } from 'react';
import { Input, List, Typography, Space } from 'antd';
import { SearchOutlined } from '@ant-design/icons';
import { searchDocuments, Dialogue } from '../services/api';

const { Text } = Typography;

interface Props {
  dialogues: Dialogue[];
  compact?: boolean;
}

const SearchPanel: React.FC<Props> = ({ dialogues, compact }) => {
  const [query, setQuery] = useState('');
  const [results, setResults] = useState<any[]>([]);
  const [searched, setSearched] = useState(false);
  const [loading, setLoading] = useState(false);

  const handleSearch = async (value: string) => {
    if (!value.trim()) return;
    setQuery(value);
    setSearched(true);
    setLoading(true);
    try {
      const res = await searchDocuments(value);
      setResults(res.data?.data?.results || []);
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
          placeholder="搜索文档内容..."
          prefix={<SearchOutlined />}
          onPressEnter={(e) => handleSearch((e.target as HTMLInputElement).value)}
          style={{ flex: 1, fontSize: compact ? 13 : 14 }}
        />
      </Space.Compact>

      {searched && (
        <List
          dataSource={results}
          loading={loading}
          locale={{ emptyText: `未找到相关结果` }}
          renderItem={(item: any, idx: number) => (
            <List.Item key={idx} style={{ padding: '6px 0', border: 'none' }}>
              <List.Item.Meta
                title={
                  <Text style={{ fontSize: 13 }} strong>
                    {highlight(item.source || '', query)}
                    {item.score != null && (
                      <Text style={{ fontSize: 11, marginLeft: 6 }} type="secondary">
                        {(item.score * 100).toFixed(0)}% 匹配
                      </Text>
                    )}
                  </Text>
                }
                description={
                  <Text style={{ fontSize: 12, whiteSpace: 'pre-wrap', display: 'block' }} ellipsis>
                    {item.content
                      ? highlight(item.content.substring(0, 100), query)
                      : ''}
                  </Text>
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
