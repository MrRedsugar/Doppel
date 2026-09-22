import assert from 'node:assert/strict';
import { access, mkdtemp, readFile, rm, writeFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import path from 'node:path';
import { pathToFileURL } from 'node:url';
import { spawnSync } from 'node:child_process';

const candidates = [process.env.DOPPEL_CHROMIUM,
  'C:/Program Files/Google/Chrome/Application/chrome.exe',
  'C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe',
  '/usr/bin/chromium', '/usr/bin/google-chrome'].filter(Boolean);
const browser = (await Promise.all(candidates.map(async file => {
  try { await access(file); return file; } catch { return null; }
}))).find(Boolean);
assert.ok(browser, 'Set DOPPEL_CHROMIUM to an installed Chromium browser; this check downloads nothing');
const bundle = await readFile(new URL('../../android/sdk/src/main/assets/third_party/jina-reader/reader-network.js', import.meta.url), 'utf8');
const folder = await mkdtemp(path.join(tmpdir(), 'doppel-reader-network-'));

function setup() {
  const state = globalThis.networkSmoke = { calls: [], nativeCalls: 0, replyError: false };
  addEventListener('error', event => { document.querySelector('#result').textContent = `FAIL ${event.message}`; });
  addEventListener('unhandledrejection', event => { document.querySelector('#result').textContent = `FAIL ${event.reason}`; });
  setTimeout(() => {
    if (document.querySelector('#result').textContent === 'pending')
      document.querySelector('#result').textContent = 'FAIL pending ' + JSON.stringify({ status: globalThis.DoppelReaderNetworkStatus, state });
  }, 4000);
  globalThis.fetch = () => { state.nativeCalls++; throw new Error('Native fetch used'); };
  XMLHttpRequest.prototype.send = function () { state.nativeCalls++; throw new Error('Native XHR used'); };
  globalThis.DoppelReaderNetwork = { request(raw) {
    const input = JSON.parse(raw);
    state.calls.push(input);
    return JSON.stringify(state.replyError ? { error: 'cancelled' } : {
      status: input.method === 'HEAD' ? 204 : 201,
      headers: { 'content-type': input.headers['content-type'] || 'application/octet-stream', 'x-reader-test': 'bridged' },
      body: input.body,
    });
  } };
}

async function verify() {
  const check = (value, message) => { if (!value) throw new Error(message); };
  if (location.search) {
    check(!DoppelReaderNetworkStatus.ready && /Promise.withResolvers/.test(DoppelReaderNetworkStatus.error), 'Missing API must report failed initialization');
    return;
  }
  check(DoppelReaderNetworkStatus.ready, JSON.stringify(DoppelReaderNetworkStatus));
  const state = networkSmoke;
  const text = JSON.stringify({ documentId: '指南-🌍', catalogName: 'public' });
  const endpoint = 'https://public.example.test/documentPortal/getCenterDocument';
  const response = await fetch(endpoint, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: text });
  check(response.status === 201 && await response.text() === text, 'Fetch JSON body/status changed');
  const first = state.calls[0];
  check(first.url === endpoint && first.method === 'POST' && first.headers['content-type'] === 'application/json', 'Fetch URL/method/headers changed');
  check(new TextDecoder().decode(Uint8Array.from(atob(first.body), c => c.charCodeAt(0))) === text, 'Fetch bytes did not reach bridge');

  const xhr = (body, type, contentType = 'application/json') => new Promise((resolve, reject) => {
    const request = new XMLHttpRequest();
    request.open('POST', endpoint);
    request.responseType = type;
    request.setRequestHeader('Content-Type', contentType);
    request.onload = () => resolve(request);
    request.onerror = () => reject(new Error('XHR failed'));
    request.send(body);
  });
  const json = await xhr(text, 'json');
  check(json.status === 201 && json.response.documentId === '指南-🌍' && json.getResponseHeader('x-reader-test') === 'bridged', 'XHR JSON response/status/headers changed');
  check(state.calls[1].body === first.body && state.calls[1].headers['content-type'] === 'application/json', 'XHR JSON bytes did not reach bridge');
  const binary = new Uint8Array([0, 128, 255, 10]);
  const array = await xhr(binary.buffer, 'arraybuffer', 'application/octet-stream');
  check(Array.from(new Uint8Array(array.response)).join() === Array.from(binary).join(), 'XHR binary body changed');
  check(state.calls[2].body === btoa(String.fromCharCode(...binary)), 'XHR binary bytes did not reach bridge');
  for (const method of ['GET', 'HEAD', 'OPTIONS']) {
    const result = await fetch(endpoint, { method });
    check(await result.text() === '' && state.calls.at(-1).method === method && state.calls.at(-1).body === '', `${method} failed`);
  }
  const full = new Uint8Array(128 * 1024).fill(255);
  check((await (await fetch(endpoint, { method: 'POST', body: full })).arrayBuffer()).byteLength === full.length, '128 KiB body failed');
  const before = state.calls.length;
  for (const init of [{ method: 'PUT', body: text }, { method: 'POST', body: new Uint8Array(128 * 1024 + 1) }]) {
    let rejected = false;
    try { await fetch(endpoint, init); } catch { rejected = true; }
    check(rejected, 'Disallowed request succeeded');
  }
  check(state.calls.length === before, 'Disallowed request reached bridge');
  state.replyError = true;
  for (const operation of [() => fetch(endpoint), () => xhr(text, 'json')]) {
    let rejected = false;
    try { await operation(); } catch { rejected = true; }
    check(rejected, 'Bridge failure became a successful request');
  }
  check(state.nativeCalls === 0, 'Request bypassed bridge');
}

try {
  for (const unsupported of [false, true]) {
    const file = path.join(folder, unsupported ? 'unsupported.html' : 'network.html');
    await writeFile(file, `<!doctype html><meta charset="utf-8"><body><pre id="result">pending</pre><script>(${setup})();${unsupported ? 'Promise.withResolvers=undefined;' : ''}</script><script>${bundle.replaceAll('</script', '<\\/script')}</script><script>(${verify})().then(()=>document.querySelector('#result').textContent='PASS').catch(error=>document.querySelector('#result').textContent='FAIL '+error.stack)</script>`);
    const url = pathToFileURL(file).href + (unsupported ? '?unsupported' : '');
    const result = spawnSync(browser, ['--headless', '--disable-gpu', '--disable-background-networking', '--disable-component-update',
      '--disable-sync', '--no-first-run', '--no-default-browser-check', '--host-resolver-rules=MAP * ~NOTFOUND',
      `--user-data-dir=${path.join(folder, unsupported ? 'unsupported-profile' : 'profile')}`,
      '--virtual-time-budget=5000', '--dump-dom', url], { encoding: 'utf8', timeout: 30000, windowsHide: true });
    assert.equal(result.status, 0, result.error?.message || result.stderr);
    const message = result.stdout.match(/<pre id="result">([\s\S]*?)<\/pre>/)?.[1];
    assert.equal(message, 'PASS', message || result.stderr);
  }
  console.log('Reader network smoke passed: real Chromium fetch/XHR, JSON/binary bytes, limits, errors, missing API; zero native sends.');
} finally {
  assert.ok(path.resolve(folder).startsWith(path.resolve(tmpdir()) + path.sep + 'doppel-reader-network-'));
  await rm(folder, { recursive: true, force: true });
}
