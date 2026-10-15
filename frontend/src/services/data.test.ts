import { describe, expect, it, vi } from 'vitest';
import { api, formatPayload } from './data';
import { http } from './http';
describe('data service', () => {
  it('formats JSON and preserves plain text', () => {
    expect(formatPayload('{"a":1}')).toContain('  "a": 1');
    expect(formatPayload('plain')).toBe('plain');
  });
  it('uses explicit CRUD contracts', async () => {
    const get = vi.spyOn(http, 'get').mockResolvedValue({ data: { items: [], total: 42 } });
    const post = vi.spyOn(http, 'post').mockResolvedValue({ data: { id: '1' } });
    const put = vi.spyOn(http, 'put').mockResolvedValue({ data: { id: '1' } });
    const del = vi.spyOn(http, 'delete').mockResolvedValue({});
    expect((await api.list()).total).toBe(42);
    await api.status();
    await api.create({ success: false, payload: 'x' });
    await api.update('1', { success: true, payload: 'y' });
    await api.remove('1');
    expect(get).toHaveBeenCalledWith('/status');
    expect(post).toHaveBeenCalledWith('/data', { success: false, payload: 'x' });
    expect(put).toHaveBeenCalledWith('/data/1', { success: true, payload: 'y' });
    expect(del).toHaveBeenCalledWith('/data/1');
    vi.restoreAllMocks();
  });
});
