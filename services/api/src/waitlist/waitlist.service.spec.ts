import { afterEach, describe, expect, it, vi } from 'vitest';
import { Config } from '../config.js';
import { WaitlistEntry } from '../db/entities.js';
import { WaitlistService } from './waitlist.service.js';

const config = (extra: Partial<Config> = {}): Config =>
  ({ databaseUrl: 'postgres://localhost/test', jwtSecret: 'x'.repeat(32), port: 3000, host: '127.0.0.1', trustProxy: 1, ...extra }) as Config;

const setup = (existing: WaitlistEntry | null, cfg = config()) => {
  const saved: WaitlistEntry[] = [];
  const repo = {
    findOne: vi.fn(async () => existing),
    create: vi.fn((d) => d as WaitlistEntry),
    save: vi.fn(async (e: WaitlistEntry) => { saved.push(e); return e; }),
  };
  return { saved, service: new WaitlistService(repo as never, cfg) };
};

afterEach(() => vi.unstubAllGlobals());

describe('WaitlistService', () => {
  it('saves a normalised signup without a GitHub token', async () => {
    const { saved, service } = setup(null);
    const res = await service.submit({ email: ' TEST@Example.com ', platforms: ['Android', 'windows', 'android'], featureRequest: 'Scheduled lock' });
    expect(res.ok).toBe(true);
    expect(saved[0]).toMatchObject({ email: 'test@example.com', platforms: ['android', 'windows'], featureRequest: 'Scheduled lock', githubIssueUrl: null });
  });

  it('opens a GitHub issue with a quoted, mention-safe body and no email', async () => {
    const fetchMock = vi.fn(async () => ({ ok: true, json: async () => ({ html_url: 'https://github.com/o/r/issues/1' }) }));
    vi.stubGlobal('fetch', fetchMock);
    const { saved, service } = setup(null, config({ githubToken: 't', githubRepo: 'o/r' }));
    const res = await service.submit({ email: 'a@b.co', platforms: ['ios'], featureRequest: 'ping @someone\n# heading' });
    const sent = JSON.parse((fetchMock.mock.calls[0] as unknown as [string, { body: string }])[1].body);
    expect(sent.body).toContain('> ping @​someone\n> # heading');
    expect(sent.body).not.toContain('a@b.co');
    expect(sent.title).not.toContain('@');
    expect(res.githubIssueUrl).toBe('https://github.com/o/r/issues/1');
    expect(saved[0].githubIssueUrl).toBe('https://github.com/o/r/issues/1');
  });

  it('still saves the signup when GitHub fails', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => ({ ok: false, status: 403, text: async () => 'nope' })));
    const { saved, service } = setup(null, config({ githubToken: 't' }));
    const res = await service.submit({ email: 'a@b.co', featureRequest: 'x' });
    expect(res.githubIssueUrl).toBeNull();
    expect(saved).toHaveLength(1);
  });

  it('reuses the row for a repeat email and merges platforms', async () => {
    const existing = { id: 'old', email: 'a@b.co', platforms: ['android'], featureRequest: 'x', githubIssueUrl: 'u' } as WaitlistEntry;
    const { saved, service } = setup(existing);
    const res = await service.submit({ email: 'A@b.co', platforms: ['macos'] });
    expect(res.id).toBe('old');
    expect(saved[0]).toMatchObject({ id: 'old', platforms: ['android', 'macos'], featureRequest: 'x', githubIssueUrl: 'u' });
  });
});
