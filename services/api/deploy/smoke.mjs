#!/usr/bin/env node
// Two-device smoke test against a running API: node deploy/smoke.mjs [baseUrl] [--idle=SECONDS]
// register -> second device logs in -> WS nudge -> pull -> idle keepalive -> revoke closes the socket -> delete account.
// Creates one throwaway account and always deletes it. Exits non-zero on the first failed check.
import assert from 'node:assert/strict';
import { randomUUID } from 'node:crypto';
import WebSocket from 'ws';

const args = process.argv.slice(2);
const base = (args.find((a) => !a.startsWith('--')) ?? 'https://api.nowfocus.online').replace(/\/$/, '');
const idle = Number(args.find((a) => a.startsWith('--idle='))?.split('=')[1] ?? 0);
const email = `smoke-${Date.now()}-${randomUUID().slice(0, 8)}@example.com`;
const password = randomUUID();
const wait = (ms) => new Promise((r) => setTimeout(r, ms));
const step = (msg) => console.log(`ok  ${msg}`);

async function call(method, path, { token, body, expect } = {}) {
  const res = await fetch(base + path, {
    method,
    headers: { 'content-type': 'application/json', 'user-agent': 'nowfocus-smoke/1', ...(token ? { authorization: `Bearer ${token}` } : {}) },
    body: body ? JSON.stringify(body) : undefined,
  });
  const text = await res.text();
  const json = text ? JSON.parse(text) : null;
  if (expect !== undefined) assert.equal(res.status, expect, `${method} ${path} -> ${res.status} ${text.slice(0, 200)}`);
  return json;
}

function socket(token) {
  const ws = new WebSocket(base.replace(/^http/, 'ws') + '/ws/device', { headers: { authorization: `Bearer ${token}` } });
  const s = { ws, messages: [], pings: 0, closed: new Promise((res) => ws.on('close', (code) => res(code))) };
  ws.on('message', (m) => s.messages.push(JSON.parse(m.toString())));
  ws.on('ping', () => s.pings++);
  s.opened = new Promise((res, rej) => { ws.on('open', res); ws.on('error', rej); });
  s.next = async (n, ms = 5000) => {
    const end = Date.now() + ms;
    while (s.messages.length < n && Date.now() < end) await wait(25);
    assert.ok(s.messages.length >= n, `expected ${n} websocket message(s), got ${JSON.stringify(s.messages)}`);
  };
  return s;
}

let a;
let deleted = false;
try {
  const health = await call('GET', '/healthz', { expect: 200 });
  assert.equal(health.ok, true);
  step(`healthz ${health.sha} (up ${health.uptime}s)`);

  a = await call('POST', '/v1/auth/register', { body: { email, password, device: { name: 'smoke macos', platform: 'macos' } }, expect: 201 });
  const b = await call('POST', '/v1/auth/login', { body: { email, password, device: { name: 'smoke android', platform: 'android' } }, expect: 200 });
  step('registered device A, logged in device B');

  const sock = socket(b.accessToken);
  await sock.opened;
  await sock.next(1);
  assert.deepEqual(sock.messages[0], { type: 'changes', cursor: 0 });
  step('B websocket connected, got its cursor');

  const id = randomUUID();
  const push = await call('POST', '/v1/sync/push', {
    token: a.accessToken, expect: 200,
    body: { changes: [{ type: 'policy', id, updatedAt: new Date().toISOString(), data: { id, name: 'smoke', domainRules: [{ id: randomUUID(), domain: 'HTTPS://WWW.Example.com/x', includeSubdomains: true, enabled: true }] } }] },
  });
  assert.equal(push.results[0].status, 'applied');
  await sock.next(2);
  assert.deepEqual(sock.messages[1], { type: 'changes', cursor: 1 });
  step('A pushed a policy, B got the websocket nudge');

  const pull = await call('GET', '/v1/sync/pull?cursor=0', { token: b.accessToken, expect: 200 });
  assert.equal(pull.changes.length, 1);
  assert.equal(pull.changes[0].data.domainRules[0].domain, 'example.com', 'domain is stored normalized');
  assert.ok(Math.abs(Date.parse(pull.serverTime) - Date.now()) < 5 * 60_000, 'serverTime is close to local time');
  step('B pulled the policy (domain normalized, serverTime sane)');

  if (idle > 0) {
    console.log(`... idling the socket for ${idle}s (proxy / Cloudflare keepalive check)`);
    await wait(idle * 1000);
    assert.equal(sock.ws.readyState, WebSocket.OPEN, 'socket survived the idle period');
    assert.ok(sock.pings >= 1, 'server pings reached the client');
    step(`socket still open after ${idle}s, ${sock.pings} server ping(s)`);
  }

  await call('DELETE', `/v1/devices/${b.device.id}`, { token: a.accessToken, expect: 204 });
  assert.equal(await sock.closed, 4403);
  assert.deepEqual(sock.messages.at(-1), { type: 'device_revoked' });
  await call('GET', '/v1/sync/pull', { token: b.accessToken, expect: 401 });
  step('A revoked B: socket closed with 4403, B tokens dead');

  await call('POST', '/v1/me/delete', { token: a.accessToken, body: { password }, expect: 204 });
  deleted = true;
  await call('GET', '/v1/me', { token: a.accessToken, expect: 401 });
  step('account deleted');
  console.log('SMOKE PASSED');
} catch (e) {
  console.error(`FAILED: ${e.message}`);
  process.exitCode = 1;
} finally {
  if (a && !deleted) {
    try { await call('POST', '/v1/me/delete', { token: a.accessToken, body: { password } }); console.log('cleaned up the smoke account'); }
    catch { console.error(`could not delete smoke account ${email}; remove it by hand`); }
  }
}
