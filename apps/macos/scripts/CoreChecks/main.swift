import Foundation

// Cases are literal ports of the Android unit tests (PeopleRotationTest, HistoryStatsTest,
// SessionEngineTest, GoalsTest), so the three platforms agree. Uses check() + exit(1), not assert,
// so the result doesn't depend on the optimisation level.

var failures = 0
func check(_ ok: @autoclosure () -> Bool, _ message: String, line: Int = #line) {
    if !ok() { print("FAIL line \(line): \(message)"); failures += 1 }
}

let day: TimeInterval = 86_400
let now = Date(timeIntervalSince1970: 100 * day)

// MARK: People rotation and labels (PeopleRotationTest)

func person(_ id: String, added: Double = 0, talked: Double? = nil, phone: String? = "+100") -> UserConnection {
    UserConnection(
        id: id, name: id, phoneNumber: phone,
        createdAt: Date(timeIntervalSince1970: added), updatedAt: Date(timeIntervalSince1970: added),
        lastTalkedAt: talked.map { Date(timeIntervalSince1970: $0) }
    )
}
func talked(daysAgo: Double) -> Double { (100 - daysAgo) * day }

check(PeopleRotation.next([]) == nil, "next is nil with nobody added")
check(PeopleRotation.next([person("recent", talked: 900), person("oldest", talked: 100), person("middle", talked: 500)])?.id == "oldest", "picks whoever you talked to longest ago")
check(PeopleRotation.next([person("long-ago", talked: 1), person("unknown", talked: nil)])?.id == "unknown", "can't remember counts as oldest")
check(PeopleRotation.next([person("later", added: 20), person("earlier", added: 10)])?.id == "earlier", "ties go to whoever was added first")
check(PeopleRotation.next([person("no-number", talked: nil, phone: nil), person("has-number", talked: 500)])?.id == "has-number", "someone without a number is never picked")
check(PeopleRotation.next([person("blank", phone: "  ")]) == nil, "a blank number is not reachable")

check(person("a", talked: nil).sinceLabel(now: now) == nil, "sinceLabel nil when unknown")
check(person("a", talked: talked(daysAgo: 2)).sinceLabel(now: now) == nil, "no claim for a very recent chat")
check(person("a", talked: talked(daysAgo: 3)).sinceLabel(now: now) == "3 days", "3 days")
check(person("a", talked: talked(daysAgo: 13)).sinceLabel(now: now) == "13 days", "13 days")
check(person("a", talked: talked(daysAgo: 14)).sinceLabel(now: now) == "2 weeks", "2 weeks at 14 days")
check(person("a", talked: talked(daysAgo: 59)).sinceLabel(now: now) == "8 weeks", "8 weeks at 59 days")
check(person("a", talked: talked(daysAgo: 65)).sinceLabel(now: now) == "2 months", "2 months at 65 days")

// Chips: same labels and day counts as Android's LAST_TALKED_CHOICES / choiceFor.
check(LastTalked.choices.map { $0.label } == ["This week", "About 2 weeks", "About a month", "Longer", "Can't remember"], "chip labels")
check(LastTalked.choices.map { $0.daysAgo } == [3, 14, 30, 90, nil], "chip day counts")
check(LastTalked.choice(for: nil, now: now) == nil, "unknown highlights Can't remember")
check(LastTalked.choice(for: Date(timeIntervalSince1970: talked(daysAgo: 8)), now: now) == 3, "8 days highlights This week")
check(LastTalked.choice(for: Date(timeIntervalSince1970: talked(daysAgo: 9)), now: now) == 14, "9 days highlights About 2 weeks")
check(LastTalked.choice(for: Date(timeIntervalSince1970: talked(daysAgo: 21)), now: now) == 14, "21 days highlights About 2 weeks")
check(LastTalked.choice(for: Date(timeIntervalSince1970: talked(daysAgo: 22)), now: now) == 30, "22 days highlights About a month")
check(LastTalked.choice(for: Date(timeIntervalSince1970: talked(daysAgo: 59)), now: now) == 30, "59 days highlights About a month")
check(LastTalked.choice(for: Date(timeIntervalSince1970: talked(daysAgo: 60)), now: now) == 90, "60 days highlights Longer")

// Cap of five and no duplicate numbers.
check(PeopleRotation.max == 5, "max five people")
let five = (1...5).map { person("p\($0)", phone: "+10\($0)") }
check(!PeopleRotation.canAdd(phone: "+199", to: five), "a sixth person is refused")
check(PeopleRotation.canAdd(phone: "+199", to: Array(five.prefix(4))), "a fifth is fine")
check(!PeopleRotation.canAdd(phone: "+101", to: Array(five.prefix(4))), "the same number twice is refused")
check(!PeopleRotation.canAdd(phone: "   ", to: []), "a blank number is refused")

// Call/Text hand the number to tel:/sms:. Formatting must not leak into the URL, and a leading + must survive.
check(PhoneLink.dialString("+1 (555) 123-4567") == "+15551234567", "formatting is stripped, plus kept")
check(PhoneLink.dialString("555.123.4567") == "5551234567", "dots are stripped")
check(PhoneLink.dialString("  *67 #  ") == "*67#", "star and hash are dialable")
check(PhoneLink.dialString("call me") == nil, "no digits, no link")
check(PhoneLink.url(scheme: "tel", phone: "+1 555 0100")?.absoluteString == "tel:+15550100", "tel url")
check(PhoneLink.url(scheme: "sms", phone: "020 7946 0000")?.absoluteString == "sms:02079460000", "sms url")
check(PhoneLink.url(scheme: "tel", phone: "") == nil, "empty phone has no url")

// MARK: Urge map (HistoryStatsTest)

var utc = Calendar(identifier: .gregorian)
utc.timeZone = TimeZone(identifier: "UTC")!
func date(_ d: Int, _ hour: Int, _ minute: Int = 0) -> Date {
    utc.date(from: DateComponents(year: 2026, month: 9, day: d, hour: hour, minute: minute))!
}
func attempt(_ app: String, _ d: Int, _ hour: Int, _ minute: Int = 0, type: String = "app_blocked") -> SessionEvent {
    SessionEvent(sessionId: "s", type: type, occurredAt: date(d, hour, minute), metadataJson: "{\"bundleId\":\"\(app)\",\"name\":\"\(app)\"}")
}

let hours = HistoryStats.urgeByHour([attempt("a", 21, 15), attempt("a", 22, 15, 59), attempt("b", 21, 9)], calendar: utc)
check(hours.count == 24, "24 buckets")
check(hours[15] == 2 && hours[9] == 1 && hours.reduce(0, +) == 3, "attempts land in their local hour")

var plus2 = Calendar(identifier: .gregorian)
plus2.timeZone = TimeZone(secondsFromGMT: 7200)!
let shifted = HistoryStats.urgeByHour([attempt("a", 21, 23, 30)], calendar: plus2)
check(shifted[1] == 1 && shifted[23] == 0, "urgeByHour uses the given zone, not UTC")

let busy = [attempt("insta", 21, 15), attempt("insta", 21, 15, 20), attempt("reddit", 21, 15, 40), attempt("reddit", 21, 9), attempt("reddit", 21, 9, 5)]
let peak = HistoryStats.peakUrge(busy, calendar: utc)
check(peak?.hour == 15 && peak?.count == 3 && peak?.topApp == "insta", "peak hour and the app most tried in it")
check(HistoryStats.peakUrge([attempt("a", 21, 20), attempt("b", 21, 8)], calendar: utc)?.hour == 8, "a tie goes to the earlier hour")
check(HistoryStats.peakUrge([], calendar: utc) == nil, "no attempts, no peak")
check(HistoryStats.urgeByHour([attempt("a", 21, 10, type: "session_started")], calendar: utc).reduce(0, +) == 0, "only app_blocked events are urges")

check(HistoryStats.turnedAwayCount((1...5).map { attempt("app\($0)", 21, 10) } + [attempt("app1", 21, 11), attempt("x", 21, 12, type: "session_started")]) == 6,
      "turnedAwayCount counts every blocked attempt, not just the top three, and ignores other events")

// MARK: canCancel (SessionEngineTest)

check(SessionEngine.canCancel(mode: .normal, unlockCompleted: false), "normal can always cancel")
check(!SessionEngine.canCancel(mode: .strict, unlockCompleted: false), "strict needs the unlock first")
check(SessionEngine.canCancel(mode: .strict, unlockCompleted: true), "strict after the unlock")
check(!SessionEngine.canCancel(mode: .locked, unlockCompleted: true), "locked never cancels")
check(!SessionEngine.canCancel(mode: .strict, unlockCompleted: true, hasVoiceNote: true, listened: false), "a note must be heard")
check(SessionEngine.canCancel(mode: .strict, unlockCompleted: true, hasVoiceNote: true, listened: true), "heard the note")
check(!SessionEngine.canCancel(mode: .strict, unlockCompleted: false, hasVoiceNote: true, listened: true), "listening never replaces the unlock")
check(SessionEngine.canCancel(mode: .strict, unlockCompleted: true, hasVoiceNote: false, listened: false), "no note falls back to the plain unlock")
check(SessionEngine.canCancel(mode: .normal, unlockCompleted: false, hasVoiceNote: true, listened: false), "a note changes nothing for normal")
check(!SessionEngine.canCancel(mode: .locked, unlockCompleted: true, hasVoiceNote: true, listened: true), "a note changes nothing for locked")

// MARK: Goals (GoalsTest, mirrors DatabaseManager.fetchRandomGoal)

struct SeededGenerator: RandomNumberGenerator {
    var state: UInt64
    mutating func next() -> UInt64 { state = state &* 6364136223846793005 &+ 1442695040888963407; return state }
}
func goal(_ text: String, _ p: GoalPriority) -> UserGoal { UserGoal(id: text, text: text, priority: p) }

var g0 = SeededGenerator(state: 1)
check(Goals.pick([], using: &g0) == nil, "no goals, nothing to show")
let mixed = [goal("low", .low), goal("high", .high), goal("med", .medium)]
var alwaysHigh = true
for seed in 0..<50 { var g = SeededGenerator(state: UInt64(seed)); if Goals.pick(mixed, using: &g)?.text != "high" { alwaysHigh = false } }
check(alwaysHigh, "a high priority goal is always preferred")
var seen = Set<String>()
for seed in 0..<50 { var g = SeededGenerator(state: UInt64(seed)); if let t = Goals.pick([goal("a", .medium), goal("b", .low)], using: &g)?.text { seen.insert(t) } }
check(seen == ["a", "b"], "without a high goal any goal can be picked")
var seenHigh = Set<String>()
for seed in 0..<50 { var g = SeededGenerator(state: UInt64(seed)); if let t = Goals.pick([goal("x", .high), goal("y", .high)], using: &g)?.text { seenHigh.insert(t) } }
check(seenHigh.count == 2, "among several high goals the pick varies")
check(GoalPriority.allCases.map { $0.label } == ["High", "Med", "Low"], "labels match Android")

// MARK: Voice notes (Android VoiceNote: pending -> per-session file, purge the rest)

let voiceDir = FileManager.default.temporaryDirectory.appendingPathComponent("nowfocus-voice-check-\(UUID().uuidString)")
defer { try? FileManager.default.removeItem(at: voiceDir) }
let notes = VoiceNoteStore(directory: voiceDir)

func writeNote(_ url: URL, bytes: Int = 8) { try? Data(count: bytes).write(to: url) }

check(!notes.hasPending, "no pending note at first")
check(!notes.hasNote(sessionId: "s1"), "no session note at first")
writeNote(notes.pendingURL)
check(notes.hasPending, "a recorded note is pending")
check(notes.adoptPending(as: "s1"), "adopting a pending note succeeds")
check(!notes.hasPending && notes.hasNote(sessionId: "s1"), "adopting moves the file to the session")
check(!notes.adoptPending(as: "s2"), "nothing to adopt the second time")

writeNote(notes.noteURL(sessionId: "s2"))
writeNote(notes.pendingURL)
notes.purge(except: "s2")
check(notes.hasNote(sessionId: "s2"), "purge keeps the note it is told to keep")
check(!notes.hasNote(sessionId: "s1") && !notes.hasPending, "purge removes every other note, pending included")
notes.purge(except: nil)
check(!notes.hasNote(sessionId: "s2"), "purge with no keeper removes everything")

writeNote(notes.noteURL(sessionId: "empty"), bytes: 0)
check(!notes.hasNote(sessionId: "empty"), "an empty file is not a note (can't trap anyone)")
notes.purge(except: nil)

writeNote(notes.pendingURL, bytes: 0)
check(!notes.hasPending, "an empty pending file does not count")
notes.deletePending()
check(!FileManager.default.fileExists(atPath: notes.pendingURL.path), "deletePending removes the file")

// MARK: Feed rules (the cross-platform contract: same seven names, same order, same labels as Android's PartialRule)

check(FeedRules.all.map { $0.id } == ["YT_SHORTS", "YT_HOME", "YT_RELATED", "FB_REELS", "IG_REELS", "X_FOR_YOU", "TT_FOR_YOU"], "the seven rule names and their order")
check(FeedRules.all.map { $0.label } == ["YouTube Shorts", "YouTube Home feed", "YouTube up next / related", "Facebook Reels", "Instagram Reels & Explore", "X \u{201C}For you\u{201D} feed", "TikTok feed"], "rule labels match Android")
check(FeedRules.all.allSatisfy { !$0.detail.isEmpty }, "every rule explains itself")
check(FeedRules.enforceable == ["YT_SHORTS", "IG_REELS", "FB_REELS"] && Set(FeedRules.enforceable).isSubset(of: Set(FeedRules.all.map { $0.id })), "a Mac enforces the three rules that have an address")

// MARK: Feed URLs (a Mac sees a tab's address, not the page; keep these cases identical to core/src/feed_url.rs)

for (url, want) in [
    ("https://www.youtube.com/shorts/abc123", "YT_SHORTS"), ("youtube.com/shorts/abc123", "YT_SHORTS"),
    ("m.youtube.com/shorts/abc123?feature=share", "YT_SHORTS"), ("YouTube.com/Shorts", "YT_SHORTS"), ("youtube.com:443/shorts/x", "YT_SHORTS"),
    ("https://www.instagram.com/reels/", "IG_REELS"), ("instagram.com/reel/Cxyz/", "IG_REELS"),
    ("instagram.com/explore/", "IG_REELS"), ("instagram.com/explore", "IG_REELS"),
    ("https://www.facebook.com/reel/123", "FB_REELS"), ("web.facebook.com/reels", "FB_REELS"), ("m.facebook.com/reels/?x=1#top", "FB_REELS"),
] {
    check(FeedURLMatcher.rule(for: url) == want, "\(url) is \(want)")
}
for url in [
    "https://www.youtube.com/", "youtube.com/watch?v=abc", "youtube.com/feed/subscriptions", "youtube.com/shortsfoo",
    "youtube.com/@creator/shorts", "youtube.com/watch?v=x&next=/shorts/y",
    "instagram.com/", "instagram.com/explorer", "instagram.com/someone/",
    "facebook.com/", "facebook.com/watch/?v=1", "facebook.com/reelsfoo",
    "notyoutube.com/shorts/a", "youtube.com.evil.com/shorts/a", "youtube.com@evil.com/shorts/a", "evil.com/youtube.com/shorts/a",
    "youtube.com:evil/shorts/a", "www.google.com/search?q=youtube.com/shorts", "youtube shorts", "", "   ", "https://",
] {
    check(FeedURLMatcher.rule(for: url) == nil, "\"\(url)\" is not a feed page")
}
do {
    var gate = CloseGate()
    check(gate.allow(now: now), "the first close is allowed")
    check(!gate.allow(now: now.addingTimeInterval(CloseGate.cooldown - 0.1)), "a second close inside the cooldown is not")
    check(gate.allow(now: now.addingTimeInterval(CloseGate.cooldown)), "and one after it is")
}

// Sync: the Mac owns the three address-checked names and carries every other name through untouched.
do {
    let raw = "{\"id\":\"p1\",\"name\":\"Deep work\",\"mode\":\"blocklist\",\"partial\":[\"YT_SHORTS\",\"FB_REELS\",\"YT_RELATED\",\"TT_FOR_YOU\",\"X_FOR_YOU\"]}"
    let local = PolicyWire.toLocal(J(raw))
    check(local.partial == ["YT_SHORTS", "FB_REELS"], "only the names a Mac enforces are imported")
    let pushed = JSONKit.array(PolicyWire.merge(local, into: J(raw)), "partial").compactMap { $0 as? String }
    check(Set(pushed) == ["YT_SHORTS", "FB_REELS", "YT_RELATED", "TT_FOR_YOU", "X_FOR_YOU"], "an untouched profile pushes the same list")
    var off = local
    off.partial = ["YT_SHORTS"]
    let after = Set(JSONKit.array(PolicyWire.merge(off, into: J(raw)), "partial").compactMap { $0 as? String })
    check(after == ["YT_SHORTS", "YT_RELATED", "TT_FOR_YOU", "X_FOR_YOU"], "switching one off drops only that name")
    var on = local
    on.partial = ["YT_SHORTS", "IG_REELS", "FB_REELS"]
    check(Set(JSONKit.array(PolicyWire.merge(on, into: J(raw)), "partial").compactMap { $0 as? String }).contains("IG_REELS"), "switching one on adds it")
    check(PolicyWire.weakens(old: local, new: off), "dropping a feed rule weakens")
    check(!PolicyWire.weakens(old: off, new: local), "adding one does not")
    check(!PolicyWire.same(local, off), "a feed rule is part of what a profile means")
    let none = PolicyWire.merge(pol("n"), into: nil)
    check(none["partial"] == nil, "an empty list invents no field on a new policy")
    let decoded = try JSONDecoder().decode(BlockPolicy.self, from: JSONEncoder().encode(local))
    check(decoded.partial == local.partial, "partial survives the daemon's JSON round trip")
    var legacy = (try JSONSerialization.jsonObject(with: JSONEncoder().encode(local))) as! [String: Any]
    legacy["partial"] = nil
    let legacyDecoded = try JSONDecoder().decode(BlockPolicy.self, from: JSONSerialization.data(withJSONObject: legacy))
    check(legacyDecoded.partial == [], "a payload from before feed rules still decodes")
}

// MARK: Commitment Shield (port of Android's CommitmentShield)

let shield = CommitmentShield(startAt: now, domains: ["x.com"], createdUptime: 1_000)
check(shield.endAt == now.addingTimeInterval(14 * day), "shield lasts 14 days")
check(shield.canCancel(now: now.addingTimeInterval(30), uptime: 1_030), "can cancel inside the 60s grace")
check(!shield.canCancel(now: now.addingTimeInterval(61), uptime: 1_061), "cannot cancel after grace")
check(!shield.canCancel(now: now.addingTimeInterval(30), uptime: 10), "cannot cancel after a reboot")
check(!shield.isOver(now: now.addingTimeInterval(15 * day), uptime: 1_000 + 13 * day), "moving the date forward can't end it early")
check(shield.isOver(now: now, uptime: 1_000 + 14 * day), "over once 14 days elapsed on the monotonic clock")
check(shield.isOver(now: now.addingTimeInterval(14 * day), uptime: 5), "after a reboot, falls back to the wall clock")
check(!shield.isOver(now: now.addingTimeInterval(13 * day), uptime: 5), "after a reboot, not over before 14 wall-clock days")
check(shield.remaining(now: now, uptime: 1_000 + 13 * day) == day, "one day to go")

// MARK: Sync (services/api/WIRE_FORMAT.md): the Mac's mapper and the pure rules, ported from Android's SyncLogicTest/WireMapperTest

func J(_ text: String) -> JSONObject { JSONKit.object(text)! }
func rec(_ type: String, _ id: String, _ data: String, deleted: Bool = false, rev: Int = 1, at: Int64 = 1_000) -> ServerRecord {
    ServerRecord(type: type, id: id, dataJson: data, deleted: deleted, revision: rev, updatedAt: at)
}
func policyJSON(id: String, name: String = "Work", domains: [String] = ["reddit.com"], extra: String = "") -> String {
    let rules = domains.map { "{\"id\":\"r-\($0)\",\"domain\":\"\($0)\",\"includeSubdomains\":true,\"enabled\":true}" }.joined(separator: ",")
    return "{\"id\":\"\(id)\",\"name\":\"\(name)\",\"mode\":\"blocklist\",\"domainRules\":[\(rules)],\"applicationRules\":[]\(extra)}"
}
func linked(_ policies: [BlockPolicy] = [], bedtime: BedtimeSettings = BedtimeSettings(), initialPullDone: Bool = true) -> SyncLocal {
    var st = SyncState(); st.userId = "u1"; st.initialPullDone = initialPullDone
    return SyncLocal(policies: policies, bedtime: bedtime, state: st)
}
func pol(_ id: String, _ name: String = "Work", domains: [String] = ["reddit.com"]) -> BlockPolicy {
    BlockPolicy(id: id, name: name, domains: domains.map { DomainRule(domain: $0) })
}
let T: Int64 = 5_000

// An Android-shaped record: other platform's app rule, partial names, quietNotifications-style extras, a disabled rule.
let androidShaped = "{\"id\":\"p1\",\"name\":\"Deep Work\",\"mode\":\"blocklist\"," +
    "\"domainRules\":[{\"id\":\"d1\",\"domain\":\"youtube.com\",\"includeSubdomains\":true,\"enabled\":true},{\"id\":\"d2\",\"domain\":\"x.com\",\"includeSubdomains\":false,\"enabled\":false}]," +
    "\"applicationRules\":[{\"id\":\"a1\",\"platform\":\"android\",\"nativeIdentifier\":\"com.google.android.youtube\",\"displayName\":\"YouTube\",\"enabled\":true}," +
    "{\"id\":\"a2\",\"platform\":\"macos\",\"nativeIdentifier\":\"com.tinyspeck.slackmacgap\",\"displayName\":\"Slack\",\"enabled\":true}]," +
    "\"partial\":[\"YT_SHORTS\",\"FB_REELS\"],\"categories\":[\"social\"],\"notificationPolicy\":\"quiet\",\"feedRules\":{\"v\":2},\"futureField\":{\"a\":[1,2]}}"

// toLocal: only what this Mac can express, and a hostile pulled domain never reaches the model.
let importedPolicy = PolicyWire.toLocal(J(androidShaped))
check(importedPolicy.id == "p1" && importedPolicy.name == "Deep Work", "id and name imported")
check(importedPolicy.domains.map { $0.domain } == ["youtube.com", "x.com"], "both domains imported, including the disabled one")
check(importedPolicy.domains[1].enabled == false && importedPolicy.domains[1].includeSubdomains == false, "enabled and includeSubdomains survive")
check(importedPolicy.applications.map { $0.nativeIdentifier } == ["com.tinyspeck.slackmacgap"], "only macos app rules are imported")
check(importedPolicy.categories == ["social"] && importedPolicy.notificationPolicy == .quiet, "categories and notificationPolicy are read")
let hostile = PolicyWire.toLocal(J("{\"id\":\"p9\",\"name\":\"x\",\"domainRules\":[{\"domain\":\"evil.com\\n1.2.3.4 bank.com\"},{\"domain\":\"HTTP://Reddit.COM/r/all\"}]}"))
check(hostile.domains.map { $0.domain } == ["reddit.com"], "a newline-bearing domain is dropped, a URL is normalized")

// merge: the preserve-unknown rule. Edit on the Mac, everything it doesn't own survives.
var edited = importedPolicy
edited.name = "Focus"
edited.domains.append(DomainRule(domain: "tiktok.com"))
edited.domains.removeAll { $0.domain == "x.com" }              // the user removed the disabled rule
edited.applications.removeAll()                                 // and the Slack rule
let merged = PolicyWire.merge(edited, into: J(androidShaped))
check(JSONKit.string(merged, "name") == "Focus", "name merged")
check(JSONKit.array(merged, "partial").compactMap { $0 as? String } == ["YT_SHORTS", "FB_REELS"], "partial names carried over untouched")
check(JSONKit.string(merged, "notificationPolicy") == "quiet" && JSONKit.array(merged, "categories").count == 1, "categories and notificationPolicy carried over")
check((merged["futureField"] as? JSONObject) != nil && (merged["feedRules"] as? JSONObject) != nil, "unknown fields carried over")
check(JSONKit.objects(merged, "applicationRules").map { JSONKit.string($0, "platform") ?? "" } == ["android"], "android's app rule kept; the Mac's own removed rule gone")
check(JSONKit.objects(merged, "domainRules").map { JSONKit.string($0, "domain") ?? "" } == ["youtube.com", "tiktok.com"], "removed rule dropped even though it was disabled; new one appended")
check(JSONKit.string(J(androidShaped), "name") == "Deep Work", "merge never mutates its input")
let fresh = PolicyWire.merge(pol("new1", "Fresh"), into: nil)
check(JSONKit.string(fresh, "mode") == "blocklist" && JSONKit.string(fresh, "id") == "new1" && fresh["partial"] == nil, "a new policy is built from scratch with no invented fields")
check(PolicyWire.same(PolicyWire.toLocal(merged), edited), "what we push reads back as what we edited")
check(PolicyWire.same(pol("ABC"), pol("abc")), "ids compare case-insensitively")
check(PolicyWire.weakens(old: pol("p", domains: ["a.com", "b.com"]), new: pol("p", domains: ["a.com"])), "dropping a domain weakens")
check(!PolicyWire.weakens(old: pol("p", domains: ["a.com"]), new: pol("p", domains: ["a.com", "b.com"])), "adding a domain does not")
check(PolicyWire.weakens(old: pol("p", domains: ["a.com"]), new: BlockPolicy(id: "p", name: "Work", domains: [DomainRule(domain: "a.com", includeSubdomains: false)])), "turning subdomains off weakens")
check(PolicyWire.weakens(old: pol("p", domains: ["a.com"]), new: BlockPolicy(id: "p", name: "Work", domains: [DomainRule(domain: "a.com", enabled: false)])), "disabling weakens")

// allowlist and unknown modes: kept, never enforced.
do {
    let pulled = SyncLogic.applyPulled(linked(), records: [rec("policy", "al1", "{\"id\":\"al1\",\"name\":\"Only these\",\"mode\":\"allowlist\",\"domainRules\":[{\"domain\":\"docs.com\"}]}"),
                                                         rec("policy", "fut", "{\"id\":\"fut\",\"name\":\"?\",\"mode\":\"quantum\"}")], cursor: 2, inUse: [], now: T)
    check(pulled.local.policies.isEmpty, "allowlist and unknown-mode policies are not imported")
    check(pulled.local.state.policies["al1"]?.imported == false && pulled.local.state.policies["al1"]?.rawJson != nil, "but the record is kept so it is never mistaken for a deletion")
    check(SyncLogic.planPush(pulled.local, now: T).isEmpty, "and nothing is pushed for it")
}

// pull: first sync, server wins, cursor moves, other types are skipped.
do {
    let pulled = SyncLogic.applyPulled(linked([pol("p1", "Local name")]), records: [rec("policy", "P1", policyJSON(id: "p1", name: "Server name")), rec("session", "s1", "{}"), rec("shield_item", "i1", "{}")], cursor: 7, inUse: [], now: T)
    check(pulled.local.policies.count == 1 && pulled.local.policies[0].name == "Server name", "no bookkeeping yet: the server wins")
    check(pulled.local.state.cursor == 7, "cursor advances past records this build doesn't sync")
    check(pulled.local.state.policies["p1"]?.dirtyAt == nil, "ids are matched case-insensitively and the result is clean")
}

// push: a new profile goes up once, and comes back clean.
do {
    let local = linked([pol("n1", "Brand new")])
    let plan = SyncLogic.planPush(local, now: T)
    check(plan.count == 1 && plan[0].type == "policy" && plan[0].id == "n1" && plan[0].updatedAt == T && !plan[0].deleted, "a never-uploaded profile is planned with now as updatedAt")
    check(SyncLogic.planPush(linked([pol("n1")], initialPullDone: false), now: T).isEmpty, "nothing is uploaded before the first pull has finished")
    var signedOut = linked([pol("n1")]); signedOut.state.userId = nil
    check(SyncLogic.planPush(signedOut, now: T).isEmpty, "unlinked means nothing to push")
    let res = [PushOutcome(type: "policy", id: "n1", status: "applied", record: rec("policy", "n1", plan[0].dataJson!, rev: 1, at: T), code: nil)]
    let done = SyncLogic.applyPushResults(local, sent: plan, results: res, inUse: [], now: T)
    check(SyncLogic.planPush(done.local, now: T + 1).isEmpty, "after the server accepts it nothing is dirty")
    check(done.local.state.policies["n1"]?.dirtyAt == nil && done.local.state.policies["n1"]?.revision == 1, "meta settled with the server's revision")
}

// stamping at write time: only a linked device keeps bookkeeping, and edits move dirtyAt.
do {
    let base = SyncMeta(rawJson: policyJSON(id: "p1"), revision: 3)
    let p = PolicyWire.toLocal(J(policyJSON(id: "p1")))
    if case .set(let m) = SyncLogic.stampSaved(meta: base, old: p, new: pol("p1", "Renamed"), now: T) { check(m.dirtyAt == T, "a real edit stamps dirtyAt") } else { check(false, "edit must stamp") }
    if case .keep = SyncLogic.stampSaved(meta: base, old: p, new: p, now: T) {} else { check(false, "a no-op save is not an edit") }
    if case .set(let m) = SyncLogic.stampSaved(meta: SyncMeta(rawJson: base.rawJson, revision: 3, dirtyAt: 100), old: pol("p1", "Renamed"), new: p, now: T) { check(m.dirtyAt == nil, "editing back to the server's version is clean again") } else { check(false, "revert must stamp") }
    if case .remove = SyncLogic.stampDeleted(meta: nil, now: T) {} else { check(false, "deleting something the server never saw needs no tombstone") }
    guard case .set(let tomb) = SyncLogic.stampDeleted(meta: base, now: T) else { fatalError("tombstone expected") }
    check(tomb.deleted && tomb.dirtyAt == T, "deleting a known profile records a tombstone")
    var local = linked(); local.state.policies["p1"] = tomb
    let plan = SyncLogic.planPush(local, now: T + 1)
    check(plan.count == 1 && plan[0].deleted && plan[0].dataJson == nil && plan[0].updatedAt == T, "the tombstone is pushed with the delete time")
    let ack = SyncLogic.applyPushResults(local, sent: plan, results: [PushOutcome(type: "policy", id: "p1", status: "applied", record: rec("policy", "p1", "{}", deleted: true, rev: 4, at: T), code: nil)], inUse: [], now: T)
    check(ack.local.state.policies["p1"] == nil, "an acknowledged delete forgets the record")
}

// last-write-wins between a dirty local edit and a pulled record.
do {
    var local = linked([pol("p1", "Edited here")])
    local.state.policies["p1"] = SyncMeta(rawJson: policyJSON(id: "p1", name: "Old"), revision: 1, dirtyAt: 3_000)
    let older = SyncLogic.applyPulled(local, records: [rec("policy", "p1", policyJSON(id: "p1", name: "Elsewhere"), rev: 2, at: 2_000)], cursor: 1, inUse: [], now: T)
    check(older.local.policies[0].name == "Edited here", "a newer local edit beats an older server change")
    check(SyncLogic.planPush(older.local, now: T).first?.updatedAt == 3_000, "and keeps its own timestamp for the push")
    let newer = SyncLogic.applyPulled(local, records: [rec("policy", "p1", policyJSON(id: "p1", name: "Elsewhere"), rev: 2, at: 4_000)], cursor: 1, inUse: [], now: T)
    check(newer.local.policies[0].name == "Elsewhere", "a newer server change beats an older local edit")
}

// stale push answers adopt the server copy; rejections are remembered until the user edits again.
do {
    let local = linked([pol("n1", "Mine")])
    let plan = SyncLogic.planPush(local, now: T)
    let stale = SyncLogic.applyPushResults(local, sent: plan, results: [PushOutcome(type: "policy", id: "n1", status: "stale", record: rec("policy", "n1", policyJSON(id: "n1", name: "Server won"), rev: 5, at: 9_000), code: nil)], inUse: [], now: T)
    check(stale.local.policies[0].name == "Server won", "stale: the server's newer copy is adopted")
    let rej = SyncLogic.applyPushResults(local, sent: plan, results: [PushOutcome(type: "policy", id: "n1", status: "rejected", record: nil, code: "invalid_data")], inUse: [], now: T)
    check(rej.rejected == 1, "rejected is counted")
    // A rejected never-uploaded profile has no meta row to hang the fingerprint on yet, so it is retried; a known one is not.
    var known = linked([pol("k1", "Changed")]); known.state.policies["k1"] = SyncMeta(rawJson: policyJSON(id: "k1", name: "Was"), revision: 1, dirtyAt: 10)
    let kp = SyncLogic.planPush(known, now: T)
    let kr = SyncLogic.applyPushResults(known, sent: kp, results: [PushOutcome(type: "policy", id: "k1", status: "rejected", record: nil, code: "invalid_data")], inUse: [], now: T)
    check(SyncLogic.planPush(kr.local, now: T).isEmpty && SyncLogic.rejectedCount(kr.local.state) == 1, "a rejected payload is not resent until it changes")
    var again = kr.local; again.policies = [pol("k1", "Changed again")]
    check(SyncLogic.planPush(again, now: T).count == 1, "a new edit is sent again")
}

// SAFETY: pulled data never deletes or weakens a profile a running session is using. Bedtime merely pointing at a profile is not "in use".
do {
    var local = linked([pol("p1", "Strict profile", domains: ["a.com", "b.com"])])
    local.state.policies["p1"] = SyncMeta(rawJson: policyJSON(id: "p1", name: "Strict profile", domains: ["a.com", "b.com"]), revision: 1)
    let del = SyncLogic.applyPulled(local, records: [rec("policy", "p1", "{}", deleted: true, rev: 2, at: 8_000)], cursor: 1, inUse: ["p1"], now: T)
    check(del.local.policies.count == 1, "a remote delete never removes a profile a running session is using")
    let up = SyncLogic.planPush(del.local, now: T)
    check(up.count == 1 && !up[0].deleted && up[0].updatedAt > 8_000, "it is pushed back as the newer change so it wins on the server")
    let free = SyncLogic.applyPulled(local, records: [rec("policy", "p1", "{}", deleted: true, rev: 2, at: 8_000)], cursor: 1, inUse: [], now: T)
    check(free.local.policies.isEmpty && free.local.state.policies["p1"] == nil, "an unreferenced profile is deleted by a remote delete")
    let weaker = SyncLogic.applyPulled(local, records: [rec("policy", "p1", policyJSON(id: "p1", name: "Strict profile", domains: ["a.com"]), rev: 2, at: 8_000)], cursor: 1, inUse: ["p1"], now: T)
    check(weaker.local.policies[0].domains.count == 2, "a remote edit that weakens a referenced profile is not applied")
    check(SyncLogic.planPush(weaker.local, now: T).count == 1, "and our copy is pushed back")
    let stronger = SyncLogic.applyPulled(local, records: [rec("policy", "p1", policyJSON(id: "p1", name: "Strict profile", domains: ["a.com", "b.com", "c.com"]), rev: 2, at: 8_000)], cursor: 1, inUse: ["p1"], now: T)
    check(stronger.local.policies[0].domains.count == 3, "a remote edit that only adds blocks is applied")
    var bedOnly = local; bedOnly.bedtime.policyId = "p1"
    let bedDel = SyncLogic.applyPulled(bedOnly, records: [rec("policy", "p1", "{}", deleted: true, rev: 2, at: 8_000)], cursor: 1, inUse: [], now: T)
    check(bedDel.local.policies.isEmpty, "a profile only bedtime points at (no session) IS deleted by a remote delete")
    let bedWeak = SyncLogic.applyPulled(bedOnly, records: [rec("policy", "p1", policyJSON(id: "p1", name: "Strict profile", domains: ["a.com"]), rev: 2, at: 8_000)], cursor: 1, inUse: [], now: T)
    check(bedWeak.local.policies[0].domains.count == 1 && SyncLogic.planPush(bedWeak.local, now: T).filter { $0.type == "policy" }.isEmpty, "and a remote edit that removes a site applies and the profile is not pushed back")
    let toAllow = SyncLogic.applyPulled(local, records: [rec("policy", "p1", "{\"id\":\"p1\",\"name\":\"x\",\"mode\":\"allowlist\"}", rev: 2, at: 8_000)], cursor: 1, inUse: ["p1"], now: T)
    check(toAllow.local.policies.count == 1, "a referenced profile that turns into an allowlist elsewhere keeps being enforced as it was")
}

// first sign-in merge: server wins singletons, union profiles, drop an untouched starter only when the account has profiles.
do {
    let seed = BlockPolicy.makeSeed()
    check(seed.isUntouchedSeed, "the starter profile is recognised")
    var edited = seed; edited.domains.removeLast()
    check(!edited.isUntouchedSeed, "an edited starter is a real profile")
    let hasServer = SyncLogic.applyPulled(linked([seed, pol("mine", "Mine")], initialPullDone: false), records: [rec("policy", "srv", policyJSON(id: "srv", name: "From phone"))], cursor: 1, inUse: [], now: T)
    let done = SyncLogic.finishInitialPull(hasServer.local, referenced: [])
    check(done.policies.map { $0.name }.sorted() == ["From phone", "Mine"], "starter dropped, own profile kept, account's profile added")
    check(done.state.initialPullDone, "initial pull marked done")
    check(SyncLogic.planPush(done, now: T).map { $0.id } == ["mine"], "only the profile the account lacks goes up")
    let empty = SyncLogic.finishInitialPull(linked([seed], initialPullDone: false), referenced: [])
    check(empty.policies.count == 1, "with nothing on the server the starter stays")
    let pinned = SyncLogic.finishInitialPull(hasServer.local, referenced: [seed.id.lowercased()])
    check(pinned.policies.contains { $0.isUntouchedSeed }, "a starter that bedtime or a session points at is never dropped")
    let tombOnly = SyncLogic.applyPulled(linked([seed], initialPullDone: false), records: [rec("policy", "gone", "{}", deleted: true)], cursor: 1, inUse: [], now: T)
    check(SyncLogic.finishInitialPull(tombOnly.local, referenced: []).policies.count == 1, "a server that only holds tombstones doesn't cost the starter either")
}

// bedtime: server wins when we have no bookkeeping; a newer local edit wins; Android's extras survive our edit.
do {
    var custom = BedtimeSettings(); custom.sleepMinute = 1_400; custom.policyId = "ABC"
    let serverBed = "{\"enabled\":true,\"windDownMinute\":1260,\"sleepMinute\":1320,\"wakeMinute\":400,\"lockAtSleep\":false,\"policyId\":\"P1\",\"quietNotifications\":true}"
    let first = SyncLogic.applyPulled(linked([], bedtime: custom), records: [rec("bedtime_settings", "default", serverBed)], cursor: 1, inUse: [], now: T)
    check(first.bedtimeChanged && first.local.bedtime.sleepMinute == 1_320 && first.local.bedtime.policyId == "p1" && !first.local.bedtime.lockAtSleep, "server wins the singleton on first sync (ids lowercased)")
    var mine = first.local.bedtime; mine.wakeMinute = 450
    let stamped = SyncLogic.stampBedtime(meta: first.local.state.bedtime, old: first.local.bedtime, new: mine, now: 6_000)
    check(stamped?.dirtyAt == 6_000, "a bedtime edit stamps dirtyAt")
    var local = first.local; local.bedtime = mine; local.state.bedtime = stamped
    let plan = SyncLogic.planPush(local, now: T)
    let sent = J(plan[0].dataJson!)
    check(plan.count == 1 && plan[0].type == "bedtime_settings" && plan[0].id == "default" && JSONKit.int(sent, "wakeMinute") == 450, "the edit is pushed")
    check(JSONKit.bool(sent, "quietNotifications") == true, "quietNotifications (Android's) is preserved")
    let kept = SyncLogic.applyPulled(local, records: [rec("bedtime_settings", "default", serverBed, rev: 2, at: 2_000)], cursor: 2, inUse: [], now: T)
    check(kept.local.bedtime.wakeMinute == 450 && !kept.bedtimeChanged, "a newer local bedtime edit beats an older server record")
    check(SyncLogic.planPush(linked([], bedtime: BedtimeSettings()), now: T).isEmpty, "default bedtime that never reached the server is not worth uploading")
    let bad = SyncLogic.applyPulled(linked(), records: [rec("bedtime_settings", "default", "{\"sleepMinute\":99999,\"wakeMinute\":-5}")], cursor: 1, inUse: [], now: T)
    check(bad.local.bedtime.sleepMinute == BedtimeSettings().sleepMinute && bad.local.bedtime.wakeMinute == BedtimeSettings().wakeMinute, "out-of-range minutes are ignored, not trusted")
}

// bedtime settings saved before newer fields existed (or by a peer that still sends a retired one) still decode.
do {
    let old = try! JSONDecoder().decode(BedtimeSettings.self, from: Data("{\"enabled\":true,\"windDownMinute\":1320,\"sleepMinute\":1380,\"wakeMinute\":420,\"lockAtSleep\":true,\"greyscale\":true}".utf8))
    check(old.enabled && old.windDownMinute == 1320 && old.lockAtSleep, "settings with a retired or missing field still decode")
}

// linking: same account resumes, a different one starts clean; sign-out keeps nothing but tokens (state is the caller's).
do {
    var st = SyncState(); st.userId = "u1"; st.cursor = 9
    check(SyncLogic.link(st, userId: "u1").cursor == 9, "same account resumes")
    check(SyncLogic.link(st, userId: "u2").cursor == 0 && SyncLogic.link(st, userId: "u2").userId == "u2", "a different account starts clean")
    check(SyncLogic.unlink().userId == nil, "unlink forgets the account")
}

// JSON helpers: booleans are not numbers and numbers are not booleans.
do {
    let o = J("{\"b\":true,\"n\":1,\"s\":\"x\",\"z\":null}")
    check(JSONKit.bool(o, "b") == true && JSONKit.int(o, "b") == nil, "a boolean is not an int")
    check(JSONKit.int(o, "n") == 1 && JSONKit.bool(o, "n") == nil, "1 is not true")
    check(JSONKit.string(o, "z") == nil && JSONKit.string(o, "s") == "x", "null reads as absent")
    check(SyncTime.ms(fromISO: "2026-10-01T09:30:00.000Z") == 1_790_847_000_000 && SyncTime.iso(fromMs: 1_790_847_000_000) == "2026-10-01T09:30:00.000Z", "ISO-8601 round trip")
    check(SyncTime.ms(fromISO: "2026-10-01T09:30:00Z") == 1_790_847_000_000, "ISO-8601 without fraction")
}

// MARK: Menu bar countdown text (replaces SwiftUI's Text(timerInterval:), which broke MenuBarExtra)

check(Countdown.text(remaining: 0) == "0:00", "zero")
check(Countdown.text(remaining: 59) == "0:59", "seconds only")
check(Countdown.text(remaining: 61) == "1:01", "a minute and a second")
check(Countdown.text(remaining: 24 * 60 + 35) == "24:35", "minutes and seconds")
check(Countdown.text(remaining: 3599) == "59:59", "just under an hour")
check(Countdown.text(remaining: 3600) == "1:00:00", "an hour")
check(Countdown.text(remaining: 3725) == "1:02:05", "hours, minutes, seconds")
check(Countdown.text(remaining: -5) == "0:00", "never negative")
check(Countdown.text(remaining: 59.2) == "1:00", "a fractional second rounds up, so it never shows 0:00 while time remains")

// MARK: What the block screen says (BlockCopyTest on Android)

check(BlockCopy.title(appName: "YouTube") == "NowFocus closed YouTube", "title names NowFocus and the app")
check(BlockCopy.title(appName: nil) == "NowFocus closed this app", "title without an app name")
check(BlockCopy.reason(.focusSession, until: "3:45 PM", appName: "YouTube") == "You're in a focus session until 3:45 PM.", "focus session reason")
check(BlockCopy.reason(.bedtime, until: "6:00 AM", appName: "YouTube") == "It's bedtime wind-down until 6:00 AM.", "bedtime reason")
check(BlockCopy.reason(.commitmentShield, until: "Oct 9", appName: "YouTube") == "Locked by your Commitment Shield until Oct 9.", "shield reason")
check(BlockCopy.reason(.dailyLimit, until: "ignored", appName: "YouTube", limitMinutes: 30) == "You've used your 30 minutes of YouTube today. It's back at midnight.", "daily limit reason")
check(BlockCopy.timeLabel(.bedtime) == "Left in bedtime" && BlockCopy.timeLabel(.focusSession) == "Left in session", "countdown labels")

// MARK: DNS during a session (core/src/dns_resolvers.rs on Windows, DnsUpstream.kt on Android)

check(DNSChoice().servers.isEmpty && DNSChoice(provider: .custom, custom: "").servers.isEmpty && DNSChoice(provider: .custom, custom: "1.2.3").servers.isEmpty, "System, and a custom list that is empty or not valid, touch nothing")
check(DNSChoice(provider: .adguardFamily).servers == ["94.140.14.15", "94.140.15.16"], "AdGuard Family preset")
check(DNSChoice(provider: .cloudflareFamily).servers == ["1.1.1.3", "1.0.0.3"], "Cloudflare Family preset")
check(DNSChoice(provider: .cleanbrowsingFamily).servers == ["185.228.168.168", "185.228.169.168"], "CleanBrowsing Family preset")
check(DNSChoice(provider: .quad9).servers == ["9.9.9.9", "149.112.112.112"], "Quad9 preset")
check(DNSChoice(provider: .custom, custom: "192.168.1.2 , 1.1.1.1;1.1.1.1  2606:4700:4700::1111").servers == ["192.168.1.2", "1.1.1.1", "2606:4700:4700::1111"], "custom list is split, deduplicated and kept in order")
check(DNSResolvers.parse("1.1.1.1 1.0.0.1 8.8.8.8 8.8.4.4 9.9.9.9") == nil, "more than four servers is refused")
for bad in ["0.0.0.0", "255.255.255.255", "224.0.0.1", "::", "ff02::1", "fe80::1", "dns.example.com", "1.1.1.1/24", "1.1.1.1; rm -rf /"] {
    check(DNSResolvers.parse(bad) == nil, "\(bad) must be refused")
}
check(DNSResolvers.parse("127.0.0.1") == ["127.0.0.1"], "a local resolver is fine")
check(DNSResolvers.validate(["1.1.1.3", "1.0.0.3"]) != nil && DNSResolvers.validate([]) == nil && DNSResolvers.validate(["$(reboot)"]) == nil, "the daemon revalidates what arrives over XPC")
do {
    let defaults = UserDefaults(suiteName: "nowfocus.corechecks.dns")!
    defaults.removePersistentDomain(forName: "nowfocus.corechecks.dns")
    check(DNSChoice.load(defaults) == DNSChoice(), "default is System")
    DNSChoice(provider: .quad9, custom: "10.0.0.2").save(defaults)
    check(DNSChoice.load(defaults) == DNSChoice(provider: .quad9, custom: "10.0.0.2"), "the choice and the typed addresses are remembered")
    defaults.removePersistentDomain(forName: "nowfocus.corechecks.dns")
}

// Always-on DNS (core/src/dns_resolvers.rs: the same transitions)
do {
    let v = ["9.9.9.9"]
    check(DNSRules.afterSessionApply(nil, v).keep == false && DNSRules.afterSessionApply(DNSState(servers: ["1.1.1.3"], keep: false), v).keep == false, "a session does not turn always-on on")
    let kept = DNSRules.afterSessionApply(DNSState(servers: ["1.1.1.3"], keep: true), v)
    check(kept.keep && kept.servers == v, "a session keeps always-on, and a new pick replaces the servers")
    let session = DNSState(servers: v, keep: false), keeping = DNSState(servers: v, keep: true)
    check(DNSRules.onClear(nil) == .restore && DNSRules.onClear(session) == .restore && DNSRules.onClear(keeping) == .nothing, "ending a session restores unless kept")
    check(DNSRules.onRelease(nil) == .nothing && DNSRules.onRelease(session) == .nothing && DNSRules.onRelease(keeping) == .restore, "turning always-on off restores only when it was on")
    check(DNSRules.onStart(keeping) == .apply(v) && DNSRules.onStart(session) == .restore && DNSRules.onStart(nil) == .restore, "daemon start re-applies a kept DNS and undoes the rest")
    let want = ["94.140.14.15", "94.140.15.16"]
    check(DNSRules.status(expected: [], alwaysOn: true, sessionLive: true, serviceUp: true, applied: nil) == "off", "status: off")
    check(DNSRules.status(expected: want, alwaysOn: false, sessionLive: false, serviceUp: true, applied: nil) == "waiting", "status: waiting")
    check(DNSRules.status(expected: want, alwaysOn: true, sessionLive: false, serviceUp: false, applied: nil) == "unavailable", "status: daemon down")
    check(DNSRules.status(expected: want, alwaysOn: true, sessionLive: false, serviceUp: true, applied: nil) == "notApplied", "status: nothing applied")
    check(DNSRules.status(expected: want, alwaysOn: true, sessionLive: false, serviceUp: true, applied: DNSState(servers: ["8.8.8.8"], keep: true)) == "notApplied", "status: another server applied")
    check(DNSRules.status(expected: want, alwaysOn: false, sessionLive: true, serviceUp: true, applied: DNSState(servers: want, keep: false)) == "active", "status: a live session counts")
    // A choice saved before always-on existed still loads, as off.
    let old = try! JSONDecoder().decode(DNSChoice.self, from: Data("{\"provider\":\"quad9\",\"custom\":\"\"}".utf8))
    check(old == DNSChoice(provider: .quad9, custom: "", alwaysOn: false), "an older saved choice decodes with always-on off")
}

// Reading networksetup (real shapes, including this Mac's encrypted-DNS profile, which reports a URL)
check(NetworkSetupOutput.services("An asterisk (*) denotes that a network service is disabled.\nThunderbolt Bridge\n*USB 10/100 LAN\nWi-Fi\n") == ["Thunderbolt Bridge", "Wi-Fi"], "services: header and disabled ones are dropped")
check(NetworkSetupOutput.dnsServers("There aren't any DNS Servers set on Wi-Fi.\n") == [], "automatic DNS reads as empty")
check(NetworkSetupOutput.dnsServers("192.168.1.1\n8.8.8.8\n") == ["192.168.1.1", "8.8.8.8"], "static servers")
check(NetworkSetupOutput.dnsServers("https://family.adguard-dns.com/dns-query\n") == nil, "an encrypted-DNS profile is left alone, never overwritten or restored from")
check(NetworkSetupOutput.dnsServers("2606:4700:4700::1111\n") == ["2606:4700:4700::1111"], "IPv6 servers are kept")

// MARK: Existing self-checks

HistoryStats.runSelfCheck()
BedtimeSchedule.runSelfCheck()

if failures > 0 { print("\(failures) check(s) failed"); exit(1) }
print("core checks passed")
