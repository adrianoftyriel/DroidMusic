# Formats

What DroidMusic reads, and what it writes.

---

## Charts it opens

| Extension | Treated as | Transposable |
|---|---|---|
| `.pdf` | Pages, via the platform renderer | No |
| `.png .jpg .jpeg .webp .gif .bmp .heic .heif` | One image, one page | No |
| `.cho .chopro .chord .chordpro .pro .crd` | ChordPro | **Yes** |
| `.txt .text .tab .md` | Sniffed — ChordPro, chords-over-lyrics, or plain text | **Yes**, where chords are found |

A PDF is a picture of a page. There is nothing in it the app could rewrite, so
the transposition control is simply absent rather than present and broken.

### Format sniffing

The format is decided by **looking at the file**, not at its extension, because
plenty of ChordPro lives in files called `.txt`. A file is ChordPro if it
contains a `{directive}` or an inline `[C]`; otherwise it is chords-over-lyrics
if any line parses as a chord line; otherwise it is plain text and is laid out
as-is.

---

## ChordPro

Directives understood:

| Directive | Aliases | Effect |
|---|---|---|
| `{title:}` | `{t:}` | Title |
| `{subtitle:}` | `{st:}` | Subtitle |
| `{artist:}` | `{composer:}` | Artist |
| `{key:}` | | Declared key — overrides detection |
| `{capo:}` | | Capo position |
| `{tempo:}` | `{bpm:}` | Tempo |
| `{time:}` | `{meter:}` | Time signature |
| `{comment:}` | `{c:}` `{ci:}` `{cb:}` | A line of comment |
| `{start_of_chorus}` | `{soc}` | Section heading |
| `{start_of_verse}` | `{sov}` | Section heading |
| `{start_of_bridge}` | `{sob}` | Section heading |
| `{start_of_grid}` | `{sog}` | Section heading |
| `{start_of_tab}` … `{end_of_tab}` | `{sot}` `{eot}` | Held verbatim |

Anything else is kept so export can round-trip it.

Bracket contents that do not parse as a chord are **kept as literal text** rather
than dropped — charts use brackets for annotations like `[x2]` and `[slowly]`,
and silently deleting somebody's performance note would be worse than showing
it.

---

## Chords over lyrics

```
G                   C         G
Amazing grace how sweet the sound
              Em        D
That saved a wretch like me
```

A chord at column *n* belongs to whatever begins at column *n*, so segments run
from one chord's column to the next — mid-word included, which is exactly what a
chart means when it puts a chord mid-word.

### Deciding what is a chord line

**Every** whitespace-separated token must parse as a chord, or as bar-line
furniture: `|` `||` `|:` `:|` `%` `N.C.` `x2` and similar.

The asymmetry that drives this rule: a missed chord line is visible and
harmless, while a lyric line mistaken for chords is silently rewritten and the
player has no idea why. So the test is strict, and the chord grammar behind it
(see [DESIGN.md §3](DESIGN.md)) rejects English words rather than accepting
anything spelled with the right letters.

A line of `| | |` alone is not a chord line — there is nothing to transpose.

Tab lines are checked **first**, since `E|-------------|` would otherwise look
like the chord E followed by bar furniture.

### Chords understood

Root `A`–`G`, any run of `#`/`♯` or `b`/`♭`, then a quality built from real
quality tokens, optionally `/bass`.

Round-trips exactly: `C` `Am` `G7` `Dsus4` `Fmaj7` `Bm7b5` `C#m` `Ebmaj9`
`A7sus4` `Gadd9` `F#dim` `Baug` `C6/9` `E-9` `Amaj7#11` `G(no3)` `Cø7` `F+`
`Bb13` `D/F#`.

`C6/9` keeps `6/9` as a quality; `D/F#` splits the bass off. The slash is only a
bass separator when what follows is exactly a note.

The quality is carried through transposition **as text, untouched** — since
transposition never needs to know what `maj7#11` means, the safest thing to do
with it is nothing.

---

## Tablature

Tab is displayed verbatim. Column positions inside tablature *are* the notation,
so reflowing it would destroy it.

Shifting by fret is available but **off by default**, and refuses rather than
producing a fret of −2 or 27. Adding *n* to every fret does give the same music,
but in a completely different position on the neck with different open strings —
useful for a riff, useless for a fingerstyle arrangement. Column widths are
preserved when a one-digit fret becomes two.

---

## `.dmset` — the set list file

Plain JSON, on purpose. A band mate on a phone that has never run this app can
still open the attachment and read what the set is; a binary format would buy
nothing and cost that.

```json
{
  "formatVersion": 1,
  "setlist": {
    "id": "…",
    "name": "Friday at the Anchor",
    "venue": "The Anchor",
    "date": "2026-09-04",
    "entries": [
      {
        "songId": "…",
        "title": "Wagon Wheel",
        "contentHash": "…",
        "artist": "Old Crow Medicine Show",
        "transposeSemitones": 2,
        "capo": 0,
        "targetKeyText": "A",
        "note": "segue into the next one"
      }
    ],
    "createdAt": 0,
    "updatedAt": 0
  },
  "exportedBy": "Jim's Pixel",
  "exportedAt": 0,
  "producer": "DroidMusic 0.1.0"
}
```

MIME type `application/json`.

### Why an entry carries a title and a hash as well as an id

`songId` is meaningful on the device that wrote the file and on no other. Without
`title` and `contentHash`, a shared set list arriving anywhere else is a list of
identifiers pointing at nothing.

### How an incoming set list is matched

1. **Content hash.** Exact.
2. **Title**, normalised for case, punctuation and a leading "the".

The fallback is not a nicety. Two people rarely have byte-identical copies of
the same chart — one has a scan, the other a ChordPro of the same song — and
matching those is the entire point.

The hash covers the first megabyte plus the file length. Hashing a 60 MB scanned
songbook in full, for every file, on a phone, to decide whether two devices have
the same chart is not a trade worth making; a collision needs two different
charts sharing both their first megabyte and their exact length, and the title
fallback catches it anyway.

Anything genuinely missing is **named** on import rather than silently dropped.
Importing a list that refers to charts you do not have yet is the normal case,
not an error — they are usually in a shared folder you have not added.

### Versioning

`formatVersion` is checked on import. A **newer** version is refused with a
message rather than half-read, because for a set list a partial read means
quietly losing somebody's transpositions. Unknown *fields* within a known
version are ignored, so adding a field later does not break older builds.

Malformed input returns nothing rather than throwing. This is fed by files from
other people's devices, and a crash on a bad attachment is not an acceptable
failure mode.
