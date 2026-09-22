import { FetchInterceptor } from '@mswjs/interceptors/fetch/web';
import { XMLHttpRequestInterceptor } from '@mswjs/interceptors/XMLHttpRequest/web';

const methods = new Set(['GET', 'HEAD', 'POST', 'OPTIONS']);
const interceptors = [];

function encode(bytes) {
  let binary = '';
  for (let i = 0; i < bytes.length; i += 32768)
    binary += String.fromCharCode(...bytes.subarray(i, i + 32768));
  return btoa(binary);
}

async function requestThroughBridge({ request, controller }) {
  try {
    if (!methods.has(request.method)) throw new Error('Unsupported public request method');
    const body = new Uint8Array(await request.arrayBuffer());
    if (body.length > 128 * 1024) throw new Error('Public request body exceeds 128 KiB');
    if (request.signal.aborted) throw new Error('Public request cancelled');
    const result = JSON.parse(globalThis.DoppelReaderNetwork.request(JSON.stringify({
      url: request.url, method: request.method,
      headers: Object.fromEntries(request.headers), body: encode(body),
    })));
    if (result.error) throw new Error('Public reference request failed');
    if (!Number.isInteger(result.status) || result.status < 200 || result.status > 599 ||
        typeof result.body !== 'string' || result.body.length > Math.ceil(5 * 1024 * 1024 / 3) * 4)
      throw new Error('Invalid public response');
    const bytes = Uint8Array.from(atob(result.body), c => c.charCodeAt(0));
    if (bytes.length > 5 * 1024 * 1024) throw new Error('Public response exceeds 5 MiB');
    controller.respondWith(new Response(
      request.method === 'HEAD' || [204, 205, 304].includes(result.status) ? null : bytes,
      { status: result.status, headers: result.headers },
    ));
  } catch (error) {
    controller.errorWith(error);
  }
}

try {
  if (typeof Promise.withResolvers !== 'function' || typeof URL.canParse !== 'function')
    throw new Error('WebView requires Promise.withResolvers and URL.canParse');
  if (typeof globalThis.DoppelReaderNetwork?.request !== 'function')
    throw new Error('Public network bridge unavailable');
  for (const interceptor of [new FetchInterceptor(), new XMLHttpRequestInterceptor()]) {
    interceptors.push(interceptor);
    interceptor.on('request', requestThroughBridge);
    interceptor.apply();
    if (interceptor.readyState !== 'ACTIVE') throw new Error('WebView network API unavailable');
  }
  globalThis.DoppelReaderNetworkStatus = { ready: true };
} catch (error) {
  for (const interceptor of interceptors) interceptor.dispose();
  globalThis.DoppelReaderNetworkStatus = { ready: false, error: error.message };
}
