import { expect, it } from 'vitest';
import { flushPromises } from '@vue/test-utils';
import { AxiosError } from 'axios';
import { authHttp } from './services/http';
it('renders the login screen after an anonymous bootstrap', async () => {
  document.body.innerHTML = '<div id="app"></div>';
  authHttp.defaults.adapter = async (config) => {
    if (config.method === 'get')
      return {
        config,
        data: { token: 'csrf', headerName: 'X-XSRF-TOKEN' },
        status: 200,
        statusText: 'OK',
        headers: {},
      };
    throw new AxiosError('anonymous');
  };
  await import('./main');
  await flushPromises();
  const { router } = await import('./router');
  await router.isReady();
  await flushPromises();
  expect(document.body.textContent).toContain('Вход в систему');
  expect(document.querySelector('input[autocomplete="username"]')).not.toBeNull();
});
