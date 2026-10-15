import axios, { AxiosError, type InternalAxiosRequestConfig } from 'axios';
export interface User {
  id: string;
  username: string;
  roles: string[];
}
export interface Session {
  accessToken: string;
  tokenType: string;
  expiresIn: number;
  user: User;
}
type RetryConfig = InternalAxiosRequestConfig & {
  retried?: boolean;
  sessionEpoch?: number;
  tokenVersion?: number;
};
export const authHttp = axios.create({
  baseURL: import.meta.env.VITE_API_URL || '/api',
  withCredentials: true,
  timeout: 12000,
});
export const http = axios.create({
  baseURL: import.meta.env.VITE_API_URL || '/api',
  withCredentials: true,
  timeout: 12000,
});
let access: string | null = null;
let epoch = 0;
let tokenVersion = 0;
let refreshFlight: Promise<Session> | null = null;
let refreshEpoch = -1;
let authQueue: Promise<unknown> = Promise.resolve();
let listener: (session: Session | null) => void = () => {};
export function onSession(callback: typeof listener) {
  listener = callback;
}
export function clearSession() {
  epoch++;
  access = null;
  listener(null);
}
export function sessionEpoch() {
  return epoch;
}
function authPost<T>(path: string, body?: unknown, started?: number) {
  const operation = authQueue.then(async () => {
    if (started !== undefined && epoch !== started) throw new Error('Сессия завершена');
    const csrf = await authHttp.get<{ token: string; headerName: string }>('/auth/csrf');
    if (started !== undefined && epoch !== started) throw new Error('Сессия завершена');
    return authHttp.post<T>(path, body, { headers: { [csrf.data.headerName]: csrf.data.token } });
  });
  // Serialize cookie changes, including CSRF bootstrap, even after a failed operation.
  authQueue = operation.catch(() => {});
  return operation;
}
function accept(session: Session, started: number) {
  if (epoch !== started) throw new Error('Сессия завершена');
  access = session.accessToken;
  tokenVersion++;
  listener(session);
  return session;
}
export async function login(username: string, password: string) {
  clearSession();
  const started = epoch;
  return accept(
    (await authPost<Session>('/auth/login', { username, password }, started)).data,
    started,
  );
}
export function refresh() {
  if (!refreshFlight || refreshEpoch !== epoch) {
    const started = epoch;
    refreshEpoch = started;
    const flight = authPost<Session>('/auth/refresh', undefined, started)
      .then((r) => accept(r.data, started))
      .catch((error) => {
        if (epoch === started) clearSession();
        throw error;
      })
      .finally(() => {
        if (refreshFlight === flight) refreshFlight = null;
      });
    refreshFlight = flight;
  }
  return refreshFlight;
}
export async function logout() {
  clearSession();
  const started = epoch;
  try {
    // The queue lets pending login/refresh cookies arrive before revoking them.
    await authPost('/auth/logout');
  } finally {
    if (epoch === started) clearSession();
  }
}
http.interceptors.request.use((config: RetryConfig) => {
  if (config.sessionEpoch !== undefined && config.sessionEpoch !== epoch)
    throw new Error('Сессия завершена');
  config.sessionEpoch = epoch;
  config.tokenVersion = tokenVersion;
  if (access) config.headers.Authorization = `Bearer ${access}`;
  return config;
});
http.interceptors.response.use(
  (response) => {
    const config = response.config as RetryConfig;
    if (config.sessionEpoch !== epoch) return Promise.reject(new Error('Сессия завершена'));
    return response;
  },
  async (error: AxiosError) => {
    const config = error.config as RetryConfig | undefined;
    if (
      error.response?.status !== 401 ||
      !config ||
      config.retried ||
      config.sessionEpoch !== epoch
    )
      throw error;
    config.retried = true;
    if (config.tokenVersion === tokenVersion) await refresh();
    if (config.sessionEpoch !== epoch) throw new Error('Сессия завершена');
    return http.request(config);
  },
);
export function errorMessage(error: unknown) {
  if (axios.isAxiosError(error)) {
    const fields = error.response?.data?.fieldErrors;
    if (fields) return Object.values(fields).join('; ');
    return (
      error.response?.data?.message ||
      (error.response ? 'Не удалось выполнить запрос' : 'Нет соединения с сервером')
    );
  }
  return error instanceof Error ? error.message : 'Неизвестная ошибка';
}
