# RTP-MIDI for Android

`rtpmidi` is a reusable Android library for AppleMIDI sessions on a local network. It publishes
and browses `_apple-midi._udp.`, prefers the standard UDP 5004/5005 control/data pair, and
implements the AppleMIDI invitation, clock synchronization, receiver-feedback, and RTP-MIDI
packet formats. If the fixed pair is occupied, it binds a random consecutive pair for passive
receive sessions and disables locally initiated sessions and user MIDI output.

It does not implement or accept XenSynth's former raw UDP/JSON discovery protocol.

Android participants publish their device model in the optional Bonjour `model` TXT attribute.
Peers without a valid model remain compatible and expose their AppleMIDI participant name as the
display fallback. Bonjour conflict aliases such as `Name` and `Name (2)` are retained as exact
discovery records but shown as one Destination when their host, service type, and logical name
match; losing one alias therefore does not remove another live endpoint. When both a fixed and a
fallback port are present, the fixed 5004/5005 endpoint is selected first.

Discovery is IPv4-only by default: only RFC1918 or link-local IPv4 records are shown in
Destination, and both RTP sockets are bound as AF_INET. Set `ipv4Only = false` only for an
IPv6-only network; IPv4 remains preferred and IPv6 is then used as an explicit fallback.

```kotlin
val manager = AppleMidiManager(
    context = applicationContext,
    configuration = AppleMidiConfiguration(serviceName = "My App - Android"),
    listener = object : AppleMidiListener {
        override fun onMidiEvent(event: AppleMidiEvent) {
            // event.bytes is one complete MIDI 1.0 channel message.
            // Do not feed network-origin events back to manager.send().
        }
    },
)

manager.start()
val peers = manager.scan()
manager.setDestinationIds(listOf(peers.first().id))
manager.send(listOf(byteArrayOf(0x90.toByte(), 60, 100)))
```

The public package is `icu.ringona.rtpmidi`. The release AAR is produced by:

```text
./gradlew :rtpmidi:assembleRelease
```

The library carries MIDI 1.0 channel voice messages and implements the critical RFC 6295 recovery
journal subset used by piano traffic: Chapter N for Note On/Off and Chapter C toggle/count logs for
sustain (CC64), CC120-121, and the CC123-127 channel-mode releases. Receiver Feedback (`RS`) advances
the closed-loop checkpoint across 16-bit sequence wrap. Feedback is coalesced per session and sent
at most once per 25 ms, with an immediate flush after gap recovery. Unacknowledged Note Off and
other release state is sent again in an empty, marker-clear journal heartbeat after about 35 ms;
other unacknowledged journal state retains the 100 ms heartbeat. The RTP-MIDI
`P` flag is treated only as phantom-status metadata: every packet's first channel command must
still carry an explicit status octet, as required by RFC 6295.

Playback starts with a 60 ms adaptive jitter buffer and stays within a 24-120 ms range. The queue
is bounded to 6,144 events. RTP sequence gaps wait 12 ms for reordering before journal recovery;
late packets that fill the gap cancel recovery. Output uses a bounded 256-message accumulator,
normally batches for 2 ms (4 ms under pressure), and hard-limits each RTP header + command section
+ journal datagram to 1,200 bytes. Continuous CC, pitch bend, channel pressure, and poly pressure
may be coalesced or evicted first. Note Off, zero-velocity Note On, sustain release, and channel
mode releases can then evict ordinary Note On, Program Change, or discrete CC/RPN traffic. New
ordinary discrete traffic is dropped when no lower-priority slot remains. Only a queue already
filled entirely with critical releases is replaced by a complete 16-channel sustain-off/All Sound
Off/All Notes Off panic. Encoded RTP data writes run on a bounded, 128-task serial executor so a
blocked UDP send cannot stall playout deadlines; saturation drops ordinary work, while critical
release saturation replaces the pending backlog with the same full panic.

Listeners that implement `AppleMidiScheduledListener` receive events ahead of
`AppleMidiEvent.targetTimeNanos` by the configured lookahead (8 ms by default); ordinary listeners
keep deadline-time delivery. XenSynth and JustPiano use 24 ms so their native engines can cover the
three-burst output buffer and enqueue against an Oboe presentation-frame timestamp without
sleeping.
`sessionStatistics()` exposes per-session packet loss, reorder, duplicate/late, recovery, jitter,
feedback, heartbeat, and send counters. `transportStatistics` exposes output queue pressure,
coalescing, drops, and panic fallbacks.

This version does not implement RFC 6295 system journals, Chapters P/M/W/E/T/A, enhanced Chapter C,
SysEx, or MIDI 2.0 UMP. Chapter C recovery protection is intentionally limited to CC64, CC120-121,
and CC123-127; CC122 Local Control remains outside this subset. Chapter N does not add Chapter E
protection for overlapping Note Ons on the same key. If a loss packet carries an unsupported or
uncovered journal, the receiver falls back to releasing its
tracked notes and sustain state. The manager prefers the standard 5004/5005 receive pair; if those
ports are occupied it advertises a random consecutive pair in passive-receive mode and blocks
active invitations and user MIDI output.
