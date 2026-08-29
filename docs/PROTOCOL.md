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
{"type":"welcome","seq":0,"accepted":true,"sessionName":"Anchor","leaderName":"Jim","protocolVersion":1}
```

A mismatched `protocolVersion` is **refused** with `accepted:false` and a reason,
rather than half-talking to a build that means something different by the same
message.

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

### `setlist` — the leader pushing the running order

```json
{"type":"setlist","seq":13,"setlist":{"id":"…","name":"Friday at the Anchor","entries":[…]}}
```

Followers resolve it against their own library by content hash, then title. See
[FORMATS.md](FORMATS.md).

### `ping` / `pong` — heartbeat, every 5 s

```json
{"type":"ping","seq":14,"sentAt":1724946000000}
{"type":"pong","seq":3,"sentAt":1724946000000,"deviceId":"…"}
```

A TCP socket whose far end has walked out of range does not report an error — it
goes quiet, sometimes for minutes. Without a heartbeat a follower cannot tell
"the leader has not turned a page" from "the leader is gone", and those need
opposite responses.

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

If that is not acceptable for a given room — an open venue network, say — the
answer is not to start a session, and every device keeps working exactly as it
does alone.

**Not a general sync protocol.** It syncs a position and a running order.
Anything else two devices need to share travels as a file.
