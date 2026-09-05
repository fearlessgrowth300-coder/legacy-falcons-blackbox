// Read-only CDP network metadata capture. Requires Node 22+ and an explicitly
// enabled BlackBox debug WebView forwarded to a localhost TCP port with adb.
import { open } from 'node:fs/promises';
import { summarizeUrl, summarizeFailure } from './request-summary.mjs';

const [portArg = '9223', secondsArg = '60', output = 'twitch-request-summary.jsonl'] = process.argv.slice(2);
const port = Number(portArg), seconds = Number(secondsArg);
if (!Number.isInteger(port) || port < 1024 || port > 65535 || !Number.isFinite(seconds) || seconds < 1 || seconds > 300) {
  throw new Error('Usage: node capture-webview.mjs [port 1024-65535] [seconds 1-300] [new output file]');
}
if (typeof WebSocket !== 'function') throw new Error('Node 22+ is required.');
const targets = await fetch('http://127.0.0.1:' + port + '/json/list', {signal: AbortSignal.timeout(5000)}).then(r => r.json());
const candidates = targets.filter(t => t.type === 'page' && summarizeUrl(t.url));
if (candidates.length !== 1) throw new Error('Expected exactly one Twitch WebView; found ' + candidates.length + '. Open only the intended signup WebView.');
const endpoint = new URL(candidates[0].webSocketDebuggerUrl);
if (!['localhost', '127.0.0.1'].includes(endpoint.hostname) || endpoint.protocol !== 'ws:') throw new Error('Debugger endpoint is not local.');
endpoint.hostname = '127.0.0.1';
endpoint.port = String(port);
const file = await open(output, 'wx'); // Never overwrite previous evidence.
const socket = new WebSocket(endpoint);
const requests = new Map();
let writes = Promise.resolve();
let writeError;
const record = value => {
  writes = writes.then(() => file.write(JSON.stringify({time: new Date().toISOString(), ...value}) + '\n'))
    .catch(error => { writeError = error; socket.close(); });
};
let ready = false;
const connectTimeout = setTimeout(() => { socket.close(); }, 10000);
const stop = setTimeout(() => socket.close(), seconds * 1000);
process.once('SIGINT', () => socket.close());
socket.addEventListener('open', () => {
  socket.send(JSON.stringify({id: 1, method: 'Network.enable'}));
});
socket.addEventListener('message', event => {
  const message = JSON.parse(String(event.data));
  if (message.id === 1) {
    if (message.error) { console.error('Network diagnostics could not be enabled.'); socket.close(); return; }
    ready = true;
    clearTimeout(connectTimeout);
    console.log('Recording sanitized Twitch request metadata. Reproduce one signup attempt now.');
    return;
  }
  const p = message.params;
  if (!p) return;
  if (message.method === 'Network.requestWillBeSent') {
    const url = summarizeUrl(p.request.url);
    if (url) {
      requests.set(p.requestId, url);
      record({event: 'request', id: p.requestId, ...url, method: p.request.method});
      if (requests.size > 5000) requests.delete(requests.keys().next().value);
    } else requests.delete(p.requestId);
  } else if (message.method === 'Network.responseReceived' && requests.has(p.requestId)) {
    record({event: 'response', id: p.requestId, ...requests.get(p.requestId), status: p.response.status});
  } else if (message.method === 'Network.loadingFailed' && requests.has(p.requestId)) {
    record({event: 'failed', id: p.requestId, ...requests.get(p.requestId), ...summarizeFailure(p)});
    requests.delete(p.requestId);
  } else if (message.method === 'Network.loadingFinished') requests.delete(p.requestId);
});
await new Promise(resolve => {
  socket.addEventListener('close', resolve, {once: true});
  socket.addEventListener('error', resolve, {once: true});
});
clearTimeout(connectTimeout);
clearTimeout(stop);
await writes;
await file.close();
if (writeError) throw writeError;
if (!ready) throw new Error('WebView diagnostics did not become ready; no request-level conclusion is possible.');
console.log('Capture finished. Cookies, passwords, request/response bodies, and URL queries were not recorded.');
