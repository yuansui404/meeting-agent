import React, { useState, useEffect, useRef, useCallback } from 'react';
import { Input, Button, Typography, Spin, message as antMsg, Dropdown, Tag } from 'antd';
import type { MenuProps } from 'antd';
import {
  SendOutlined,
  RobotOutlined,
  UserOutlined,
  UploadOutlined,
  SearchOutlined,
  FileTextOutlined,
  BulbOutlined,
  PictureOutlined,
  VideoCameraOutlined,
  AudioOutlined,
  DeleteOutlined,
  PaperClipOutlined,
  ArrowUpOutlined,
  CloseOutlined,
} from '@ant-design/icons';
import ReactMarkdown from 'react-markdown';
import {
  Dialogue,
  DialogueMessage,
  getDialogue,
  streamChat,
  uploadFile,
  UploadedFile,
  listDialogueMeetings,
  deleteDocument,
  getDocumentFileUrl,
  getDocumentTextContent,
  getDialogueFileUrl,
  getDialogueFileTextContent,
  api,
} from '../services/api';
import PreviewDrawer from './PreviewDrawer';

const { Text, Title } = Typography;
const { TextArea } = Input;

interface Props {
  activeDialogue: Dialogue | null;
  freshDialogue?: boolean;
  onDialogueUpdated: () => void;
  onStartChat: (message: string) => Promise<Dialogue | null>;
  onCreateDialogue: () => Promise<Dialogue | null>;
  onDialogueUsed?: () => void;
}

interface StreamingState {
  content: string;
  active: boolean;
}

interface ToolCallEvent {
  action: string;
  id: string;
  name?: string;
  delta?: string;
}

interface ToolCallDisplay {
  id: string;
  name: string;
  result: string;
  completed: boolean;
}

const suggestions = [
  { icon: <BulbOutlined />, text: '帮我总结最近的会议内容' },
  { icon: <FileTextOutlined />, text: '搜索关于项目的讨论' },
  { icon: <SearchOutlined />, text: '列出所有会议记录' },
];

// Extend DialogueMessage for local display with file attachments
interface DisplayMessage extends DialogueMessage {
  files?: UploadedFile[];
}

interface PendingFileCard {
  id: number;
  fileId?: string;
  filePath?: string;
  name: string;
  ext: string;
  fileSize: number | null;
  status: string;
  uploading: boolean;
}

const DialoguePanel: React.FC<Props> = ({ activeDialogue, freshDialogue, onDialogueUpdated, onStartChat, onCreateDialogue, onDialogueUsed }) => {
  const [messages, setMessages] = useState<DisplayMessage[]>([]);
  const [messagesLoaded, setMessagesLoaded] = useState(false);
  const [input, setInput] = useState('');
  const [sending, setSending] = useState(false);
  const [streaming, setStreaming] = useState<StreamingState>({ content: '', active: false });
  const [generating, setGenerating] = useState(false);
  const pollingRef = useRef<ReturnType<typeof setInterval> | null>(null);
  const [thinkingText, setThinkingText] = useState('');
  const [toolCalls, setToolCalls] = useState<ToolCallDisplay[]>([]);
    const [uploadingFiles, setUploadingFiles] = useState<{ name: string; id?: number }[]>([]);
  const [uploadAccept, setUploadAccept] = useState('');
  const [uploadedFiles, setUploadedFiles] = useState<UploadedFile[]>([]);
  const [pendingFileCards, setPendingFileCards] = useState<PendingFileCard[]>([]);
  const [previewFile, setPreviewFile] = useState<UploadedFile | null>(null);
  const [previewContent, setPreviewContent] = useState<string | null>(null);
  const fileInputRef = useRef<HTMLInputElement>(null);
  const messagesEndRef = useRef<HTMLDivElement>(null);
  const abortRef = useRef<AbortController | null>(null);
  const textareaRef = useRef<HTMLTextAreaElement>(null);
  const streamingContentRef = useRef('');
  const handleSuggestionClickRef = useRef<(text: string) => Promise<void>>(async () => {});

  const showWelcome = !activeDialogue || freshDialogue || (messagesLoaded && messages.length === 0);

  useEffect(() => {
    if (activeDialogue) {
      setMessagesLoaded(false);
      setMessages([]);
      loadFiles(activeDialogue.id);
      loadMessages(activeDialogue.id);
    } else {
      setMessages([]);
      setMessagesLoaded(false);
      setStreaming({ content: '', active: false });
      setUploadedFiles([]);
    }
    // 切换对话时清除轮询
    stopPolling();
    setGenerating(false);
  }, [activeDialogue]);

  // 组件卸载时清除轮询
  useEffect(() => {
    return () => stopPolling();
  }, []);

  useEffect(() => {
    scrollToBottom();
  }, [messages, streaming.content]);

  const scrollToBottom = () => {
    setTimeout(() => {
      messagesEndRef.current?.scrollIntoView({ behavior: 'smooth' });
    }, 50);
  };

  const stopPolling = () => {
    if (pollingRef.current) {
      clearInterval(pollingRef.current);
      pollingRef.current = null;
    }
  };

  const startPolling = (id: number) => {
    stopPolling();
    pollingRef.current = setInterval(async () => {
      try {
        const res = await getDialogue(id);
        const msgs: DisplayMessage[] = (res.data.messages || []).map((m: DialogueMessage) => ({
          ...m,
          files: (m.files || []).map((f: any) => ({
            ...f,
            title: f.title || f.fileName || f.name || '未知文件',
          })),
        }));
        setMessages(msgs);
        // 最后一条是 assistant 消息，说明生成完毕
        if (msgs.length > 0 && msgs[msgs.length - 1].role === 'assistant') {
          stopPolling();
          setGenerating(false);
        }
      } catch {
        // 轮询出错时不停止，等下次重试
      }
    }, 3000);
  };

  const loadMessages = async (id: number) => {
    try {
      const res = await getDialogue(id);
      const data = res.data as any;
      const msgs: DisplayMessage[] = ((data.data?.messages || data.messages) || []).map((m: DialogueMessage) => ({
        ...m,
        files: (m.files || []).map((f: any) => ({
          ...f,
          title: f.title || f.fileName || f.name || '未知文件',
        })),
      }));
      setMessages(msgs);
      setMessagesLoaded(true);
      // 最后一条是 user 消息，说明正在生成中
      if (msgs.length > 0 && msgs[msgs.length - 1].role === 'user') {
        setGenerating(true);
        startPolling(id);
      } else {
        setGenerating(false);
        stopPolling();
      }
    } catch {
      setMessages([]);
    }
  };

  const loadFiles = async (id: number) => {
    try {
      const res = await listDialogueMeetings(id);
      const data = res.data as any;
      setUploadedFiles(data.data || data || []);
    } catch {
      setUploadedFiles([]);
    }
  };

  const handleDeleteFile = async (file: UploadedFile) => {
    try {
      if ((file as any).fileId) {
        // New-style files are in state_json — just refresh the list
        // (disk cleanup can be async)
        if (activeDialogue) loadFiles(activeDialogue.id);
      } else {
        await deleteDocument(file.id);
        if (activeDialogue) loadFiles(activeDialogue.id);
      }
    } catch {
      antMsg.error('删除失败');
    }
  };

  const handleRemovePendingFileCard = async (id: number) => {
    const card = pendingFileCards.find(c => c.id === id);
    setPendingFileCards(prev => prev.filter(c => c.id !== id));
    if (id > 0 && !card?.fileId) {
      // Old-style file in meeting_minutes — delete the DB record
      try {
        await deleteDocument(id);
      } catch { /* ignore */ }
      if (activeDialogue) loadFiles(activeDialogue.id);
    }
    // New-style pending files (fileId exists) have no DB record — just remove the card
  };

  const sendMessage = useCallback(async (content: string, dialogueId: number) => {
    onDialogueUsed?.();
    setSending(true);
    setInput('');
    setGenerating(false);
    stopPolling();

    const filesForDisplay = pendingFileCards
      .filter(f => !f.uploading)
      .map(f => ({ id: f.id, title: f.name, ext: f.ext, fileSize: f.fileSize, status: f.status, createdAt: new Date().toISOString() } as UploadedFile));
    setPendingFileCards([]);

    const userMsg: DisplayMessage = {
      id: Date.now(),
      dialogueId,
      role: 'user',
      content,
      messageType: 'text',
      timestamp: new Date().toISOString(),
      files: filesForDisplay,
    };
    setMessages(prev => [...prev, userMsg]);
    setStreaming({ content: '', active: true });
    setThinkingText('');
    setToolCalls([]);

    try {
      // Build file metadata for new-style files (state_json backed)
      const filesMeta = pendingFileCards
        .filter(f => !f.uploading && f.fileId)
        .map(f => ({
          fileId: f.fileId,
          fileName: f.name,
          filePath: f.filePath,
          fileSize: f.fileSize,
          ext: f.ext,
        }));
      abortRef.current = streamChat(
        dialogueId,
        content,
        (token) => {
          streamingContentRef.current += token;
          setStreaming(prev => ({ content: prev.content + token, active: true }));
        },
        () => {
          streamingContentRef.current = '';
          setStreaming({ content: '', active: false });
          setSending(false);
          setThinkingText(prev => prev ? prev + '\n\n---\n' : '');
          loadMessages(dialogueId);
          onDialogueUpdated();
        },
        (err) => {
          setStreaming({ content: '', active: false });
          setSending(false);
          antMsg.error('对话出错: ' + err.message);
        },
        filesMeta && filesMeta.length > 0 ? filesMeta : undefined,
        (delta) => {
          setThinkingText(prev => prev + delta);
        },
        (data) => {
          if (data.action === 'start') {
            setToolCalls(prev => [...prev, { id: data.id, name: data.name || '', result: '', completed: false }]);
          } else if (data.action === 'end') {
            setToolCalls(prev => prev.map(tc => tc.id === data.id ? { ...tc, completed: true } : tc));
          }
        },
        (data) => {
          if (data.action === 'delta' && data.delta) {
            setToolCalls(prev => prev.map(tc => tc.id === data.id ? { ...tc, result: tc.result + data.delta } : tc));
          }
        },
        (text) => {
          streamingContentRef.current = text;
          setStreaming({ content: text, active: false });
        }
      );
    } catch (err: any) {
      setStreaming({ content: '', active: false });
      setSending(false);
      antMsg.error('发送失败: ' + err.message);
    }
  }, [onDialogueUpdated, pendingFileCards, onDialogueUsed]);

  const handleSend = useCallback(async () => {
    const content = input.trim();
    if (!content || sending) return;

    if (activeDialogue) {
      await sendMessage(content, activeDialogue.id);
    } else {
      console.log('[DialogueCreate] handleSend: 无活跃对话，触发 auto-create, content=' + content);
      console.trace();
      const newDialogue = await onStartChat(content);
      if (newDialogue) {
        setTimeout(() => sendMessage(content, newDialogue.id), 100);
      }
    }
  }, [input, activeDialogue, sending, onStartChat, sendMessage]);

  // Wait for sendMessage to be defined with the latest sendMessage
  const handleSendRef = useRef(handleSend);
  handleSendRef.current = handleSend;

  const handleSuggestionClick = async (text: string) => {
    if (activeDialogue) {
      await sendMessage(text, activeDialogue.id);
    } else {
      console.log('[DialogueCreate] handleSuggestionClick: 无活跃对话，触发 auto-create, text=' + text);
      console.trace();
      const newDialogue = await onStartChat(text);
      if (newDialogue) {
        setTimeout(() => sendMessage(text, newDialogue.id), 100);
      }
    }
  };
  handleSuggestionClickRef.current = handleSuggestionClick;

  const handleKeyDown = (e: React.KeyboardEvent) => {
    if (e.key === 'Enter' && !e.shiftKey) {
      e.preventDefault();
      handleSendRef.current();
    }
  };

  const handleFileUpload = async (file: File) => {
    const MAX_FILE_SIZE = 10 * 1024 * 1024;       // 10MB per file
    const MAX_TOTAL_SIZE = 100 * 1024 * 1024;      // 100MB total
    const MAX_FILE_COUNT = 10;

    if (file.size > MAX_FILE_SIZE) {
      antMsg.warning(`文件 ${file.name} 超过 10MB 限制`);
      return;
    }

    const existingCount = uploadedFiles.length + pendingFileCards.length;
    if (existingCount >= MAX_FILE_COUNT) {
      antMsg.warning(`最多上传 ${MAX_FILE_COUNT} 个文件`);
      return;
    }

    const existingTotalSize = uploadedFiles.reduce((sum, f) => sum + (f.fileSize || 0), 0)
      + pendingFileCards.reduce((sum, f) => sum + (f.fileSize || 0), 0);
    if (existingTotalSize + file.size > MAX_TOTAL_SIZE) {
      antMsg.warning('文件总大小超过 100MB 限制');
      return;
    }

    let dialogue = activeDialogue;
    if (!dialogue) {
      console.log('[DialogueCreate] handleFileUpload: 无活跃对话，触发 auto-create');
      console.trace();
      dialogue = await onCreateDialogue();
      if (!dialogue) return;
    }
    const ext = file.name.substring(file.name.lastIndexOf('.')) || '';
    setPendingFileCards(prev => [...prev, {
      id: -Date.now(), name: file.name, ext, fileSize: file.size,
      status: 'processing', uploading: true,
    }]);
    try {
      const res = await uploadFile(file, dialogue.id);
      // 解包 ApiResponse：res.data = { success, data: FileUploadVO }
      const uploadResult = (res.data as any)?.data || res.data;
      if (uploadResult.fileId) {
        setPendingFileCards(prev => prev.map(c =>
          c.name === file.name && c.uploading
            ? {
                ...c,
                id: uploadResult.fileId || c.id,
                fileId: uploadResult.fileId,
                filePath: uploadResult.filePath,
                uploading: false,
                status: 'completed',
              }
            : c
        ));
        if (activeDialogue) loadFiles(activeDialogue.id);
        onDialogueUpdated();
        setTimeout(() => textareaRef.current?.focus(), 100);
      }
    } catch (err: any) {
      setPendingFileCards(prev => prev.filter(c => c.name !== file.name || !c.uploading));
      antMsg.error('上传失败: ' + (err?.response?.data?.message || err?.message || '未知错误'));
    }
  };

  const handleFileInputChange = (e: React.ChangeEvent<HTMLInputElement>) => {
    const file = e.target.files?.[0];
    if (file) {
      handleFileUpload(file);
    }
    e.target.value = '';
  };

  const triggerUpload = (accept: string) => {
    setUploadAccept(accept);
    // Small delay to let state propagate, then trigger file dialog
    setTimeout(() => fileInputRef.current?.click(), 0);
  };

  
  const handleFilePreview = useCallback(async (file: UploadedFile) => {
    setPreviewFile(file);
    setPreviewContent(null);
    // Pre-fetch text content as fallback for non-PDF/DOCX formats
    try {
      if ((file as any).fileId && activeDialogue) {
        const resp = await getDialogueFileTextContent(activeDialogue.id, (file as any).fileId);
        setPreviewContent(resp.data?.content || null);
      } else if (file.id > 0) {
        const textResp = await getDocumentTextContent(file.id);
        setPreviewContent(textResp.data?.data?.content || null);
      }
    } catch {
      setPreviewContent(null);
    }
  }, [activeDialogue]);

  return (
    <div style={{ flex: 1, display: 'flex', flexDirection: 'column', height: '100%', background: 'var(--content-bg)' }}>
      {/* Model selector */}
      <div style={{
        padding: '6px 20px',
        borderBottom: '1px solid var(--border-color)',
        background: 'var(--app-bg)',
        display: 'flex',
        alignItems: 'center',
        gap: 8,
        flexShrink: 0,
      }}>
        <Tag icon={<RobotOutlined />} color="blue" style={{ borderRadius: 6, margin: 0, fontSize: 12 }}>
          deepseek-v4-flash
        </Tag>
        <Text type="secondary" style={{ fontSize: 11 }}>当前模型</Text>
      </div>
      {/* Messages / Welcome area */}
      <div style={{ flex: 1, overflow: 'auto', padding: 0 }}>
        {!showWelcome ? (
          <div style={{ maxWidth: 800, margin: '0 auto', padding: '20px 0' }}>
            {messages.map((msg) => (
              <MessageBubble key={msg.id} message={msg} onFilePreview={handleFilePreview} />
            ))}
            {generating && !streaming.active && (
              <div style={{ display: 'flex', padding: '12px 24px', justifyContent: 'flex-start' }}>
                <div style={{ display: 'flex', maxWidth: '75%', gap: 12, alignItems: 'flex-start' }}>
                  <div style={{
                    width: 36, height: 36, borderRadius: 8,
                    display: 'flex', alignItems: 'center', justifyContent: 'center',
                    flexShrink: 0, background: 'var(--hover-bg)', color: 'var(--text-tertiary)', fontSize: 16,
                  }}>
                    <RobotOutlined />
                  </div>
                  <div style={{
                    padding: '10px 16px', borderRadius: 12,
                    background: 'var(--msg-agent-bg)', border: '1px solid var(--border-color)',
                    fontSize: 14, lineHeight: 1.6,
                    display: 'flex', alignItems: 'center', gap: 8,
                  }}>
                    <Spin size="small" />
                    <span style={{ color: 'var(--text-secondary)' }}>AI 正在生成中...</span>
                  </div>
                </div>
              </div>
            )}
            {(thinkingText || toolCalls.length > 0) && (
              <ThoughtProcess
                thinkingText={thinkingText}
                toolCalls={toolCalls}
                isStreaming={streaming.active}
              />
            )}
            {streaming.active && streaming.content && (
              <StreamingBubble content={streaming.content} />
            )}
            <div ref={messagesEndRef} />
          </div>
        ) : (
          <div style={{
            display: 'flex', flexDirection: 'column', alignItems: 'center', justifyContent: 'center',
            height: '100%', padding: 40, position: 'relative', overflow: 'hidden',
            background: 'var(--welcome-bg)',
          }}>
            {/* Decorative circles */}
            <div style={{
              position: 'absolute', width: 300, height: 300, borderRadius: '50%',
              background: 'radial-gradient(circle, rgba(22,119,255,0.06) 0%, transparent 70%)',
              top: -80, right: -60,
            }} />
            <div style={{
              position: 'absolute', width: 400, height: 400, borderRadius: '50%',
              background: 'radial-gradient(circle, rgba(114,46,209,0.04) 0%, transparent 70%)',
              bottom: -120, left: -100,
            }} />

            {/* Logo area */}
            <div style={{
              width: 72, height: 72, borderRadius: 20,
              background: 'linear-gradient(135deg, #1677ff, #7c3aed)',
              display: 'flex', alignItems: 'center', justifyContent: 'center',
              marginBottom: 20, boxShadow: '0 8px 24px rgba(22,119,255,0.2)',
            }}>
              <RobotOutlined style={{ fontSize: 36, color: '#fff' }} />
            </div>
            <Title level={3} style={{ margin: 0, marginBottom: 6, fontSize: 22 }}>
              你好，你问呗
            </Title>
            <Text type="secondary" style={{ fontSize: 14, marginBottom: 32, textAlign: 'center', maxWidth: 360 }}>
              上传会议视频自动转写，智能搜索历史内容，帮你高效管理会议信息
            </Text>

            {/* Centered input box */}
            <div style={{ width: '100%', maxWidth: 560, marginBottom: 24 }}>
              <div style={{
                display: 'flex', gap: 8, alignItems: 'center',
                borderRadius: 12, padding: '4px 4px 4px 16px',
                background: 'var(--content-bg)',
                border: '1px solid var(--border-color)',
                boxShadow: '0 2px 8px rgba(0,0,0,0.04)',
              }}>
                <Dropdown
                  trigger={['hover']}
                  placement="bottomLeft"
                  menu={{
                    items: [
                      { key: 'video', icon: <VideoCameraOutlined />, label: '视频', extra: 'MP4, AVI, MOV, MKV' },
                      { key: 'audio', icon: <AudioOutlined />, label: '音频', extra: 'MP3, WAV, M4A, AAC' },
                      { key: 'document', icon: <FileTextOutlined />, label: '文档', extra: 'PDF, DOC, TXT, MD' },
                      { key: 'image', icon: <PictureOutlined />, label: '图片', extra: 'JPG, PNG, GIF, WebP' },
                    ],
                    onClick: ({ key }) => {
                      const acceptMap: Record<string, string> = {
                        video: '.mp4,.avi,.mov,.mkv,.webm,.wmv,.flv',
                        audio: '.mp3,.wav,.m4a,.aac,.ogg,.wma,.flac',
                        document: '.pdf,.doc,.docx,.txt,.md,.csv,.xlsx,.pptx',
                        image: '.jpg,.jpeg,.png,.gif,.bmp,.webp,.svg',
                      };
                      triggerUpload(acceptMap[key] || '');
                    },
                  }}
                >
                  <Button
                    type="text"
                    icon={<PaperClipOutlined />}
                    style={{ color: 'var(--text-tertiary)', fontSize: 16, flexShrink: 0 }}
                  />
                </Dropdown>
                <Input
                  size="large"
                  placeholder="提问..."
                  value={input}
                  onChange={(e) => setInput(e.target.value)}
                  onPressEnter={() => handleSendRef.current()}
                  variant="borderless"
                  style={{ flex: 1, fontSize: 15, background: 'transparent' }}
                />
                <Button
                  type="primary"
                  shape="circle"
                  icon={<ArrowUpOutlined />}
                  onClick={handleSend}
                  disabled={!input.trim()}
                  style={{ flexShrink: 0 }}
                />
              </div>
            </div>

            {/* Suggestion buttons */}
            <div style={{ display: 'flex', gap: 10, flexWrap: 'wrap', justifyContent: 'center', maxWidth: 560 }}>
              {suggestions.map((s, i) => (
                <Button
                  key={i}
                  icon={s.icon}
                  size="large"
                  style={{
                    borderRadius: 10, padding: '18px 20px', height: 'auto',
                    borderColor: 'var(--border-color)',
                    background: 'var(--welcome-card-bg)', backdropFilter: 'blur(8px)',
                    boxShadow: '0 1px 4px rgba(0,0,0,0.04)',
                    fontSize: 14, fontWeight: 500,
                  }}
                  onClick={() => handleSuggestionClickRef.current(s.text)}
                >
                  {s.text}
                </Button>
              ))}
            </div>

            {/* Footer hint */}
            <Text type="secondary" style={{ fontSize: 11, marginTop: 24, color: 'rgba(0,0,0,0.3)' }}>
              由 DeepSeek V4 Flash 驱动
            </Text>
          </div>
        )}
      </div>

      {/* Input area */}
      <div style={{
          borderTop: !showWelcome ? '1px solid var(--border-color)' : 'none',
          background: 'var(--app-bg)',
          padding: !showWelcome ? '8px 24px 16px' : '0 24px 16px',
        }}>
        <div style={{ maxWidth: 800, margin: '0 auto' }}>
          {/* Integrated input container */}
          <div style={{
            background: 'var(--content-bg)',
            borderRadius: 12,
            padding: pendingFileCards.length > 0 ? '8px 8px 4px 12px' : '4px 4px 4px 16px',
          }}>
            {/* File cards area (only when files are pending) */}
            {pendingFileCards.length > 0 && (
              <div style={{ marginBottom: 6 }}>
                {pendingFileCards.map((card) => (
                  <div
                    key={card.id}
                    style={{
                      display: 'inline-flex', alignItems: 'center', gap: 6,
                      padding: '5px 6px 5px 10px', marginRight: 6, marginBottom: 4,
                      borderRadius: 8, background: 'var(--card-bg)',
                      height: 36, maxWidth: 280,
                    }}
                  >
                    {card.uploading ? (
                      <Spin size="small" style={{ flexShrink: 0 }} />
                    ) : (
                      <FileTextOutlined style={{ fontSize: 14, color: 'var(--primary-color)', flexShrink: 0 }} />
                    )}
                    <Text style={{ fontSize: 12, lineHeight: '20px' }} ellipsis={{ tooltip: card.name }}>
                      {card.name}
                    </Text>
                    {!card.uploading && card.fileSize != null && (
                      <Text type="secondary" style={{ fontSize: 10, flexShrink: 0 }}>
                        {formatFileSize(card.fileSize)}
                      </Text>
                    )}
                    <Button
                      type="text"
                      size="small"
                      icon={<CloseOutlined style={{ fontSize: 10 }} />}
                      onClick={() => handleRemovePendingFileCard(card.id)}
                      style={{
                        width: 18, height: 18, minWidth: 18,
                        display: 'flex', alignItems: 'center', justifyContent: 'center',
                        color: 'var(--text-secondary)', flexShrink: 0,
                      }}
                    />
                  </div>
                ))}
                {/* Quick action pills */}
                <div style={{
                  display: 'flex', gap: 4, marginTop: 4, marginBottom: 2,
                  paddingLeft: 2,
                }}>
                  {['详细总结', '简单摘要', '提取要点'].map((text) => (
                    <Button
                      key={text}
                      type="text"
                      size="small"
                      style={{
                        fontSize: 11, color: '#1677ff', padding: '0 8px',
                        height: 22, borderRadius: 11,
                        background: 'rgba(22,119,255,0.06)',
                        border: '1px solid rgba(22,119,255,0.12)',
                      }}
                      onClick={() => {
                        if (activeDialogue) {
                          sendMessage(text, activeDialogue.id);
                        }
                      }}
                    >
                      {text} -&gt;
                    </Button>
                  ))}
                </div>
              </div>
            )}

            {/* Text input row — only when not in welcome/fresh state */}
            {!showWelcome && (
            <div style={{
              display: 'flex',
              gap: 8,
              alignItems: 'flex-end',
            }}>
              <Dropdown
                trigger={['hover']}
                placement="bottomLeft"
                menu={{
                  items: [
                    { key: 'video', icon: <VideoCameraOutlined />, label: '视频', extra: 'MP4, AVI, MOV, MKV' },
                    { key: 'audio', icon: <AudioOutlined />, label: '音频', extra: 'MP3, WAV, M4A, AAC' },
                    { key: 'document', icon: <FileTextOutlined />, label: '文档', extra: 'PDF, DOC, TXT, MD' },
                    { key: 'image', icon: <PictureOutlined />, label: '图片', extra: 'JPG, PNG, GIF, WebP' },
                  ],
                  onClick: ({ key }) => {
                    const acceptMap: Record<string, string> = {
                      video: '.mp4,.avi,.mov,.mkv,.webm,.wmv,.flv',
                      audio: '.mp3,.wav,.m4a,.aac,.ogg,.wma,.flac',
                      document: '.pdf,.doc,.docx,.txt,.md,.csv,.xlsx,.pptx',
                      image: '.jpg,.jpeg,.png,.gif,.bmp,.webp,.svg',
                    };
                    triggerUpload(acceptMap[key] || '');
                  },
                }}
              >
                <Button
                  type="text"
                  icon={<PaperClipOutlined />}
                  style={{ color: 'var(--text-tertiary)', fontSize: 16, flexShrink: 0, marginBottom: 4 }}
                />
              </Dropdown>
              <TextArea
                ref={textareaRef}
                value={input}
                onChange={(e) => setInput(e.target.value)}
                onKeyDown={handleKeyDown}
                placeholder={pendingFileCards.length > 0 ? "输入你对文件的指令..." : "提问"}
                rows={1}
                autoSize={{ minRows: pendingFileCards.length > 0 ? 2 : 1, maxRows: 6 }}
                variant="borderless"
                style={{ flex: 1, resize: 'none', fontSize: 14 }}
                disabled={sending}
              />
              <Button
                type="primary"
                icon={<ArrowUpOutlined />}
                onClick={handleSend}
                loading={sending}
                disabled={!input.trim()}
                style={{ borderRadius: 8, height: 36, width: 36, flexShrink: 0 }}
              />
            </div>
            )}
          </div>

          {/* Hidden file input (shared by welcome page upload icon) */}
          <input
            ref={fileInputRef}
            type="file"
            accept={uploadAccept}
            onChange={handleFileInputChange}
            style={{ display: 'none' }}
          />
          <Text type="secondary" style={{ fontSize: 11, display: 'block', marginTop: 6, textAlign: 'center' }}>
            由 DeepSeek V4 Flash 驱动 · 支持上传 MP4 会议视频
          </Text>
        </div>
      </div>

      {/* File Preview Drawer */}
      <PreviewDrawer
        open={!!previewFile}
        onClose={() => { setPreviewFile(null); setPreviewContent(null); }}
        title={previewFile?.title || '文件预览'}
        fileUrl={previewFile
          ? ((previewFile as any).fileId && activeDialogue
            ? getDialogueFileUrl(activeDialogue.id, (previewFile as any).fileId)
            : getDocumentFileUrl(previewFile.id))
          : ''}
        fileType={previewFile?.ext || ''}
        textContent={previewContent || undefined}
        width={440}
      />

    </div>
  );
};

// Tool call item inside the thought process block
const TOOL_INFO: Record<string, { icon: string; label: string }> = {
  search_knowledge_base: { icon: '📚', label: '搜索知识库' },
  search_meeting_titles: { icon: '🔍', label: '搜索会议标题' },
  memory_search: { icon: '🧠', label: '查询记忆' },
  memory_get: { icon: '🧠', label: '读取记忆' },
  read_profile: { icon: '👤', label: '读取用户信息' },
  update_profile: { icon: '✏️', label: '更新用户信息' },
  upload_to_knowledge_base: { icon: '📤', label: '上传到知识库' },
  list_meetings: { icon: '📋', label: '获取会议列表' },
};

function summarizeResult(toolName: string, resultText: string): string {
  if (!resultText) return '';
  try {
    const data = JSON.parse(resultText);
    if (toolName === 'search_knowledge_base' && Array.isArray(data)) {
      const names = data
        .filter((r: any) => r.source)
        .map((r: any) => r.source.replace(/\.(docx|doc|pdf|txt|md)$/i, ''))
        .filter(Boolean);
      if (names.length > 0) return `找到 ${names.length} 条相关记录：${names.join('、')}`;
      if (data.length > 0) return `找到 ${data.length} 条结果`;
    }
    if (toolName === 'memory_search') {
      return '已查询个人记忆数据';
    }
  } catch {}
  return '';
}

const ToolCallItem: React.FC<{ toolCall: ToolCallDisplay }> = ({ toolCall }) => {
  const info = TOOL_INFO[toolCall.name] || { icon: '🔧', label: toolCall.name };
  const summary = summarizeResult(toolCall.name, toolCall.result);
  const hasRawResult = toolCall.result.length > 0 && !summary;
  const [showRaw, setShowRaw] = useState(false);

  return (
    <div style={{ marginBottom: 6, fontSize: 13, lineHeight: 1.6 }}>
      <span>
        {info.icon}
        {' '}{info.label}
        {' '}{toolCall.completed ? '✓' : '...'}
      </span>
      {summary && (
        <div style={{ marginLeft: 20, color: 'var(--text-secondary)', fontSize: 12 }}>
          {summary}
        </div>
      )}
      {hasRawResult && (
        <div
          onClick={() => setShowRaw(!showRaw)}
          style={{ marginLeft: 20, fontSize: 12, color: 'var(--text-secondary)', cursor: 'pointer' }}
        >
          {showRaw ? '▾ 查看返回数据' : '▸ 查看返回数据'}
          {showRaw && (
            <pre style={{
              marginTop: 2, padding: 4, fontSize: 11, lineHeight: 1.4,
              background: 'var(--toolcall-expanded-bg)', borderRadius: 4,
              maxHeight: 120, overflow: 'auto', whiteSpace: 'pre-wrap', wordBreak: 'break-word',
            }}>
              {toolCall.result}
            </pre>
          )}
        </div>
      )}
    </div>
  );
};

// Thought process — Qwen-style unified collapsible block for thinking + tool calls
const ThoughtProcess: React.FC<{
  thinkingText: string;
  toolCalls: ToolCallDisplay[];
  isStreaming: boolean;
}> = ({ thinkingText, toolCalls, isStreaming }) => {
  const [expanded, setExpanded] = useState(true);

  // Auto-collapse when streaming finishes
  useEffect(() => {
    if (!isStreaming) {
      // Delay collapse slightly so user sees the final state
      const timer = setTimeout(() => setExpanded(false), 500);
      return () => clearTimeout(timer);
    }
  }, [isStreaming]);

  const hasContent = thinkingText.length > 0 || toolCalls.length > 0;

  if (!hasContent) return null;

  return (
    <div style={{ display: 'flex', padding: '8px 24px 2px 24px', justifyContent: 'flex-start' }}>
      <div style={{ maxWidth: '75%', marginLeft: 48, width: '100%' }}>
        <div
          onClick={() => setExpanded(!expanded)}
          style={{
            display: 'flex', alignItems: 'center', gap: 8,
            padding: '6px 12px',
            background: 'var(--toolcall-bg)',
            borderRadius: 8,
            border: '1px solid var(--border-color)',
            cursor: 'pointer', userSelect: 'none',
            fontSize: 12,
            color: 'var(--text-secondary)',
          }}
        >
          <BulbOutlined style={{ fontSize: 13, color: '#faad14' }} />
          <span style={{ fontWeight: 500, flex: 1 }}>
            {isStreaming ? 'AI 思考中...' : 'AI 的思考过程'}
          </span>
          {isStreaming && <Spin size="small" style={{ fontSize: 10 }} />}
          {expanded ? '▾' : '▸'}
        </div>
        {expanded && (
          <div style={{
            marginTop: 4,
            padding: '8px 12px',
            background: 'var(--thinking-bg)',
            borderRadius: 8,
            border: '1px solid var(--border-color)',
            fontSize: 13, lineHeight: 1.6,
            color: 'var(--text-tertiary)',
          }}>
            {/* Tool calls */}
            {toolCalls.map(tc => (
              <ToolCallItem key={tc.id} toolCall={tc} />
            ))}
            {/* Thinking text */}
            {thinkingText && (
              <div style={{ whiteSpace: 'pre-wrap', wordBreak: 'break-word' }}>
                {thinkingText}
              </div>
            )}
          </div>
        )}
      </div>
    </div>
  );
};

// Message bubble
const MessageBubble: React.FC<{ message: DisplayMessage; onFilePreview?: (file: UploadedFile) => void }> = ({ message, onFilePreview }) => {
  const isUser = message.role === 'user';
  const fileList = (message as DisplayMessage).files || [];
  const hasFiles = fileList.length > 0;

  // Extract thinking/tool calls from persisted metadata
  let persistedThinking: string | null = null;
  let persistedToolCalls: ToolCallDisplay[] | null = null;
  if (!isUser && message.metadata) {
    try {
      const meta = typeof message.metadata === 'string'
        ? JSON.parse(message.metadata) : message.metadata;
      if (meta.type === 'chat') {
        persistedThinking = meta.thinking || null;
        persistedToolCalls = (meta.toolCalls || []).map((tc: any) => ({
          id: tc.id, name: tc.name, result: tc.result || '', completed: true,
        }));
      }
    } catch { /* ignore */ }
  }

  return (
    <>
      {!isUser && (persistedThinking || (persistedToolCalls && persistedToolCalls.length > 0)) && (
        <ThoughtProcess
          thinkingText={persistedThinking || ''}
          toolCalls={persistedToolCalls || []}
          isStreaming={false}
        />
      )}
      <div style={{ display: 'flex', padding: '12px 24px', justifyContent: isUser ? 'flex-end' : 'flex-start' }}>
        <div style={{ display: 'flex', maxWidth: '75%', gap: 12, flexDirection: isUser ? 'row-reverse' : 'row', alignItems: 'flex-start' }}>
          <div style={{
            width: 36, height: 36, borderRadius: 8,
            display: 'flex', alignItems: 'center', justifyContent: 'center',
            flexShrink: 0, background: isUser ? 'var(--primary-color)' : 'var(--hover-bg)',
            color: isUser ? '#fff' : 'var(--text-tertiary)', fontSize: 16,
          }}>
            {isUser ? <UserOutlined /> : <RobotOutlined />}
          </div>
          <div style={{
            padding: '10px 16px', borderRadius: 12,
            background: isUser ? 'var(--primary-color)' : 'var(--msg-agent-bg)',
            color: isUser ? '#fff' : 'var(--text-color)',
            border: isUser ? 'none' : '1px solid var(--border-color)',
            fontSize: 14, lineHeight: 1.6, wordBreak: 'break-word',
            maxWidth: 'calc(100% - 48px)',
          }}>
            {hasFiles && (
              <div style={{
                marginBottom: 8, padding: '6px 10px',
                background: isUser ? 'rgba(255,255,255,0.15)' : 'var(--toolcall-bg)',
                borderRadius: 6, fontSize: 12,
              }}>
                {fileList.map((f, i) => (
                  <div key={i} style={{ display: 'flex', alignItems: 'center', gap: 6, marginBottom: i < fileList.length - 1 ? 4 : 0 }}>
                    <PaperClipOutlined style={{ fontSize: 12, opacity: 0.8 }} />
                    <span
                      onClick={() => onFilePreview?.(f)}
                      style={{
                        opacity: 0.9, cursor: 'pointer',
                        textDecoration: 'underline',
                        textDecorationStyle: 'dotted',
                        textUnderlineOffset: 2,
                      }}
                    >
                      {f.title}
                    </span>
                    <span style={{ opacity: 0.6, fontSize: 11 }}>
                      {isUser ? '(已上传)' : '(AI 生成)'}
                    </span>
                  </div>
                ))}
              </div>
            )}
            {isUser ? (
              <span style={{ whiteSpace: 'pre-wrap' }}>{message.content}</span>
            ) : (
              <div className="markdown-content">
                <ReactMarkdown>{message.content}</ReactMarkdown>
              </div>
            )}
          </div>
        </div>
      </div>
    </>
  );
};

// Streaming bubble
const StreamingBubble: React.FC<{ content: string }> = ({ content }) => {
  return (
    <div style={{ display: 'flex', padding: '12px 24px', justifyContent: 'flex-start' }}>
      <div style={{ display: 'flex', maxWidth: '75%', gap: 12, alignItems: 'flex-start' }}>
        <div style={{
          width: 36, height: 36, borderRadius: 8,
          display: 'flex', alignItems: 'center', justifyContent: 'center',
          flexShrink: 0, background: 'var(--hover-bg)', color: 'var(--text-tertiary)', fontSize: 16,
        }}>
          <RobotOutlined />
        </div>
        <div style={{
          padding: '10px 16px', borderRadius: 12,
          background: 'var(--msg-agent-bg)', border: '1px solid var(--border-color)',
          fontSize: 14, lineHeight: 1.6, wordBreak: 'break-word',
          maxWidth: 'calc(100% - 48px)',
        }}>
          <div className="markdown-content">
            <ReactMarkdown>{content}</ReactMarkdown>
          </div>
          <span style={{
            display: 'inline-block', width: 8, height: 14,
            background: '#1677ff', marginLeft: 2,
            animation: 'blink 1s step-end infinite',
            verticalAlign: 'text-bottom',
          }} />
        </div>
      </div>
    </div>
  );
};

// Rewrite message bubble with document-level feedback and preview
const formatFileSize = (bytes: number | null): string => {
  if (!bytes) return '';
  if (bytes < 1024) return bytes + ' B';
  if (bytes < 1024 * 1024) return (bytes / 1024).toFixed(1) + ' KB';
  return (bytes / (1024 * 1024)).toFixed(1) + ' MB';
};

const getFileTypeInfo = (ext: string): { icon: React.ReactNode; color: string } => {
  const videoExts = ['.mp4', '.avi', '.mov', '.mkv', '.webm', '.wmv', '.flv'];
  const audioExts = ['.mp3', '.wav', '.m4a', '.aac', '.ogg', '.wma', '.flac'];
  const imageExts = ['.jpg', '.jpeg', '.png', '.gif', '.bmp', '.webp', '.svg'];
  const docExts = ['.pdf', '.doc', '.docx', '.txt', '.md', '.csv', '.xlsx', '.pptx'];

  const e = ext.toLowerCase();
  if (videoExts.includes(e)) return { icon: <VideoCameraOutlined />, color: '#722ed1' };
  if (audioExts.includes(e)) return { icon: <AudioOutlined />, color: '#13c2c2' };
  if (imageExts.includes(e)) return { icon: <PictureOutlined />, color: '#52c41a' };
  if (docExts.includes(e)) return { icon: <FileTextOutlined />, color: '#1677ff' };
  return { icon: <FileTextOutlined />, color: '#666' };
};

const getStatusColor = (status: string): string => {
  switch (status) {
    case 'completed': return '#52c41a';
    case 'processing': return '#faad14';
    case 'error': return '#ff4d4f';
    default: return '#999';
  }
};

const getStatusLabel = (status: string): string => {
  switch (status) {
    case 'completed': return '已完成';
    case 'processing': return '转写中';
    case 'error': return '失败';
    default: return status;
  }
};

export default DialoguePanel;
