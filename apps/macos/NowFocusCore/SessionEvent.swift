import Foundation

public struct SessionEvent: Codable, Identifiable {
    public let id: String
    public let sessionId: String
    public let type: String
    public let occurredAt: Date
    public let metadataJson: String?
    
    public init(id: String = UUID().uuidString,
                sessionId: String,
                type: String,
                occurredAt: Date = Date(),
                metadataJson: String? = nil) {
        self.id = id
        self.sessionId = sessionId
        self.type = type
        self.occurredAt = occurredAt
        self.metadataJson = metadataJson
    }
}
