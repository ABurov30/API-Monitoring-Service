<script setup lang="ts">
import { onUnmounted, ref, watch } from 'vue';
import type { ApiRecord } from '../services/data';
import { api } from '../services/data';
import { errorMessage, sessionEpoch } from '../services/http';
const props = defineProps<{ modelValue: boolean; record: ApiRecord | null }>();
const emit = defineEmits<{ 'update:modelValue': [value: boolean]; saved: [] }>();
const payload = ref('');
const success = ref(true);
const loading = ref(false);
const error = ref('');
let disposed = false;
onUnmounted(() => {
  disposed = true;
});
watch(
  () => props.modelValue,
  (value) => {
    if (value) {
      payload.value = props.record?.payload || '';
      success.value = props.record?.success ?? true;
      error.value = '';
    }
  },
);
function close() {
  if (!loading.value) emit('update:modelValue', false);
}
async function submit() {
  if (loading.value) return;
  if (!payload.value.trim()) {
    error.value = 'Введите содержимое записи';
    return;
  }
  if (new TextEncoder().encode(payload.value).length > 65536) {
    error.value = 'Максимум 65 536 байт UTF-8';
    return;
  }
  loading.value = true;
  error.value = '';
  const started = sessionEpoch();
  try {
    const body = { success: success.value, payload: payload.value };
    if (props.record) await api.update(props.record.id, body);
    else await api.create(body);
    if (disposed || started !== sessionEpoch()) return;
    emit('saved');
    emit('update:modelValue', false);
  } catch (e) {
    if (!disposed && started === sessionEpoch()) error.value = errorMessage(e);
  } finally {
    loading.value = false;
  }
}
</script>
<template>
  <el-dialog
    :model-value="modelValue"
    :title="record ? 'Редактировать запись' : 'Новая запись'"
    width="min(620px, calc(100vw - 24px))"
    :close-on-click-modal="!loading"
    :close-on-press-escape="!loading"
    :show-close="!loading"
    @update:model-value="close"
    ><el-alert v-if="error" :title="error" type="error" :closable="false" />
    <form @submit.prevent="submit">
      <label for="record-success">Результат</label
      ><el-switch
        id="record-success"
        v-model="success"
        active-text="Успех"
        inactive-text="Ошибка"
        :disabled="loading"
      /><label for="payload">Содержимое</label
      ><el-input
        id="payload"
        v-model="payload"
        type="textarea"
        :rows="10"
        :disabled="loading"
        placeholder="JSON или обычный текст"
      />
      <p class="muted">До 65 536 байт UTF-8. Ручная запись сохраняется в базе.</p>
      <div class="dialog-actions">
        <el-button :disabled="loading" @click="close">Отмена</el-button
        ><el-button type="primary" native-type="submit" :loading="loading">Сохранить</el-button>
      </div>
    </form></el-dialog
  >
</template>
