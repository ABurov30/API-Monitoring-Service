import { createApp } from 'vue';
import { createPinia } from 'pinia';
import {
  ElAlert,
  ElButton,
  ElConfigProvider,
  ElDialog,
  ElEmpty,
  ElInput,
  ElSkeleton,
  ElSwitch,
  ElTag,
} from 'element-plus';
import 'element-plus/dist/index.css';
import 'element-plus/theme-chalk/dark/css-vars.css';
import './style.css';
import App from './App.vue';
import { router } from './router';
const app = createApp(App).use(createPinia()).use(router);
for (const component of [
  ElAlert,
  ElButton,
  ElConfigProvider,
  ElDialog,
  ElEmpty,
  ElInput,
  ElSkeleton,
  ElSwitch,
  ElTag,
])
  app.use(component);
app.mount('#app');
