import { createRouter, createWebHistory } from 'vue-router';
import { useAuth } from '../store/auth';
import Login from '../views/Login.vue';
import Dashboard from '../views/Dashboard.vue';
import Forbidden from '../views/Forbidden.vue';
export const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/login', component: Login },
    { path: '/dashboard', component: Dashboard },
    { path: '/admin', component: Dashboard, meta: { admin: true } },
    { path: '/403', component: Forbidden },
    { path: '/:pathMatch(.*)*', redirect: '/dashboard' },
  ],
});
router.beforeEach(async (to) => {
  const auth = useAuth();
  await auth.bootstrap();
  if (!auth.user && to.path !== '/login') return '/login';
  if (auth.user && to.path === '/login') return '/dashboard';
  if (to.meta.admin && !auth.admin) return '/403';
});
