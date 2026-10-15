<script setup lang="ts">
import { useRouter } from 'vue-router';
import { watch } from 'vue';
import { useAuth } from '../store/auth';
import { useTheme } from '../store/theme';
import { errorMessage } from '../services/http';
import { ElMessage } from 'element-plus';
const auth = useAuth();
const theme = useTheme();
const router = useRouter();
watch(
  () => auth.user,
  (user) => {
    if (!user) void router.replace('/login');
  },
);
async function leave() {
  try {
    await auth.logout();
  } catch (e) {
    ElMessage.error(errorMessage(e));
  } finally {
    await router.replace('/login');
  }
}
</script>
<template>
  <div class="app-shell">
    <header class="topbar">
      <router-link class="brand" to="/dashboard"
        ><span class="brand-mark">к</span> курс<span class="brand-divider">/</span
        ><span class="brand-sub">API MONITOR</span></router-link
      >
      <nav aria-label="Пользователь">
        <el-button
          :aria-label="theme.dark ? 'Включить светлую тему' : 'Включить тёмную тему'"
          circle
          @click="theme.toggle"
          >{{ theme.dark ? '☀' : '◐' }}</el-button
        ><template v-if="auth.user"
          ><span class="identity"
            >{{ auth.user.username
            }}<small>{{ auth.admin ? 'Администратор' : 'Наблюдатель' }}</small></span
          ><el-button @click="leave">Выйти</el-button></template
        >
      </nav>
    </header>
    <main><slot /></main>
    <footer>Курс · Мониторинг валютного API <span>EUR → USD, GBP, CHF</span></footer>
  </div>
</template>
