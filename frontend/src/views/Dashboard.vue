<script setup lang="ts">
import { onMounted, onUnmounted, ref, watch } from 'vue';
import { ElMessageBox } from 'element-plus';
import { useAuth } from '../store/auth';
import { api, formatPayload, type ApiRecord } from '../services/data';
import { errorMessage, sessionEpoch } from '../services/http';
import RecordDialog from '../components/RecordDialog.vue';
const auth = useAuth();
const loading = ref(true);
const refreshing = ref(false);
const error = ref('');
const available = ref(false);
const checkedAt = ref('');
const items = ref<ApiRecord[]>([]);
const total = ref(0);
const loaded = ref(false);
const dialog = ref(false);
const selected = ref<ApiRecord | null>(null);
const viewing = ref<ApiRecord | null>(null);
const deleting = ref<string | null>(null);
let disposed = false;
let confirming = false;
let timer: ReturnType<typeof setInterval>;
watch(
  () => auth.user,
  (user) => {
    if (!user) {
      items.value = [];
      total.value = 0;
      selected.value = null;
      viewing.value = null;
      dialog.value = false;
      available.value = false;
      checkedAt.value = '';
    }
  },
);
async function load() {
  if (refreshing.value) return;
  refreshing.value = true;
  error.value = '';
  const started = sessionEpoch();
  let statusReceived = false;
  try {
    const status = await api.status();
    if (disposed || started !== sessionEpoch()) return;
    available.value = status.status === 'UP';
    statusReceived = true;
    checkedAt.value = status.checkedAt;
    if (auth.admin) {
      const data = await api.list();
      if (disposed || started !== sessionEpoch()) return;
      items.value = data.items;
      total.value = data.total;
    }
    loaded.value = true;
  } catch (e) {
    if (!disposed && started === sessionEpoch()) {
      if (!statusReceived) available.value = false;
      error.value = errorMessage(e);
    }
  } finally {
    if (!disposed) {
      loading.value = false;
      refreshing.value = false;
    }
  }
}
function edit(record: ApiRecord | null) {
  selected.value = record;
  dialog.value = true;
}
async function remove(record: ApiRecord) {
  if (deleting.value || confirming) return;
  const started = sessionEpoch();
  confirming = true;
  try {
    await ElMessageBox.confirm(
      'Запись будет удалена из базы. Уже созданные события сохранятся.',
      'Удалить запись?',
      { confirmButtonText: 'Удалить', cancelButtonText: 'Отмена', type: 'warning' },
    );
  } catch {
    return;
  } finally {
    confirming = false;
  }
  if (disposed || started !== sessionEpoch()) return;
  deleting.value = record.id;
  try {
    await api.remove(record.id);
    if (!disposed && started === sessionEpoch()) await load();
  } catch (e) {
    if (!disposed && started === sessionEpoch()) error.value = errorMessage(e);
  } finally {
    deleting.value = null;
  }
}
function date(value: string) {
  return new Date(value).toLocaleString('ru-RU');
}
onMounted(() => {
  void load();
  timer = setInterval(() => void load(), 60000);
});
onUnmounted(() => {
  disposed = true;
  clearInterval(timer);
  items.value = [];
});
</script>
<template>
  <div class="page-heading">
    <div>
      <p class="eyebrow">РАБОЧЕЕ ПРОСТРАНСТВО / ОБЗОР</p>
      <h1>Мониторинг API<span class="heading-dot">.</span></h1>
      <p class="muted">
        Состояние сервиса{{ auth.admin ? ' и последние результаты опросов' : '' }}
      </p>
    </div>
    <el-button :loading="refreshing" @click="load">↻ Обновить</el-button>
  </div>
  <el-alert
    v-if="error"
    :title="error"
    :description="
      loaded
        ? 'На экране сохранены последние полученные данные. Они могут быть устаревшими.'
        : 'Попробуйте обновить страницу через некоторое время.'
    "
    type="error"
    :closable="false"
    show-icon
  />
  <div v-if="loading" class="panel" role="status">
    <el-skeleton :rows="4" animated /><span>Загрузка состояния…</span>
  </div>
  <template v-else
    ><div class="metrics" :class="{ 'user-metrics': !auth.admin }">
      <section class="panel status-card">
        <p class="eyebrow">HTTP BACKEND</p>
        <h2>
          <span class="live-dot" :class="{ offline: !available }"></span
          >{{ available ? 'Сервис доступен' : 'Статус не определён' }}
        </h2>
        <p class="muted">
          {{ checkedAt ? 'Проверено ' + date(checkedAt) : 'Нет ответа от сервера' }}
        </p>
      </section>
      <template v-if="auth.admin"
        ><section class="panel metric-card">
          <p class="eyebrow">ЗАПИСЕЙ В БАЗЕ</p>
          <strong>{{ total.toLocaleString('ru-RU') }}</strong>
          <p class="muted">Все результаты и ручные записи</p>
        </section>
        <section class="panel metric-card">
          <p class="eyebrow">ИСТОЧНИК КУРСОВ</p>
          <strong class="source-title">Frankfurter <span>↗</span></strong>
          <p class="muted">EUR → USD · GBP · CHF / раз в минуту</p>
        </section></template
      >
    </div>
    <template v-if="auth.admin"
      ><section v-if="items[0]" class="panel latest">
        <div>
          <p class="eyebrow">ПОСЛЕДНЯЯ ЗАПИСЬ</p>
          <el-tag :type="items[0].success ? 'success' : 'danger'">{{
            items[0].success ? 'Успех' : 'Ошибка источника / записи'
          }}</el-tag>
          <p class="muted">{{ date(items[0].createdAt) }}</p>
        </div>
        <pre>{{ formatPayload(items[0].payload) }}</pre>
      </section>
      <section class="panel records">
        <div class="section-heading">
          <div>
            <h2>История записей</h2>
            <p class="muted">Последние 10 · сначала новые</p>
          </div>
          <el-button type="primary" @click="edit(null)">+ Новая запись</el-button>
        </div>
        <el-empty
          v-if="!items.length && !error"
          description="Записей пока нет. Дождитесь опроса или создайте запись."
        />
        <div v-else class="table-scroll">
          <table>
            <thead>
              <tr>
                <th>Создано</th>
                <th>Результат</th>
                <th>Содержимое</th>
                <th><span class="sr-only">Действия</span></th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="record in items" :key="record.id">
                <td class="nowrap">
                  {{ date(record.createdAt)
                  }}<small class="record-id">{{ record.id.slice(0, 8) }}</small>
                </td>
                <td>
                  <el-tag :type="record.success ? 'success' : 'danger'">{{
                    record.success ? 'Успех' : 'Ошибка'
                  }}</el-tag>
                </td>
                <td>
                  <button class="payload-link" @click="viewing = record">
                    {{ record.payload.slice(0, 90) }}
                  </button>
                </td>
                <td class="nowrap">
                  <el-button size="small" @click="edit(record)">Изменить</el-button
                  ><el-button
                    size="small"
                    type="danger"
                    plain
                    :loading="deleting === record.id"
                    :disabled="deleting !== null"
                    @click="remove(record)"
                    >Удалить</el-button
                  >
                </td>
              </tr>
            </tbody>
          </table>
        </div>
      </section>
      <RecordDialog v-model="dialog" :record="selected" @saved="load" /><el-dialog
        :model-value="viewing !== null"
        title="Содержимое записи"
        width="min(760px, calc(100vw - 24px))"
        @update:model-value="viewing = null"
      >
        <pre v-if="viewing" class="payload-view">{{ formatPayload(viewing.payload) }}</pre>
      </el-dialog></template
    >
    <section v-else class="panel observer">
      <span class="observer-symbol">◎</span>
      <h2>Всё под наблюдением</h2>
      <p class="muted">
        Статус обновляется каждую минуту. Доступ к истории и управлению записями есть у
        администратора.
      </p>
    </section></template
  >
</template>
