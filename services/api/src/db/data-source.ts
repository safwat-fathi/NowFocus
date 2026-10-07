import { DataSourceOptions, DefaultNamingStrategy } from 'typeorm';
import { Init1759000000000 } from '../migrations/1759000000000-init.js';
import { Waitlist1759500000000 } from '../migrations/1759500000000-waitlist.js';
import { Reports1760000000000 } from '../migrations/1760000000000-reports.js';
import { Device, IssueReport, User, WaitlistEntry } from './entities.js';

class SnakeNaming extends DefaultNamingStrategy {
  columnName(prop: string, custom: string | undefined) {
    return custom ?? prop.replace(/[A-Z]/g, (c) => `_${c.toLowerCase()}`);
  }
}

// Shared by the app, migrations and tests. Schema changes only via migrations (never synchronize).
export const dataSourceOptions = (url: string): DataSourceOptions => ({
  type: 'postgres',
  url,
  entities: [User, Device, WaitlistEntry, IssueReport],
  migrations: [Init1759000000000, Waitlist1759500000000, Reports1760000000000],
  namingStrategy: new SnakeNaming(),
});
