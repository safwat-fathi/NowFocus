import type { WebSocket } from 'ws';
import { Hub } from './hub.js';

/** Just enough of a ws socket for the hub: readyState, ping, terminate. */
function fakeSocket() {
  return { readyState: 1, pings: 0, terminated: false, ping() { this.pings++; }, terminate() { this.terminated = true; } };
}
const asWs = (s: ReturnType<typeof fakeSocket>) => s as unknown as WebSocket;

describe('Hub.sweep (keepalive)', () => {
  it('pings a fresh socket and keeps one that answered', () => {
    const hub = new Hub();
    const s = fakeSocket();
    hub.add('u', 'd', asWs(s));
    hub.sweep();
    expect(s.pings).toBe(1);
    hub.markAlive(asWs(s)); // the pong
    hub.sweep();
    expect(s.terminated).toBe(false);
    expect(s.pings).toBe(2);
  });

  it('terminates and forgets a socket that missed a pong', () => {
    const hub = new Hub();
    const dead = fakeSocket();
    const live = fakeSocket();
    hub.add('u', 'dead', asWs(dead));
    hub.add('u', 'live', asWs(live));
    hub.sweep();
    hub.markAlive(asWs(live));
    hub.sweep(); // dead never answered the first ping
    expect(dead.terminated).toBe(true);
    expect(live.terminated).toBe(false);
    hub.sweep();
    expect(dead.pings).toBe(1); // forgotten: not pinged again
  });

  it('drops sockets that are no longer open', () => {
    const hub = new Hub();
    const s = fakeSocket();
    hub.add('u', 'd', asWs(s));
    s.readyState = 3;
    hub.sweep();
    expect(s.terminated).toBe(true);
    expect(s.pings).toBe(0);
  });
});
