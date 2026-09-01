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
| `.docx` | Word document — unzipped to text, then sniffed the same way | **Yes**, where chords are found |

A PDF is a picture of a page. There is nothing in it the app could rewrite, so
the transposition control is simply absent rather than present and broken.

`.doc` — the old binary Word format — is **not** read. It is not a zip and not
XML, and offering it only to fail on the stand is worse than not offering it.
Word will save one as `.docx` in a couple of taps.

### Format sniffing

The format is decided by **looking at the file**, not at its extension, because
plenty of ChordPro lives in files called `.txt`. A file is ChordPro if it
contains a `{directive}` or an inline `[C]`; otherwise it is chords-over-lyrics
if any line parses as a chord line; otherwise it is plain text and is laid out
as-is.

### And when the name says nothing at all

ChordPro has no registered MIME type. Android's own table has never heard of
`.cho`, `.chopro`, `.crd` or `.pro`, so every provider on the device hands one
over as `application/octet-stream` — the same answer it gives for a firmware
image. Two consequences, both of which used to read as "this app does not
support ChordPro":

- **Opening one from outside the app.** DroidMusic accepts
  `application/octet-stream` on its VIEW and SEND filters, so a chart tapped in
  a file manager, or shared out of another editor, offers DroidMusic as
  somewhere to open it. Without that line the system says the file type is not
  supported and there is nothing the app can do about it, because it was never
  asked.
- **A file picked by hand whose extension is not on the list above.** The table
  is a convenience, not a gate. When a picked file's name says nothing useful,
  the first 4KB are read and asked whether they are text — a NUL byte is the
  giveaway — and anything that is text is opened as a chart, with the format
  sniffing above then working out what kind. Somebody else's convention
  (`.songbook`, `.cp`, no extension at all) is not wrong, it is just not on a
  list this app happened to write down.

Sniffing is only done for files picked or opened deliberately. A folder scan
stays on extensions, because sniffing every file in a scanned folder would mean
opening every file in a scanned folder.

A file that is genuinely not readable is **named**. "Could not read
Rehearsal.zip" is a thing to act on; a file that was asked for and then quietly
did not appear is not.

---

## ChordPro

Read against the [ChordPro file format
specification](https://www.chordpro.org/chordpro/), version 6. Where the
specification is silent or disagrees with itself, the
[reference implementation](https://github.com/ChordPro/chordpro) is the
tie-breaker - which is what the specification itself says to do.

### The shape of a file

A chart is lyrics with `[C]` chords in them, `{directives}` in braces, blank
lines and `#` remarks. Before any line is asked what it means:

| Written | Read as |
|---|---|
| A line ending in `\` | Joined to the next line, whose leading whitespace goes |
| `♥`, `\u{1F3B8}`, a surrogate pair | The character named |
| A tab | One space - not a jump to the next tab stop |
| `#` in **column zero** | A remark, dropped |
| `  #` indented | A lyric that starts with a hash |

The remark test is anchored at column zero on purpose. Tablature is full of
lines a looser reading would delete.

### Directives

The name is separated from its argument by a colon, a space, or both, so
`{title: X}`, `{title X}` and `{title:X}` are one directive. Arguments may also
be named, HTML-style: `{start_of_verse: label="Verse 1"}`.

| Directive | Aliases | Effect |
|---|---|---|
| `{title:}` `{sorttitle:}` | `{t:}` | Title |
| `{subtitle:}` | `{st:}` | Subtitle |
| `{artist:}` `{sortartist:}` `{composer:}` `{lyricist:}` `{arranger:}` | | Who made it |
| `{album:}` `{year:}` `{copyright:}` | | Where it came from |
| `{key:}` | | Declared key - overrides detection |
| `{capo:}` `{tempo:}` `{time:}` `{duration:}` | | How to play it |
| `{transpose:}` | | Semitones the file itself asks for |
| `{meta: name value}` | | Any of the above, and anything else |
| `{comment:}` | `{c:}` | A line of comment |
| `{comment_italic:}` `{comment_box:}` `{highlight:}` | `{ci:}` `{cb:}` | The same, styled |
| `{start_of_X:}` … `{end_of_X}` | | A section - **any** name |
| `{start_of_chorus:}` … | `{soc}` `{eoc}` | Chorus |
| `{start_of_verse:}` … | `{sov}` `{eov}` | Verse |
| `{start_of_bridge:}` … | `{sob}` `{eob}` | Bridge |
| `{start_of_tab:}` … | `{sot}` `{eot}` | Held verbatim |
| `{start_of_grid:}` … | `{sog}` `{eog}` | A chord grid |
| `{chorus}` | | Play the chorus here |
| `{new_page}` `{new_physical_page}` | `{np}` `{npp}` | Start a new page |
| `{column_break}` | `{colb}` | Start a new column |

`{cb}` is a **boxed comment**, not a column break. The published cheat sheet says
otherwise and the reference implementation does not; `cb` in real charts is
overwhelmingly a boxed comment, so the reference wins.

Everything else a chart declares - `{tuning:}`, `{define:}`, fonts, colours,
page sizes, `{x_myapp_whatever}` - is kept so that exporting a transposed chart
does not throw away the parts of it that were never about transposition.

### Sections can be called anything

ChordPro 6 lets a chart name a section whatever it likes, and only `chorus`,
`tab` and `grid` behave specially. `{start_of_part: Solo}` is a section called
`part` labelled "Solo", and it is written back out as itself rather than being
flattened into a comment. A section left unclosed at the end of a file is closed
silently - the intent is obvious, and refusing to draw the last verse over a
missing `{end_of_verse}` would be a strange thing to do on a stand.

An unlabelled section is shown under its own name, so `{start_of_intro_riff}`
draws as "Intro Riff" - but it is still *unlabelled*, and exporting does not put
a label on a line that never had one.

### Conditional directives

`{comment-alto: Very softly}` applies only when the selector matches, and `!`
reverses it. DroidMusic configures neither an instrument nor a user, so only the
metadata test can succeed: a selector naming something the chart itself declared
selects, and anything else does not. A deselected `{start_of_X}` takes the whole
section with it, contents and directives alike.

This means the alto's note does not appear on the tenor's stand. Showing every
variant of a conditional line at once would be worse than showing none.

### Chords and annotations

What is in the brackets is a chord if it parses as one, and an **annotation**
otherwise - printed where a chord would go, and not transposed.

| Written | Read as |
|---|---|
| `[C]` `[Bm7b5]` `[D/F#]` | The chord |
| `[(C)]` | The chord `C` - play it if you like |
| `[*Coda]` `[*Rit.]` | An annotation, explicitly |
| `[x2]` `[slowly]` | An annotation, because it is not a chord |
| `[\|]` `[  ]` | An annotation - a bar with no chord change |

`[x2]` used to be pushed into the middle of the lyric, where it reads as
something to sing. Above the line, in the column it was written in, is where the
writer meant it.

Exporting only stars an annotation when leaving the star off would change it: an
annotation that happens to spell a chord, or one that starts with a star. `[x2]`
is written back as `[x2]`.

### Grids

A `{start_of_grid}` block is a jazz grille - chords in a rectangle of cells
divided by bar lines. The columns are kept exactly as written, and the chords in
them transpose with the rest of the song, which the specification requires. When
transposition makes a chord wider, the space after it gives way so the next bar
line stays in its column.

Bar lines, repeats, voltas, `%` measure repeats, margin notes and strum arrows
are held verbatim. The visual grid - drawn cells and rules - is a renderer
feature and is not drawn; a grid appears as the text it was written as.

### Two deliberate departures

- **Bare tablature is recognised.** A run of `e|---9-7--|` lines that nobody
  wrapped in `{start_of_tab}` is still tablature, and is held verbatim rather
  than laid out as lyrics. The specification has no notion of detecting tab, but
  charts from the usual places look like this constantly. Exporting supplies the
  `{start_of_tab}` the original was missing, so the file other tools read means
  what this one meant.
- **A chart is one song.** `{new_song}` is kept so exporting does not destroy it,
  but a file holding a songbook is read as its first song. The library is a list
  of charts, and splitting one file into several entries behind the user's back
  would be worse than not reading the rest.

### What is not implemented

Chord diagrams (`{define:}`, `{chord:}`), images, the delegated environments
(`abc`, `ly`, `svg`, `textblock`), markup (`<b>`, `<span>`), and the font, size
and colour directives are **preserved but not drawn**. They describe a printed
page, and this is a screen on a stand.

### The title block

A ChordPro chart is drawn with its **title, artist and key across the head of
the first page**, and only the first page.

It is built as rows of the chart rather than as a banner the viewer paints over
it, which is what makes "only the first page" true for free: pagination pushes
the music down by exactly the space the heading takes, and page two starts with
music.

The key in it is the key the chart is **currently in**, not the key the file was
written in — the heading is built from the transposed song, so moving a chart to
B♭ moves its heading too, and a capo is named beside it. A subtitle is shown when
it says something the artist line does not, which is where a great many files put
the artist.

**Only ChordPro gets one.** A chords-over-lyrics chart has its title in the text
already, at the top of the file where its author typed it; printing it a second
time above itself would be a bug wearing a feature's clothes.

---

## Word documents

A `.docx` is a zip with an XML file in it. DroidMusic unzips it, takes the
characters, and hands them to exactly the same parser everything else goes
through — so a chord chart typed in Word is sniffed, transposed, key-detected
and paginated like any other text chart. There is no second code path and no
document library in the APK.

What is taken:

| In the document | Comes out as |
|---|---|
| A paragraph | A line |
| A line break inside a paragraph | A line |
| A table row | One line, cells separated |
| Tabs | Spaces, to the next 4-column stop |
| Non-breaking spaces | Ordinary spaces |
| Text deleted by a tracked change | Nothing — it stays deleted |
| Field codes, bold, italic, fonts, colours | Nothing |

Two limits worth knowing before pointing the app at a folder of them:

- **A chart aligned by eye in a proportional font will not line up.** It never
  lined up in characters — only in millimetres — and columns are what a
  chords-over-lyrics chart means by "this chord goes over that syllable". Charts
  typed in Courier or another monospaced font come out exactly as they went in.
  Anything laid out visually wants to be a PDF.
- **A tab becomes spaces on a fixed grid.** In Word a tab means "as far as the
  next stop, wherever the ruler puts it", and there is no ruler once the
  formatting is gone. Four columns keeps a chord roughly where the writer put it
  without flinging it across the line, which eight-column stops would.

If a Word chart reads badly, the reliable fix is to set it in a monospaced font
before saving, or to export it as a PDF and let it be a picture of a page.

---

## Importing from a chart page

A link to an Ultimate Guitar chord chart, shared into the app, becomes a
ChordPro file in the library. What is stored is the chart; the link is kept only
as a `{source:}` directive.

The page carries its own chart as data rather than as rendered markup: a JSON
blob in a `data-content` attribute, which the site's own front end reads to draw
the page. That is what is read, so what lands in the library is the chart as its
author typed it rather than a guess at what the screen looked like.

| On the page | Comes out as |
|---|---|
| `[ch]C[/ch]` | The chord `C`, in the column the marker sat in |
| `[tab]` … `[/tab]` | Nothing — the markers go, the alignment stays |
| `[Verse 1]`, `[Chorus]`, `[Bridge]` | `{start_of_verse}`, `{start_of_chorus}`, `{start_of_bridge}` |
| Any other bracketed line, e.g. `[Guitar Solo]` | `{comment:}` |
| Song name, artist, key, capo, tuning | `{title:}` `{artist:}` `{key:}` `{capo:}` `{tuning:}` |
| The address it came from | `{source:}` |
| HTML entities | The characters they stand for |

**Removing the markers does not move anything.** `[ch]` and `[tab]` are invisible
to somebody reading the page, so the spacing either side of them is already
counted as though they were not there. Take them off and the columns are exactly
where the chart's author put them — which, for a chords-over-lyrics chart, *is*
the notation.

**The format is stated, not sniffed.** Everything else the app opens goes through
[format sniffing](#format-sniffing); this does not, and the difference matters.
The sniffer reads a bracketed `[C…]` as an inline ChordPro chord, and this site's
section headings are bracketed — `[Chorus]` and `[Guitar Solo]` both look like
one. A body sniffed that way is handed to the ChordPro parser, which finds no
chords in a chart whose chords are a line *above* the words, and every chord in
the song is lost silently. The source is known here, so the format is asserted.

**What is not imported.** Official and Pro tabs are interactive players with no
text chart behind them. There is nothing in one to import, and the app says so
rather than saving an empty file.

**One annotation is lost.** A repeat marker that shares a line with chords *and*
has lyrics beneath it — `C  G  D  x2` over a sung line — does not survive, because
it lives in the chord line and every part of that line that is not a chord
belongs to the lyric beneath it. On an instrumental line, where there are no
lyrics to align to, it is kept. This is the ordinary chords-over-lyrics
behaviour and not particular to importing.

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

The hash covers the first megabyte **and the file's length**. Hashing a 60 MB
scanned songbook in full, for every file, on a phone, to decide whether two
devices have the same chart is not a trade worth making; a collision needs two
different charts sharing both their first megabyte and their exact length, and
the title fallback catches it anyway.

The length is load-bearing, not a detail. Without it the prefix is the whole
fingerprint, and every file over a megabyte that opens the same way is the same
file as far as matching is concerned - which for a library of scans from one
songbook, each starting on the same cover page, is not hypothetical. See
`ContentHash`, which has a test for exactly that pair.

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
