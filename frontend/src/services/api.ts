import axios from 'axios';

const api = axios.create({
  baseURL: process.env.REACT_APP_API_URL || 'http://localhost:8080/api',
});

export interface Meeting {
  id: number;
  title: string;
  transcription: string | null;
  duration: number | null;
  fileSize: number | null;
  status: string;
  createdAt: string;
}

export interface Dialogue {
  id: number;
  title: string;
  status: string;
  updatedAt: string;
  meetingId: number | null;
  imported: boolean;
}

export interface DialogueMessage {
  id: number;
  dialogueId: number;
  role: string;
  content: string;
  messageType: string;
  timestamp: string;
}

export interface SearchResult {
  id: number;
  title: string;
  transcription: string;
  duration: number;
  createdAt: string;
  type: string;
  matchedContent?: string;
}

// 上传文件
export const uploadFile = (file: File) => {
  const formData = new FormData();
  formData.append('file', file);
  return api.post('/upload', formData);
};

// 获取会议详情
export const getMeeting = (id: number) =>
  api.get<Meeting>(`/meeting/${id}`);

// 获取所有会议
export const listMeetings = () =>
  api.get<Meeting[]>('/meetings');

// 创建对话
export const createDialogue = (title: string, meetingId?: number) =>
  api.post<{ dialogueId: number }>('/dialogue', { title, meetingId });

// 发送消息
export const addMessage = (dialogueId: number, role: string, content: string, messageType?: string) =>
  api.post(`/dialogue/${dialogueId}/message`, { role, content, messageType });

// 获取对话历史
export const getDialogue = (id: number) =>
  api.get<{ dialogue: Dialogue; messages: DialogueMessage[] }>(`/dialogue/${id}`);

// 获取对话列表
export const listDialogues = () =>
  api.get<Dialogue[]>('/dialogues');

// 归档对话
export const archiveDialogue = (id: number) =>
  api.post(`/dialogue/${id}/archive`);

// 导入知识库
export const importDialogue = (id: number) =>
  api.post(`/dialogue/${id}/import`);

// 搜索
export const searchMeetings = (query: string, dialogueId?: number) =>
  api.get<{ query: string; results: SearchResult[] }>('/search', {
    params: { query, dialogueId },
  });

export default api;
