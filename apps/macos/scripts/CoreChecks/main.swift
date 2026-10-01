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

// MARK: Feed rules (the cross-platform contract: same six names, same order, same labels as Android's PartialRule)

check(FeedRules.all.map { $0.id } == ["YT_SHORTS", "YT_HOME", "YT_RELATED", "FB_REELS", "IG_REELS", "X_FOR_YOU"], "the six rule names and their order")
check(FeedRules.all.map { $0.label } == ["YouTube Shorts", "YouTube Home feed", "YouTube up next / related", "Facebook Reels", "Instagram Reels & Explore", "X \u{201C}For you\u{201D} feed"], "rule labels match Android")
check(FeedRules.all.allSatisfy { !$0.detail.isEmpty }, "every rule explains itself")

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

// MARK: Existing self-checks

HistoryStats.runSelfCheck()
BedtimeSchedule.runSelfCheck()

if failures > 0 { print("\(failures) check(s) failed"); exit(1) }
print("core checks passed")
