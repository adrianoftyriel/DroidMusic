# Design notes

Why each choice was made, including the ones that were nearly made differently.

---

## 1. Why the core is not an Android module

Four of the five modules — `:core:music`, `:core:library`, `:core:session`,
`:core:update` — have no Android dependency at all. That is not architectural tidiness for its
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
./gradlew -PcoreOnly coreTests      # 310 tests, no Android SDK, seconds
```

which runs on any machine with a JDK, gates the APK build in CI, and gives a
reviewer something they can execute in their first five minutes.

The `-PcoreOnly` flag is opt-in rather than auto-detected from whether an SDK is
present. Auto-detection was tempting and is a trap: a CI run that quietly skips
the app module because it could not find an SDK is far worse than one that fails
with a message telling you to install it.

### What the split cannot catch

The core tests run on OpenJDK. The app runs on Android. Where the two disagree,
a green suite proves nothing — and they do disagree about regular expressions,
because Android's `java.util.regex` is backed by ICU and OpenJDK's is not.

The one that has actually bitten is the brace. OpenJDK reads a lone `}` as the
literal character; ICU rejects the whole pattern. A `Regex` held in an `object`
is compiled the first time that class is touched, so the failure surfaces as an
`ExceptionInInitializerError` and then a `NoClassDefFoundError` on every later
reference — which presents to the user as *every* ChordPro file failing to open,
from a parser whose entire suite passes.

`AndroidRegexTest` reads the source of every module, `app` included, and fails on
any regex holding a brace that is neither escaped nor part of a `{n,m}`
quantifier. It is a strange-looking test — a test that greps its own repository —
and it exists because this is a class of bug the fast suite is structurally
unable to see. The rule it enforces: in a regular expression, escape braces.

The general lesson is worth stating plainly, because the next divergence will not
be about braces: **the core suite tests logic, not the platform.** Anything that
depends on a platform library's behaviour rather than on arithmetic has to be
checked on a device or guarded some other way.

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

### Where exact spelling has to give up

Letter-and-alter spelling is exact, and exact means it can be asked for a note
that does not exist to be written down. A chart in G♭ with an E♯ in it,
transposed a tritone and then read behind a capo on 7, lands on a note four
sharps above B. There is no such symbol.

This was a crash, not a wrong chord: `Note` asserts that its alteration is
within a triple accidental — a good invariant, checked in the constructor — and
the transposer walked straight into it. The assertion failed inside a data class
while a player was looking at the chart, and took the app down. Worse, the same
arithmetic runs on **open**, because the capo suggestions transpose every chord
behind the scenes to see which fingering is easiest, so a file nobody had
touched could kill the app the moment it was tapped.

So past a triple accidental the exact spelling is abandoned for the plainest one
of the same pitch — fewest accidentals, and on a tie the side the music was
already heading, so a flat chart does not sprout a sharp. That is what an
arranger writing the part out would do. The pitch is always right; only the
spelling is approximated, and only where no readable spelling exists.

Two tests hold this down: every note against every interval must land on the
correct pitch class, and the whole matrix — every key spelling, every chord
spelling, every transposition the UI offers, every capo position — must not
throw.

Belt and braces on top of that: a chart file is arbitrary input, so opening one
that defeats the parser or the analyser fails to "this chart will not open",
which the viewer already knows how to say, rather than to a dead app thirty
seconds before the downbeat.

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
- **Some providers do not offer their folders at all** — see below.

The mitigation is explicit rather than hidden: the app will copy a chart into
its own storage, where it is simply a local file that will open on a stage with
no signal. That is the answer for anything that has to work, and it is offered
rather than assumed, because copying somebody's entire Drive folder onto their
phone without asking would be its own kind of wrong.

Persisting the URI permission (`takePersistableUriPermission`) is not optional.
Without it the app can read the folder until the process dies and then quietly
cannot, which presents to the user as "my library is empty" the next morning.

The grant is also handed **back** when a folder is removed from the library. A
persistable grant this app no longer uses still shows up in Android's own
storage-access screens as this app having access to that folder, which is untrue
and unnerving to find; and the platform caps how many an app may hold, so a
library rearranged a few times over a couple of years can quietly stop being able
to take a new one.

### Read access, and what that rules out

The grant this app asks for is **read only** — `FLAG_GRANT_READ_URI_PERMISSION`
and no more, both when a folder is picked and when it is persisted. That is a
deliberate limit rather than an oversight, and it is worth being explicit about
what it costs, because the cost is a feature people ask for.

It means the app cannot delete or rename a file in one of the user's folders. So
the press-and-hold menu on a chart offers **Remove from library**, which is
bookkeeping and always works, and offers **Delete file** only for the copies the
app made itself — a photographed page, a chart imported from a link — where it
owns the file outright. `DocumentSources.canDeleteFile` asks before the menu is
drawn rather than letting the action fail when tapped, and it checks both the
grant and the provider's own `FLAG_SUPPORTS_DELETE`, so if a write grant is ever
introduced the item starts appearing without that code changing.

Renaming is the same shape of answer. It is stored as an override on the library
entry (`SongRef.userTitle`) rather than written into the file, which keeps the
app out of somebody's synced folder and works for every source and every format
including PDFs. The honest cost is that DroidMusic's name for a chart can then
differ from the name every other app shows.

Asking for write access would fix both and is not obviously right: the same grant
that permits deleting one chart permits rewriting the whole folder, on an app
whose entire job is to be pointed at somebody's sheet music collection. It would
also not be retroactive — every folder already added would stay read-only until
re-picked — so it is a decision with a migration attached rather than a flag to
flip.

The one deletion the app does perform is confined by construction, not by care:
`DocumentSources.managedFile` returns a `File` only when it resolves inside the
app's own managed directory, and it is the only route to a `File.delete()` in the
codebase. A song row that had somehow picked up a `file://` URI from elsewhere
cannot become a deleted chart.

### The folder that is not in the folder picker

Point the app at a folder and OneDrive is not among the choices. Open the same
app's *file* picker and there it is. This reads exactly like a bug in this app,
and it is worth writing down that it is not one.

Android's folder picker (`ACTION_OPEN_DOCUMENT_TREE`) only lists roots whose
provider declares `Root.FLAG_SUPPORTS_IS_CHILD` — "I can tell you whether this
document is inside that tree", which is what makes granting a whole subtree
meaningful. A provider that does not declare it is filtered out by the system
picker before this app is involved. There is no flag to pass, nothing to retry,
and no fallback API: the same grant simply is not on offer.

So the app stops treating a folder as the only way in:

- **Picking files individually reaches every provider**, because
  `ACTION_OPEN_DOCUMENT` has no such requirement. That path is now offered
  alongside "add a folder" rather than only when the library is empty, which is
  where it used to be — reachable exactly once, before the thing that makes
  somebody need it has happened.
- **Picked files are grouped by the provider they came from**, so "OneDrive
  files" is a real place in the library that can be filtered by and removed in
  one go, rather than an anonymous pile called "Picked files".
- **The reason is said out loud**, in the folder list, at the moment somebody is
  looking for OneDrive and not finding it. A limit nobody can explain is
  indistinguishable from a broken app.

What this costs is that OneDrive charts do not appear when new ones are added to
the folder, because there is no folder — each is added once, by hand. That is
worse than a tree and much better than nothing, and it is the provider's decision
rather than this app's.

### Removing a folder

Adding a folder without being able to remove one is not half a feature, it is a
trap: a library grows, someone reorganises their Drive, and the app goes on
listing four hundred charts it can no longer open with no way to say so.

The confirmation leads with the thing the user needs to know — **the files are
not deleted**. "Remove" next to a folder full of somebody's sheet music is
frightening without that sentence, and a library nobody dares tidy is how it
ended up needing tidying.

Files the app copied into its *own* storage are the one exception: those are
deleted, because nothing outside the app has a reference to them and forgetting
them without deleting them would strand a scanned songbook's worth of space on
the device with nothing left that could ever open it.

---

## 5. Reading a chart out of a Word document

Plenty of bands keep their chord charts in Word, so `.docx` is read. The
interesting decisions are what *not* to do.

**No document library.** Apache POI reads `.docx` and would also bring several
megabytes of spreadsheet, presentation and OLE2 code into an APK whose job is to
open one chart on a phone at a gig. A `.docx` is a zip with an XML file in it,
and the only thing this app wants from it is the characters in the order they
were typed. That reader is a couple of hundred lines with no dependencies, and it
lives in `:core:library`, which means it is tested on a plain JVM rather than on
a device.

**No second code path.** The reader returns a string. From there the chart goes
through the same sniffing, the same chord parser, the same key detection, the
same transposer and the same layout engine as a `.txt`. Word support is a
decoder, not a parallel implementation, which is why it is a small change and why
it cannot rot separately from everything else.

**A scanner, not an XML parser.** The only questions being asked of the markup
are "is this a run of text" and "does this end a line". Both are answerable by
walking the tags, without a parser on the device and without depending on the
file declaring its namespaces the way the specification says it should.

Three details that would each be a silent bug:

- `<w:tab/>` means two different things depending on where it sits: a tab
  character inside a run, and *the definition of a tab stop* inside `w:pPr`.
  Reading the second as the first indents every paragraph in a document that has
  a ruler set, which is most of them. Every property element in WordprocessingML
  ends in `Pr`, so all of them are skipped whole.
- `<w:delText>` holds text a tracked change **deleted**. Putting it back would
  show the player a line somebody deliberately took out of the chart.
- Word writes non-breaking spaces wherever it decides a gap should not be broken,
  and `Character.isWhitespace` says they are not whitespace. A chord line padded
  with them is one enormous token, fails the "every token on the line is a chord"
  test, and the chart quietly stops being transposable. They are turned back into
  ordinary spaces.

The honest limit is alignment. A chart typed in a proportional font and lined up
by eye never lined up in *characters*, and columns are exactly what a
chords-over-lyrics chart means. Monospaced Word charts come out perfectly;
visually-aligned ones want to be PDFs, and [FORMATS.md](FORMATS.md) says so
rather than leaving it to be discovered on a stand.

---

## 6. Updating the app from inside the app

There is no Play Store listing, so an update is a downloaded APK and a system
install prompt. That is a small feature with three sharp edges, and each of them
is a decision rather than an accident.

### The version the app thinks it is

Every pre-release cut from `dev` is built from the same `gradle.properties`, so
all of them carry `versionName 0.1.0`. An updater that compared version *names*
would find `0.1.0` on both sides forever and report "up to date" to a player
eleven builds behind.

So the identity is the **tag** — `v0.1.0-dev.12` — and CI stamps it into the APK
as a `BuildConfig` field. The tag is computed in the build job, before anything
is compiled, and the publish job then uses that same string rather than
recomputing it. Two copies of that arithmetic drifting apart would produce an app
convinced it is a release nobody published, which for an updater is a loop: it
checks, does not match, installs, and still does not match.

A build that CI did not make has no tag, and says "built from source" rather than
claiming to be the release of that name.

### Comparing two versions is not comparing two strings

`v0.1.0-dev.9` and `v0.1.0-dev.10`. As text the first sorts second, and a player
on dev.9 is told they are current — and goes on being told that for the next
ninety releases. Nothing about that failure is visible.

So versions are parsed into a numeric core plus pre-release identifiers and
compared by SemVer's rules: numeric identifiers numerically, and a version
carrying a pre-release suffix ranking *below* the same version without one, so
that publishing `v0.1.0` reads as an upgrade to everyone on a dev build. It is a
pure function in `:core:update` with a test file to itself, for the same reason
the transposer is: being wrong here is silent.

The newest release is chosen **by version, not by the order GitHub returned**.
GitHub sorts by creation time, which is usually the same ordering and is not the
same thing — a release re-published after deletion, or cut from an older commit,
arrives out of order and would otherwise be offered as an upgrade to somebody
already past it.

Going backwards is never offered. Android would allow it: every dev build has the
same `versionCode`, so the installer sees a reinstall rather than a downgrade and
raises no objection. The check in this app is the only thing standing between a
player and being walked backwards, which is why the downgrade case is a test.

`versionCode` is deliberately **not** bumped per pre-release. It would make the
platform enforce the ordering too, and it would also mean that publishing
`v0.1.0` — versionCode 1 — to somebody running dev build number 12 would be
refused by Android as a downgrade. The two schemes cannot both be monotonic, and
the one that has to work is release-follows-pre-release.

### What actually makes the install safe

Not the download, and not the checksum. **Android refuses to replace an installed
app with a package signed by a different key**, and that refusal is the security
boundary: a substituted APK cannot become the DroidMusic on somebody's phone, it
can only fail to install.

The `SHA256SUMS.txt` published with each release is checked, and it is an
*integrity* check, not a signature — the checksum comes from the same release as
the APK, so anyone who could replace one could replace the other. What it earns
its place catching is the realistic failure: a download truncated by bad venue
wifi, or a captive portal that answered with a login page and a 200. Those arrive
as an APK that fails to install with a message explaining nothing. A release with
no checksum file is reported as unverified rather than quietly accepted.

The honest consequence of the signing rule, and it is worse than it first looks.
With no signing secrets configured, the release build falls back to the Android
debug key — and a fresh CI runner has no debug keystore, so the Android Gradle
Plugin generates **a new one every run**. Three releases were published that way
before anybody tried to update between two of them, and each was signed by a
different key: `v0.1.0-dev.12` and `v0.1.0-dev.15` carry certificates whose
`notBefore` timestamps are their own build times. No release could update any
other, and the only symptom is Android saying "conflicts with an existing
package".

Two things follow, and both are now built in:

- **CI reads the fingerprint back out of the assembled APK** and prints it in the
  release notes, so "can this build update that one" is answerable by looking
  rather than by trying. When the key is a per-run debug key, the notes say so in
  a warning rather than a footnote.
- **The app checks before it offers.** Once an APK is downloaded, its signing
  certificate is compared with the running app's, and a mismatch is explained
  where it happens: what Android is about to refuse, why the rule exists, that
  the only way through is an uninstall, and that an uninstall takes the set lists
  and the folder list with it. Android's own wording names neither the package
  nor the conflict, and reads to everybody as "the app is broken".

The real fix is a signing key in the repository's secrets, which the workflow has
always supported; see the README. Note that even after that, the first properly
signed release cannot replace a debug-signed one already installed. That one
install needs an uninstall first.

Separately, the debug *build type* carries a `.debug` application id, so a release
APK would install beside it rather than over it — which is why the updater
refuses to run on a debug build instead of doing that to somebody.

### Nothing happens on its own

No check on launch, no periodic check, no background download, no notification.
Every request is the direct result of somebody pressing a button.

This is not minimalism. An app that decides to fetch nine megabytes over a
venue's wifi ninety seconds before the first song has done the worst thing a page
turner can do, and the only way to be sure it never happens is for there to be no
code that could start it.

---

## 7. Photographing a page

A player without a scanner still has a folder of paper. The camera turns it into
the library.

### The camera is not in this app

Capture goes through `ACTION_IMAGE_CAPTURE`, so the photograph is taken by
whatever camera app the phone already has. Three things follow, and they are the
reason for the choice: **no CAMERA permission is requested at all**, because this
app never touches the camera; no camera library enters the APK; and the player
gets the viewfinder they already know, with their own flash, grid and focus.

The alternative was CameraX and an in-app viewfinder, which buys one real thing:
a live outline of the detected page while aiming, so a badly framed shot is
obvious before it is taken. That is a genuine loss, and the mitigation is that
every page is shown back for approval before anything is saved. It is the right
trade at this size; it would not be if scanning were the app's main job.

### Finding the page without a vision library

OpenCV would do this and is twenty times the size of the rest of the app. ML
Kit's document scanner would do it better and requires Google Play Services,
which is a dependency on a phone's provenance rather than on a library.

What is here instead is about a hundred and fifty lines:

1. **Otsu's method** splits the photograph into two brightness classes. Not a
   fixed threshold: paper under a pub light is a mid grey against a darker grey,
   and paper by a window is 250 against 30. One fixed cut is right for exactly
   one of those.
2. **The class in the middle of the frame is the page**, rather than the brighter
   one. A player points the camera at the thing they are photographing, and
   assuming "page = bright" crops dark music on a white table out of its own
   photograph.
3. **The largest connected run** of those pixels is the page, which is what makes
   a lamp or a bright window elsewhere in the shot irrelevant.
4. **The corners are the extremes** of x+y and x−y over that run. For a convex
   blob that is exactly the four corners, in four passes, with no line fitting,
   no Hough transform and nothing to tune. It also degrades into something
   sensible when an edge is partly in shadow, where contour tracing degrades into
   nothing.

Straightening is `Matrix.setPolyToPoly` over those four corners — a full
perspective transform, because a page photographed from slightly above is a
trapezium and rotating a trapezium leaves a trapezium.

### Refusing is a feature

Four checks reject a detection: too small a share of the frame, too large a share
(which is what every degenerate case collapses to), a side too short, and a blob
that does not fill the quad drawn round it — an L, a ring, two patches with a gap.

**Every refusal keeps the photograph whole**, and the page is labelled as kept
whole so the player knows why it looks like the picture they took. A scanner that
crops a page through the middle of the last line is worse than one that does
nothing, because the player only finds out at the stand, and by then the paper is
at home.

### Why a PDF and where it goes

The app already opens folders of images, so this could have saved four JPEGs. A
PDF makes them one piece of music: one library row, one set list entry, one file
to send, and it turns pages in the order they were taken rather than in whatever
order a file manager considers alphabetical. It is written with the platform's own
`PdfDocument`, so there is no PDF library in the APK.

Scans are written to the app's managed storage and never into a folder the user
added. That folder is somebody's Drive, and an app that starts writing files into
it uninvited has overstepped — which is also why the confirmation for removing a
folder can promise that nothing in it is ever touched.

---

## 8. Band-leader mode

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

## 9. The viewer

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

### Double tap to fill the screen with the music

A scan of a piece of sheet music is mostly paper. Fitted to a phone on a stand
the notation occupies perhaps two thirds of the height and half the width, and
the player is reading something far smaller than their screen could show. A
double tap crops the margins away and scales what is left to fill the viewport.

**Finding the edge of the music.** The page is scanned for the box its content
sits in. Three decisions in that scan are what make it work on real files rather
than on clean ones:

- **The background is measured, not assumed.** "Crop the white" fails on a
  photograph taken under a warm light, on a scan with a grey cast, and on a chart
  printed light on dark. The most common luminance in the page is taken as the
  paper, whatever it is, and anything far enough from it is ink. It has one known
  limit, stated rather than hidden: a page more than half covered in ink would
  invert the sense of that, but sheet music is never remotely that dense.
- **One dark pixel is not ink.** A speck of scanner dust, a punch hole or a JPEG
  artefact out in the margin is enough to push the box back to the full page - at
  which point the zoom silently does nothing, on every page of the scan. So a row
  counts as printed only once enough pixels in it are ink, which a line of music
  always has and a speck never does.
- **The crop leaves a little room.** Cropping exactly to the ink puts the
  outermost notehead against the edge of the screen, which reads as the page
  having been cut off even though nothing is missing.

**The page is re-rendered, not magnified.** The crop is handed down to the
renderer, so a PDF is drawn again at the larger scale and an image is decoded
with a subsample chosen for the region being shown. Scaling up the bitmap already
on screen would give identical geometry and none of the extra detail, which on a
scan is the difference between reading it and squinting at it.

**Zoom survives the page turn.** A player who zooms in is saying that this stand,
at this distance, needs the music bigger - and that does not stop being true at
the end of the page. Each page measures its own crop, so a songbook whose margins
wander still lands right.

**What it costs, and where.** Compose can only tell a single tap from the first
half of a double tap by waiting out the double tap window, so registering a
double tap handler delays every tap-to-turn by roughly a third of a second. On an
app whose whole point is turning pages, that is not a footnote.

Two things keep it contained. The handler is registered **only on pages that can
be zoomed** - a chord chart has no margins to crop and reflows to fit already, so
it keeps its instant taps. And a foot switch never comes through the tap surface
at all, so the pedal, which is what most players actually use on stage, is
untouched either way. For anyone who taps to turn and would rather have the
instant turn back, the whole thing is one switch in Settings.

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

## 10. Foot switches

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

## 11. Building a set list by hand

Two gestures, both of them the ones a phone has already taught everybody.

**A long press in the library files a chart into a set list.** The alternative
is a trip to the set list screen and back for each of twenty songs, and the
decision is made at the moment the player is looking at the chart and thinking
"yes, that one" — so that is where it has to be possible. The dialog that opens
is the whole flow: the set lists, a way to make a new one, and nothing in
between. A song already in the list is said so and added anyway, because a song
that comes back in the encore is in the set twice and that is the band's call.

**A long press on a row in a set list picks it up to drag.** The row under the
finger is tracked in viewport coordinates, which is what makes a drag that runs
off the bottom of the screen pull the list along under it rather than stop at
the edge. A row takes a neighbour's place when its centre line crosses into it —
the arithmetic is in `ReorderGeometry`, out of the composable and tested,
because a row that lands one place off still looks like a list and the running
order is only found to be wrong from the stage.

The order is written when the finger lifts, not on every row it crosses. Each
save is a whole-file write (section 12), and thirty of them during one drag would
be both slow and a good way to leave a half-written set list behind. Until then
the screen shows its own copy of the order, because the saved one comes back
through a file write and a flow and cannot keep up with a moving finger.

One wrinkle worth writing down: a long press that never becomes a drag still
ends with a finger lifting, and a plain clickable row reads that as a tap. So
the flag that suppresses the tap goes up when the press is recognised, not when
the drag ends — the click can be delivered before the gesture detector hears
that the finger has gone.

---

## 12. Storage

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

## 13. Things deliberately not built

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
- **Drag-and-drop set list reordering *instead of* buttons.** The drag is built
  — section 11 — but the up and down buttons stay next to it. A mis-drag that
  silently moves song four to position eleven is not noticed until somebody is
  on stage, and a drag is invisible to a screen reader; the buttons are both the
  careful path and the accessible one.
