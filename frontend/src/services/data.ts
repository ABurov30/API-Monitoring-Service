import { http } from './http';
export interface ApiRecord {
  id: string;
  createdAt: string;
  success: boolean;
  payload: string;
}
export interface DataInput {
  success: boolean;
  payload: string;
}
export const api = {
  status: async () => (await http.get<{ status: string; checkedAt: string }>('/status')).data,
  list: async () => (await http.get<{ items: ApiRecord[]; total: number }>('/data')).data,
  create: async (body: DataInput) => (await http.post<ApiRecord>('/data', body)).data,
  update: async (id: string, body: DataInput) =>
    (await http.put<ApiRecord>(`/data/${id}`, body)).data,
  remove: async (id: string) => {
    await http.delete(`/data/${id}`);
  },
};
export function formatPayload(payload: string) {
  try {
    return JSON.stringify(JSON.parse(payload), null, 2);
  } catch {
    return payload;
  }
}
