import { AxiosError, AxiosHeaders, type InternalAxiosRequestConfig } from 'axios';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { flushPromises } from '@vue/test-utils';
function response(config: InternalAxiosRequestConfig, data: unknown, status = 200) {
  return { data, status, statusText: 'OK', headers: {}, config };
}
const session = {
  accessToken: 'token',
  tokenType: 'Bearer',
  expiresIn: 900,
  user: { id: '1', username: 'admin', roles: ['ROLE_ADMIN'] },
};
describe('session transport', () => {
  beforeEach(() => vi.resetModules());
  it('logs in with CSRF and sends Bearer only to business endpoints', async () => {
    const m = await import('./http');
    const listener = vi.fn();
    m.onSession(listener);
    let headers: unknown;
    m.authHttp.defaults.adapter = async (c) => {
      if (c.method === 'get') return response(c, { token: 'csrf', headerName: 'X-XSRF-TOKEN' });
      headers = c.headers.get('X-XSRF-TOKEN');
      return response(c, session);
    };
    await m.login('admin', 'password');
    expect(headers).toBe('csrf');
    expect(listener).toHaveBeenCalledWith(session);
    m.http.defaults.adapter = async (c) => {
      expect(c.headers.get('Authorization')).toBe('Bearer token');
      return response(c, {});
    };
    await m.http.get('/status');
  });
  it('coalesces simultaneous 401 into one refresh and retries once', async () => {
    const m = await import('./http');
    let refreshes = 0;
    let calls = 0;
    m.authHttp.defaults.adapter = async (c) => {
      if (c.method === 'get') return response(c, { token: 'csrf', headerName: 'X-XSRF-TOKEN' });
      refreshes++;
      await new Promise((r) => setTimeout(r, 5));
      return response(c, session);
    };
    m.http.defaults.adapter = async (c) => {
      calls++;
      if (!c.headers.get('Authorization'))
        throw new AxiosError('unauthorized', '401', c, {}, response(c, {}, 401));
      return response(c, { ok: true });
    };
    await Promise.all([m.http.get('/status'), m.http.get('/data')]);
    expect(refreshes).toBe(1);
    expect(calls).toBe(4);
  });
  it('never refreshes 403', async () => {
    const m = await import('./http');
    const adapter = vi.fn();
    m.authHttp.defaults.adapter = adapter;
    m.http.defaults.adapter = async (c) => {
      throw new AxiosError('forbidden', '403', c, {}, response(c, {}, 403));
    };
    await expect(m.http.get('/data')).rejects.toThrow('forbidden');
    expect(adapter).not.toHaveBeenCalled();
  });
  it('reuses the renewed token for a delayed 401 from the same expired token', async () => {
    const m = await import('./http');
    let refreshes = 0;
    let release: () => void = () => {};
    const waiting = new Promise<void>((resolve) => {
      release = resolve;
    });
    m.authHttp.defaults.adapter = async (c) => {
      if (c.method === 'get') return response(c, { token: 'csrf', headerName: 'X-XSRF-TOKEN' });
      refreshes++;
      return response(c, session);
    };
    m.http.defaults.adapter = async (c) => {
      if (!c.headers.get('Authorization')) {
        if (c.url === '/data') await waiting;
        throw new AxiosError('unauthorized', '401', c, {}, response(c, {}, 401));
      }
      return response(c, {});
    };
    const late = m.http.get('/data');
    await m.http.get('/status');
    release();
    await late;
    expect(refreshes).toBe(1);
  });
  it('logout revokes the cookie from a login already in progress', async () => {
    const m = await import('./http');
    const listener = vi.fn();
    m.onSession(listener);
    let release: () => void = () => {};
    const waiting = new Promise<void>((resolve) => {
      release = resolve;
    });
    const posts: string[] = [];
    let loginStarted = false;
    m.authHttp.defaults.adapter = async (c) => {
      if (c.method === 'get') return response(c, { token: 'csrf', headerName: 'X-XSRF-TOKEN' });
      if (c.url === '/auth/login') {
        loginStarted = true;
        await waiting;
      }
      posts.push(c.url!);
      return response(c, session);
    };
    const pending = m.login('admin', 'password');
    const rejected = expect(pending).rejects.toThrow('Сессия завершена');
    await vi.waitFor(() => expect(loginStarted).toBe(true));
    const leaving = m.logout();
    await flushPromises();
    release();
    await rejected;
    await leaving;
    expect(posts).toEqual(['/auth/login', '/auth/logout']);
    expect(listener).not.toHaveBeenCalledWith(session);
  });
  it('failed refresh clears session without an interceptor loop', async () => {
    const m = await import('./http');
    const listener = vi.fn();
    m.onSession(listener);
    let posts = 0;
    m.authHttp.defaults.adapter = async (c) => {
      if (c.method === 'get') return response(c, { token: 'csrf', headerName: 'X-XSRF-TOKEN' });
      posts++;
      throw new AxiosError('expired', '401', c, {}, response(c, {}, 401));
    };
    m.http.defaults.adapter = async (c) => {
      throw new AxiosError('unauthorized', '401', c, {}, response(c, {}, 401));
    };
    await expect(m.http.get('/status')).rejects.toThrow('expired');
    expect(posts).toBe(1);
    expect(listener).toHaveBeenCalledWith(null);
  });
  it('cancels login before its POST if logout happens during CSRF bootstrap', async () => {
    const m = await import('./http');
    let release: () => void = () => {};
    const waiting = new Promise<void>((resolve) => {
      release = resolve;
    });
    let csrfStarted = false;
    const posts: string[] = [];
    m.authHttp.defaults.adapter = async (c) => {
      if (c.method === 'get') {
        csrfStarted = true;
        await waiting;
        return response(c, { token: 'csrf', headerName: 'X-XSRF-TOKEN' });
      }
      posts.push(c.url!);
      return response(c, session);
    };
    const pending = m.login('admin', 'password');
    const rejected = expect(pending).rejects.toThrow('Сессия завершена');
    await vi.waitFor(() => expect(csrfStarted).toBe(true));
    const leaving = m.logout();
    release();
    await rejected;
    await leaving;
    expect(posts).toEqual(['/auth/logout']);
  });
  it('lets a new login finish after a logout already in progress', async () => {
    const m = await import('./http');
    const listener = vi.fn();
    m.onSession(listener);
    let release: () => void = () => {};
    const waiting = new Promise<void>((resolve) => {
      release = resolve;
    });
    let logoutStarted = false;
    const posts: string[] = [];
    m.authHttp.defaults.adapter = async (c) => {
      if (c.method === 'get') return response(c, { token: 'csrf', headerName: 'X-XSRF-TOKEN' });
      if (c.url === '/auth/logout') {
        logoutStarted = true;
        await waiting;
      }
      posts.push(c.url!);
      return response(c, session);
    };
    const leaving = m.logout();
    await vi.waitFor(() => expect(logoutStarted).toBe(true));
    const entering = m.login('admin', 'password');
    release();
    await leaving;
    await entering;
    expect(posts).toEqual(['/auth/logout', '/auth/login']);
    expect(listener).toHaveBeenLastCalledWith(session);
  });
  it('does not retry an old request when logout follows refresh acceptance', async () => {
    const m = await import('./http');
    m.onSession((value) => {
      if (value) m.clearSession();
    });
    m.authHttp.defaults.adapter = async (c) =>
      response(c, c.method === 'get' ? { token: 'csrf', headerName: 'X-XSRF-TOKEN' } : session);
    const requests = vi.fn(async (c: InternalAxiosRequestConfig) => {
      throw new AxiosError('unauthorized', '401', c, {}, response(c, {}, 401));
    });
    m.http.defaults.adapter = requests;
    await expect(m.http.get('/status')).rejects.toThrow('Сессия завершена');
    expect(requests).toHaveBeenCalledTimes(1);
  });
  it('does not retry an original request twice', async () => {
    const m = await import('./http');
    m.authHttp.defaults.adapter = async (c) =>
      response(c, c.method === 'get' ? { token: 'csrf', headerName: 'X-XSRF-TOKEN' } : session);
    let requests = 0;
    m.http.defaults.adapter = async (c) => {
      requests++;
      throw new AxiosError('unauthorized', '401', c, {}, response(c, {}, 401));
    };
    await expect(m.http.get('/status')).rejects.toThrow();
    expect(requests).toBe(2);
  });
  it('logout clears immediately and late refresh cannot restore user', async () => {
    const m = await import('./http');
    const listener = vi.fn();
    m.onSession(listener);
    let release: (v: unknown) => void = () => {};
    const waiting = new Promise((r) => {
      release = r;
    });
    m.authHttp.defaults.adapter = async (c) => {
      if (c.method === 'get') return response(c, { token: 'csrf', headerName: 'X-XSRF-TOKEN' });
      if (c.url === '/auth/refresh') await waiting;
      return response(c, session);
    };
    const pending = m.refresh();
    await vi.waitFor(() => expect(listener).not.toHaveBeenCalled());
    const leaving = m.logout();
    expect(listener).toHaveBeenCalledWith(null);
    release(null);
    await expect(pending).rejects.toThrow('Сессия завершена');
    await leaving;
    await m.logout();
    expect(listener).not.toHaveBeenCalledWith(session);
  });
  it('rejects old business responses after session changes', async () => {
    const m = await import('./http');
    let release: () => void = () => {};
    const waiting = new Promise<void>((r) => {
      release = r;
    });
    m.http.defaults.adapter = async (c) => {
      await waiting;
      return response(c, {});
    };
    const request = m.http.get('/data');
    await Promise.resolve();
    m.clearSession();
    release();
    await expect(request).rejects.toThrow('Сессия завершена');
  });
  it('normalizes network and field errors', async () => {
    const m = await import('./http');
    expect(m.errorMessage(new Error('x'))).toBe('x');
    expect(m.errorMessage(null)).toBe('Неизвестная ошибка');
    expect(m.errorMessage(new AxiosError('network'))).toBe('Нет соединения с сервером');
    const c = { headers: new AxiosHeaders() } as InternalAxiosRequestConfig;
    expect(
      m.errorMessage(
        new AxiosError(
          'bad',
          '400',
          c,
          {},
          response(c, { fieldErrors: { payload: 'required' } }, 400),
        ),
      ),
    ).toBe('required');
    expect(m.errorMessage(new AxiosError('bad', '400', c, {}, response(c, {}, 400)))).toBe(
      'Не удалось выполнить запрос',
    );
    expect(
      m.errorMessage(new AxiosError('bad', '400', c, {}, response(c, { message: 'message' }, 400))),
    ).toBe('message');
  });
});
