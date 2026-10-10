import { DataSourceOptions, DefaultNamingStrategy } from 'typeorm';
import { Init1759000000000 } from '../migrations/1759000000000-init.js';
import { Waitlist1759500000000 } from '../migrations/1759500000000-waitlist.js';
import { Reports1760000000000 } from '../migrations/1760000000000-reports.js';
import { WaitlistSends1760500000000 } from '../migrations/1760500000000-waitlist-sends.js';
import { PendingSignupsMigration1761000000000 } from '../migrations/1761000000000-pending-signups.js';
import { Device, IssueReport, PendingSignup, User, WaitlistEntry, WaitlistSend } from './entities.js';

class SnakeNaming extends DefaultNamingStrategy {
  columnName(prop: string, custom: string | undefined) {
    return custom ?? prop.replace(/[A-Z]/g, (c) => `_${c.toLowerCase()}`);
  }
}

// Shared by the app, migrations and tests. Schema changes only via migrations (never synchronize).
export const dataSourceOptions = (url: string): DataSourceOptions => ({
  type: 'postgres',
  url,
  entities: [User, PendingSignup, Device, WaitlistEntry, WaitlistSend, IssueReport],
  migrations: [Init1759000000000, Waitlist1759500000000, Reports1760000000000, WaitlistSends1760500000000, PendingSignupsMigration1761000000000],
  namingStrategy: new SnakeNaming(),
});
