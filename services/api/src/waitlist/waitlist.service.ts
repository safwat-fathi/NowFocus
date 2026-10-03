import { Injectable, Logger } from '@nestjs/common';
import { InjectRepository } from '@nestjs/typeorm';
import { randomUUID } from 'node:crypto';
import { Repository } from 'typeorm';
import { Config } from '../config.js';
import { WaitlistEntry } from '../db/entities.js';
import { WaitlistDto } from './waitlist.dto.js';

/** Stops user text from pinging people or injecting markdown headings/links into a public issue. */
const quote = (text: string) => text.replace(/@/g, '@​').split('\n').map((l) => `> ${l}`).join('\n');

@Injectable()
export class WaitlistService {
  private readonly logger = new Logger(WaitlistService.name);

  constructor(
    @InjectRepository(WaitlistEntry)
    private readonly repo: Repository<WaitlistEntry>,
    private readonly config: Config,
  ) {}

  /** Idempotent per email: a repeat signup updates the row instead of adding one (the response never says which). */
  async submit(dto: WaitlistDto): Promise<{ ok: boolean; id: string; githubIssueUrl?: string | null }> {
    const email = dto.email.trim().toLowerCase();
    const platforms = [...new Set((dto.platforms ?? []).map((p) => p.trim().toLowerCase()))];
    const featureRequest = dto.featureRequest?.trim() || null;
    const existing = await this.repo.findOne({ where: { email } });
    const id = existing?.id ?? randomUUID();

    let githubIssueUrl = existing?.githubIssueUrl ?? null;
    if (featureRequest && featureRequest !== existing?.featureRequest && this.config.githubToken) {
      try {
        githubIssueUrl = await this.createGithubIssue(platforms, featureRequest);
      } catch (err: unknown) {
        const msg = err instanceof Error ? err.message : String(err);
        this.logger.warn(`Failed to create GitHub issue for waitlist ${id}: ${msg}`);
      }
    }

    await this.repo.save(
      this.repo.create({
        id,
        email,
        platforms: existing ? [...new Set([...existing.platforms, ...platforms])] : platforms,
        featureRequest: featureRequest ?? existing?.featureRequest ?? null,
        githubIssueUrl,
      }),
    );
    return { ok: true, id, githubIssueUrl };
  }

  private async createGithubIssue(platforms: string[], request: string): Promise<string | null> {
    const repo = this.config.githubRepo ?? 'safwat-fathi/NowFocus';
    const firstLine = request.split('\n')[0].replace(/@/g, '').slice(0, 60);
    const body = [
      '### Waitlist feature request',
      '',
      `**Platform(s):** ${platforms.length > 0 ? platforms.join(', ') : 'not specified'}`,
      '',
      quote(request),
    ].join('\n');

    const res = await fetch(`https://api.github.com/repos/${repo}/issues`, {
      method: 'POST',
      headers: {
        Authorization: `Bearer ${this.config.githubToken}`,
        Accept: 'application/vnd.github+json',
        'User-Agent': 'NowFocus-API',
        'Content-Type': 'application/json',
      },
      body: JSON.stringify({ title: `[Feature request] ${firstLine || 'Community request'}`, body, labels: ['feature-request'] }),
    });
    if (!res.ok) throw new Error(`GitHub API returned ${res.status}: ${await res.text()}`);
    return ((await res.json()) as { html_url?: string }).html_url ?? null;
  }
}
