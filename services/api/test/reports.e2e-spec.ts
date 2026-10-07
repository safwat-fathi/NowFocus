import { createTestApp } from './helpers/app.js';

const ok = { message: 'it crashes', platform: 'windows', appVersion: '0.4.2', osVersion: 'windows x86_64' };

describe('POST /v1/reports', () => {
  it('accepts a valid report anonymously and rejects bad ones', async () => {
    const t = await createTestApp();
    try {
      expect((await t.http.post('/v1/reports').send(ok)).status).toBe(201);
      expect((await t.http.post('/v1/reports').send({ ...ok, message: '' })).status).toBe(400);
      expect((await t.http.post('/v1/reports').send({ ...ok, message: 'x'.repeat(4001) })).status).toBe(400);
      expect((await t.http.post('/v1/reports').send({ ...ok, platform: 'ios' })).status).toBe(400);
    } finally {
      await t.close();
    }
  });

  it('is throttled per client', async () => {
    const t = await createTestApp({ throttle: true });
    try {
      const post = () => t.http.post('/v1/reports').set('X-Forwarded-For', '203.0.113.50').send(ok);
      for (let i = 0; i < 5; i++) expect((await post()).status).toBe(201);
      expect((await post()).status).toBe(429);
    } finally {
      await t.close();
    }
  });
});
