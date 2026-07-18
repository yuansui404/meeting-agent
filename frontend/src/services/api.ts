import axios from 'axios';

export const api = axios.create({
  baseURL: process.env.REACT_APP_API_URL || 'http://localhost:8080/api',
});

// 拦截所有对话创建请求，用于排查"新对话"被自动创建的问题
api.interceptors.request.use(config => {
  if (config.method === 'post' && config.url === '/dialogue') {
    console.log('[DialogueCreate] axios POST /dialogue intercepted');
    console.log('  body:', JSON.stringify(config.data));
    console.trace();
  }
  return config;
});

export interface Dialogue {
  id: number;
  title: string;
  status: string;
  updatedAt: string;
  meetingId: number | null;
}

export interface DialogueMessage {
  id: number;
  dialogueId: number;
  role: string;
  content: string;
  messageType: string;
  timestamp: string;
  metadata?: any;
  files?: any[];
}

// 上传文件
export const uploadFile = (file: File, dialogueId?: number) => {
  const formData = new FormData();
  formData.append('file', file);
  if (dialogueId) formData.append('dialogueId', String(dialogueId));
  return api.post('/upload', formData);
};

// 创建对话
export const createDialogue = (title: string, meetingId?: number) =>
  api.post<{ dialogueId: number }>('/dialogue', { title, meetingId });

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

// 获取对话下的文件列表
export const listDialogueMeetings = (dialogueId: number) =>
  api.get<UploadedFile[]>(`/dialogue/${dialogueId}/meetings`);

// 获取对话文件URL（state_json 存储的文件）
export const getDialogueFileUrl = (dialogueId: number, fileId: string) =>
  `${api.defaults.baseURL || 'http://localhost:8080/api'}/dialogue/${dialogueId}/file/${fileId}`;

// 获取对话文件文本内容（state_json 存储的文件）
export const getDialogueFileTextContent = (dialogueId: number, fileId: string) =>
  api.get<{ content: string }>(`/dialogue/${dialogueId}/file/${fileId}/text-content`);

export interface UploadedFile {
  id: number;
  fileId?: string;
  title: string;
  fileSize: number | null;
  status: string;
  createdAt: string;
  knowledgeBase?: boolean;
  /** @deprecated 新代码不应使用 — 对话文件已存入 state_json */
  dialogueId?: number;
  ext: string;
  hasMd?: boolean;
  mdFilePath?: string;
  filePath?: string;
}

// ============================================================
// RAG 文档 API
// ============================================================

export interface RagDocument {
  id: number;
  title: string;
  fileType: string;
  filePath: string;
  fileSize: number;
  meetingDate: string | null;
  status: string;
  styleTags?: string | null;
  participants?: string | null;
  mdFilePath?: string | null;
  createdAt: string;
  updatedAt: string;
}

// 获取文档列表
export const listDocuments = (page = 0, size = 20) =>
  api.get<{ success: boolean; data: { content: RagDocument[]; totalElements: number } }>('/document', { params: { page, size } });

// 获取单个文档
export const getDocument = (id: number) =>
  api.get<{ success: boolean; data: RagDocument }>(`/document/${id}`);

// 获取文档文本内容（用于预览）
export const getDocumentTextContent = (id: number) =>
  api.get<{ success: boolean; data: { content: string } }>(`/document/${id}/text-content`);

// 获取文档文件下载URL
export const getDocumentFileUrl = (id: number) =>
  `${api.defaults.baseURL || 'http://localhost:8080/api'}/document/${id}/file`;

// 删除文档
export const deleteDocument = (id: number) =>
  api.delete(`/document/${id}`);

// 上传文档
export const uploadDocument = (file: File) => {
  const formData = new FormData();
  formData.append('file', file);
  return api.post<{ success: boolean; data: RagDocument }>('/document/upload', formData);
};

export const searchDocumentsByTitle = (keyword: string, limit = 20) =>
  api.get<{ success: boolean; data: RagDocument[] }>('/document/search', { params: { keyword, limit } });

// 搜索文档
// ============================================================
// 流式对话（RAG + 改写路由）
// ============================================================
export const streamChat = (
  dialogueId: number,
  message: string,
  onToken: (token: string) => void,
  onDone: () => void,
  onError: (err: Error) => void,
  files?: any[],
  onThinking?: (delta: string) => void,
  onToolCall?: (data: any) => void,
  onToolResult?: (data: any) => void,
  onCorrected?: (text: string) => void,
): AbortController => {
  const controller = new AbortController();
  const baseUrl = api.defaults.baseURL || 'http://localhost:8080/api';

  (async () => {
    try {
      const body: any = { message };
      if (files && files.length > 0) {
        body.files = files;
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
          if (!trimmed) {
            currentEvent = '';  // SSE event boundary — reset so unnamed data: events are treated as tokens
          } else if (trimmed.startsWith('event:')) {
            currentEvent = trimmed.slice(6).trim();
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
            if (currentEvent === 'thinking') {
              onThinking?.(data);
              continue;
            }
            if (currentEvent === 'tool_call') {
              try { onToolCall?.(JSON.parse(data)); } catch { /* ignore */ }
              continue;
            }
            if (currentEvent === 'tool_result') {
              try { onToolResult?.(JSON.parse(data)); } catch { /* ignore */ }
              continue;
            }
            if (currentEvent === 'corrected') {
              onCorrected?.(data);
              continue;
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

// ============================================================
// Agent Memory API
// ============================================================

export const getMemory = () =>
  api.get<{ success: boolean; data: { content: string } }>('/memory');

export const saveMemory = (content: string) =>
  api.put('/memory', { content });

// ============================================================
// Profile API
// ============================================================

export interface ProfileFileItem {
  filename: string;
  description?: string;
  enabled?: boolean;
  updatedAt?: string;
}

export const getProfileFiles = () =>
  api.get<{ success: boolean; data: ProfileFileItem[] }>('/profile/files');

export const getProfileFile = (filename: string) =>
  api.get<{ success: boolean; data: { content: string } }>(`/profile/${filename}`);

export const saveProfileFile = (filename: string, data: { content: string; description?: string }) =>
  api.put(`/profile/${filename}`, data);

export const createProfileFile = (filename: string, data?: { content?: string; description?: string }) =>
  api.post(`/profile/${filename}`, data);

export const deleteProfileFile = (filename: string) =>
  api.delete(`/profile/${filename}`);

export const toggleProfileFile = (filename: string, enabled: boolean) =>
  api.patch(`/profile/${filename}/toggle`, { enabled });

// 获取文件 Blob（用于前端预览）
export const getFileBlob = async (url: string): Promise<Blob> => {
  const resp = await fetch(url);
  if (!resp.ok) throw new Error(`HTTP ${resp.status}`);
  return resp.blob();
};

export default api;
