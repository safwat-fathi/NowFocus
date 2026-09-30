import { Injectable } from '@nestjs/common';
import type { WebSocket } from 'ws';

const OPEN = 1;

/** Which sockets belong to which user/device. Nudges only — clients recover state via GET /v1/sync/pull. */
// ponytail: in-memory, single instance. Behind a load balancer, fan out via Redis pub/sub instead.
@Injectable()
export class Hub {
  private sockets = new Map<string, { userId: string; ws: WebSocket }>(); // deviceId -> live socket

  add(userId: string, deviceId: string, ws: WebSocket) {
    this.sockets.get(deviceId)?.ws.terminate(); // a reconnect replaces the stale socket
    this.sockets.set(deviceId, { userId, ws });
  }

  remove(ws: WebSocket) {
    for (const [deviceId, s] of this.sockets) if (s.ws === ws) this.sockets.delete(deviceId);
  }

  /** Tell the user's other devices something changed. */
  notify(userId: string, exceptDeviceId: string, message: object) {
    const text = JSON.stringify(message);
    for (const [deviceId, s] of this.sockets) {
      if (s.userId === userId && deviceId !== exceptDeviceId && s.ws.readyState === OPEN) s.ws.send(text);
    }
  }

  revoke(deviceId: string) {
    const s = this.sockets.get(deviceId);
    if (!s) return;
    this.sockets.delete(deviceId);
    if (s.ws.readyState === OPEN) {
      s.ws.send(JSON.stringify({ type: 'device_revoked' }));
      s.ws.close(4403, 'device revoked');
    }
  }
}
