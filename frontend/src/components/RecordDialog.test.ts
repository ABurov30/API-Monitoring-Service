import { mount, flushPromises } from '@vue/test-utils';
import { defineComponent, nextTick } from 'vue';
import { beforeEach, expect, it, vi } from 'vitest';
import RecordDialog from './RecordDialog.vue';
import { api } from '../services/data';
const session = vi.hoisted(() => ({ epoch: 0 }));
vi.mock('../services/http', () => ({
  sessionEpoch: () => session.epoch,
  errorMessage: (error: Error) => error.message,
}));
vi.mock('../services/data', () => ({ api: { create: vi.fn(), update: vi.fn() } }));
const stubs = {
  ElDialog: defineComponent({
    props: ['modelValue'],
    template: `<div v-if="modelValue"><slot /></div>`,
  }),
  ElInput: defineComponent({
    props: ['modelValue'],
    emits: ['update:modelValue'],
    template: `<textarea :value="modelValue" @input="$emit('update:modelValue', $event.target.value)" />`,
  }),
  ElSwitch: defineComponent({
    props: ['modelValue'],
    emits: ['update:modelValue'],
    template: `<input type="checkbox" :checked="modelValue" @change="$emit('update:modelValue', $event.target.checked)" />`,
  }),
  ElButton: defineComponent({
    props: ['nativeType', 'disabled'],
    template: `<button :type="nativeType || 'button'" :disabled="disabled"><slot /></button>`,
  }),
  ElAlert: defineComponent({ props: ['title'], template: `<p role="alert">{{title}}</p>` }),
};
async function dialog(record: unknown = null) {
  const wrapper = mount(RecordDialog, {
    props: { modelValue: false, record: record as null },
    global: { stubs },
  });
  await wrapper.setProps({ modelValue: true });
  return wrapper;
}
beforeEach(() => {
  vi.resetAllMocks();
  session.epoch = 0;
});
it('validates empty and UTF-8 oversized input', async () => {
  const w = await dialog();
  await w.find('form').trigger('submit');
  expect(w.text()).toContain('Введите содержимое');
  await w.find('textarea').setValue('я'.repeat(32769));
  await w.find('form').trigger('submit');
  expect(w.text()).toContain('65 536');
  expect(api.create).not.toHaveBeenCalled();
  w.unmount();
});
it('creates false success and emits saved', async () => {
  vi.mocked(api.create).mockResolvedValue({} as never);
  const w = await dialog();
  await w.find('textarea').setValue('text');
  await w.find('input').setValue(false);
  await w.find('form').trigger('submit');
  await flushPromises();
  expect(api.create).toHaveBeenCalledWith({ success: false, payload: 'text' });
  expect(w.emitted('saved')).toHaveLength(1);
  expect(w.emitted('update:modelValue')).toEqual([[false]]);
  w.unmount();
});
it('edits existing record, blocks duplicate submission and reports failure', async () => {
  let reject: (reason: Error) => void = () => {};
  vi.mocked(api.update).mockReturnValue(
    new Promise((_r, j) => {
      reject = j;
    }),
  );
  const w = await dialog({ id: '1', payload: 'original', success: false });
  expect((w.find('textarea').element as HTMLTextAreaElement).value).toBe('original');
  await w.find('form').trigger('submit');
  await w.find('form').trigger('submit');
  expect(api.update).toHaveBeenCalledTimes(1);
  reject(new Error('Ошибка сервера'));
  await flushPromises();
  expect(w.text()).toContain('Ошибка сервера');
  await nextTick();
  await w.findAll('button')[0].trigger('click');
  expect(w.emitted('update:modelValue')).toEqual([[false]]);
  w.unmount();
});
it('does not emit saved for a response from a previous session', async () => {
  let finish: (value: never) => void = () => {};
  vi.mocked(api.create).mockReturnValue(
    new Promise((resolve) => {
      finish = resolve;
    }),
  );
  const w = await dialog();
  await w.find('textarea').setValue('text');
  await w.find('form').trigger('submit');
  session.epoch++;
  finish({} as never);
  await flushPromises();
  expect(w.emitted('saved')).toBeUndefined();
  expect(w.emitted('update:modelValue')).toBeUndefined();
  w.unmount();
});
