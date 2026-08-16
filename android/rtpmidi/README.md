# RTP-MIDI for Android

`rtpmidi` is a reusable Android library for AppleMIDI sessions on a local network. It publishes
and browses `_apple-midi._udp.`, reserves a dynamic consecutive UDP control/data port pair, and
implements the AppleMIDI invitation, clock synchronization, receiver-feedback, and RTP-MIDI
packet formats.

It does not implement or accept XenSynth's former raw UDP/JSON discovery protocol.

Android participants publish their device model in the optional Bonjour `model` TXT attribute.
Peers without a valid model remain compatible and expose their AppleMIDI participant name as the
display fallback. Bonjour conflict aliases such as `Name` and `Name (2)` are retained as exact
discovery records but shown as one Destination when their host, service type, and logical name
match; losing one alias therefore does not remove another live endpoint.

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

The current baseline carries MIDI 1.0 channel voice messages and can receive and preserve an
opaque recovery journal. It sends packets without a recovery journal; SysEx and MIDI 2.0 UMP are
outside this version's API.

Playback starts with a 60 ms adaptive jitter buffer and stays within a 24-120 ms range. The queue
is bounded to 6,144 events, and packet output coalesces only a 1 ms burst while retaining each
message timestamp as RTP-MIDI delta time. The manager prefers the standard 5004/5005 receive pair;
if those ports are occupied it advertises a random consecutive pair in passive-receive mode and
blocks active invitations and user MIDI output. A detected sequence gap releases locally tracked
notes and sustain state so packet loss cannot leave a hanging note.
