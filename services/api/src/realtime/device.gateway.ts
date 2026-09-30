import type { IncomingMessage } from 'node:http';
import { type OnModuleDestroy, type OnModuleInit } from '@nestjs/common';
import { type OnGatewayConnection, type OnGatewayDisconnect, WebSocketGateway } from '@nestjs/websockets';
import type { Subscription } from 'rxjs';
import type { WebSocket } from 'ws';
import { AuthService } from '../auth/auth.service.js';
import { RecordsService } from '../sync/records.service.js';
import { Hub } from './hub.js';

/** WS /ws/device — authenticated with `Authorization: Bearer <access token>` on the upgrade request. */
@WebSocketGateway({ path: '/ws/device' })
export class DeviceGateway implements OnGatewayConnection, OnGatewayDisconnect, OnModuleInit, OnModuleDestroy {
  private subs: Subscription[] = [];

  constructor(private auth: AuthService, private hub: Hub, private records: RecordsService) {}

  onModuleInit() {
    this.subs.push(
      this.records.committed.subscribe((e) => this.hub.notify(e.userId, e.originDeviceId, { type: 'changes', cursor: e.cursor })),
      this.auth.revoked.subscribe((deviceId) => this.hub.revoke(deviceId)),
    );
  }

  onModuleDestroy() {
    this.subs.forEach((s) => s.unsubscribe());
  }

  async handleConnection(client: WebSocket, req: IncomingMessage) {
    try {
      const me = await this.auth.authenticate(req.headers.authorization);
      this.hub.add(me.userId, me.deviceId, client);
      // Read the cursor only AFTER registering: anything committed earlier is covered by this message,
      // anything committed later is nudged normally — so no change can fall between the two.
      client.send(JSON.stringify({ type: 'changes', cursor: await this.records.cursorOf(me.userId) }));
    } catch {
      client.close(4401, 'unauthorized');
    }
  }

  handleDisconnect(client: WebSocket) {
    this.hub.remove(client);
  }
}
