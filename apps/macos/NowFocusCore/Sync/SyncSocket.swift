import Foundation

enum SocketEvent {
    /// Another device committed changes; if `cursor` is ahead of ours, sync.
    case changes(cursor: Int)
    /// The server revoked this device.
    case revoked
    /// The connection ended. `code` is the WebSocket close code, or the HTTP status when the upgrade itself was
    /// refused (401), or -1.
    case closed(code: Int)
}

/// WS /ws/device. Only a hint that a pull is worthwhile: sync never depends on it. The server pings every 30 s and
/// URLSession answers the pongs on its own.
final class SyncSocket {
    private let url: URL
    private let userAgent: String

    init(baseURL: URL, userAgent: String) {
        var parts = URLComponents(url: baseURL, resolvingAgainstBaseURL: false)!
        parts.scheme = parts.scheme == "http" ? "ws" : "wss"
        parts.path = "/ws/device"
        self.url = parts.url!
        self.userAgent = userAgent
    }

    /// One connection. The stream ends when the connection does; cancelling the consumer closes the socket.
    func connect(accessToken: String) -> AsyncStream<SocketEvent> {
        AsyncStream { continuation in
            var request = URLRequest(url: url)
            request.setValue("Bearer \(accessToken)", forHTTPHeaderField: "Authorization")   // never in the URL
            request.setValue(userAgent, forHTTPHeaderField: "User-Agent")
            let connection = Connection(continuation: continuation)
            let session = URLSession(configuration: .ephemeral, delegate: connection, delegateQueue: nil)
            let task = session.webSocketTask(with: request)
            connection.task = task
            continuation.onTermination = { _ in task.cancel(with: .goingAway, reason: nil); session.invalidateAndCancel() }
            task.resume()
            connection.receiveLoop()
        }
    }

    private final class Connection: NSObject, URLSessionWebSocketDelegate {
        let continuation: AsyncStream<SocketEvent>.Continuation
        var task: URLSessionWebSocketTask?
        private let finishLock = NSLock()
        private var finished = false

        init(continuation: AsyncStream<SocketEvent>.Continuation) { self.continuation = continuation }

        func receiveLoop() {
            task?.receive { [weak self] result in
                guard let self else { return }
                switch result {
                case .success(let message):
                    if case .string(let text) = message { self.handle(text) }
                    self.receiveLoop()
                case .failure:
                    // The upgrade being refused (an expired access token) arrives here as a failure with the HTTP response.
                    let http = (self.task?.response as? HTTPURLResponse)?.statusCode
                    let close = self.task?.closeCode.rawValue ?? 0
                    self.finish(code: close > 1_000 ? close : (http ?? -1))
                }
            }
        }

        private func handle(_ text: String) {
            guard let o = JSONKit.object(text) else { return }
            switch JSONKit.string(o, "type") {
            case "changes": if let cursor = JSONKit.int(o, "cursor") { continuation.yield(.changes(cursor: cursor)) }
            case "device_revoked": continuation.yield(.revoked)
            default: break
            }
        }

        func urlSession(_ session: URLSession, webSocketTask: URLSessionWebSocketTask, didCloseWith closeCode: URLSessionWebSocketTask.CloseCode, reason: Data?) {
            finish(code: closeCode.rawValue)
        }

        private func finish(code: Int) {
            finishLock.lock(); defer { finishLock.unlock() }
            guard !finished else { return }
            finished = true
            continuation.yield(.closed(code: code))
            continuation.finish()
        }
    }
}
