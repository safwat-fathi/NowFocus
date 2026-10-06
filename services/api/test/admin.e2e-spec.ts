import { DataSource } from 'typeorm';
import { signSession } from '../src/admin/admin.guard.js';
import { createTestApp } from './helpers/app.js';

const TOKEN = 'a'.repeat(32);
const auth = { Authorization: `Bearer ${TOKEN}` };

describe('admin dashboard', () => {
  it('is disabled (404) without ADMIN_TOKEN', async () => {
    delete process.env.ADMIN_TOKEN;
    const t = await createTestApp();
    try {
      expect((await t.http.get('/v1/admin/waitlist')).status).toBe(404);
    } finally {
      await t.close();
    }
  });

  it('rejects missing/wrong tokens, lists and deletes entries with the right one', async () => {
    process.env.ADMIN_TOKEN = TOKEN;
    const t = await createTestApp();
    try {
      const email = `admin-test-${Date.now()}@example.com`;
      const { id } = (await t.http.post('/v1/waitlist').send({ email, platforms: ['android'], featureRequest: 'dark mode' })).body;

      expect((await t.http.get('/v1/admin/waitlist')).status).toBe(401);

      expect((await t.http.post('/v1/admin/session').set('Authorization', 'Bearer nope')).status).toBe(401);
      const { token } = (await t.http.post('/v1/admin/session').set(auth)).body;
      expect((await t.http.get('/v1/admin/waitlist').set('Authorization', `Bearer ${token}`)).status).toBe(200);
      expect((await t.http.get('/v1/admin/waitlist').set('Authorization', `Bearer ${token.slice(0, -1)}0`)).status).toBe(401);
      expect((await t.http.get('/v1/admin/waitlist').set('Authorization', `Bearer ${signSession(TOKEN, Date.now() - 1)}`)).status).toBe(401);
      expect((await t.http.get('/v1/admin/waitlist').set('Authorization', 'Bearer nope')).status).toBe(401);

      const list = await t.http.get('/v1/admin/waitlist').set(auth);
      expect(list.status).toBe(200);
      expect(list.body.find((r: { id: string }) => r.id === id)).toMatchObject({ email, platforms: ['android'], featureRequest: 'dark mode' });

      expect((await t.http.delete(`/v1/admin/waitlist/${id}`).set(auth)).status).toBe(204);
      expect((await t.http.delete(`/v1/admin/waitlist/${id}`).set(auth)).status).toBe(404);

      const page = await t.http.get('/admin');
      expect(page.status).toBe(200);
      expect(page.headers['x-frame-options']).toBe('DENY');
    } finally {
      await t.app.get(DataSource).query("delete from waitlist where email like 'admin-test-%'");
      delete process.env.ADMIN_TOKEN;
      await t.close();
    }
  });
});
