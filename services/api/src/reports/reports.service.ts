import { Injectable, Logger } from '@nestjs/common';
import { InjectRepository } from '@nestjs/typeorm';
import { randomUUID } from 'node:crypto';
import { Repository } from 'typeorm';
import { Config } from '../config.js';
import { IssueReport } from '../db/entities.js';
import { createGithubIssue, quote } from '../github.js';
import { ReportDto } from './reports.dto.js';

@Injectable()
export class ReportsService {
  private readonly logger = new Logger(ReportsService.name);

  constructor(
    @InjectRepository(IssueReport) private readonly repo: Repository<IssueReport>,
    private readonly config: Config,
  ) {}

  /** Always stores the report; the GitHub issue is best effort. */
  async submit(dto: ReportDto): Promise<{ ok: boolean; id: string }> {
    const message = dto.message.trim();
    const contact = dto.contact?.trim() || null;
    const id = randomUUID();
    let githubIssueUrl: string | null = null;
    if (this.config.githubToken) {
      const title = `[App report] ${message.split('\n')[0].replace(/@/g, '').slice(0, 60)}`;
      const meta = `${dto.platform} ${dto.appVersion} · ${dto.osVersion}`.replace(/[\r\n]+/g, ' ');
      const body = [quote(meta), '', quote(message)].join('\n');
      try {
        githubIssueUrl = await createGithubIssue(this.config, title, body, 'app-report');
      } catch (err: unknown) {
        this.logger.warn(`Failed to create GitHub issue for report ${id}: ${err instanceof Error ? err.message : String(err)}`);
      }
    }
    await this.repo.save(
      this.repo.create({ id, message, contact, platform: dto.platform, appVersion: dto.appVersion, osVersion: dto.osVersion, githubIssueUrl }),
    );
    return { ok: true, id };
  }
}
