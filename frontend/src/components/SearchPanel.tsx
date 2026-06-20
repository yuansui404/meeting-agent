import React, { useState } from 'react';
import { Card, Input, List, Typography, Select, Space } from 'antd';
import { SearchOutlined } from '@ant-design/icons';
import { searchMeetings, SearchResult, Dialogue } from '../services/api';

const { Text } = Typography;
const { Search } = Input;

interface Props {
  dialogues: Dialogue[];
}

const SearchPanel: React.FC<Props> = ({ dialogues }) => {
  const [query, setQuery] = useState('');
  const [results, setResults] = useState<SearchResult[]>([]);
  const [searched, setSearched] = useState(false);
  const [dialogueId, setDialogueId] = useState<number | undefined>(undefined);

  const handleSearch = async (value: string) => {
    if (!value.trim()) return;
    setQuery(value);
    setSearched(true);
    try {
      const res = await searchMeetings(value, dialogueId);
      setResults(res.data.results || []);
    } catch {
      setResults([]);
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
    <Card title="智能搜索" size="small">
      <Space.Compact style={{ width: '100%', marginBottom: 16 }}>
        <Select
          allowClear
          placeholder="关联对话（可选）"
          style={{ width: 200 }}
          value={dialogueId}
          onChange={setDialogueId}
          options={dialogues.map(d => ({ label: d.title, value: d.id }))}
        />
        <Search
          placeholder="搜索会议内容..."
          enterButton={<><SearchOutlined /> 搜索</>}
          onSearch={handleSearch}
          style={{ flex: 1 }}
        />
      </Space.Compact>

      {searched && (
        <List
          dataSource={results}
          locale={{ emptyText: `未找到与 "${query}" 相关的结果` }}
          renderItem={(item) => (
            <List.Item>
              <List.Item.Meta
                title={
                  <Text strong>
                    {highlight(item.title, query)}
                    <Text style={{ fontSize: 12, marginLeft: 8 }} type="secondary">
                      {item.type === 'vector' ? '语义匹配' : '关键词匹配'}
                    </Text>
                  </Text>
                }
                description={
                  <div>
                    <Text style={{ fontSize: 12, whiteSpace: 'pre-wrap', display: 'block' }}>
                      {item.matchedContent
                        ? highlight(item.matchedContent, query)
                        : (item.transcription?.substring(0, 200) || '')}
                    </Text>
                    {item.transcription && item.transcription.length > 200 && (
                      <Text type="secondary" style={{ fontSize: 11 }}>...</Text>
                    )}
                  </div>
                }
              />
            </List.Item>
          )}
        />
      )}
    </Card>
  );
};

export default SearchPanel;
