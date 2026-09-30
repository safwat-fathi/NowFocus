import Foundation
import GRDB

// The plain types live in Goals.swift and People.swift so they compile without GRDB (see
// scripts/core-checks.sh); only the persistence conformances are here.

extension UserGoal: FetchableRecord, PersistableRecord {
    public static let databaseTableName = "userGoal"
}

extension UserConnection: FetchableRecord, PersistableRecord {
    public static let databaseTableName = "userConnection"
}
