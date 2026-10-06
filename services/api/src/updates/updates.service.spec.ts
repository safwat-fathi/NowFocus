import { afterEach, describe, expect, it, vi } from 'vitest';
import { Clock } from '../clock.js';
import { Config } from '../config.js';
import { isNewer, UpdatesService } from './updates.service.js';

const latestJson = (version: string) => ({
  version,
  notes: 'n',
  pub_date: '2026-10-06T00:00:00Z',
  platforms: { 'windows-x86_64-nsis': { url: `https://github.com/o/r/releases/download/windows-v${version}/NowFocus_${version}_x64-setup.exe`, signature: 'sig' } },
});
const ok = (body: unknown) => ({ ok: true, json: async () => body });

class TestClock extends Clock {
  t = 0;
  now() { return new Date(this.t); }
}
const setup = () => {
  const clock = new TestClock();
  return { clock, service: new UpdatesService({ githubRepo: 'o/r' } as Config, clock) };
};

afterEach(() => vi.unstubAllGlobals());

describe('isNewer', () => {
  it('compares dotted versions numerically', () => {
    expect(isNewer('0.4.1', '0.4.0')).toBe(true);
    expect(isNewer('0.10.0', '0.9.9')).toBe(true);
    expect(isNewer('0.4.0', '0.4.0')).toBe(false);
    expect(isNewer('0.4.0', '0.4.1')).toBe(false);
    expect(isNewer('v0.4.1', '0.4.1-rc.1')).toBe(false);
    expect(isNewer('0.4.1', 'garbage')).toBe(true);
  });
});

describe('UpdatesService', () => {
  it('maps latest.json to the updater shape and caches it', async () => {
    const fetchMock = vi.fn(async () => ok(latestJson('0.4.1')));
    vi.stubGlobal('fetch', fetchMock);
    const { clock, service } = setup();
    expect(await service.latest()).toMatchObject({ version: '0.4.1', signature: 'sig', url: expect.stringContaining('x64-setup.exe') });
    await service.latest();
    expect(fetchMock).toHaveBeenCalledTimes(1);
    clock.t += 6 * 60_000;
    await service.latest();
    expect(fetchMock).toHaveBeenCalledTimes(2);
  });

  it('serves the stale release when GitHub fails, and 503s with nothing cached', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => ({ ok: false, status: 500 })));
    await expect(setup().service.latest()).rejects.toMatchObject({ status: 503 });

    const { clock, service } = setup();
    vi.stubGlobal('fetch', vi.fn(async () => ok(latestJson('0.4.1'))));
    await service.latest();
    clock.t += 6 * 60_000;
    vi.stubGlobal('fetch', vi.fn(async () => ({ ok: false, status: 500 })));
    expect((await service.latest()).version).toBe('0.4.1');
  });

  it('rejects a latest.json without a windows entry', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => ok({ version: '1.0.0', platforms: {} })));
    await expect(setup().service.latest()).rejects.toMatchObject({ status: 503 });
  });
});
