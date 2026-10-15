<script setup lang="ts">
import { ref } from 'vue';
import { useRouter } from 'vue-router';
import { useAuth } from '../store/auth';
import { errorMessage } from '../services/http';
const auth = useAuth();
const router = useRouter();
const username = ref('');
const password = ref('');
const loading = ref(false);
const error = ref('');
async function submit() {
  if (loading.value) return;
  error.value = '';
  if (!username.value.trim() || !password.value) {
    error.value = 'Введите логин и пароль';
    return;
  }
  loading.value = true;
  try {
    await auth.login(username.value.trim(), password.value);
    await router.replace('/dashboard');
  } catch (e) {
    error.value = errorMessage(e);
  } finally {
    loading.value = false;
  }
}
</script>
<template>
  <section class="login-grid">
    <div class="login-intro">
      <p class="eyebrow">ВАЛЮТЫ. СОБЫТИЯ. КОНТРОЛЬ.</p>
      <h1>Держите API<br /><span>в поле зрения.</span></h1>
      <p class="lead">
        Статус сервиса и история запросов к валютному API — в одном рабочем пространстве.
      </p>
      <div class="source-note">
        <span class="live-dot"></span>
        <div>Frankfurter API<small>Курсы обновляются ежедневно · опрос каждую минуту</small></div>
      </div>
      <div class="currency-tiles">
        <span>€<small>EUR / БАЗА</small></span
        ><span>$<small>USD</small></span
        ><span>£<small>GBP</small></span
        ><span>₣<small>CHF</small></span>
      </div>
    </div>
    <section class="panel login-panel">
      <p class="eyebrow">РАБОЧЕЕ ПРОСТРАНСТВО</p>
      <h2>Вход в систему</h2>
      <p class="muted">Используйте свою учётную запись</p>
      <el-alert v-if="error" :title="error" type="error" :closable="false" show-icon />
      <form @submit.prevent="submit">
        <label for="username">Логин</label
        ><el-input
          id="username"
          v-model="username"
          autocomplete="username"
          placeholder="Ваш логин"
          :disabled="loading"
        /><label for="password">Пароль</label
        ><el-input
          id="password"
          v-model="password"
          type="password"
          show-password
          autocomplete="current-password"
          placeholder="Введите пароль"
          :disabled="loading"
        /><el-button class="full-width" type="primary" native-type="submit" :loading="loading"
          >Войти в пространство →</el-button
        >
      </form>
      <p class="login-help">Доступ зависит от роли вашей учётной записи.</p>
    </section>
  </section>
</template>
