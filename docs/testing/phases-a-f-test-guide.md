# NowFocus: hands-on test guide for phases A to F

Branch `feat/phases-a-f`. This guide is for you to run on your own devices and write results into. When you are done, tell me "read the test results" and I will read this file and take it from there.

**How to fill it in.** Each test has steps, what you should see, and a result line. Change `[ ]` to `[x]` on exactly one of pass / fail / skipped, and write anything odd under **Notes** (what you saw instead, the time, a screenshot name). A failure with a note is worth more to me than ten passes.

## What was and wasn't checked before you got this

| Area | Checked automatically | NOT checked by anyone yet |
|---|---|---|
| Android logic (passes, cheat day, schedules, limits, streak, score, session sync rules) | 269 unit tests pass, including live sync tests against a local API | Every screen and flow on a real phone, except installing it |
| Android service behaviour (Accessibility, VPN, notifications, alarms) | nothing | all of it |
| Windows logic (cheat day, passes, schedules, streak, sync rules) | 100+ Rust tests; two Windows sync engines converged through the real API (profiles, bedtime, a session started on one and joined and ended on the other) | The app has **never run on a real Windows PC**. Install, the service, the hosts file, the overlay, the tray, the credential store: all untested |
| Server | 64 end-to-end tests pass locally, including the new 24-hour session cap | **Not deployed.** `api.nowfocus.online` does not have the cap yet |

If something fails, assume it is real. Several parts (Windows especially) have never run before.

## Before you start

**Android (Samsung Galaxy M52 5G, Android 13)**
- Build and install the branch: `cd apps/android && ./gradlew installDebug` with the phone on USB. This replaces the installed 0.3 in place and keeps its data.
- Replaying onboarding (test A4) needs the app's data cleared, which **erases your sessions, stats and profiles on that phone**. Only do it if you accept that, or use a second phone/emulator.
- Optional but useful: keep `adb logcat -s FocusVpn AndroidRuntime` open in a terminal.

**Windows PC (Windows 10 or 11, 64-bit, admin rights)**
- Get the installer: GitHub, repository `NowFocus`, Actions, workflow **Windows**, the latest run on `feat/phases-a-f`, artifact **NowFocus-windows-x64-setup**. Unzip it and run the `.exe`.
- It is unsigned: SmartScreen says "Windows protected your PC". Choose **More info**, then **Run anyway**. Windows asks for admin approval once (it registers a background service called `NowFocusService`).
- Uninstall later from Settings, Apps. The uninstaller removes the service and the blocks it added.
- Handy commands (run in PowerShell): `sc query NowFocusService` (should say RUNNING), `Get-Content C:\Windows\System32\drivers\etc\hosts` (blocks appear between `### FOCUS APP BLOCK START ###` markers).
- Write down: Windows version, whether the PC has other VPN or browser DNS-over-HTTPS settings on.

**Accounts.** Make one test account (any email you can type, password 8+ characters) from the Windows app (Devices, Account, Create account) or from the phone (Devices, Account). Use the same account on both. The server is `https://api.nowfocus.online` (production). Your test account is a real account there; delete it at the end from the Account screen.

**Record these**

| | |
|---|---|
| Date and tester | |
| Android phone, Android version, build installed | |
| Windows version and PC | |
| Installer artifact run number | |

---

## Results summary (fill in at the end)

| Block | Pass | Fail | Skipped |
|---|---|---|---|
| W0 Windows first run | | | |
| A Quick fixes (Android) | | | |
| A-W Quick fixes (Windows) | | | |
| B Pass and cheat day (Android) | | | |
| B-W Pass and cheat day (Windows) | | | |
| C Schedules | | | |
| D Limits, friction, insights (Android only) | | | |
| E Streak, score, share | | | |
| F Account and cross-device sessions | | | |

---

# W0. Windows first run (do this first: nothing else on Windows counts until it passes)

### W0.1 Install and the service
1. Run the installer, approve the admin prompt.
2. Open PowerShell: `sc query NowFocusService`.
3. Start NowFocus from the Start menu.

**Expect:** installs without errors; the service state is `RUNNING`; the app opens on the "Welcome to NowFocus on this PC" screen; Devices shows "NowFocus Service" as running.

**Result:** [ ] pass  [ ] fail  [ ] skipped
**Notes:**

### W0.2 Website blocking
1. In Profiles, add `example.com` to a profile (or use the onboarding box in W0.3).
2. Start a 10-minute Normal session on it.
3. Open `example.com` in Chrome, Edge and Firefox. Open the hosts file as above.
4. End the session and reload.

**Expect:** the site fails to load during the session ("refused to connect"); the hosts file has the block markers; after ending, the site loads and the block lines are gone.
Also note whether Chrome with "Use secure DNS" on, and Firefox with DNS over HTTPS on, still block it (this is a known weak spot).

**Result:** [ ] pass  [ ] fail  [ ] skipped
**Notes (browser by browser):**

### W0.3 App blocking and the overlay
1. Add an app to a profile (Profiles, add application, pick an .exe such as Notepad `C:\Windows\System32\notepad.exe` or a game).
2. Start a session. Open that app.

**Expect:** the app closes and a full-screen "can wait" overlay appears on every monitor; "Back to work" dismisses it.

**Result:** [ ] pass  [ ] fail  [ ] skipped
**Notes:**

### W0.4 Tray, quit and restart
1. During a session, close the window with X: it should hide to the tray. Right-click the tray icon, Quit.
2. Open Task Manager and end `NowFocus.exe` during a session (this is the known weak point).
3. Restart the app mid-session.

**Expect:** Quit is refused during a session. Killing the app stops app blocking (known). On restart the session is still running and blocking comes back within about 30 seconds (new: the tick re-applies it).

**Result:** [ ] pass  [ ] fail  [ ] skipped
**Notes:**

### W0.5 Commitment (14 days, held by the service)
1. Commitment screen: add `example.org`, "Start 14-Day Commitment", confirm.
2. Within 60 seconds use the Undo button.

**Expect:** a confirm appears first; Undo works only inside the first minute. (Do not test past the grace window unless you want a 14-day block.)

**Result:** [ ] pass  [ ] fail  [ ] skipped
**Notes:**

---

# A. Quick fixes: Android

### A1. Confirm before ending a Normal session
1. Start a Normal session. On the Active screen tap **End session early**.

**Expect:** a screen "End early?" saying "End this session now? <time> left." with **End session now** and **Keep going** (it did not end by itself). Keep going returns to the session; End session now ends it.

**Result:** [ ] pass  [ ] fail  [ ] skipped
**Notes:**

### A2. Confirm before removing a profile
1. Rules tab. On any profile tap **Remove**.
2. Cancel. Then set Bedtime (Rules, Bedtime Wind-Down) to use that profile and tap **Remove** again.

**Expect:** a dialog "Remove <name>?"; **Cancel** keeps it. With Bedtime pointing at it, the text adds that Bedtime will stop blocking until you pick another.

**Result:** [ ] pass  [ ] fail  [ ] skipped
**Notes:**

### A3. Blocked-site explanation
1. Start a session on a profile with a blocked site (the default has youtube.com). Open Chrome and go to that site.
2. Pull down the notification shade. Lock the phone and look at the lock screen.
3. Leave the phone for 10 minutes with the session running and **no browser open**, screen on a few times.

**Expect:** the session notification says "Blocked sites won't load until the timer ends." After the site fails, one notification "NowFocus blocked a site / It will load again when the block ends." appears (at most once a minute, names no site, **not** visible on the lock screen). In step 3, check whether that notification appears with nobody trying a site: if it does, tell me, I will remove it.

**Result:** [ ] pass  [ ] fail  [ ] skipped
**Notes (did it fire by itself?):**

### A4. Onboarding ends on a first session (clears app data)
1. Settings, Apps, NowFocus, Storage, **Clear data** (this erases everything on the phone's NowFocus). Open NowFocus.
2. Go through Welcome, Goals, People, Permissions. On Permissions leave Accessibility **off**.
3. On the last step, pick an app. Try to start.
4. Turn Accessibility on (use "Allow restricted settings" if the switch is grey) and come back.
5. Tap **Start 10 minutes**. Open the app you picked.

**Expect:** 5 steps ("STEP 5 OF 5, YOUR FIRST SESSION"). With Accessibility off the start button stays disabled and the step shows the Accessibility row. With it on, the session starts on a profile called "First focus" and the app you picked is bounced to the "This can wait" screen. Android 13 asked for notification permission on this step.

**Result:** [ ] pass  [ ] fail  [ ] skipped
**Notes:**

# A-W. Quick fixes: Windows

### A-W1. Confirm before ending a Normal session (3 places)
1. Start a Normal session. Click **End session early** on the Active screen.
2. Trigger the shield (open a blocked app) and click **I really need it**.

**Expect:** a native "End this session now?" dialog with **End session** and **Keep going** in both. (The tray flyout view is rarely reachable on Windows; skip it if you can't find it.)

**Result:** [ ] pass  [ ] fail  [ ] skipped
**Notes:**

### A-W2. Onboarding first session
1. Close the app (tray, Quit), delete the folders `%APPDATA%\app.getnowfocus.windows` and `%LOCALAPPDATA%\app.getnowfocus.windows` (or use a fresh Windows user), and open the app. This also resets the Welcome screen.
2. Type `example.com` in "Your first session", click **Start 10 minutes**.

**Expect:** a profile "First focus" is created (not the "Deep Work" starter), a 10-minute session starts and the Active screen opens. If the service isn't running you should see an error under the box and still be able to click Not now.

**Result:** [ ] pass  [ ] fail  [ ] skipped
**Notes:**

### A-W3. Blocked-site wording
The Active screen should say blocked sites show "can't be reached" until the session ends.

**Result:** [ ] pass  [ ] fail  [ ] skipped
**Notes:**

---

# B. Emergency pass and cheat day

**Rules you agreed:** a pass opens one blocked **app** for 5 minutes, twice per session, only in Normal and Strict sessions (never Locked, Bedtime or the Shield). A cheat day is planned at least 24 hours ahead, once a week, covers sessions, Bedtime, schedules and limits, and never touches the Commitment Shield.

## Android

### B1. Pass in a Normal session
1. Make a profile blocking a messaging app (for example WhatsApp). Start a Normal session on it.
2. Open WhatsApp: the block screen appears. Tap **Open WhatsApp for 5 min (2 left)**.
3. Use it, leave it, come back after the 5 minutes pass, and open it again.
4. Take the second pass the same way. Try a third time.

**Expect:** WhatsApp opens and stays usable for 5 minutes. After it ends, if you are still inside it the app is closed again (this is the new re-check). The count goes 2, 1, then no pass button on the third block screen.

**Result:** [ ] pass  [ ] fail  [ ] skipped
**Notes:**

### B2. No pass in Locked, or for the Shield
1. Start a Locked session on a profile with an app. Open the app.
2. (Optional, 14 days) a Commitment Shield app block.

**Expect:** the block screen has **no** "Open … for 5 min" button in Locked, and none for Shield blocks.

**Result:** [ ] pass  [ ] fail  [ ] skipped
**Notes:**

### B3. Cheat day planning rules
1. Rules, **Cheat day**. Look at the days offered.
2. Pick one, **Yes, plan it**. Reopen the screen.
3. Cancel it. Plan it again. Try to plan a second day within a week of the first.

**Expect:** tomorrow is not offered unless it is at least 24 hours away (so usually "the day after tomorrow" first). After planning, the screen says "Set for <day>"; Cancel frees your week. A second day within 7 days of a planned one isn't offered.

**Result:** [ ] pass  [ ] fail  [ ] skipped
**Notes:**

### B4. Cheat day actually pauses blocking
This needs the day to arrive. Two ways: wait, or plan the earliest day and then set the phone's date to that day at 00:05 (Settings, General management, Date and time, turn **Automatic date and time** off; set it back afterwards).
1. On the cheat day, start a Normal session and open a blocked app and a blocked site.
2. Look at Home and the Active screen. Check Bedtime/DND does not engage.
3. Tap **End it early** (Cheat day screen).

**Expect:** nothing is blocked; Home shows "Cheat day · blocking is paused until midnight"; Active shows "Cheat day: blocking is paused until midnight." A Commitment Shield, if you have one, still blocks. After ending it early, blocking returns at once.

**Result:** [ ] pass  [ ] fail  [ ] skipped
**Notes:**

## Windows

### B-W1. Pass
1. Normal or Strict session on a profile with an app (W0.3). Open the app, overlay appears.
2. Click **Let me use <app> for 5 min (2 left)**, then open the app again.

**Expect:** the app stays open for 5 minutes; a second pass later; then no pass button. None in Locked. (Passes are forgotten if NowFocus restarts: known.)

**Result:** [ ] pass  [ ] fail  [ ] skipped
**Notes:**

### B-W2. Cheat day
1. Sidebar, **Cheat day**: same rules as B3 (plan, cancel).
2. As in B4, set the PC date to the cheat day at 00:05 and start a session.

**Expect:** the session shows "Cheat day: blocking is paused"; sites and apps are not blocked; the hosts file has no session block lines; the Commitment (if any) is untouched. After the day or "End it early", the block comes back within 30 seconds.

**Result:** [ ] pass  [ ] fail  [ ] skipped
**Notes:**

---

# C. Schedules (both platforms)

### C1. A schedule starts a session by itself
1. Android: Rules, **Schedules**, **+ New schedule**. Windows: sidebar, **Schedules**. Set today's day, start 3 minutes from now, end 20 minutes from now, a profile with a blocked app, mode Normal. Save.
2. Wait. (Android alarms are inexact: it can start a few minutes late.)

**Expect:** a session starts by itself at about that time; the app/site is blocked; Active shows the countdown to the schedule's end.

**Result:** [ ] pass  [ ] fail  [ ] skipped
**Notes (how late did it start?):**

### C2. Ending it early sticks; other rules
1. End that session early (confirm it).
2. Wait a couple of minutes, or reopen the app.
3. Start another schedule that overlaps a running manual session. Plan a cheat day and check a schedule doesn't start on it (use the date trick from B4 if you like).

**Expect:** the same window does not restart after you ended it. A schedule never replaces a running session. Nothing starts on a cheat day.

**Result:** [ ] pass  [ ] fail  [ ] skipped
**Notes:**

### C3. Survives restarts
Reboot the phone (Android) / restart the PC app (Windows) before a schedule's start time.

**Expect:** it still starts (Android re-arms its alarm at boot; Windows only runs while the app is running, and the app has no autostart yet: **expected to fail if the app isn't open**, tell me what you see).

**Result:** [ ] pass  [ ] fail  [ ] skipped
**Notes:**

---

# D. Daily limits, opening friction, insights (Android only; Windows has app daily limits, unverified on a real PC)

### D1. Daily limit
1. Rules, **Daily limits**. Tap **Allow usage access** and switch NowFocus on.
2. **+ Add a limit…**, pick an app, choose 15 minutes.
3. Use that app for 15 minutes in total (spread out is fine). Keep checking the screen's "N of 15 min today".
4. Open it again after it is used up.
5. On the "Daily limit" screen tap **Open <app> for N min (2 left)**. N is 5 plus 1 per day of your current streak (see Stats), up to 15, and a line under the button names the streak. Use the app, and see it blocked again when the N minutes end. Do it a second time, then check the third is not offered.
6. Raise the limit to 60. Then, on another app that still has time left, remove its limit. Then remove the limit of the used-up app.

**Expect:** usage shown on the screen is roughly right. When used up you get a "Daily limit" screen saying you've used your 15 minutes and it is back at midnight, with the pass (two per app per day, 5 minutes plus 1 per streak day up to 15, back at midnight) and no "I really need it". The Limits dialog of a used-up limit says raising or removing it counts from midnight. Raising a limit counts from midnight; lowering one counts at once; **removing one with time left counts at once (the app opens straight away)**, while removing a used-up one says "removed at midnight". A cheat day pauses limits. The pass is offered on Normal/Strict session blocks (5 minutes) and on daily limits (streak-sized), never in Locked sessions, Bedtime or the Commitment Shield. The buttons stay on screen on a small phone even with a reach-out card shown.

**Result:** [ ] pass  [ ] fail  [ ] skipped
**Notes (does the app get stopped while open at the 15-minute mark?):**

### D1b. Website daily limit (Android; Windows has app limits only)
1. Rules, **Daily limits**, **+ Add a website limit…**, type `youtube.com`, choose 15 minutes. Switch NowFocus Accessibility on if the screen says it isn't.
2. In Chrome, open youtube.com and watch for a few minutes. Switch tabs to another site, and to a search box, for a minute each. Repeat in each other browser you use (Brave, Samsung Internet, Firefox, Edge).
3. Keep checking "N of 15 min today". Once used up, keep browsing youtube.com (and m.youtube.com).
4. After midnight (or move the phone clock and timezone past it), open youtube.com again.

5. Pull the notification shade down and back up mid-page; lock and unlock the phone; watch a video fullscreen (toolbar hidden) for a few minutes.

**Expect:** minutes count only while a youtube.com page is showing, not on other sites or while typing in the address bar. Once used up you are stepped back out of the page and get the "Daily limit" screen naming youtube.com. After midnight it works again, and the Limits screen and block screen clear themselves. A cheat day pauses it. **Check per browser:** the address-bar view ids are unverified; a browser that never counts needs its id added to `FocusAccessibilityService.BROWSER_BARS`.

**Result:** [ ] pass  [ ] fail  [ ] skipped
Also expect counting to carry on after the shade/lock steps and during fullscreen video.
**Notes (which browsers counted? did a limit stay "used up" after midnight?):**

### D1c. Daily limit on Windows (apps only, never run on a real PC)
1. Daily limits (sidebar), **Add application…**, pick an `.exe`, it starts at 30 minutes; choose 15.
2. Keep that app in front for 15 minutes in total. Watch "N of 15 min today".
3. Keep it open past 15 minutes without switching away; then reopen it.
4. Raise it to 60 and remove it.

**Expect:** the count is roughly right. Within about 30 seconds of 15 minutes the app is closed and a "daily limit" overlay appears (no "I really need it"); reopening shows it again. Raising or removing says it counts from midnight; lowering is at once. A cheat day pauses it. **Check:** does a blocked app behind a "save changes?" dialog get the overlay re-shown every 30 seconds?

**Result:** [ ] pass  [ ] fail  [ ] skipped
**Notes:**

### D2. Opening friction
1. Rules, **Opening friction**, **+ Add an app…**, pick an app.
2. Open that app from the home screen, with no session running.
3. Wait the 10 seconds, pick an answer, tap **Open <app>**.
4. Switch away and back within 10 minutes. Try **Not now** another time.

**Expect:** a dark "A PAUSE BEFORE <APP>" screen with a breathing countdown and three answer chips. The open button is disabled until the countdown ends and a chip is picked. After opening it stays open for 10 minutes without asking again. Not now sends you home.

**Result:** [ ] pass  [ ] fail  [ ] skipped
**Notes:**

### D3. Insight that offers a schedule
1. Over a few days, try blocked apps at the same hour several times (3+ tries of one app).
2. Stats, "YOUR URGES": find the per-app line and tap **Block <app> at <hour> every day**.

**Expect:** a line like "<app>: most tries around 11 PM (5 of 8)" and a button that opens a prefilled schedule (all days, that hour). If you have no profile with that app, one is made.

**Result:** [ ] pass  [ ] fail  [ ] skipped
**Notes:**

---

# E. Streak, focus score, weekly share

### E1. Streak needs real time
1. Start a session and end it within a minute. Check Stats streak.
2. Do a session that really runs 10+ minutes (or two of 5+ on the same day).

**Expect:** the one-minute session doesn't extend the streak; 10 focused minutes in a day does.

**Result:** [ ] pass  [ ] fail  [ ] skipped (Android / Windows: write which)
**Notes:**

### E2. Focus score and share
1. Android Stats: find **Focus score** and tap **Share this week**. Windows Stats: **Focus score (wk)** and **Copy this week**, then paste somewhere.

**Expect:** a number 0-100 (a dash with no sessions). The text reads like "My NowFocus week: 2h 10m focused across 4 sessions (3 completed). Focus score 71. 3-day streak. Turned away 12 times." with **no** app or site names.

**Result:** [ ] pass  [ ] fail  [ ] skipped
**Notes:**

---

# F. Account and cross-device sessions

**Not tested against production before: be gentle and write down everything.** The production server does not yet cap sessions at 24 hours (the code is written, not deployed). The phone and PC both cap what they will join at 24 hours themselves.

### F1. Sign in and profile sync
1. Windows: Devices, Account, **Create account**. Phone: Devices, Account, **Sign in** with the same account.
2. On the PC add a domain to a profile. Watch the phone (Rules) for it to appear, then edit on the phone and watch the PC (wait up to 30 seconds on the PC; the phone syncs when opened).

**Expect:** one "Deep Work" profile on each (the untouched starter is not duplicated); edits arrive both ways; the later edit wins on a conflict. Devices on this account lists both. A signed-out device makes no network calls (check by signing out and watching nothing happens).

**Result:** [ ] pass  [ ] fail  [ ] skipped
**Notes:**

### F2. Start on the phone, PC joins
1. Phone: start a Normal 30-minute session on a profile that blocks a site/app also present on the PC.
2. On the PC: wait up to about 30 seconds.

**Expect:** the PC starts the same session (Active screen, countdown matches), blocks the profile's sites/apps, and shows it as a normal session.

**Result:** [ ] pass  [ ] fail  [ ] skipped
**Notes (seconds until it joined):**

### F3. Start on the PC, phone joins
1. PC: start a Normal 30-minute session.
2. Phone: open NowFocus, or leave it and wait up to 15 minutes (background sync every 15 min, inexact).

**Expect:** the phone starts the session (notification "Focus session in progress") and blocks.

**Result:** [ ] pass  [ ] fail  [ ] skipped
**Notes (opened vs left alone, minutes until it joined):**

### F4. Ending one ends the other
1. With a Normal session running on both, end it on one device (confirm).
2. Do the same with a Strict session (the typing and wait flow happens on the device where you end it).

**Expect:** the other device ends it on its next sync. A **Locked** session cannot be ended on either device until its end time.

**Result:** [ ] pass  [ ] fail  [ ] skipped
**Notes:**

### F5. Waits its turn, switch, offline
1. Start a session on the phone, then a different one on the PC while the phone's is still running.
2. Phone: Devices, Account, turn off **Join sessions from my other devices** and start a session on the PC.
3. Turn on airplane mode, start a session on the PC, turn it off.

**Expect:** a running session is never replaced by a remote one; it joins after the current one ends if still running. With the toggle off nothing joins. Offline: blocking works locally; the other device catches up after reconnecting.

**Result:** [ ] pass  [ ] fail  [ ] skipped
**Notes:**

### F6. Sign out, delete account
1. Sign out on the PC. Check your profiles, bedtime and any session still work. Sign back in.
2. At the end, **Delete account** on the PC (password).

**Expect:** sign out keeps everything local; delete removes the account from every device's list, but local data and blocking continue.

**Result:** [ ] pass  [ ] fail  [ ] skipped
**Notes:**

---

# G. Why was this app closed? (all platforms)

### G1. Every close names NowFocus and the rule
1. Start a Normal focus session that blocks an app (on Android also a website). Open the app.
2. End it. Turn on Bedtime wind-down (or wait for it) and open a blocked app.
3. Android and Windows: use up a daily limit and open that app again.
4. Android: with a Commitment Shield running, open a blocked app.

**Expect:** the top line always reads **NowFocus closed <the app's name>** (a website on Windows reads "blocked"). The line under the big "This can wait." names the rule with its time: "You're in a focus session until 3:45 PM." / "It's bedtime wind-down until 6:00 AM." / "You've used your 30 minutes of <app> today. It's back at midnight." / "Locked by your Commitment Shield until Oct 9." The countdown label says "Left in session", "Left in bedtime", "Locked for" or "Back in". Android's blocked-website notification names the rule too ("Your focus session is on…", "Bedtime wind-down is on…", "Your Commitment Shield is on…") and still never names the site.

**Result:** [ ] pass  [ ] fail  [ ] skipped
**Notes:**

---

# Things I know are rough (you don't need to report these as bugs)

- **Windows has never run on a PC.** Seven files still carry "UNVERIFIED" banners; W0 is the first real test.
- **Windows passes and sessions' pass counts reset if NowFocus restarts.** Pass only covers apps, not sites.
- **Windows enforcement is weaker than Android's:** Task Manager ends app blocking; hosts-file blocking has no URL paths, ignores DNS-over-HTTPS in some browsers, and an administrator can stop the service.
- **Windows has no autostart**, so schedules, Bedtime and sync only work while the app is running.
- **Windows partial blocking (Shorts, Reels) is saved but not enforced.** Android's partial blocking is still experimental and unverified.
- **Android background sync is every ~15 minutes and inexact.** Samsung's battery manager may delay it; the session joins sooner if you open the app.
- **A remote Locked session can lock a device for up to 24 hours** (the phone and PC cap it; the server cap is not deployed). Turn "Join sessions from my other devices" off if that worries you.
- **Cheat day uses the wall clock**, so changing the date can start one early. It is a plan-ahead tool, not tamper-proof.
- **The public download pages and privacy page still say "no sync".** They were updated for the first-session and confirm changes only. Don't publish before they are updated and the VPS backups are confirmed running.

When you are done, save this file and tell me. I will read your results and fix what failed.
