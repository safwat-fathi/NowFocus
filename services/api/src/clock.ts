import { Injectable } from '@nestjs/common';

/** The server's notion of "now" — the trusted time for shield locks and refresh expiry. Faked in tests. */
// ponytail: plain Node clock, assumes an NTP-synced host.
@Injectable()
export class Clock {
  now(): Date {
    return new Date();
  }
}
