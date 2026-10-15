import { beforeEach, expect, it, vi } from 'vitest';
import { createPinia, setActivePinia } from 'pinia';
const refresh = vi.hoisted(() => vi.fn());
let callback: (s: unknown) => void = () => {};
vi.mock('../services/http', () => ({
  refresh,
  onSession: (cb: typeof callback) => {
    callback = cb;
  },
  login: vi.fn(),
  logout: vi.fn(),
}));
import { useAuth } from './auth';
import { useTheme } from './theme';
beforeEach(() => {
  setActivePinia(createPinia());
  localStorage.clear();
  refresh.mockReset();
});
it('bootstraps once and reacts to session and role changes', async () => {
  refresh.mockResolvedValue({});
  const a = useAuth();
  await Promise.all([a.bootstrap(), a.bootstrap()]);
  await a.bootstrap();
  expect(refresh).toHaveBeenCalledTimes(1);
  callback({ user: { username: 'admin', roles: ['ROLE_ADMIN'] } });
  expect(a.admin).toBe(true);
  callback(null);
  expect(a.user).toBeNull();
  expect(a.admin).toBe(false);
});
it('failed bootstrap stays anonymous', async () => {
  refresh.mockRejectedValue(new Error());
  const a = useAuth();
  await a.bootstrap();
  expect(a.user).toBeNull();
});
it('restores and toggles both themes', () => {
  localStorage.setItem('theme', 'dark');
  const t = useTheme();
  expect(document.documentElement.classList.contains('dark')).toBe(true);
  t.toggle();
  expect(localStorage.getItem('theme')).toBe('light');
  t.toggle();
  expect(t.dark).toBe(true);
  expect(localStorage.getItem('theme')).toBe('dark');
});
