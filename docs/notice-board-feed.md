# Notice board feed

Dock's notice board shows temporary notices — bins out tonight, the guest Wi-Fi
password, a plumber arriving Thursday — on their own dashboard section, and
takes the section away again when nothing is posted. The notices come from a
feed you host. This is the contract between that feed and the TV.

Turn the section on in Settings → Notice Board, and paste the feed URL there (or
type it from a laptop through Settings → "Set up from phone or laptop").

## The request

```
GET <your feed URL>
Accept: application/json
If-None-Match: "<etag from the previous 200>"      (only when Dock holds one)
```

Dock fetches every 2 minutes while the dashboard is in the foreground, and
additionally when the app resumes, when the board section is opened, and right
after the URL is saved in Settings. Anything that answers a plain GET works —
a static JSON file on any web server is enough.

The URL is used exactly as typed, so a shared secret can ride in the query
string (`?key=…`). Plain `http://` on the LAN is fine; Dock allows cleartext.

## The response

`200 OK`, `Content-Type: application/json`, and an `ETag` if you can manage one.

```json
{
  "version": 1,
  "notices": [
    {
      "id": "plumber-visit",
      "title": "Plumber coming Thursday",
      "html": "<p>Between <b>10 and 12</b>. Leave the side gate unlocked.</p>",
      "starts_at": "2026-09-10T18:00:00+05:30",
      "ends_at":   "2026-09-12T12:00:00+05:30"
    },
    {
      "id": "wifi-pass",
      "html": "<h2>Guest Wi-Fi</h2><p>Network <code>Dock-Guest</code></p>",
      "starts_at": null,
      "ends_at": "2026-09-14T23:59:00Z"
    }
  ]
}
```

| Field | Required | Meaning |
|---|---|---|
| `version` | no | If present it must be `1`. Any other value rejects the whole payload and Dock keeps the board it already had. |
| `notices` | yes | Display order. An empty array means the board is clear, and the section disappears from the dashboard. |
| `id` | yes in practice | Stable, unique. Dock keeps the viewer on the same notice across a refresh by id. A missing id becomes `notice-<position>`, which shifts if the list is reordered. |
| `title` | no | Plain text heading. Dock escapes it; markup in here shows up as characters. |
| `html` | yes | An HTML *fragment* — no `<html>` or `<body>`. Dock wraps it in its own dark shell. Blank drops the notice. |
| `starts_at` | no | ISO-8601 **with an offset**. Missing or `null` means live immediately. Inclusive. |
| `ends_at` | yes | ISO-8601 **with an offset**. Exclusive: at that instant the notice is gone. |

### Rules Dock enforces

- **The TV decides what is live**, never the server: `starts_at <= now < ends_at`
  against the TV's own clock, re-checked every minute. Send the whole board;
  Dock shows the part of it that applies.
- **Send future notices too.** A notice scheduled for 18:00 appears at 18:00
  sharp rather than at the next poll.
- **A timestamp needs a zone** (`Z` or `+05:30`). Local time with no offset is
  refused, because the feed and the TV need not agree on one.
- **One bad notice is dropped, not the board.** A missing `ends_at`, an
  unreadable timestamp, or empty `html` loses that notice only; its siblings
  still render.
- **Unknown fields are ignored**, so you can carry your own metadata.
- **`304 Not Modified`** in reply to `If-None-Match` keeps what Dock has. The
  ETag can be anything opaque — a hash of the body works.
- **Failures are survivable.** On 4xx, 5xx, an unreachable host, or unparseable
  JSON, Dock keeps the last good board from the same URL and retries at the
  next poll. Expiry keeps running against that cached copy, so a server that
  dies cannot leave a notice up past its end time — it can only fail to bring
  new ones in. Changing the feed URL clears the board rather than carrying the
  old notices over.
- Keep the payload small (tens of KB). It becomes one HTML document.

## Writing a notice that fits

Dock shows **one notice at a time and never scrolls within it**. D-pad UP/DOWN
step between live notices, the board auto-advances every 30 seconds when more
than one is up, and a counter in the header says "2 of 3". Anything that does
not fit its card is clipped, so size each notice to a single screen.

Every notice is laid out on a fixed canvas **1280 CSS px wide**, scaled by the
TV to whatever the panel is. The usable height works out around 660 px on a
16:9 screen. **Design for 1280 × 600** and treat what is below that as bleed —
the page indicator strip crosses the bottom of the screen.

The shell already provides all of this, so a fragment need not repeat it:

| | |
|---|---|
| Background | black, 48 px padding inside the card |
| Body text | 28 px, `#B3B3B3`, line height 1.4 |
| Headings (`title` and `<h2>`) | 40 px, `#F5F5F5` |
| Muted text (`<small>`, `.meta`) | `#707070` |
| Images | capped at `max-width:100%` |
| Vertical placement | centred when it fits, top-aligned when it does not |

Measured budget for one card, with a title (a title costs about 68 px):

| Content | Fits |
|---|---|
| One flowing paragraph | 12 wrapped lines, about 93 characters each |
| Separate short paragraphs | 9 of them |
| No title at all | 14 wrapped lines |
| Text plus an image | a 1184 × 260 image beside about 5 lines |
| Image alone | up to 1184 × 500 px |
| Two columns | `display:flex;gap:32px`, about 570 px each |
| A code or number to read across the room | one line at up to 96 px, plus a caption |

Rules of thumb:

- Nothing below 24 px: it cannot be read from a sofa.
- Style with inline `style=""` or a `<style>` block inside the fragment.
  Colours have to work on black.
- **JavaScript never runs.** Scripts in a notice are inert, so no countdowns or
  live widgets. Images, CSS and links render normally; links do not navigate.
- Relative image URLs resolve against the feed URL, so `/img/bins.png` is
  same-origin and `img/bins.png` is relative to the feed's own directory. Serve
  images over the same scheme as the feed.
- No `height:100%` or `100vh` inside a fragment — the shell already fixes the
  card height. Give images a fixed height so nothing jumps as they load.
- To preview, drop the fragment into a 1280 × 600 black box with `padding:48px`
  and `font:28px/1.4 sans-serif`. If it needs a scrollbar, cut it down or split
  it into two notices.

The fragment is **not sanitised** — this is a feed you host for your own TV. Do
not point the board at something you do not control.
