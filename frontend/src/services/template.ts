import api from './api';

export interface Template {
  id: number;
  name: string;
  filePath: string;
  styleTags: string | null;
  createdAt: string;
  updatedAt: string;
}

export const uploadTemplate = (file: File, name: string, styleTags?: string) => {
  const formData = new FormData();
  formData.append('file', file);
  formData.append('name', name);
  if (styleTags) formData.append('styleTags', styleTags);
  return api.post<{ success: boolean; data: Template }>('/template/upload', formData);
};

export const listTemplates = () =>
  api.get<{ success: boolean; data: Template[] }>('/template/list');

export const deleteTemplate = (id: number) =>
  api.delete(`/template/${id}`);

export const getTemplateDownloadUrl = (id: number) =>
  `/api/template/${id}/file`;

export const getTemplatePreview = (id: number) =>
  api.get<{ success: boolean; data: string }>(`/template/${id}/preview`);