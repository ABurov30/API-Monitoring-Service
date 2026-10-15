import { createPinia, setActivePinia } from 'pinia';
import { beforeEach, expect, it, vi } from 'vitest';
import { router } from './index';
import { useAuth } from '../store/auth';
beforeEach(() => {
  setActivePinia(createPinia());
  vi.spyOn(useAuth(), 'bootstrap').mockResolvedValue();
});
it('redirects anonymous visitors to login', async () => {
  await router.push('/dashboard');
  expect(router.currentRoute.value.path).toBe('/login');
});
it('forbids USER admin route and redirects logged-in login', async () => {
  useAuth().user = { id: '1', username: 'user', roles: ['ROLE_USER'] };
  await router.push('/admin');
  expect(router.currentRoute.value.path).toBe('/403');
  await router.push('/login');
  expect(router.currentRoute.value.path).toBe('/dashboard');
});
it('allows ADMIN and redirects unknown routes', async () => {
  useAuth().user = { id: '1', username: 'admin', roles: ['ROLE_ADMIN'] };
  await router.push('/admin');
  expect(router.currentRoute.value.path).toBe('/admin');
  await router.push('/missing');
  expect(router.currentRoute.value.path).toBe('/dashboard');
});
