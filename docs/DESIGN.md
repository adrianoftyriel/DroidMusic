# Design notes

Why each choice was made, including the ones that were nearly made differently.

---

## 1. Why the core is not an Android module

Three of the four modules — `:core:music`, `:core:library`, `:core:session` —
have no Android dependency at all. That is not architectural tidiness for its
own sake; it comes from what is in them.

The parts of this app where being wrong is **loud** are all in the Android
module: a page that will not render, a folder that will not list, a socket that
will not connect. Somebody notices immediately.

The parts where being wrong is **silent** are all in the core. A chart
transposed into a key nobody can read. A set list that quietly loses a song when
it crosses to another phone. A follower left three pages behind after a
reconnect, on a device the leader cannot see. Nobody notices until it matters,
and by then they are on stage.

Silent failures need tests, and tests need to be cheap enough that they are run
constantly. Splitting the core out means:

```sh
./gradlew -PcoreOnly coreTests      # 105 tests, no Android SDK, seconds
```

which runs on any machine with a JDK, gates the APK build in CI, and gives a
reviewer something they can execute in their first five minutes.

The `-PcoreOnly` flag is opt-in rather than auto-detected from whether an SDK is
present. Auto-detection was tempting and is a trap: a CI run that quietly skips
the app module because it could not find an SDK is far worse than one that fails
with a message telling you to install it.

---

## 2. Transposition, and why the obvious approach is wrong

This is the part of the app most likely to be built badly, so it is worth
setting out in full.

### The obvious approach

Represent a note as one of twelve pitch classes. To transpose up three
semitones, add three, modulo twelve. Then, to display it, pick a name for the
result.

That last step is where it falls apart. Pitch class 3 is both E♭ and D♯. The
code has to guess, and every guess is wrong about half the time. Worse, it
guesses **per chord**, so a chart in C transposed up three comes back as
E♭, G♯, A♯ — a mixture of flats and sharps that no musician would write and
that is materially harder to read at speed.

### What is done instead

A note is a **letter plus an alteration** (`Note(letter, alter)`), never a bare
pitch class. An interval carries **how many letter names to move** as well as
how many semitones (`Interval(letterSteps, semitones)`).

Transposition then moves the letter by the first number, and the alteration
becomes whatever is needed to satisfy the second:

> E♭ up a perfect fourth. E moves four letter names to A. A is five semitones
> above E; we need five; the alteration carries across. **A♭.**

There was never a decision to get wrong. The spelling falls out of the
arithmetic.

### And one interval for the whole chart

The second half of the problem is choosing the target. Given "up three
semitones", the app picks the target **key** first — by counting accidentals,
the way an arranger would:

| From C, up… | Candidates | Chosen | Why |
|---|---|---|---|
| 1 semitone | D♭ (5 flats), C♯ (7 sharps) | **D♭** | Fewer accidentals |
| 3 semitones | E♭ (3 flats), D♯ (9 sharps) | **E♭** | D♯ major is unwritable |
| 6 semitones | G♭ (6 flats), F♯ (6 sharps) | **G♭** | A genuine tie; caller's preference decides |

Then it derives **one interval** from the original key to that key, and applies
that same interval to every chord. This is what makes the whole chart
consistent: all flats or all sharps, because they all came from the same
interval, not from twelve independent spelling decisions.

`unwritable keys are never chosen` is a test, and it checks all 24 keys against
all 12 transpositions.

### Capo

A capo changes what you finger without changing what anyone hears. So a capo of
3 shows the chart **three semitones lower** than it sounds, and the header still
says the sounding key. This is easy to implement backwards, so it is a test:

```
{key: Bb} with capo 3  →  sounds in Bb, played in G, chords G C D
```

---

## 3. Recognising a chord without rewriting somebody's lyrics

Plain-text charts put chords on their own line above the words. To transpose
one, the app has to decide which lines are chord lines. The cost of the two
possible errors is wildly asymmetric:

- **False negative:** a chord line is missed and left untransposed. Visible,
  annoying, harmless.
- **False positive:** a *lyric* line is treated as chords and silently rewritten.
  The chart is now wrong and the player has no idea why.

So the rule is strict: **every** whitespace-separated token on the line must
parse as a chord (or as bar-line furniture like `|`, `%`, `N.C.`).

That still leaves the question of what parses as a chord, and the first attempt
at it was wrong. An allowlist of characters — "qualities are made of `m`, `a`,
`j`, `i`, `n`, digits, `#`, `b`…" — accepts any English word built from those
letters. `Add`, `And` and `Are` all became chords.

What is used instead is a **grammar**: the tail after the root has to be a
sequence of actual quality tokens (`maj`, `min`, `m`, `sus`, `add`, `dim`,
`aug`, `no`, digits, accidentals, parentheses…). `Add` fails because `dd` is not
a thing a chord quality is made of. This keeps the open-ended real-world
vocabulary — `maj7#11`, `m7b5`, `-9`, `sus2`, `6/9`, `(no3)` — while rejecting
prose.

There is a test that feeds it 32 English words that start with A–G and asserts
that none of them is a chord.

The chord quality itself is then carried through transposition **as text**,
untouched. Chart notation is not a closed vocabulary, and a parser that only
understands the qualities somebody thought of will mangle the rest. Since
transposition never needs to know what `maj7#11` *means*, the safest thing to do
with it is nothing.

---

## 4. Reaching cloud storage without five SDKs

The requirement was Google Drive, OneDrive, Proton Drive, Box and Dropbox.

### What was not done

Five SDKs, five OAuth flows, five sets of API credentials shipped inside the
APK, five vendor relationships to maintain, and five things to re-certify each
time one of them changes their terms. Plus, for the user, five sign-ins *inside
this app*, each granting it standing access to an entire drive.

### What was done

Android already solved this. Every one of those services ships a
`DocumentsProvider`. All of them therefore appear inside the system file picker,
and a tree granted through that picker is readable through the ordinary
`ContentResolver` regardless of who is behind it.

One integration. No keys in the APK. No vendor sign-in. Access scoped to the one
folder the user picked rather than their whole drive. And a provider that does
not exist yet works on the day it ships, with no update to this app.

### What it costs

This is a genuine trade and not a free win:

- The app gets whatever the provider chooses to expose. Some are slow to list a
  large folder.
- Some providers only expose files that have been marked available offline.
- There is no way to trigger a sync, or to know whether a file will need the
  network until you try to open it.

The mitigation is explicit rather than hidden: the app will copy a chart into
its own storage, where it is simply a local file that will open on a stage with
no signal. That is the answer for anything that has to work, and it is offered
rather than assumed, because copying somebody's entire Drive folder onto their
phone without asking would be its own kind of wrong.

Persisting the URI permission (`takePersistableUriPermission`) is not optional.
Without it the app can read the folder until the process dies and then quietly
cannot, which presents to the user as "my library is empty" the next morning.

---

## 5. Band-leader mode

### Absolute positions, not instructions

The leader sends **where they are**, not "turn the page". This is the single
most important decision in the protocol.

A "next page" instruction is a delta, and deltas compound. A follower whose wifi
wobbled through three messages is now three pages behind **for the rest of the
song**, and nothing will correct it. An absolute position is idempotent: any one
message is enough to be right again, a duplicate delivery costs nothing, and a
stale one is discarded by its sequence number.

The sequence number increments on **every** announcement, including one that
repeats the same page — otherwise a resend after a reconnect would be discarded
as stale at exactly the moment it is most needed.

### Losing the network is a supported state, not an error

The requirement was that a player who loses connectivity can still turn their
own pages. Meeting it properly needs more than "don't crash":

The viewer does not know whether it is leading, following or alone. It reports
where the player went and asks where it should be; a coordinator decides. There
is therefore exactly **one** page-turn code path, and it is the same one whether
or not anybody is listening. The offline case cannot rot, because it is not a
special case.

The remaining decisions live in `FollowerMachine`, as a pure function of state
and event so they can be tested exhaustively:

| Situation | What happens | Why |
|---|---|---|
| Following, leader turns | Page follows | The point of the feature |
| Following, **player** turns | Player takes control; one tap to rejoin | Otherwise the leader yanks them back half a second later and the two fight over the screen |
| Link drops | Player turns their own pages immediately | The requirement |
| Reconnects, player **did not** turn a page | Resyncs silently | Nothing to lose, and it is what they want |
| Reconnects, player **did** turn pages | Stays put, offers to rejoin | They are reading somewhere on purpose. Jumping them mid-phrase is worse than being out of step |

That last row is the one that would be got wrong by default, and it is the one
that decides whether the feature is trusted.

### mDNS, and its honest failure mode

Discovery is mDNS because it is the only option needing no configuration: the
leader taps Start, everyone else taps the name that appears. On a venue's wifi
nobody knows the subnet and nobody wants to type an IP address ninety seconds
before the first song.

Some access points block multicast between clients. On those networks discovery
finds nothing however well everything else works, and there is nothing the app
can do about it from the inside. The UI says exactly that rather than spinning.

### Line-delimited JSON over TCP

Not a binary format, and deliberately so. Three properties beat efficiency here:

- **Debuggable at a soundcheck.** When a follower is not turning pages and there
  are ten minutes before doors, being able to read the wire is worth more than
  saving bytes.
- **Nothing here is secret.** It is the page number of a song, on a local
  network, for the next three hours.
- **It is a handful of devices in one room.** The simplest thing that cannot go
  wrong beats throughput that will never be needed.

Every write is best-effort. A follower whose phone went into a pocket must never
be able to stall the leader's page turn, so a failed send drops that connection
and moves on.

There is a test asserting that an encoded message is exactly one line even when
a set list name contains newlines — because set list names are user text, and
newline framing is only sound if that holds.

---

## 6. The viewer

### Tap zones

Left third back, right two thirds forward, as asked for — and the asymmetry is
right rather than arbitrary. Forward happens ninety-nine times out of a hundred,
so it gets the larger target, and the smaller back zone sits where a hand is
least likely to brush it while turning in a hurry.

A band across the top centre opens the controls, which means no tap is ever
ambiguous between "turn the page" and "I need the menu".

### One page or two

The brief said one page in portrait and two in landscape. Implemented literally,
that gives a phone in landscape — 780dp by 360dp — two columns of illegible
squiggle.

So the decision is made on measured width and height, not orientation. Below
about 820dp wide there is not room for two readable pages of what is essentially
A4, so a phone in landscape gets **one** page, bigger. A tablet gets its spread.
This is also the right way round for foldables, which are a phone or a tablet
depending on the minute.

In spread mode the left page is kept even, so the same two pages always face
each other. Allowing it to drift odd would silently re-pair the entire document
the first time somebody turned back and forward again.

### Reflow keeps the line, not the page number

A PDF has fixed pages. A chord chart does not — its pagination depends on the
font size and the screen. So on rotation the app remembers **which line you were
reading** and finds the page that now contains it. Restoring "page 3" instead
would move a reader by an arbitrary amount every single time they turned the
phone.

This also means a phone and a tablet in the same session do not agree on what
page four of a chart is. The protocol syncs a page number and each device
resolves it against its own layout; for PDFs, which is most performance
material, the two agree exactly.

### Monospaced, and horizontally scrolling

Charts are drawn in a monospaced font. Not for looks: the chord sits above the
exact character it belongs to, and that alignment is only true if every
character is the same width. In a proportional font the chords drift a little
further off with every word.

For the same reason a too-wide chart scrolls sideways rather than wrapping. A
wrapped chord chart puts chords over the wrong words, and a chart that is
slightly awkward beats one that is wrong.

When transposing widens a chord — C becoming D♭ — the **lyric** is padded to
make room rather than letting the chord slide. The chord stays above its
syllable and the words open up a little.

---

## 7. Foot switches

The thing that makes this tractable is that both Bluetooth and USB pedals
present themselves to Android as HID keyboards. By the time the app sees
anything, the platform has already turned the pedal into key events. There is no
pedal SDK to integrate, no USB protocol to implement, and a USB pedal and a
Bluetooth pedal are the **same code path**.

What differs between pedals is only which keys they send, and there is no
standard. The defaults cover what the common pedals send; anything else is
handled by a learn mode that listens to the **raw** key stream rather than to
mapped actions — necessarily, since a pedal that needs configuring is by
definition one whose keys currently produce no action.

Two guards, both of which are the difference between usable and not:

- **Auto-repeat is dropped.** A held or sticky pedal otherwise turns thirty
  pages. This is the single worst thing a page turner can do.
- **Contact bounce is absorbed** by a 120 ms per-key guard — below the fastest
  anybody deliberately turns two pages, well above a bouncing switch. Per key,
  not global, so going forward and immediately back still works.

Volume keys are unmapped by default. Some pedals send them, but so does the
volume rocker, and stealing it from a player running a backing track is a bad
trade.

The distinction between "not mapped" and "mapped but suppressed" is load-bearing
in the activity: an unmapped key must go **back to the system** so the volume
rocker and back button still work, while a debounced repeat must be swallowed so
it does not also scroll something.

---

## 8. Storage

Settings, set lists and the file index are JSON files, not a database.

All three are read whole, written whole, and never queried. What a database
would add is a schema to migrate; what it would not add is anything this app
needs.

Writes go through a temporary file and a rename. The moment a phone is most
likely to be killed by the system is after its screen has been on for three
hours in a hot room — which is the night of the gig, and a half-written set list
then is not a recoverable situation.

A file that will not parse is moved aside rather than deleted. It is somebody's
set list; a corrupt one they can send us beats one that silently became empty.

The device id is a generated UUID rather than `ANDROID_ID`. `ANDROID_ID` would
be stable across reinstalls, which is exactly why it is not used — a page turner
has no business carrying a device fingerprint that outlives it. The id exists so
a phone that drops off the wifi is recognised as the same phone when it returns,
and a random UUID does that perfectly.

---

## 9. Things deliberately not built

- **Per-vendor cloud SDKs.** Section 4.
- **Transposing PDFs.** A PDF is a picture of a page. The control is absent
  rather than present and broken.
- **Tab transposition by default.** It is offered, and it refuses rather than
  emitting a negative fret — but adding three to every fret produces the same
  notes in a completely different position, which is useful for a riff and
  useless for a fingerstyle arrangement.
- **Automatic page turning by tempo.** Nobody plays to a click that reliably,
  and a chart that turns itself at the wrong moment is worse than no feature.
- **A cloud account or a sync server.** The band are in the same room. Set lists
  travel as files or over the local network, and nothing needs an account.
- **Drag-and-drop set list reordering.** Buttons instead. Drag demos better and
  is worse here: this is used on a phone balanced on an amp ten minutes before a
  set, and a mis-drag that silently moves song four to position eleven is not
  noticed until somebody is on stage.
