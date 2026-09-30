import { randomBytes, scrypt as scryptCb, timingSafeEqual } from 'node:crypto';

const LOG_N = 15; // N=32768, r=8: OWASP-recommended scrypt cost
const KEYLEN = 32;

const scrypt = (pw: string, salt: Buffer, logN: number) =>
  new Promise<Buffer>((resolve, reject) =>
    scryptCb(pw, salt, KEYLEN, { N: 2 ** logN, r: 8, p: 1, maxmem: 128 * 2 ** logN * 8 * 2 }, (e, k) => (e ? reject(e) : resolve(k))),
  );

/** Format: scrypt$<log2 N>$<salt b64>$<hash b64> — the cost travels with the hash so it can be raised later. */
export async function hashPassword(pw: string): Promise<string> {
  const salt = randomBytes(16);
  return `scrypt$${LOG_N}$${salt.toString('base64')}$${(await scrypt(pw, salt, LOG_N)).toString('base64')}`;
}

export async function verifyPassword(pw: string, stored: string): Promise<boolean> {
  const [alg, logN, salt, hash] = stored.split('$');
  if (alg !== 'scrypt' || !logN || !salt || !hash) return false;
  const expected = Buffer.from(hash, 'base64');
  const actual = await scrypt(pw, Buffer.from(salt, 'base64'), Number(logN));
  return expected.length === actual.length && timingSafeEqual(expected, actual);
}
