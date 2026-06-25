import axios from 'axios';

export const api = axios.create({
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
  updatedAt?: string;
  knowledgeBase?: boolean;
  filePath?: string;
  dialogueId?: number | null;
  mdFilePath?: string | null;
  meetingDate?: string | null;
  styleExemplar?: boolean;
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
  metadata?: string;
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
export const uploadFile = (file: File, dialogueId?: number) => {
  const formData = new FormData();
  formData.append('file', file);
  if (dialogueId) formData.append('dialogueId', String(dialogueId));
  return api.post('/upload', formData);
};

// 获取会议详情
export const getMeeting = (id: number) =>
  api.get<Meeting>(`/meeting/${id}`);

// 获取会议文本内容（用于预览）
export const getMeetingTextContent = (id: number) =>
  api.get<{ content: string; warning?: string }>(`/meeting/${id}/text-content`);

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

// 删除对话
export const deleteDialogue = (id: number) =>
  api.delete(`/dialogue/${id}`);

// 重命名对话
export const renameDialogue = (id: number, title: string) =>
  api.put(`/dialogue/${id}/title`, { title });

// 导入知识库
export const importDialogue = (id: number) =>
  api.post(`/dialogue/${id}/import`);

// 搜索
export const searchMeetings = (query: string, dialogueId?: number) =>
  api.get<{ query: string; results: SearchResult[] }>('/search', {
    params: { query, dialogueId },
  });

// 向量化会议
export const vectorizeMeeting = (id: number) =>
  api.post(`/meeting/${id}/vectorize`);

// 切换知识库状态
export const toggleKnowledgeBase = (id: number) =>
  api.post<{ success: boolean; knowledgeBase: boolean }>(`/meeting/${id}/knowledge-base`);

// 获取知识库会议列表
export const listKnowledgeBase = () =>
  api.get<Meeting[]>('/meetings/knowledge-base');

// 上传文件到知识库（自动向量化）
export const uploadKnowledgeBaseFile = (file: File) => {
  const formData = new FormData();
  formData.append('file', file);
  return api.post<{ success: boolean; meetingId: number; title: string }>('/meetings/knowledge-base/upload', formData);
};

// 搜索知识库
export const searchKnowledgeBase = (query: string) =>
  api.get<{ query: string; results: SearchResult[] }>('/search', {
    params: { query, kbOnly: true },
  });

// 获取对话下的文件列表
export const listDialogueMeetings = (dialogueId: number) =>
  api.get<UploadedFile[]>(`/dialogue/${dialogueId}/meetings`);

// 删除文件
export const deleteMeeting = (id: number) =>
  api.delete(`/meeting/${id}`);

// 获取文件预览URL
export const getFileUrl = (id: number) =>
  `${api.defaults.baseURL || 'http://localhost:8080/api'}/meeting/${id}/file`;

export interface UploadedFile {
  id: number;
  title: string;
  fileSize: number | null;
  status: string;
  createdAt: string;
  knowledgeBase?: boolean;
  dialogueId?: number;
  ext: string;
  hasMd?: boolean;
  mdFilePath?: string;
}

// ============================================================
// RAG 新管线 API
// ============================================================

export interface RagDocument {
  id: number;
  title: string;
  fileType: string;
  filePath: string;
  fileSize: number;
  meetingDate: string | null;
  status: string;
  chunkCount: number;
  createdAt: string;
  updatedAt: string;
}

// 获取文档列表
export const listDocuments = () =>
  api.get<{ success: boolean; data: RagDocument[] }>('/document');

// 删除文档
export const deleteDocument = (id: number) =>
  api.delete(`/document/${id}`);

// 上传文档
export const uploadDocument = (file: File) => {
  const formData = new FormData();
  formData.append('file', file);
  return api.post<{ success: boolean; data: RagDocument }>('/document/upload', formData);
};

export interface Conversation {
  id: number;
  title: string;
  status: string;
  contextSummary: string | null;
  messageCount: number;
  createdAt: string;
  updatedAt: string;
}

export interface ChatMessage {
  id: number;
  conversationId: number;
  role: string;
  content: string;
  traceId: string | null;
  metadata: string | null;
  createdAt: string;
}

// 创建会话
export const createConversation = (title?: string) =>
  api.post<{ success: boolean; data: Conversation }>('/conversation', { title });

// 获取会话列表
export const listConversations = () =>
  api.get<{ success: boolean; data: Conversation[] }>('/conversation');

// 删除会话
export const deleteConversation = (id: number) =>
  api.delete(`/conversation/${id}`);

// 获取会话消息
export const getConversationMessages = (id: number) =>
  api.get<{ success: boolean; data: ChatMessage[] }>(`/conversation/${id}/messages`);

// 流式对话（RAG）
// ============================================================
// 改写风格学习 API
// ============================================================

export interface RewriteResultData {
  id: number;
  dialogueId: number;
  sourceFileIds: string;
  referenceIds: string | null;
  content: string;
  docxPath: string | null;
  version: number;
  createdAt: string;
}

// 获取改写结果详情
export const getRewriteResult = (id: number) =>
  api.get<RewriteResultData>(`/rewrite-result/${id}`);

// 获取改写结果文件的下载 URL
export const getRewriteFileUrl = (rewriteResultId: number) =>
  `${api.defaults.baseURL || 'http://localhost:8080/api'}/rewrite-result/${rewriteResultId}/file`;

// 获取对话的改写历史
export const getRewriteHistory = (dialogueId: number) =>
  api.get<{ id: number; version: number; docxPath: string; createdAt: string }[]>(
    `/dialogue/${dialogueId}/rewrite-history`
  );

// 提交段落点赞/踩反馈
export const submitRewriteFeedback = (
  rewriteResultId: number,
  paragraphIndex: number,
  action: 'like' | 'dislike'
) => api.post('/rewrite-feedback', { rewriteResultId, paragraphIndex, action });

// 标记文档为风格范例
export const setStyleExemplar = (meetingId: number, styleExemplar: boolean, styleTags?: string) =>
  api.post(`/meeting/${meetingId}/style-exemplar`, { styleExemplar, styleTags });

// 获取风格范例列表
export const listStyleExemplars = () =>
  api.get<{ id: number; title: string; styleTags: string }[]>('/meetings/style-exemplars');

// ============================================================
// 流式对话（RAG + 改写路由）
// ============================================================
export const streamChat = (
  dialogueId: number,
  message: string,
  onToken: (token: string) => void,
  onDone: () => void,
  onError: (err: Error) => void,
  fileIds?: number[]
): AbortController => {
  const controller = new AbortController();
  const baseUrl = api.defaults.baseURL || 'http://localhost:8080/api';

  (async () => {
    try {
      const body: any = { message };
      if (fileIds && fileIds.length > 0) {
        body.fileIds = fileIds;
      }
      const response = await fetch(`${baseUrl}/dialogue/${dialogueId}/chat`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(body),
        signal: controller.signal,
      });

      if (!response.ok) {
        const errText = await response.text().catch(() => `HTTP ${response.status}`);
        throw new Error(errText);
      }

      const reader = response.body!.getReader();
      const decoder = new TextDecoder();
      let buffer = '';
      let currentEvent = '';

      while (true) {
        const { done, value } = await reader.read();
        if (done) break;

        buffer += decoder.decode(value, { stream: true });
        const lines = buffer.split('\n');
        buffer = lines.pop() || '';

        for (const line of lines) {
          const trimmed = line.trim();
          if (trimmed.startsWith('event: ')) {
            currentEvent = trimmed.slice(7).trim();
          } else if (trimmed.startsWith('data:')) {
            const data = trimmed.slice(5).trim();
            if (currentEvent === 'done') {
              onDone();
              return;
            }
            if (currentEvent === 'error') {
              onError(new Error(data));
              return;
            }
            if (data && data !== '[DONE]') {
              onToken(data);
            }
          }
        }
      }
      onDone();
    } catch (err: any) {
      if (err.name !== 'AbortError') {
        onError(err);
      }
    }
  })();

  return controller;
};

export default api;
