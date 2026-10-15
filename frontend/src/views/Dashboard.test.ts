import { mount, flushPromises } from '@vue/test-utils';
import { defineComponent } from 'vue';
import { beforeEach, afterEach, expect, it, vi } from 'vitest';
import Dashboard from './Dashboard.vue';
const mocks = vi.hoisted(() => ({
  auth: { admin: true },
  status: vi.fn(),
  list: vi.fn(),
  remove: vi.fn(),
  confirm: vi.fn(),
  epoch: 0,
}));
vi.mock('../store/auth', () => ({ useAuth: () => mocks.auth }));
vi.mock('../services/data', () => ({
  api: { status: mocks.status, list: mocks.list, remove: mocks.remove },
  formatPayload: (s: string) => s,
}));
vi.mock('../services/http', () => ({
  sessionEpoch: () => mocks.epoch,
  errorMessage: (e: Error) => e.message,
}));
vi.mock('element-plus', () => ({ ElMessageBox: { confirm: mocks.confirm } }));
const row = { id: 'abc-123', createdAt: '2026-10-14T12:00:00Z', success: true, payload: 'sample' };
const stubs = {
  ElButton: defineComponent({ template: '<button><slot /></button>' }),
  ElAlert: defineComponent({
    props: ['title', 'description'],
    template: '<p role="alert">{{title}} {{description}}</p>',
  }),
  ElTag: defineComponent({ template: '<span><slot /></span>' }),
  ElEmpty: defineComponent({ props: ['description'], template: '<p>{{description}}</p>' }),
  ElSkeleton: true,
  RecordDialog: true,
  ElDialog: defineComponent({
    props: ['modelValue'],
    template: '<div v-if="modelValue"><slot /></div>',
  }),
};
function button(w: ReturnType<typeof mount>, text: string) {
  return w.findAll('button').find((b) => b.text().includes(text))!;
}
beforeEach(() => {
  vi.resetAllMocks();
  mocks.epoch = 0;
  mocks.auth.admin = true;
  mocks.status.mockResolvedValue({ status: 'UP', checkedAt: row.createdAt });
  mocks.list.mockResolvedValue({ items: [row], total: 42 });
  mocks.remove.mockResolvedValue(undefined);
});
afterEach(() => vi.useRealTimers());
it('shows loading then full total and latest result', async () => {
  const w = mount(Dashboard, { global: { stubs } });
  expect(w.text()).toContain('Загрузка');
  await flushPromises();
  expect(w.text()).toContain('42');
  expect(w.text()).toContain('sample');
  expect(w.text()).toContain('Сервис доступен');
  await button(w, 'Новая').trigger('click');
  expect(w.findComponent({ name: 'RecordDialog' }).exists()).toBe(true);
  await button(w, 'Изменить').trigger('click');
  await w.find('.payload-link').trigger('click');
  expect(w.find('.payload-view').text()).toBe('sample');
  w.unmount();
});
it('USER never fetches data', async () => {
  mocks.auth.admin = false;
  const w = mount(Dashboard, { global: { stubs } });
  await flushPromises();
  expect(mocks.list).not.toHaveBeenCalled();
  expect(w.text()).not.toContain('ЗАПИСЕЙ В БАЗЕ');
  expect(w.text()).toContain('Всё под наблюдением');
  w.unmount();
});
it('renders empty and initial errors', async () => {
  mocks.list.mockResolvedValue({ items: [], total: 0 });
  const w = mount(Dashboard, { global: { stubs } });
  await flushPromises();
  expect(w.text()).toContain('Записей пока нет');
  w.unmount();
  mocks.status.mockRejectedValue(new Error('Нет соединения'));
  const failure = mount(Dashboard, { global: { stubs } });
  await flushPromises();
  expect(failure.text()).toContain('Нет соединения');
  expect(failure.text()).toContain('Статус не определён');
  failure.unmount();
});
it('preserves stale data and permits recovery', async () => {
  const w = mount(Dashboard, { global: { stubs } });
  await flushPromises();
  mocks.status.mockRejectedValueOnce(new Error('Недоступен'));
  await button(w, 'Обновить').trigger('click');
  await flushPromises();
  expect(w.text()).toContain('могут быть устаревшими');
  expect(w.text()).toContain('sample');
  await button(w, 'Обновить').trigger('click');
  await flushPromises();
  expect(w.find('[role=alert]').exists()).toBe(false);
  w.unmount();
});
it('confirms deletion, cancels safely and refreshes total', async () => {
  const w = mount(Dashboard, { global: { stubs } });
  await flushPromises();
  mocks.confirm.mockRejectedValueOnce('cancel');
  await button(w, 'Удалить').trigger('click');
  await flushPromises();
  expect(mocks.remove).not.toHaveBeenCalled();
  mocks.confirm.mockResolvedValue('confirm');
  mocks.list.mockResolvedValue({ items: [], total: 41 });
  await button(w, 'Удалить').trigger('click');
  await flushPromises();
  expect(mocks.remove).toHaveBeenCalledWith(row.id);
  expect(w.text()).toContain('41');
  w.unmount();
});
it('shows deletion errors and failure records', async () => {
  mocks.list.mockResolvedValue({ items: [{ ...row, success: false }], total: 1 });
  mocks.confirm.mockResolvedValue('confirm');
  mocks.remove.mockRejectedValue(new Error('Удаление не выполнено'));
  const w = mount(Dashboard, { global: { stubs } });
  await flushPromises();
  expect(w.text()).toContain('Ошибка');
  await button(w, 'Удалить').trigger('click');
  await flushPromises();
  expect(w.text()).toContain('Удаление не выполнено');
  w.unmount();
});
it('cleans polling timer on unmount', async () => {
  vi.useFakeTimers();
  const w = mount(Dashboard, { global: { stubs } });
  await flushPromises();
  await vi.advanceTimersByTimeAsync(60000);
  expect(mocks.status).toHaveBeenCalledTimes(2);
  w.unmount();
  await vi.advanceTimersByTimeAsync(60000);
  expect(mocks.status).toHaveBeenCalledTimes(2);
});
it('ignores delayed responses after logout or unmount', async () => {
  let finish: (value: unknown) => void = () => {};
  mocks.status.mockReturnValue(
    new Promise((r) => {
      finish = r;
    }),
  );
  const w = mount(Dashboard, { global: { stubs } });
  mocks.epoch++;
  finish({ status: 'UP', checkedAt: row.createdAt });
  await flushPromises();
  expect(mocks.list).not.toHaveBeenCalled();
  w.unmount();
});
it('retains confirmed HTTP status when only the database listing fails', async () => {
  mocks.list.mockRejectedValue(new Error('Хранилище временно недоступно'));
  const w = mount(Dashboard, { global: { stubs } });
  await flushPromises();
  expect(w.text()).toContain('Сервис доступен');
  expect(w.text()).toContain('Хранилище временно недоступно');
  w.unmount();
});
it('ignores a deletion confirmed after logout and prevents duplicate confirmations', async () => {
  let confirm: () => void = () => {};
  mocks.confirm.mockReturnValue(
    new Promise<void>((resolve) => {
      confirm = resolve;
    }),
  );
  const w = mount(Dashboard, { global: { stubs } });
  await flushPromises();
  await button(w, 'Удалить').trigger('click');
  await button(w, 'Удалить').trigger('click');
  expect(mocks.confirm).toHaveBeenCalledTimes(1);
  mocks.epoch++;
  confirm();
  await flushPromises();
  expect(mocks.remove).not.toHaveBeenCalled();
  w.unmount();
});
it('ignores deletion results after unmount', async () => {
  let finish: () => void = () => {};
  mocks.confirm.mockResolvedValue('confirm');
  mocks.remove.mockReturnValue(
    new Promise<void>((resolve) => {
      finish = resolve;
    }),
  );
  const w = mount(Dashboard, { global: { stubs } });
  await flushPromises();
  await button(w, 'Удалить').trigger('click');
  await flushPromises();
  w.unmount();
  finish();
  await flushPromises();
  expect(mocks.status).toHaveBeenCalledTimes(1);
});
