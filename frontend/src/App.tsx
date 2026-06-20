import React, { useState, useEffect, useCallback } from 'react';
import { Layout, Typography, Row, Col } from 'antd';
import FileUpload from './components/FileUpload';
import MeetingList from './components/MeetingList';
import DialoguePanel from './components/DialoguePanel';
import SearchPanel from './components/SearchPanel';
import { listMeetings, listDialogues, Dialogue, Meeting } from './services/api';

const { Header, Content } = Layout;
const { Title } = Typography;

const App: React.FC = () => {
  const [meetings, setMeetings] = useState<Meeting[]>([]);
  const [dialogues, setDialogues] = useState<Dialogue[]>([]);
  const [activeDialogue, setActiveDialogue] = useState<Dialogue | null>(null);

  const refreshMeetings = useCallback(async () => {
    try {
      const res = await listMeetings();
      setMeetings(res.data);
    } catch {
      // 后端未启动时静默处理
    }
  }, []);

  const refreshDialogues = useCallback(async () => {
    try {
      const res = await listDialogues();
      setDialogues(res.data);
    } catch {
      // 后端未启动时静默处理
    }
  }, []);

  useEffect(() => {
    refreshMeetings();
    refreshDialogues();
  }, [refreshMeetings, refreshDialogues]);

  return (
    <Layout style={{ minHeight: '100vh' }}>
      <Header style={{ background: '#001529', padding: '0 24px', display: 'flex', alignItems: 'center' }}>
        <Title level={3} style={{ color: '#fff', margin: 0 }}>会议纪要智能体</Title>
      </Header>
      <Content style={{ padding: '24px', background: '#f0f2f5' }}>
        <Row gutter={[16, 16]}>
          <Col span={24}>
            <FileUpload onSuccess={() => { refreshMeetings(); refreshDialogues(); }} />
          </Col>
          <Col xs={24} lg={12}>
            <MeetingList meetings={meetings} />
          </Col>
          <Col xs={24} lg={12}>
            <DialoguePanel
              dialogues={dialogues}
              activeDialogue={activeDialogue}
              onSelect={setActiveDialogue}
              onRefresh={refreshDialogues}
            />
          </Col>
          <Col span={24}>
            <SearchPanel dialogues={dialogues} />
          </Col>
        </Row>
      </Content>
    </Layout>
  );
};

export default App;
