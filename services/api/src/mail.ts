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
export const sendMail = (config: Config, to: string, subject: string, html: string) =>
  brevo(config, '/smtp/email', {
    sender: { name: 'NowFocus', email: config.mailFrom },
    replyTo: { email: config.mailFrom },
    to: [{ email: to }],
    subject,
    htmlContent: html,
  });

/** Adds (or updates) a contact on the Brevo list used for the launch email. */
export const addContact = (config: Config, email: string) =>
  brevo(config, '/contacts', { email, listIds: [config.brevoListId], updateEnabled: true });
