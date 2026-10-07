import { afterEach, describe, expect, it, vi } from 'vitest';
import { Config } from '../config.js';
import { IssueReport } from '../db/entities.js';
import { ReportsService } from './reports.service.js';

const config = (extra: Partial<Config> = {}): Config =>
  ({ databaseUrl: 'postgres://localhost/test', jwtSecret: 'x'.repeat(32), port: 3000, host: '127.0.0.1', trustProxy: 1, ...extra }) as Config;

const setup = (cfg = config()) => {
  const saved: IssueReport[] = [];
  const repo = { create: vi.fn((d) => d as IssueReport), save: vi.fn(async (e: IssueReport) => { saved.push(e); return e; }) };
  return { saved, service: new ReportsService(repo as never, cfg) };
};
const dto = { message: ' it crashes @bob ', contact: ' ', platform: 'android', appVersion: '0.7', osVersion: 'Android 14' };

afterEach(() => vi.unstubAllGlobals());

describe('ReportsService', () => {
  it('stores a trimmed report without a GitHub token', async () => {
    const { saved, service } = setup();
    await service.submit(dto);
    expect(saved[0]).toMatchObject({ message: 'it crashes @bob', contact: null, platform: 'android', githubIssueUrl: null });
  });

  it('opens a mention-safe GitHub issue, version fields included', async () => {
    const fetchMock = vi.fn(async () => ({ ok: true, json: async () => ({ html_url: 'https://github.com/o/r/issues/2' }) }));
    vi.stubGlobal('fetch', fetchMock);
    const { saved, service } = setup(config({ githubToken: 't', githubRepo: 'o/r' }));
    await service.submit({ ...dto, appVersion: '0.7' });
    const sent = JSON.parse((fetchMock.mock.calls[0] as unknown as [string, { body: string }])[1].body);
    expect(sent.body).toContain('> it crashes @​bob');
    expect(sent.body).toContain('> android 0.7 · Android 14');
    expect(sent.labels).toEqual(['app-report']);
    expect(saved[0].githubIssueUrl).toBe('https://github.com/o/r/issues/2');
  });

  it('still stores the report when GitHub fails', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => ({ ok: false, status: 403, text: async () => 'nope' })));
    const { saved, service } = setup(config({ githubToken: 't' }));
    await service.submit(dto);
    expect(saved).toHaveLength(1);
  });

  it('stops opening GitHub issues after 20 an hour but keeps storing reports', async () => {
    const fetchMock = vi.fn(async () => ({ ok: true, json: async () => ({ html_url: 'https://github.com/o/r/issues/3' }) }));
    vi.stubGlobal('fetch', fetchMock);
    const { saved, service } = setup(config({ githubToken: 't', githubRepo: 'o/r' }));
    for (let i = 0; i < 25; i++) await service.submit(dto);
    expect(fetchMock).toHaveBeenCalledTimes(20);
    expect(saved).toHaveLength(25);
    expect(saved[24].githubIssueUrl).toBeNull();
  });
});
