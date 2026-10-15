import { computed, ref } from 'vue';
import { defineStore } from 'pinia';
import * as session from '../services/http';
export const useAuth = defineStore('auth', () => {
  const user = ref<session.User | null>(null);
  const bootstrapped = ref(false);
  let bootstrapFlight: Promise<void> | null = null;
  session.onSession((value) => {
    user.value = value?.user || null;
  });
  const admin = computed(() => user.value?.roles.includes('ROLE_ADMIN') || false);
  function bootstrap() {
    if (bootstrapped.value) return Promise.resolve();
    if (!bootstrapFlight)
      bootstrapFlight = session
        .refresh()
        .then(() => {})
        .catch(() => {})
        .finally(() => {
          bootstrapped.value = true;
          bootstrapFlight = null;
        });
    return bootstrapFlight;
  }
  return { user, admin, bootstrap, login: session.login, logout: session.logout };
});
