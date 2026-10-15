import { mount, flushPromises } from '@vue/test-utils';
import { createPinia, setActivePinia } from 'pinia';
import { defineComponent } from 'vue';
import { beforeEach, expect, it, vi } from 'vitest';
import AppLayout from './AppLayout.vue';
import { useAuth } from '../store/auth';
const mocks = vi.hoisted(() => ({ replace: vi.fn(), error: vi.fn() }));
vi.mock('vue-router', () => ({ useRouter: () => ({ replace: mocks.replace }) }));
vi.mock('element-plus', () => ({ ElMessage: { error: mocks.error } }));
beforeEach(() => {
  setActivePinia(createPinia());
  localStorage.clear();
  vi.clearAllMocks();
});
const stubs = {
  RouterLink: defineComponent({ template: '<a><slot /></a>' }),
  ElButton: defineComponent({ template: '<button><slot /></button>' }),
};
it('shows user role, toggles theme and logs out', async () => {
  const auth = useAuth();
  auth.user = { id: '1', username: 'admin', roles: ['ROLE_ADMIN'] };
  vi.spyOn(auth, 'logout').mockResolvedValue();
  const w = mount(AppLayout, { global: { stubs } });
  expect(w.text()).toContain('Администратор');
  await w.findAll('button')[0].trigger('click');
  expect(localStorage.getItem('theme')).toBe('dark');
  await w.findAll('button')[0].trigger('click');
  expect(localStorage.getItem('theme')).toBe('light');
  await w.findAll('button')[1].trigger('click');
  await flushPromises();
  expect(auth.logout).toHaveBeenCalled();
  expect(mocks.replace).toHaveBeenCalledWith('/login');
  w.unmount();
});
it('reports logout failure while still leaving protected route', async () => {
  const auth = useAuth();
  auth.user = { id: '1', username: 'user', roles: ['ROLE_USER'] };
  vi.spyOn(auth, 'logout').mockRejectedValue(new Error('Connection error'));
  const w = mount(AppLayout, { global: { stubs } });
  expect(w.text()).toContain('Наблюдатель');
  await w.findAll('button')[1].trigger('click');
  await flushPromises();
  expect(mocks.error).toHaveBeenCalledWith('Connection error');
  expect(mocks.replace).toHaveBeenCalledWith('/login');
  w.unmount();
});
it('redirects immediately when a refresh failure clears the session', async () => {
  const auth = useAuth();
  auth.user = { id: '1', username: 'user', roles: ['ROLE_USER'] };
  const w = mount(AppLayout, { global: { stubs } });
  auth.user = null;
  await flushPromises();
  expect(mocks.replace).toHaveBeenCalledWith('/login');
  w.unmount();
});
