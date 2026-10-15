import { mount, flushPromises } from '@vue/test-utils';
import { defineComponent } from 'vue';
import { beforeEach, expect, it, vi } from 'vitest';
import Login from './Login.vue';
const mocks = vi.hoisted(() => ({ login: vi.fn(), replace: vi.fn() }));
vi.mock('../store/auth', () => ({ useAuth: () => ({ login: mocks.login }) }));
vi.mock('vue-router', () => ({ useRouter: () => ({ replace: mocks.replace }) }));
const stubs = {
  ElInput: defineComponent({
    props: ['modelValue'],
    emits: ['update:modelValue'],
    template: `<input :value="modelValue" @input="$emit('update:modelValue',$event.target.value)" />`,
  }),
  ElButton: defineComponent({ template: `<button><slot /></button>` }),
  ElAlert: defineComponent({ props: ['title'], template: `<p>{{title}}</p>` }),
};
beforeEach(() => vi.resetAllMocks());
it('validates missing credentials', async () => {
  const w = mount(Login, { global: { stubs } });
  await w.find('form').trigger('submit');
  expect(w.text()).toContain('Введите логин и пароль');
  expect(mocks.login).not.toHaveBeenCalled();
  w.unmount();
});
it('logs in and navigates', async () => {
  mocks.login.mockResolvedValue({});
  const w = mount(Login, { global: { stubs } });
  await w.findAll('input')[0].setValue(' user ');
  await w.findAll('input')[1].setValue('password');
  await w.find('form').trigger('submit');
  await flushPromises();
  expect(mocks.login).toHaveBeenCalledWith('user', 'password');
  expect(mocks.replace).toHaveBeenCalledWith('/dashboard');
  w.unmount();
});
it('shows backend error and guards concurrent submit', async () => {
  let reject: (e: Error) => void = () => {};
  mocks.login.mockReturnValue(
    new Promise((_r, j) => {
      reject = j;
    }),
  );
  const w = mount(Login, { global: { stubs } });
  await w.findAll('input')[0].setValue('user');
  await w.findAll('input')[1].setValue('wrong');
  await w.find('form').trigger('submit');
  await w.find('form').trigger('submit');
  expect(mocks.login).toHaveBeenCalledTimes(1);
  reject(new Error('Неверный пароль'));
  await flushPromises();
  expect(w.text()).toContain('Неверный пароль');
  w.unmount();
});
