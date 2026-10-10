import { Injectable } from '@nestjs/common';
import { Config } from './config.js';

const brevo = async (config: Config, path: string, body: unknown) => {
  const res = await fetch(`https://api.brevo.com/v3${path}`, {
    method: 'POST',
    headers: { 'api-key': config.brevoApiKey!, 'Content-Type': 'application/json', Accept: 'application/json' },
    body: JSON.stringify(body),
  });
  if (!res.ok) throw new Error(`Brevo ${path} returned ${res.status}: ${await res.text()}`);
};

/** Sends one transactional email from the configured sender. Throws on a non-2xx from Brevo. */
export const sendMail = (config: Config, to: string, subject: string, html: string, headers?: Record<string, string>, fromName = 'NowFocus') =>
  brevo(config, '/smtp/email', {
    sender: { name: fromName, email: config.mailFrom },
    replyTo: { email: config.mailFrom },
    to: [{ email: to }],
    subject,
    htmlContent: html,
    ...(headers && { headers }),
  });

/** Adds (or updates) a contact on the Brevo list used for the launch email. */
export const addContact = (config: Config, email: string) =>
  brevo(config, '/contacts', { email, listIds: [config.brevoListId], updateEnabled: true });

/** Injectable sender for the auth emails (tests swap it for a fake). No Brevo key (local dev) means nothing is sent. */
@Injectable()
export class Mailer {
  constructor(private config: Config) {}
  send(to: string, subject: string, html: string): Promise<void> {
    return this.config.brevoApiKey ? sendMail(this.config, to, subject, html) : Promise.resolve();
  }
}
