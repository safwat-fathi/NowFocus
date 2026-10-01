import { normalizeHostname as n } from './hostname.js';

// Same cases and expectations as the client tests (domain_validation.rs, test_domain_validation.swift).
describe('normalizeHostname', () => {
  it('passes a bare domain through', () => expect(n('youtube.com')).toBe('youtube.com'));
  it('strips scheme and www', () => expect(n('https://www.youtube.com')).toBe('youtube.com'));
  it('strips path and query', () => expect(n('youtube.com/watch?v=abc')).toBe('youtube.com'));
  it('case-folds and strips the path', () => expect(n('HTTP://Reddit.COM/r/all')).toBe('reddit.com'));
  it('trims surrounding whitespace', () => expect(n('  twitter.com  ')).toBe('twitter.com'));
  it('strips a port and a fragment', () => {
    expect(n('example.com:8080/x')).toBe('example.com');
    expect(n('example.com#top')).toBe('example.com');
  });
  it('rejects newline and space injection (a hosts-file line)', () => expect(n('evil.com\n1.2.3.4 bank.com')).toBeNull());
  it('rejects non-domain text', () => expect(n('not a domain')).toBeNull());
  it('rejects empty and dotless input', () => {
    expect(n('')).toBeNull();
    expect(n('localhost')).toBeNull();
  });
  it('rejects leading and trailing dot or hyphen', () => {
    for (const bad of ['-bad.com', 'bad-.com.', '.bad.com', 'bad.com-', 'bad.com.']) expect(n(bad), bad).toBeNull();
  });
  it('rejects unicode, wildcards and underscores (IDN arrives as punycode)', () => {
    expect(n('exämple.com')).toBeNull();
    expect(n('*.example.com')).toBeNull();
    expect(n('a_b.example.com')).toBeNull();
    expect(n('xn--exmple-cua.com')).toBe('xn--exmple-cua.com');
  });
});
