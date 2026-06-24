import React, { useState } from 'react';
import { Input, List, Typography, Select, Space } from 'antd';
import { SearchOutlined } from '@ant-design/icons';
import { searchMeetings, SearchResult, Dialogue } from '../services/api';

const { Text } = Typography;

interface Props {
  dialogues: Dialogue[];
  compact?: boolean;
}

const SearchPanel: React.FC<Props> = ({ dialogues, compact }) => {
  const [query, setQuery] = useState('');
  const [results, setResults] = useState<SearchResult[]>([]);
  const [searched, setSearched] = useState(false);
  const [loading, setLoading] = useState(false);
  const [dialogueId, setDialogueId] = useState<number | undefined>(undefined);

  const handleSearch = async (value: string) => {
    if (!value.trim()) return;
    setQuery(value);
    setSearched(true);
    setLoading(true);
    try {
      const res = await searchMeetings(value, dialogueId);
      setResults(res.data.results || []);
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
          placeholder="搜索会议内容..."
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
          renderItem={(item) => (
            <List.Item style={{ padding: '6px 0', border: 'none' }}>
              <List.Item.Meta
                title={
                  <Text style={{ fontSize: 13 }} strong>
                    {highlight(item.title, query)}
                    <Text style={{ fontSize: 11, marginLeft: 6 }} type="secondary">
                      {item.type === 'vector' ? '语义' : '关键词'}
                    </Text>
                  </Text>
                }
                description={
                  <Text style={{ fontSize: 12, whiteSpace: 'pre-wrap', display: 'block' }} ellipsis>
                    {item.matchedContent
                      ? highlight(item.matchedContent.substring(0, 100), query)
                      : (item.transcription?.substring(0, 100) || '')}
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
