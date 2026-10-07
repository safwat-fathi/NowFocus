import { Config } from './config.js';

/** Stops user text from pinging people or injecting markdown headings/links into a public issue. */
export const quote = (text: string) => text.replace(/@/g, '@​').split('\n').map((l) => `> ${l}`).join('\n');

/** Opens an issue in the project repo and returns its URL. Throws on a non-2xx from GitHub. */
export async function createGithubIssue(config: Config, title: string, body: string, label: string): Promise<string | null> {
  const repo = config.githubRepo ?? 'safwat-fathi/NowFocus';
  const res = await fetch(`https://api.github.com/repos/${repo}/issues`, {
    method: 'POST',
    headers: {
      Authorization: `Bearer ${config.githubToken}`,
      Accept: 'application/vnd.github+json',
      'User-Agent': 'NowFocus-API',
      'Content-Type': 'application/json',
    },
    body: JSON.stringify({ title, body, labels: [label] }),
  });
  if (!res.ok) throw new Error(`GitHub API returned ${res.status}: ${await res.text()}`);
  return ((await res.json()) as { html_url?: string }).html_url ?? null;
}
