# Calendar API research — Google Calendar API + Microsoft Graph for Dock

Researched August 2026 against live official docs. Goal: replace/augment the ICS
feeds with real APIs to get per-event colors, participants, locations, and the
user's own RSVP state on the Split Decks home.

## What each API delivers

| Need | Google Calendar API v3 | Microsoft Graph (work) |
|---|---|---|
| Per-event color | ✅ `event.colorId` (1–11) → hex via `colors.get`; no colorId → calendar's `backgroundColor` from calendarList | ❌ No per-event color exists in Outlook. Color = **categories** (`preset0–24`, names documented, hex NOT — pick own palette via `/me/outlook/masterCategories`, needs `MailboxSettings.Read`) + per-calendar `hexColor` |
| My RSVP ("am I joining?") | ✅ `attendees[]` entry with `self=true` → `responseStatus`: needsAction / declined / tentative / accepted | ✅ `event.responseStatus.response`: organizer / accepted / tentativelyAccepted / declined / notResponded — fully reliable on own calendar |
| Participants | ✅ `attendees[]`: email, displayName, responseStatus, optional, resource, organizer flags | ✅ `attendees[]`: name+SMTP, required/optional/resource, per-person RSVP + time. Caveat: others' RSVPs only tallied on the ORGANIZER's copy |
| Location | ✅ free-text `location` | ✅ `location` + rich `locations[]`: address, coordinates, locationType (conferenceRoom, restaurant, …) |
| Meeting link | ✅ `hangoutLink` / `conferenceData.entryPoints` | ✅ `isOnlineMeeting`, `onlineMeeting.joinUrl` (legacy `onlineMeetingUrl` deprecated), + dial-in numbers |
| Recurrence expansion | ✅ `events.list?singleEvents=true` (fixes our monthly/exception gaps) | ✅ `/me/calendarView?start&end` returns occurrences + exceptions pre-expanded |
| Declined events | filter `self.responseStatus == declined` | Declining REMOVES the event from the calendar by default (nothing to filter); if user enables "show declined", filter `responseStatus == declined` client-side |
| Multi-calendar | ✅ `calendarList.list`: every calendar + color + user's rename + `selected` flag | ✅ `/me/calendars` with names + colors |
| Incremental sync | `syncToken` (410 → full resync); windowed polling simpler | ✅ `/me/calendarView/delta` — only changed events + delete tombstones |

## Extras worth showing on an ambient dashboard

**Google:** `eventType` filtering — `birthday`, `outOfOffice`, `focusTime`,
`workingLocation`, `fromGmail` (flights/reservations Gmail auto-adds — these
never reach the ICS feed at all); `transparency` (free vs busy placeholder);
reliable cancelled-event removal; attachments; Google Tasks via separate Tasks
API (same OAuth consent can cover both).

**Microsoft:** `showAs` (free/tentative/busy/oof/workingElsewhere);
`sensitivity` (hide details of `private` events on the shared TV);
`importance`; `bodyPreview` (plain text); **`/me/mailboxSettings`** →
working hours, mailbox timezone, and automatic-replies status (an "OOF" badge
when auto-reply is on); **`getSchedule`** → free/busy of colleagues or rooms;
new-time proposals (`proposedNewTime`).

## Auth — the two catches

### Google: device flow is CLOSED to Calendar scopes (verified)
The TV "enter code at google.com/device" flow only allows email/profile/drive.
appdata/drive.file/youtube scopes — **no Calendar**, and nothing newer exists.
Options for a personal app:

- **(b) One-time manual provisioning (recommended start)** — "Desktop app"
  OAuth client, run a tiny loopback-flow script once on the Mac, get a refresh
  token, push it to the TV (settings screen / SetupServer / adb). TV then
  refreshes tokens itself. Zero infra, ~an afternoon.
- **(a) VPS companion pairing page (nicer UX later)** — TV shows QR → phone
  opens `https://<domain>/auth` → Google consent → VPS receives the code,
  exchanges it (`access_type=offline&prompt=consent`) and hands the refresh
  token to the TV. Re-pairing from the couch.

**Token gotcha:** OAuth consent screen must be published **"In production"**
(unverified is fine, one-time warning, 100-user cap irrelevant). In "Testing"
status, refresh tokens for Calendar scopes **die every 7 days**.
`calendar.readonly` is a sensitive scope; never submit for verification —
personal use doesn't need it.

### Microsoft: device code flow WORKS — the risk is the employer's tenant
`Calendars.Read` + `MailboxSettings.Read` + `offline_access` are supported in
the device code flow (TV shows an 8-char code, user signs in at
microsoft.com/devicelogin on the phone; MFA happens there). Register a
multi-tenant public-client app in a personal Azure tenant ("Allow public
client flows" = Yes), request against `/organizations`.

What the company tenant can block (cannot be engineered around):
1. **User-consent policy** — default allows users to consent to Calendars.Read
   themselves; many tenants restrict to verified-publisher apps only →
   "Approval required" screen (admin consent request workflow may exist).
2. **Conditional Access** — some tenants explicitly block device code flow
   (Microsoft recommends blocking it); a compliant-device requirement would
   also fail (Fire TV can't be Intune-enrolled). MFA-only policies are fine.
3. Refresh tokens: 90-day rolling, renewed on every use → effectively
   immortal under 5-min polling, but design the "show a new code" re-auth path
   as routine (`invalid_grant` / AADSTS530036).

**Test with the real work account before building** — one curl to the
devicecode endpoint answers whether the tenant allows it.

## Quotas / polling
Both APIs: free, and 5–15 min polling is orders of magnitude under limits
(Google default 1M req/day; Outlook 10k req/10 min/mailbox, honor Retry-After
on 429). Webhooks are possible via the VPS but not worth the plumbing for an
ambient display; use Graph delta + Google windowed polling.

## Color strategy for Dock
- Google events: `colorId` → hex from cached `colors.get`; else calendar's
  `backgroundColor`. (Palette: Lavender #7986CB, Sage #33B679, Grape #8E24AA,
  Flamingo #E67C73, Banana #F6BF26, Tangerine #F4511E, Peacock #039BE5,
  Graphite #616161, Blueberry #3F51B5, Basil #0B8043, Tomato #D50000 —
  unofficial listing; fetch at runtime, don't hardcode.)
- Outlook events: first category's preset color (own hex palette for the 25
  documented preset names), else calendar `hexColor`, else `outlook_blue`.

## Recommended plan
1. **Phase A — Microsoft Graph** (bigger payoff: true RSVP, participants,
   joinUrl, categories, OOF; auth is TV-native device code). Feature-flag with
   ICS fallback. Verify tenant consent early.
2. **Phase B — Google API** with one-time Mac provisioning; upgrade to the VPS
   pairing page if re-auth ever becomes annoying.
3. Model changes are already in place: `CalendarEvent` gained
   busyStatus/categories/onlineMeetingUrl/organizer/colorHex — APIs just fill
   them properly; add `myResponse` (accepted/tentative/declined/needsAction)
   and `attendeeCount` when wiring Graph/Google.
