# Band session protocol

Version 1.

A leader and its followers talk over **line-delimited JSON on a plain TCP
socket**, discovered by mDNS, entirely within the local network. Nothing leaves
the room and there is no server anywhere.

---

## Discovery

The leader binds an **ephemeral port** (port 0, so the OS picks a free one — a
fixed port would collide the moment two people in the same band both tapped
Start) and advertises:

```
service type : _droidmusic._tcp
service name : the session name, as typed
TXT "leader"  : the leader's device name
port          : whatever was bound
```

Followers browse for `_droidmusic._tcp`, resolve, and connect.

**Known failure mode.** Some access points block multicast between clients. On
those networks discovery finds nothing, however well everything else works, and
nothing the app does from the inside can change that. The UI says so rather than
spinning forever.

---

## Framing

One message, one line, UTF-8, terminated by `\n`.

Each connection has **one writer and one queue**. Sending used to launch a task
per message, which meant two announcements could reach the socket in either
order and a set list could arrive after the position that referred to it —
throwing away the one guarantee a stream protocol gives you for free. The queue
is unbounded and never blocks its caller, because the caller is a page turn.

Newline framing over a length prefix, because it can be read by anything —
including a person with `netcat` at a soundcheck, which is worth more than
saving bytes when a follower is not turning pages and there are ten minutes
before doors. No message can contain a raw newline: the JSON encoder escapes
them, and there is a test asserting exactly that against a set list name full of
them.

The message type is the `type` field.

---

## Messages

Every message carries a `seq`.

### `hello` — follower to leader, first line after connecting

```json
{"type":"hello","seq":0,"protocolVersion":1,"deviceName":"Jim's Pixel","deviceId":"…","appVersion":"0.1.0"}
```

### `welcome` — leader's answer

```json
{"type":"welcome","seq":0,"accepted":true,"sessionName":"Anchor","leaderName":"Jim",
 "protocolVersion":1,"filePort":41234}
```

`filePort` is where charts can be fetched from, or `0` when this leader is not
offering any — which is also exactly what a build older than chart sharing looks
like, since it never sends the field and it decodes as zero.

A mismatched `protocolVersion` is **refused** with `accepted:false` and a reason,
rather than half-talking to a build that means something different by the same
message.

**What follows a `welcome`, to that one connection.** A leader that is already
under way replays enough for the newcomer to be useful immediately, in this
order:

1. `setlist` — the running order this session is playing, if one has been
   pushed.
2. `check` — the readiness request, if the band has been asked one.
3. `position` — where everyone already is.

Sent to the joining device alone rather than broadcast: everybody else has all
of it already, and re-pushing a set list to a player mid-song is a screen change
they did not ask for. The order matters — a check request that arrived before
the set list it is about would be a question about nothing.

Without this a device that connected ten minutes into a soundcheck received the
current page and nothing else, and the leader had to remember to push the
running order again for them, which is to say it did not happen.

### `position` — where the leader is

```json
{"type":"position","seq":12,"setlistIndex":3,"songId":"…","songTitle":"Wagon Wheel",
 "contentHash":"…","page":2,"transposeSemitones":0,"capo":0}
```

**This is an absolute position, not an instruction to turn.** The single most
important decision in the protocol:

- A "next page" delta compounds. A follower who missed three messages while its
  wifi wobbled is three pages behind for the rest of the song, and nothing
  corrects it.
- An absolute position is idempotent. Any one message is enough to be right
  again, a duplicated delivery costs nothing, and a stale one is discarded.

Followers **ignore any `position` whose `seq` is not greater than the last one
applied.**

`setlistIndex` is −1 when a single song is open outside a set list.
`contentHash` lets a follower recognise the chart even when its own copy has a
different id.

**`transposeSemitones` is binding and `capo` is advisory**, and they are not the
same kind of thing however alike they look. A transposition is the singer saying
tonight this one is in B flat: a band where that reached one phone is a band
playing two different songs, so it applies everywhere — and it is announced the
moment the leader chooses it rather than waiting for the next page turn to carry
it. A capo changes nothing anybody hears; it is how one guitarist fingers that
same key, and it means nothing at all to the keyboard player or to the guitarist
who capos somewhere else. It is sent because "the leader is playing it capo 3"
is worth knowing, and every device keeps its own.

### `setlist` — the leader pushing the running order

```json
{"type":"setlist","seq":13,"setlist":{"id":"…","name":"Friday at the Anchor","entries":[…]}}
```

Followers resolve it against their own library by content hash, then title. See
[FORMATS.md](FORMATS.md).

### `check` — the leader asking everyone to verify tonight's charts

```json
{"type":"check","seq":20,"setlist":{"id":"…","name":"Friday at the Anchor","entries":[…]}}
```

The whole set list travels with the request rather than an id. An id means
nothing on another device, and matching an incoming id against a list adopted
five minutes ago is a way to check the wrong set — so what a follower checks is
exactly what the leader sent.

### `report` — a device's answer to a `check`

```json
{"type":"report","seq":5,"report":{"deviceId":"…","deviceName":"Jim's Pixel",
 "setlistName":"Friday at the Anchor","checkedAt":1724946000000,
 "checks":[{"index":3,"title":"Copperhead Road","state":"MISSING",
            "detail":"Not in this library."}]}}
```

`state` is one of `READY`, `MISSING`, `UNREADABLE` or `DIFFERENT`. The last of
those means the device has a copy that is not the same bytes as the leader's,
which is ordinary between two people's libraries and is reported as a warning
rather than as a fault.

A whole report at a time, rather than a running commentary: a player walking in
late produces one report, the leader's screen gains one row, and there is no
partial state anybody has to interpret. A device that has not sent one is shown
as not having answered — never as ready.

**Neither message moved the protocol version, deliberately.** They are additive:
a build that has never heard of them fails to decode the line, `decode` returns
null, and it is ignored — which is right, because a device that cannot answer a
readiness check follows page turns perfectly well. Bumping the version would
instead refuse every older device at the door over a feature it does not need,
which is a worse answer to a smaller problem.

### `wanted` — follower to leader, after a set list arrives

```json
{"type":"wanted","seq":5,"deviceId":"…","wanted":[{"contentHash":"…","title":"Wagon Wheel"}]}
```

What the follower could not resolve out of the set list it was just sent. Asked
by hash and title, the two things a set list entry already carries; a song id is
meaningful only on the device that indexed it.

### `offered` — the leader's answer

```json
{"type":"offered","seq":16,"offers":[
  {"contentHash":"…","title":"Wagon Wheel","displayName":"wagon-wheel.cho",
   "kind":"CHORDPRO","sizeBytes":2048}]}
```

Only charts the leader actually has, and only ones with a content hash — the
hash is what the receiver checks the bytes against, and something that cannot be
checked on arrival is not sent.

Sizes are included because the follower is about to ask a person whether to
accept them, and "three charts" is a different question from "three charts,
60 MB".

**The leader never offers first.** It answers `wanted` and nothing else. A leader
cannot push a file at anybody, which on a protocol with no identity is most of
what makes moving files across it defensible at all.

### `ping` / `pong` — heartbeat, every 5 s

```json
{"type":"ping","seq":14,"sentAt":1724946000000}
{"type":"pong","seq":3,"sentAt":1724946000000,"deviceId":"…"}
```

A TCP socket whose far end has walked out of range does not report an error — it
goes quiet, sometimes for minutes. Without a heartbeat a follower cannot tell
"the leader has not turned a page" from "the leader is gone", and those need
opposite responses.

**Any line from a follower counts as a sign of life, and `pong` is usually the
only one there is.** A follower that is quietly following sends no `status` for
minutes together, so a leader that refreshed its timer only on the messages
carrying news evicted the entire band twenty seconds into the first song — while
the sockets were open and the pages were turning. The follower list is the
leader's whole view of the room, so it is refreshed on every inbound line
whatever it says.

### `status` — follower to leader, advisory

```json
{"type":"status","seq":4,"deviceId":"…","deviceName":"Jim's Pixel","following":true,
 "page":2,"songId":"…","missingSong":false}
```

Drives the leader's view of who is in step, who has taken over, and who does not
have the chart at all.

### `bye` — the leader closing cleanly

```json
{"type":"bye","seq":40,"reason":"Session ended"}
```

Distinguishes "the set is over" from "the leader vanished", which the followers
would otherwise have to infer from silence.

---

## The file channel

Charts travel on a **second socket**, not on the one above.

The leader binds a second ephemeral port and names it in `welcome`. A follower
opens one connection per chart, sends a request line, gets a header line, then
reads raw bytes until the declared length or the end of the connection.

```json
→ {"contentHash":"…","offset":0}
← {"ok":true,"contentHash":"…","displayName":"wagon-wheel.cho","kind":"CHORDPRO",
   "sizeBytes":2048,"offset":0,"length":2048}
← <2048 raw bytes>
```

A refusal is a header with `ok:false` and a reason, and no body.

**Why a second socket and not the control one.** TCP delivers in order, so a
large frame blocks everything queued behind it. A forty-megabyte scan on the
control connection would stop page turns for its duration, stall the heartbeat,
and start the follower's timeout logic deciding the leader had gone home — while
base64 in a JSON line would also mean holding the whole file as one string on
both devices. A page turn must never wait for a transfer, so the two do not share
a stream.

**`length` may be `-1`.** Some providers will not say how big a file is. Then the
end of the connection is the end of the chart, and the receiver relies on the
hash to know it got a whole one. Such a chart gets an indeterminate progress bar
rather than one stuck at nothing.

**`offset` is what makes a drop survivable.** A part-finished chart is kept and
asked for again from where it stopped. On a pub's wifi a large file will not
always arrive first time, and starting from nothing on every drop is how it never
arrives at all.

### What bounds it

The transport has no authentication — see below — so the safety is in the rules,
all of which live in `ChartShare` in the core with tests around them:

| Bound | Why |
|---|---|
| Only charts the follower **asked for** | A leader cannot push a file at anybody |
| Bytes checked against the offered hash | What arrives is what was described |
| 64 MB a chart, 256 MB and 60 charts a session | A session cannot fill a phone |
| The filename is **rebuilt**, never used as sent | No sender chooses a path |
| Everything lands in the app's own storage | Never in the user's folders |

What the hash check proves is that the file matches the one that was *described*.
It cannot prove the describer was honest, because nothing in this protocol can.
That is what the other four rows are for.

---

## Sequence numbers

The leader increments `seq` on **every** announcement, including one that
repeats the same page.

This matters at exactly one moment and it is an important one: after a
reconnect, the leader resends its current position. If `seq` had not moved, that
resend would be discarded as stale by the very follower that needs it most.

---

## Follower state machine

Implemented as a pure function of state and event, in `FollowerMachine`, so it
can be tested exhaustively without a socket.

**Link:** `CONNECTED` · `RECONNECTING` · `OFFLINE`
**Mode:** `FOLLOWING` · `DETACHED`

| Event | Condition | Result |
|---|---|---|
| `position` | `seq` ≤ last applied | Ignored |
| `position` | Following | Page applied |
| `position` | Detached | Leader position recorded, page **not** moved, rejoin offered |
| Player turns a page | Connected | → `DETACHED`; leader told |
| Player turns a page | Offline | Page moves, mode unchanged, remembered as an offline turn |
| Connection lost | — | → `RECONNECTING`; page turns keep working |
| Reconnected | No offline page turns | → `FOLLOWING`, silently |
| Reconnected | Player turned pages offline | → `DETACHED`, rejoin offered |
| Rejoin | — | → `FOLLOWING`, jumps to the leader's current position |

Two rows deserve their reasoning spelled out.

**Turning a page while connected detaches you.** Otherwise a player who glances
back at the previous line is dragged forward by the leader half a second later,
and the two of them fight over the screen. Turning a page is taken as meaning
it; rejoining is one tap.

**Reconnecting does not always resync.** If the link dropped and the player kept
turning their own pages, they are reading somewhere on purpose. Silently jumping
them to the leader's page the moment the wifi returns — possibly mid-phrase,
possibly mid-song — is exactly the wrong thing. So that case offers to rejoin.
The case where they did *not* turn a page resyncs silently, because there is
nothing to lose.

---

## Reconnection

Followers reconnect with exponential backoff, 1 s doubling to a 20 s ceiling, so
a leader who has genuinely gone home does not cost everyone their battery for
the rest of the night.

The reconnect runs entirely in the background. **It never blocks the viewer** —
which is the property that made this feature worth building rather than a nice
idea.

**The address is looked up again on every retry.** The leader binds an ephemeral
port, so a leader whose app restarted is listening on a different one, and a
phone that dropped off the wifi and came back may have a different address as
well. A follower retrying the address it first saw retries it all night, which
on stage reads as "it just stopped following". Each retry therefore asks mDNS
where that session name is now, and falls back to the address it already has
when nothing answers — which is the right answer on a network that blocks
multicast, where that address is all there ever was.

**The backoff resets only after a connection that lasted.** A connection that
was accepted and closed a moment later — a refused protocol version, a socket
dying at the far end — used to count as success and reset the timer, producing
an unpaced retry loop against a leader that could not talk to this device
anyway. Five seconds is the bar.

---

## Timeouts

| Constant | Value | Why |
|---|---|---|
| Heartbeat interval | 5 s | Frequent enough to notice a departure inside a song |
| Follower timeout | 20 s | Rides out a screen lock or a wifi hiccup; a player who packed up is not still listed at the end of the set |
| Leader read timeout | 30 s | Six heartbeats — a quiet but healthy connection is never mistaken for a dead one |
| Connect timeout | 4 s | Long enough for a slow AP, short enough to retry usefully |

---

## What this protocol is not

**Not authenticated, and not encrypted.** Anyone on the same network who knows
the protocol can join a session and see the page number of a song. That is the
entire threat model: a page number, on a local network, for three hours. Adding
a key exchange would mean typing a code before every rehearsal, which is a real
cost for no real gain.

That threat model was written when a page number was all that moved. Charts
moving changes it, and the honest statement is now this: **anyone on the network
who speaks the protocol can present themselves as a leader**, and a follower that
joins them will fetch charts from them. The bounds in the table above are what
keep that to a bounded nuisance — a capped number of capped files, under names
this device chose, in the app's own storage — rather than something worse. They
do not make it *safe*, because a protocol with no identity cannot be.

If that is not acceptable for a given room — an open venue network, say — the
answer is not to start or join a session, and every device keeps working exactly
as it does alone. Declining the charts when asked also leaves the session working
as it always did.

**Not a general sync protocol.** It syncs a position, a running order, whether
everyone can open it, and — on a separate socket, only when asked, and only for
songs in that running order — the chart files a follower is missing. It is not a
filesystem, it does not reconcile edits, and nothing travels in the other
direction. A follower's library is never read by the leader.

A `report` and a transfer are two halves of one thing and stay separate on
purpose: the check says what a device has not got, in a message small enough to
be sent by everybody at once, and `wanted`/`offered` moves the bytes afterwards,
one at a time, only for what somebody asked for and agreed to receive.

*An earlier version of this document said flatly that nothing but a position and
a set list travelled here. That stopped being true when chart sharing was added,
and the sentence is corrected rather than left contradicting the code.*
