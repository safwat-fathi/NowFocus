import { readFileSync } from 'node:fs';
import { Controller, Get } from '@nestjs/common';
import { ApiExcludeController } from '@nestjs/swagger';
import { DataSource } from 'typeorm';
import { fail } from './errors.js';

// The deploy writes the git sha to REVISION next to dist/. Absent in dev and tests.
const sha = (() => {
  try { return readFileSync(new URL('../REVISION', import.meta.url), 'utf8').trim(); } catch { return 'dev'; }
})();

/** Operational probe for the proxy / uptime monitor / deploy script. Not part of the client contract. */
@ApiExcludeController()
@Controller()
export class MetaController {
  constructor(private db: DataSource) {}

  @Get('healthz')
  async health() {
    try {
      await this.db.query('select 1');
    } catch {
      throw fail(503, 'db_unavailable', 'Database is unreachable');
    }
    return { ok: true, sha, uptime: Math.round(process.uptime()) };
  }
}
