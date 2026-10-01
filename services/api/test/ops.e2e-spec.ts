import { createTestApp } from './helpers/app.js';

describe('health', () => {
  it('GET /healthz is public, checks the database and reports the revision', async () => {
    const t = await createTestApp();
    try {
      const res = await t.http.get('/healthz');
      expect(res.status).toBe(200);
      expect(res.body).toMatchObject({ ok: true, sha: 'dev' });
      expect(typeof res.body.uptime).toBe('number');
    } finally {
      await t.close();
    }
  });
});

describe('throttling behind a reverse proxy', () => {
  it('buckets by the forwarded client IP, not by the proxy', async () => {
    const t = await createTestApp({ throttle: true });
    try {
      const login = (ip: string) =>
        t.http.post('/v1/auth/login').set('X-Forwarded-For', ip).send({ email: 'a@b.io', password: 'wrong-wrong', device: { name: 'x', platform: 'macos' } });
      for (let i = 0; i < 10; i++) expect((await login('203.0.113.7')).status).toBe(401);
      expect((await login('203.0.113.7')).status).toBe(429); // that client is out of budget...
      expect((await login('198.51.100.9')).status).toBe(401); // ...but another client behind the same proxy is not
    } finally {
      await t.close();
    }
  });
});
