# In-app Arabic: glossary and rules

One file all apps follow (Android, Windows, macOS). Source of the terms: the Arabic site in `apps/web/ar/`.
Everything marked **proposed** has no precedent on the site and needs a native read before release.

## Register
- Modern Standard Arabic, second person masculine singular (اختر, اضغط), same as the site.
- `NowFocus` is always Latin. Before a Latin word use `لـ` with a tatweel ("لـ NowFocus"); `و` attaches directly.
- Arabic comma `،` and question mark `؟`. Arabic terms in «», English labels in “”.
- Digits are **Latin 0-9** everywhere (the site pins this with `@counter-style western`). Apps pin it through the locale tag `ar-u-nu-latn`.
- No letter-spacing and no uppercase styling on Arabic text; both break the letter joining.

## Terms (from the site)
| English | Arabic |
|---|---|
| focus session | جلسة تركيز |
| block / blocked | حجب / محجوب |
| block screen | شاشة الحجب |
| partial blocking | الحجب الجزئي |
| Pages to close | صفحات تُغلق |
| Browser address check | فحص عنوان المتصفح |
| focus profile | نمط تركيز |
| Normal / Strict / Locked | عادي / صارم / مقفل |
| Commitment Shield | درع الالتزام |
| bedtime wind-down | تهدئة وقت النوم |
| daily limit | الحد اليومي (plural: الحدود اليومية) |
| streak | السلسلة |
| streak grace day | يوم سماح |
| stats | الإحصاءات (not إحصائيات) |
| goals | الأهداف |
| DNS (keep the Latin letters) | DNS |
| family filter (a DNS that blocks adult content) | DNS مُصفّي (proposed, needs a native read) |
| people who matter | الأشخاص المهمّون |
| devices / this device | الأجهزة / هذا الجهاز |
| sync | المزامنة |
| account | الحساب |
| settings | الإعدادات |
| friction | حواجز التريّث / صعوبة مقصودة (واحتكاك سلوكي في السياق الفلسفي) |
| opening friction | التريّث قبل الفتح / مهلة التريّث قبل فتح التطبيقات |
| urge | الرغبة الملحّة |
| grace window | مهلة التراجع |
| voice note | ملاحظة صوتية |
| waitlist | قائمة الانتظار |

## Terms (proposed, no site precedent)
| English | Arabic |
|---|---|
| schedule | جدول |
| notification | إشعار (plural: إشعارات) |
| hold / snooze a notification until the block ends | تأجيل الإشعار |
| Notification access (the Android permission) | الوصول إلى الإشعارات |
| pause playback of a blocked app's video or audio | إيقاف … مؤقتًا ("يوقف مؤقتًا الفيديو أو الصوت") |
| floating window (picture-in-picture) | نافذة عائمة |
| Edit (a profile) | تعديل |
| pass / limit pass | تصريح |
| cheat day | يوم استراحة |
| unlock | فك القفل |
| about | حول التطبيق |
| language | اللغة |
| system default | لغة الجهاز |

Android 0.9.5 added Arabic for pausing playback, floating windows and the Notification-access consent text (app strings, privacy and download pages). They were written without a native review; have a native speaker read them.

False friends to avoid: `تمرير` (the site uses it for DNS forwarding and hover), bare `حدود` (also "borders / limitations"), `سجّل` (also the verb "record").

## Never translate
- The seed profile name **"Deep Work"**: sync dedupe compares it (Android `SyncLogic.isUntouchedSeed`, Windows `sync/src/logic.rs`).
- Names that are stored and synced as data: "First focus" (Windows Onboarding looks it up by name), "New profile", "New whitelist", "No <app>". The default *schedule* name "Work" is the one exception: it is only a starting suggestion the user can edit, so it is created in the app language.
- `PartialBlocking` matcher literals ("Shorts", "Reels", "For you", view ids): they match other apps' UI text, which follows the system language.
- Brand and copyright lines ("NowFocus", "© 2026 Safwat Fathi") and URLs. The license sentence is translated (the license name itself stays "FSL-1.1-ALv2").

## Copy rules (carry over from the site)
- No "unbypassable", "tamper-proof", "uninstalling doesn't work". The fixed disclaimer is «أداة لضبط النفس، وليس قفلًا على جهازك».
- Do not claim features that are not shipped. An account is optional; never "no account", never "nothing leaves your device".
- Block text names the rule, never the site.

## Unlock sentence
The user types it exactly to end a session early. The Arabic one uses only plain letters (no hamza or alef variants) so an exact match needs no normalization:

- English: `I am choosing to end this focus session early.`
- Arabic (proposed): `قررت الخروج من جلسة التركيز قبل موعدها.`

## Where each app keeps the switch
Settings holds the language control and About (version, links, license), on all three apps: the Settings tab on Android, the Settings entry in the Windows sidebar, and the Settings row in the macOS sidebar (which replaced General and About).
