# DroidMusic

A sheet music, chord chart and tablature viewer for Android, built for reading
from on a stand rather than for browsing on a sofa.

It opens PDFs, scans and chord charts from a folder on the device or from any
cloud storage the phone can already reach; turns pages by tap or foot switch;
holds set lists that carry each song's key; keeps a whole band on the same page
over the local network; and transposes chord charts properly, spelling the
result the way an arranger would write it.

---

## Status

**v0.1.** Everything described below is implemented. The music theory, set list,
band-sync and update layers are covered by 336 tests that run on a plain JVM; the
app layer adds its own.

CI builds an installable APK and an AAB from a clean checkout on every push.
What has *not* happened is anyone running it on a phone — see
[What has and has not been tested](#what-has-and-has-not-been-tested), which is
worth reading before relying on this at a gig.

---

## What it does

### Files, from anywhere the phone can see

Point it at a folder. Any folder the Android file picker can reach works, and
that includes **Google Drive, Dropbox, Box, Proton Drive, Nextcloud** and
anything else with a document provider installed. OneDrive is reached a file at
a time rather than a folder at a time, for a reason that is not this app's doing
and is set out below.

There is no separate sign-in for any of them, and no vendor SDK in the app.
Every one of those services publishes a `DocumentsProvider`, so all of them
appear in the system picker and a folder granted there is readable through the
ordinary content resolver whoever is behind it. One integration, no API keys
shipped in the APK, access scoped to the folder you chose rather than your whole
drive, and a provider that does not exist yet works on the day it ships.

The cost is real and worth stating plainly: what the app gets is whatever the
provider chooses to expose, and some are slow to list a large folder or only
expose files already marked available offline. For anything that has to open on
a stage with no signal, the app will copy a chart into its own storage, where it
is simply a local file.

**One of those costs has a name: OneDrive does not offer its folders.** Android's
folder picker only lists providers that can answer "is this document inside that
tree", and OneDrive does not claim to — so it is filtered out by the system
before this app is involved, while appearing perfectly normally when you pick
*files*. There is no flag that changes this and nothing to retry. So picking
files individually is offered next to "add a folder" rather than buried, charts
added that way are grouped as **OneDrive files** so they can be filtered and
removed as a unit, and the folder list says why OneDrive is missing at the moment
you are looking for it. The catch is the real one: no folder means new charts are
not picked up on their own, so each is added by hand.

**Folders can be removed**, from the same list they are added in. The
confirmation leads with the part that matters — the files are not deleted, only
forgotten — and the access Android granted is handed back at the same time, so
the app stops appearing in the system's storage settings as having access to a
folder you have taken away from it.

Opens `.pdf`, images (`.png .jpg .webp .heic` and friends), Word documents
(`.docx`), and chord charts in `.cho .chopro .pro .crd .txt .tab .md`.

Charts also arrive from outside: tap one in a file manager, or share it across
from another editor, and DroidMusic is offered. ChordPro has no registered file
type of its own, so a `.cho` looks to Android like an anonymous blob — the app
accepts those and works out what they are by reading them, rather than telling
you the file is not supported. A chart picked by hand whose extension is on
nobody's list still opens if it is text; one that genuinely cannot be read is
named rather than silently skipped.

A chord chart typed in Word is unzipped, read as text, and then transposed, key-
detected and paginated exactly like any other text chart — same parser, no second
code path, and no document library in the APK. Charts set in a monospaced font
come out as they went in; one aligned by eye in a proportional font never lined
up in *characters* and wants to be a PDF. [docs/FORMATS.md](docs/FORMATS.md) sets
out exactly what survives the trip.

### Sorting the library out

Hold a chart in the list and it offers what can be done with it.

| | |
|---|---|
| **Add to a set list** | Files it into tonight's running order without leaving the library. |
| **Transpose** | Sets the key the song is played in, for good. |
| **Rename** | Changes what DroidMusic calls it. |
| **Remove from library** | Stops listing it. The file is not touched. |
| **Delete file** | Deletes it, and only appears when that is actually possible. |

**Transposing from here is remembered.** The viewer has always been able to
transpose a chart, but only until you closed it. A key set from this menu sticks:
the chart opens in it every time, the library row shows the key it will actually
sound in rather than the one it was written in, and a capo is remembered
alongside. The file is untouched - the chords are rewritten as it is drawn, the
same as they always were.

A set list entry still carries its own key and wins where it has one, because a
running order is a decision about one particular night. Adding a song to a set
list starts that entry from the song's key rather than from the written one, so
filing a song does not quietly put it back into the writer's key.

Charts that cannot be transposed - PDFs, photographs of a page - are not offered
the option. There is nothing in a picture to rewrite.

**Renaming changes the name here and nowhere else.** A chart's title normally
comes from inside the file - `{title:}` in a ChordPro, the filename for a PDF -
and a rename is stored as an override next to it rather than written back. So the
file keeps its own name, your Drive folder keeps showing it, and clearing the box
goes back to whatever the chart says it is called. The cost is that the two names
can differ; the alternative is this app writing into a file in somebody's synced
folder, which is not a thing it should do quietly and not a thing it has
permission to do at all.

**Removing is not deleting, and it sticks.** A removed chart stops being listed,
stops resolving from a set list, and stops matching a follower's request in a
band session - but the file stays exactly where it is. It is remembered as
removed rather than simply forgotten, because forgetting it would last only until
the next folder scan found the file and put it straight back. There is an
**Undo** on the banner, and the folder list keeps a **Put back** for anything
already dismissed.

**Deleting the file is offered only where it can be done.** DroidMusic asks
Android for *read* access to your folders and nothing more, so it holds no
permission to delete anything in them - which is the right default for an app
pointed at somebody's entire sheet music collection, and it means a chart in a
folder or a cloud drive has to be deleted wherever it lives. What it *can* delete
is what it made itself: a photographed page, or a chart imported from a link. So
that is when the menu item is there, and the rest of the time it is absent rather
than present and failing.

### Photographing music

**Point the camera at a page and it becomes a PDF in the library.** DroidMusic
finds the edges of the page in the photograph, straightens out the angle the
phone was held at, and files the result. Photograph several pages and they become
one multi-page PDF — one row in the library, one thing to put in a set list, one
file to send to a band mate, turning pages in the order you took them.

The camera is Android's own, through an `ACTION_IMAGE_CAPTURE` intent. That means
**no camera permission is asked for** — the picture is taken by the camera app,
which already has one — no camera library in the APK, and the viewfinder you
already know. What it costs is a live outline of the detected page while you
aim, which is why every page is shown back to you before anything is saved.

Finding the page is done without any vision library:
[Otsu's method](https://en.wikipedia.org/wiki/Otsu%27s_method) splits the
photograph into two brightness classes, the class **the middle of the frame**
belongs to is taken as the page — so dark music on a white table works as well as
the other way round — the largest connected run of it is the page, and its four
corners are the extreme points of that run. Straightening is one perspective
transform, not a rotation, because a page shot from slightly above is a trapezium
and de-rotating a trapezium leaves a trapezium.

**It would rather do nothing than something wrong.** A blob too small to be a
page, one filling the entire frame, or one shaped like an L rather than a page is
refused, and a refusal keeps the photograph whole and says so on the page
thumbnail. A scanner that mangles a page is worse than one that leaves it alone,
because the player finds out at the stand.

Scans are saved into the app's own storage, never into a folder you added — a
folder in somebody's Drive is not somewhere an app should start writing
uninvited.

### Importing a chart from a link

**Share an Ultimate Guitar page into DroidMusic and it becomes a ChordPro file in
the library.** Find the chart in the phone's browser, tap share, pick DroidMusic:
the page is fetched, converted, saved, and opened. From then on it is an ordinary
chart — it transposes, it takes a capo, it goes into a set list, and it is
matched to other players' copies by content hash like any other.

**What is stored is the chart, not the link.** A bookmark would be less work and
useless: charts are read on stage, where the wifi belongs to somebody else and
there may be no signal at all, and a chart that has to be downloaded before it can
be read is a chart that is not there when it is needed. The page is fetched once
and the network is never involved in that song again.

The conversion is not a second chart parser. The page carries its own chart as
data — the site's front end reads it to draw the page — and once the site's `[ch]`
and `[tab]` markers come off, what is left *is* the chords-over-lyrics format the
app already reads, with the columns exactly where the chart's author put them. So
the importer is a decoder: it produces text, and the ordinary parser does the
rest. The song's name, artist, key, capo and tuning come off the page as
directives, and the address it came from is written into the file as `{source:}`,
because a chart that turns out to be a rough transcription is worth being able to
trace back.

It imports **chord charts**, not Ultimate Guitar's official or Pro tabs — those
are interactive players with no text behind them, and there is nothing in one to
import. When that is what was shared, the app says so rather than saving an empty
file.

### Turning pages

Tap the **right two thirds** to go forward, the **left third** to go back. The
split is asymmetric on purpose: forward is what you do ninety-nine times out of
a hundred, so it gets the larger, easier target, and the smaller back zone sits
where a hand is least likely to brush it. Both sides are adjustable, including
mirroring them.

**Tap the top tenth of the screen for the menu** — the full width of it, so it
can be found in the dark at arm's length without looking. It holds the three
things a player cannot reach while a chart fills the screen: the key it is in,
the size of the text, and the way back out. It is a band rather than a small
target on purpose: a target you have to aim at is one you miss, and missing it
turns the page. That band is the one part of the tap layout that cannot be
adjusted away, because a full-screen chart with no visible exit is the one state
this app must never be able to reach — it still works with tap-to-turn switched
off entirely.

**Foot switches work with no setup on most pedals.** Bluetooth and USB pedals
both present themselves to Android as HID keyboards, which means there is
nothing to pair inside this app and no USB protocol to implement — pair the
pedal in Android's own Bluetooth settings, or plug it in, and it works. The
defaults cover what AirTurn, PageFlip and the common generic pedals send. For
anything else there is a learn mode that watches the raw key stream and binds
whatever arrives, which is the only approach that works for a pedal nobody has
heard of.

Two details that matter more than they look:

- **Auto-repeat is dropped.** A pedal held down, or one with a sticky switch,
  otherwise turns thirty pages. That is the worst thing this app could do.
- **Contact bounce is absorbed** by a 120 ms guard — below the fastest anyone
  deliberately turns two pages, well above a bouncing switch.

Volume keys are left alone unless you ask for them, because some pedals send
them and so does the volume rocker.

### One page or two

One page in portrait, two side by side in landscape. But the decision is made on
the **actual size of the viewport**, not the orientation: a phone in landscape is
780dp wide and 360dp tall, and two pages of music on it are two columns of
illegible squiggle. A tablet in landscape gets its spread; a phone gets one page,
bigger, which is what you can read from a stand. You can force either.

In spread mode the left page is kept even, so turning back and forward again
lands where it started rather than silently re-pairing the whole document.

**Double tap a scan to fill the screen with the music.** A page of sheet music is
mostly paper; on a phone the notation ends up occupying a fraction of a screen
that could show it far larger. A double tap finds the box the content actually
sits in, crops the margins away and re-renders the page at the bigger scale -
re-renders, so it is sharper rather than merely larger. Double tap again for the
whole page.

It is careful about the things that break this on real files: the paper colour is
measured rather than assumed to be white, so a warm photograph or a grey scan
still works; and a speck of scanner dust in the margin cannot quietly push the
crop back out to the full page, which is the failure that would make the feature
appear to do nothing at all.

The zoom stays on across page turns, with each page measuring its own margins.
The cost is one third of a second: where a double tap is possible, a single tap
has to wait to see whether a second one is coming before it turns the page. That
delay never applies to chord charts, which have nothing to crop, and never to a
foot switch. There is a switch in Settings for anyone who would rather have the
instant tap back.

### Set lists

A set list is the running order for one night, and each entry carries **its own
key and capo** — because the same chart gets sung in different keys by different
singers, and that decision belongs to the night, not to the file.

Building one is two gestures. **Press and hold a chart in the library** to file
it into a set list, without leaving the library or losing your place in it.
**Press, hold and drag a song inside a set list** to move it — the list scrolls
under the drag when it reaches the edge, and the order is saved when you lift
your finger. The up and down buttons on each row do the same job for anyone who
would rather not risk a drag ten minutes before a set, or who is using a screen
reader.

They travel two ways, and both matter:

- **As a file** (`.dmset`, plain JSON) through the share sheet — email, a
  messaging app, a shared folder. Works when the band are not in the same room,
  and works with any means of sending a file.
- **Pushed over a live session**, instantly, to everyone who has joined.

A set list arriving from another device is matched against your own library by
**content hash first, then by title** — normalised for case, punctuation and a
leading "the". Two people rarely have byte-identical copies of the same chart:
one has a scan, the other a ChordPro. Anything genuinely missing is named rather
than silently dropped.

### Backstage

**Starting a set goes backstage first.** Every chart in the running order is
*opened* — not looked up, opened, through exactly the code path the viewer will
use at the downbeat, and a scan or a PDF is made to draw a page as well. The
index is only this app's memory of a folder listing: a chart can sit in it
perfectly while the file behind it has been renamed, left in the cloud,
half-downloaded, or put behind a permission the provider quietly withdrew. None
of that shows up until somebody taps the song, and by then the band is playing.

If you are leading a session, everyone else checks at the same moment and their
answers come back to your screen. What it shows is the thing that is actually
useful: **which song, and who.** "Two devices have problems" is not something
anyone can act on; "nobody but you has Copperhead Road" is — it means send the
file now, or move the song.

Four verdicts per chart: it opens; it is **missing** from that device; it is
there but **will not open**, with the likely reason; or it is **a different copy**
from the leader's — same song, different bytes, which is normal between two
people's libraries and still worth knowing before somebody discovers a different
repeat in bar 40.

A device that has not answered is shown as not having answered, never as ready.
And none of it blocks anything: the start button works whatever the check says,
because a band that has decided to busk a song from memory does not need an
app's permission.

Where a session is running, a player whose check found something can **ask the
leader for it** from this screen. Pushing the running order already offers
whatever a library could not resolve — see [Charts that arrive with the set
list](#charts-that-arrive-with-the-set-list) — and this is the button for
afterwards, because the check knows something that first offer cannot: a chart
that resolves perfectly and then will not open is invisible to it, and is exactly
the one somebody wants a fresh copy of.

### Updating itself

There is no Play Store listing, so **Settings - Check for updates** fetches the
newest build straight from this repository's releases and hands it to Android's
installer. You choose whether to be offered full releases only or the
pre-releases that every push to `dev` produces.

**Nothing is checked or downloaded until you press the button.** No check on
launch, no periodic check, no background download. An app that decides to fetch
nine megabytes over a venue's wifi ninety seconds before the first song has done
the worst thing a page turner can do, and the only way to be certain it never
happens is for there to be no code that could start it.

Three things it is careful about, because each is silent when it goes wrong:

- **It compares versions properly.** `v0.1.0-dev.9` and `v0.1.0-dev.10` differ by
  one character, and as text the first sorts *after* the second — so the naive
  version of this feature tells a player nine builds behind that they are up to
  date, and keeps telling them. Versions are parsed and ordered by SemVer's
  rules, and publishing `v0.1.0` correctly reads as an upgrade to everyone on a
  dev build.
- **It never offers a downgrade.** Android would allow one — every pre-release
  carries the same `versionCode`, so the installer sees a reinstall — which
  means this check is the only thing that prevents it.
- **It says what it verified.** Each release publishes a `SHA256SUMS.txt`, and
  the download is checked against it. That catches a transfer truncated by bad
  wifi or answered by a captive portal's login page; it is *not* a signature,
  and the app does not describe it as one. What actually protects the install is
  Android's own rule that an app cannot be replaced by a package signed with a
  different key.

That last rule has a consequence worth knowing before relying on any of this,
and it currently bites: a CI build made **without** signing secrets is signed
with a debug key generated fresh for that run, so it cannot update — or be
updated by — anything, including the previous pre-release. The app now detects
this before offering the install and explains it, rather than letting Android
report "conflicts with an existing package" and leaving you to guess. Fixing it
for good means configuring a signing key; see
[Signing](#signing-and-why-it-is-not-optional-any-more) and
[docs/DESIGN.md](docs/DESIGN.md).

### Band-leader mode

One device taps **Start**; the rest tap its name. Sessions find each other over
mDNS on the local network, so nobody types an IP address ninety seconds before
the first song. The leader turns a page and everyone's turns.

Nothing leaves the room: mDNS to discover, a TCP socket to follow, no server
anywhere.

**When the network drops, players keep playing.** This is the part worth
describing precisely, because it is where this kind of feature usually fails:

- Page positions are sent as **absolute** positions with a sequence number, not
  as "next page" instructions. A device that missed three messages while its
  wifi wobbled is corrected by the next message it receives, rather than ending
  up three pages behind for the rest of the song.
- A follower that loses the leader **carries on with its own page turns**
  immediately, and reconnects in the background.
- On reconnection, if the player **did not** turn a page while offline, the
  device resyncs silently — there is nothing to lose and it is what they want.
  If they **did**, the device stays where it is and offers to rejoin, because
  yanking somebody to a different page mid-phrase is worse than being out of
  step.
- Turning a page yourself while connected takes control, with one tap to fall
  back in. Otherwise the leader and the player fight over the screen.

The leader sees who is in step, who has taken over, and who does not have the
chart at all — and, before the first song, whether everyone can open every chart
in tonight's set. See [Backstage](#backstage).

### Charts that arrive with the set list

Start a session, push the running order, and any chart a band mate has not got is
offered to them — and [Backstage](#backstage) is where anybody can ask again for
one that arrived broken. They are asked once — how many, and how big — and then the
charts arrive while everyone is still plugging in, rather than being discovered
missing at the top of the second song.

The charts travel on a socket of their own, so a forty-megabyte scan cannot stop
anybody's page turning while it moves, and a transfer that drops picks up where
it stopped instead of starting again. Anything too large, or too much of it, is
named rather than silently skipped.

**It only goes one way, and only when asked.** The leader answers requests; it
never pushes a file at anybody and never reads anybody's library. A follower asks
only for songs in the set list it was just sent, checks what arrives against what
was described, and files it in DroidMusic's own storage — never in your folders.

**A word about the network.** Band sessions are unauthenticated by design: anyone
on the same wifi who speaks the protocol can join one. That was a fair trade when
a page number was all that moved, and it is a bigger one now that charts do. The
limits are real — a capped number of capped files, under names this device
chooses, in the app's own storage — but on a network you do not trust, the answer
is still not to join a session. Declining when asked leaves everything else
working. See [PROTOCOL.md](docs/PROTOCOL.md).

### Transposing, and meaning it

Chord charts in text or ChordPro format can be transposed to any key, with or
without a capo.

The output is **spelled correctly**, which is the whole difficulty. Transposing
C–F–G up three semitones has to give E♭–A♭–B♭ — not E♭–G♯–A♯, and not D♯ major,
which would need nine sharps and is unwritable. The app gets this right by
storing notes as a letter plus an alteration rather than as one of twelve
semitones, and by deriving **one interval for the entire chart** rather than
respelling each chord in isolation. Details in [docs/DESIGN.md](docs/DESIGN.md).

It also reads what is there and tells you about it:

- **Key detection** across all 24 keys, with an honest confidence figure. An
  ambiguous chart is reported as a suggestion you can override, not as a fact.
- **Capo suggestions**: a chart in E♭ becomes C with a capo on 3, and the app
  says so, counting how many of that chart's chords become open shapes.
- **Chords outside the key** singled out — judged on quality as well as root, so
  the E7 in a C major song is flagged, which is the interesting chord in the
  progression and the one a root-only check misses.

A ChordPro chart **opens with its title, artist and key** across the head of the
first page, and only the first page. The key shown is the key the chart is
currently in rather than the key the file was written in, so it follows a
transposition and names a capo when one is set. A chords-over-lyrics chart gets
no such heading, because its title is already in the text where its author typed
it and printing it twice would be a bug wearing a feature's clothes.

Both ChordPro (`{title:}`, `[C]inline`) and plain chords-over-lyrics are read,
sniffed automatically rather than by file extension. Chords stay above the right
syllable after transposing, even when a chord gets wider — the lyric opens up to
make room rather than letting the chords drift.

**What is not transposed.** A PDF is a picture of a page; there is nothing in it
the app can rewrite, and it does not pretend otherwise — the control is simply
absent rather than present and broken. Tablature can be shifted by fret, but it
is off by default and refuses rather than emitting a fret of −2, because adding
three to every fret produces the same notes in a completely different and often
unplayable position.

### Any screen

Phones, tablets, foldables, split screen. Layout is driven by measured width and
height rather than by a device category, which is the right way round when a
foldable is both a phone and a tablet depending on the minute. Reflowable charts
re-paginate on rotation and keep you on **the line you were reading**, not on
"page 3", which after a reflow is somewhere else.

---

## Building it

```sh
# The core suites. No Android SDK needed - this is the point of the split.
./gradlew -PcoreOnly coreTests

# Everything, including the APK. Needs the Android SDK.
./gradlew build
./gradlew :app:assembleRelease
```

Requires JDK 17. `-PcoreOnly` leaves the Android module out of the build
entirely, so the music theory, set list, sync and update suites run on any
machine.

### Working on it with an AI agent

`.claude/settings.json` registers a `PreToolUse` hook that routes shell commands
through [RTK](https://github.com/rtk-ai/rtk), which strips the parts of a
command's output that nothing downstream reads - Gradle's task list, its daemon
chatter, the progress bars - and keeps the parts that carry the result.

```sh
brew install rtk    # or: cargo install --git https://github.com/rtk-ai/rtk
```

Installing it is optional and there is nothing to configure. The hook checks for
the binary and exits silently when it is absent, so a checkout on a machine
without RTK behaves exactly as it did before - no rewritten commands, no
warnings, and nothing in the build that depends on it either way.

Measured on this repository, `./gradlew -PcoreOnly coreTests` goes from 2,955
bytes of output to 59 when everything is up to date, and from 6,129 to 57 when
every suite actually runs. A failing suite goes from 4,232 to 344 and still names
the failing test, the pass count and the HTML report. `grep` across the Kotlin
sources roughly halves.

**One gap worth knowing about.** RTK picks its filter from the Gradle task name,
so anything ending in `test` gets the test-output filter - and that filter keeps
failing test names but not `e:` compiler errors. A *compile* error during
`coreTests` therefore arrives as `BUILD FAILED` and nothing else, which is
precisely the failure the CI job goes to such lengths to keep visible. RTK does
print the path of the unfiltered log it tee'd, so the error is one `cat` away,
and running the compile task on its own reports it in full:

```sh
./gradlew -PcoreOnly :core:music:compileKotlin   # passes through unfiltered
rtk proxy ./gradlew -PcoreOnly coreTests         # any command, unfiltered
```

`--stacktrace`, `--info` and `--debug` also disable filtering entirely, so
reaching for them still gets the whole log.

### Releases

Every push builds and uploads an APK; a push to `dev` publishes a pre-release
and a push to `main` publishes a release, both from the artifacts that run
produced. The version lives in `gradle.properties` and nowhere else; merging to
`main` with a version that already has a release fails the run rather than
replacing an APK somebody has installed.

### Signing, and why it is not optional any more

Set `ANDROID_KEYSTORE_BASE64`, `ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS`
and `ANDROID_KEY_PASSWORD` as repository secrets to get release-signed builds.

**Without them, no release can update any other release.** The build falls back
to the Android debug key, and a fresh CI runner has no debug keystore, so one is
generated *per run* — every release is therefore signed by a different key.
Android only lets an app be replaced by a package signed with the same key, so
each build is an unrelated app as far as the installer is concerned, and
installing one over another fails with "conflicts with an existing package".

The builds still install and run perfectly well on a device with no DroidMusic on
it; it is only replacement that is impossible. Each release prints its signing
certificate fingerprint in its own notes, so whether two builds can update one
another is something you can check by looking.

To fix it permanently:

```sh
keytool -genkeypair -v -keystore droidmusic.jks -alias droidmusic \
  -keyalg RSA -keysize 4096 -validity 10000

base64 -w0 droidmusic.jks       # the value for ANDROID_KEYSTORE_BASE64
```

Keep that keystore. Losing it means never being able to update an installed copy
again. The first properly signed release still cannot replace a debug-signed one
already on a phone — that install needs an uninstall first, once.

---

## What has and has not been tested

Being specific about this, because "it builds" and "it works on stage" are
different claims.

**Tested, by 336 automated tests on the JVM.** Two of these found real bugs
during development — the pagination budget check caught a page-break rule that
could overflow a short viewport, and the chord-word test caught a parser that
would have rewritten the word "Add" as a chord.

| Area | What is covered |
|---|---|
| Note spelling | Transposition spells by interval; round trips; no unwritable keys ever chosen |
| Chord parsing | 20 real-world quality forms; 32 English words that must *not* parse as chords |
| Chart parsing | ChordPro and chords-over-lyrics; format sniffing; column alignment |
| Word documents | Paragraphs, breaks, tables, tab stops against typed tabs, tracked-change deletions, non-breaking spaces, a whole file out of the zip |
| Transposition | Whole-chart spelling consistency; slash basses; capo semantics; round trips at all 11 intervals |
| Key detection | Major, minor, flat keys; survives transposition to all 12 keys; confidence ordering |
| Analysis | Non-diatonic chords; capo suggestions; tab detection |
| Pagination | No page over budget at any size from 1 line up; no row lost or duplicated; headers never orphaned; the ChordPro title block on the first page only |
| Set lists | Reorder, JSON round trip, malformed input, cross-device matching |
| Band sync | Wire round trip for every message; stale and duplicate positions; the full follower state machine including the reconnect cases; a build that has never heard of a message ignores it rather than dropping the session |
| Updates | SemVer ordering including `dev.9` against `dev.10`; a release outranking its own pre-releases; downgrades never offered; drafts and APK-less releases skipped; the debug APK never chosen; a real releases payload; checksum parsing |
| Tap zones | The exact 1/3–2/3 split; mirroring; the menu band across the top, including with tap-to-turn off |
| Backstage | Every verdict and its wording; silence counted as silence; trouble grouped by song and person; reports replaced, and dropped with the device that sent them |
| Zoom to content | Finding the printed box on a page; dust in the margin ignored; light-on-dark and warm-cast scans; content running to the edge; a page with nothing worth cropping |
| Page detection | A squared-up page, one shot at an angle, a lamp in the corner of the frame, dark music on a light table, a dim photograph, two pages in shot, and every refusal: too small, whole-frame, and not page-shaped |
| Foot switches | Auto-repeat; contact bounce; unmapped keys passed back to the system; learn mode |
| Page layout | Spread parity; end and start detection; zero-page documents |

**Built, but not yet run on a phone.** CI produces a signed-and-installable
APK and an AAB from a clean checkout — the app compiles, lint passes, and both
unit suites are green. Nobody has installed it. These need a device and are
covered by no test:

- Whether specific pedals send what the defaults expect.
- Whether a given cloud provider lists a large folder quickly enough to be
  usable, and how each behaves offline, and which of them turn out to withhold
  their folders the way OneDrive does.
- How real Word charts, written by real bands, come out — the reader is tested
  against markup, not against a hundred documents somebody actually gigs with.
- mDNS discovery on real venue wifi, which is the environment most likely to
  block it.
- PDF rendering performance on large scanned songbooks.

Treat v0.1 as something to try at a rehearsal before trusting at a gig.

---

## Documentation

- [docs/DESIGN.md](docs/DESIGN.md) — why each choice was made, including the
  ones that were nearly made differently.
- [docs/PROTOCOL.md](docs/PROTOCOL.md) — the band session wire protocol.
- [docs/FORMATS.md](docs/FORMATS.md) — chart formats read, and the `.dmset` set
  list format.
