package icu.ringona.xensynth

import icu.ringona.xensynth.midi.MidiWaterfallParser
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MsczToMidxTest {
    @Test
    fun convertsMscxBytesToReadableMidx() {
        val parsed = MidiWaterfallParser.detectAndParse(
            MsczToMidx.convert(MINIMAL_MSCX.toByteArray(), "minimal.mscx"),
            "minimal.midx"
        )

        assertEquals("MIDX", parsed.format)
        assertEquals(1, parsed.notes.size)
        assertEquals(60, parsed.notes[0].midiPitch)
        assertEquals(60.125, parsed.notes[0].pitch, 0.001)
    }

    @Test
    fun convertsMsczBytesToReadableMidx() {
        val parsed = MidiWaterfallParser.detectAndParse(
            MsczToMidx.convert(msczBytes(), "minimal.mscz"),
            "minimal.midx"
        )

        assertEquals(1, parsed.notes.size)
        assertTrue(parsed.duration > 0.0)
    }

    @Test
    fun convertsToStandardMidiWithoutMidxOffsetExtension() {
        val midi = MsczToMidx.convertToMidi(MINIMAL_MSCX.toByteArray(), "minimal.mscx")
        val parsed = MidiWaterfallParser.detectAndParse(midi, "minimal.mid")

        assertEquals(1, parsed.notes.size)
        assertEquals(60.0, parsed.notes[0].pitch, 0.001)
        assertTrue(!midi.containsMidxOffsetMarker())
    }

    @Test
    fun keepsMuseScoreChannelBankAndProgram() {
        val parsed = MidiWaterfallParser.detectAndParse(
            MsczToMidx.convertToMidi(CHANNEL_MSCX.toByteArray(), "channel.mscx"),
            "channel.mid"
        )

        assertEquals(1, parsed.rawEvents.count { it.velocity > 0 })
        val noteOn = parsed.rawEvents.first { it.velocity > 0 }
        assertEquals(5, noteOn.channel)
        assertEquals(40, noteOn.program)
        assertEquals(3, noteOn.bankMsb)
        assertEquals(12, noteOn.bankLsb)
    }

    @Test
    fun readsMsczRootfilePathWithBackslashes() {
        val parsed = MidiWaterfallParser.detectAndParse(
            MsczToMidx.convert(msczBytes(rootPath = "/Scores/score.mscx", entryPath = "Scores\\score.mscx"), "nested.mscz"),
            "nested.midx"
        )

        assertEquals(1, parsed.notes.size)
    }

    @Test
    fun honorsMuseScoreTupletIdsAndAbsoluteTickOverrides() {
        val parsed = MidiWaterfallParser.detectAndParse(
            MsczToMidx.convert(TUPLET_AND_TICK_MSCX.toByteArray(), "tuplet.mscx"),
            "tuplet.midx"
        )

        assertEquals(listOf(0L, 320L, 700L), parsed.notes.map { it.startTick })
    }

    @Test
    fun acceptsBomAndLeadingWhitespaceBeforeMscx() {
        val bytes = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) +
            "\n  ".toByteArray() +
            MINIMAL_MSCX.toByteArray()
        val parsed = MidiWaterfallParser.detectAndParse(
            MsczToMidx.convert(bytes, "minimal.mscx"),
            "minimal.midx"
        )

        assertEquals(1, parsed.notes.size)
    }

    @Test
    fun rejectsDoctypeBeforeSystemParserHandlesIt() {
        val xml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <!DOCTYPE museScore [
              <!ENTITY external SYSTEM "file:///etc/passwd">
            ]>
            <museScore version="4.0"/>
        """.trimIndent()

        val error = runCatching {
            MsczToMidx.convert(xml.toByteArray(), "bad.mscx")
        }.exceptionOrNull()

        assertTrue(error?.message?.contains("DOCTYPE") == true)
    }

    @Test
    fun rendersMuseScoreGraceNotesBeforeMainChord() {
        val parsed = parseMuseScore(GRACE_MSCX)

        assertEquals(listOf(62, 64), parsed.notes.map { it.midiPitch })
        assertEquals(0L, parsed.notes[0].startTick)
        assertTrue(parsed.notes[1].startTick in 1L..119L)
    }

    @Test
    fun expandsCommonOrnamentsIntoPlaybackEvents() {
        val parsed = parseMuseScore(ORNAMENT_MSCX)

        assertEquals(listOf(61, 60, 59, 60), parsed.notes.map { it.midiPitch })
        assertEquals(listOf(0L, 60L, 120L, 180L), parsed.notes.map { it.startTick })
    }

    @Test
    fun expandsMuseScoreTrillSpannersIntoPlaybackEvents() {
        val parsed = parseMuseScore(TRILL_SPANNER_MSCX)

        assertTrue(parsed.notes.size > 1)
        assertEquals(listOf(60, 61, 60, 61), parsed.notes.take(4).map { it.midiPitch })
        assertEquals(listOf(0L, 60L, 120L, 180L), parsed.notes.take(4).map { it.startTick })
    }

    @Test
    fun alternatesMuseScoreTwoChordTremoloPairs() {
        val parsed = parseMuseScore(TWO_CHORD_TREMOLO_MSCX)

        assertEquals(8, parsed.notes.size)
        assertEquals(listOf(60, 67, 60, 67, 60, 67, 60, 67), parsed.notes.map { it.midiPitch })
        assertEquals(listOf(0L, 120L, 240L, 360L), parsed.notes.take(4).map { it.startTick })
    }

    @Test
    fun expandsMuseScoreGlissandoSpanners() {
        val parsed = parseMuseScore(GLISSANDO_MSCX)

        assertTrue(parsed.notes.size > 2)
        assertEquals(listOf(60, 61, 62), parsed.notes.take(3).map { it.midiPitch })
        assertTrue(parsed.notes.any { it.midiPitch == 64 && it.startTick >= 900L })
    }

    @Test
    fun expandsMuseScoreLegacyBendPoints() {
        val parsed = parseMuseScore(BEND_MSCX)

        assertTrue(parsed.notes.size > 1)
        assertTrue(parsed.notes.any { it.pitch >= 64.0 })
    }

    @Test
    fun honorsMuseScoreOrnamentIntervalsAndUpperStart() {
        val parsed = parseMuseScore(ORNAMENT_INTERVAL_MSCX)

        assertEquals(listOf(63, 60, 63), parsed.notes.take(3).map { it.midiPitch })
    }

    @Test
    fun appliesMuseScoreSwingTextToEighthPairs() {
        val parsed = parseMuseScore(SWING_MSCX)

        assertEquals(listOf(0L, 336L, 480L), parsed.notes.map { it.startTick })
    }

    @Test
    fun honorsMuseScoreArpeggioDirectionAndStretch() {
        val parsed = parseMuseScore(ARPEGGIO_DOWN_MSCX)

        assertEquals(listOf(74, 69, 65), parsed.notes.sortedBy { it.startTick }.map { it.midiPitch })
        assertTrue(parsed.notes.maxOf { it.startTick } > 30L)
    }

    @Test
    fun appliesMuseScoreOttavaPlaybackShift() {
        val parsed = parseMuseScore(OTTAVA_MSCX)

        assertEquals(71, parsed.notes.single().midiPitch)
    }

    @Test
    fun extendsMuseScoreLetRingSpanner() {
        val parsed = parseMuseScore(LET_RING_MSCX)
        val note = parsed.notes.single()

        assertTrue(note.endTick >= 900)
    }

    @Test
    fun appliesMuseScorePalmMuteGate() {
        val parsed = parseMuseScore(PALM_MUTE_MSCX)
        val note = parsed.notes.single()

        assertTrue(note.endTick - note.startTick < 300)
    }

    @Test
    fun overlapsMuseScoreSlurLegato() {
        val parsed = parseMuseScore(SLUR_MSCX)

        assertEquals(2, parsed.notes.size)
        assertTrue(parsed.notes[0].endTick > parsed.notes[1].startTick)
    }

    @Test
    fun expandsMuseScoreVibratoSpanner() {
        val parsed = parseMuseScore(VIBRATO_MSCX)

        assertTrue(parsed.notes.size > 4)
        assertTrue(parsed.notes.any { it.pitch > 60.05 || it.pitch < 59.95 })
    }

    @Test
    fun approximatesMuseScoreGuitarBendSpanner() {
        val parsed = parseMuseScore(GUITAR_BEND_MSCX)

        assertTrue(parsed.notes.any { it.startTick < 480L && it.pitch >= 63.0 })
    }

    @Test
    fun approximatesMuseScoreChordLinePlayback() {
        val parsed = parseMuseScore(CHORD_LINE_MSCX)

        assertEquals(listOf(58, 60), parsed.notes.map { it.midiPitch })
    }

    @Test
    fun appliesChordArticulationGateAndVelocity() {
        val parsed = parseMuseScore(ARTICULATION_MSCX)
        val note = parsed.notes.single()

        assertTrue(note.velocity > 80)
        assertTrue(note.endTick - note.startTick < 300)
    }

    @Test
    fun extendsNotesCoveredByPedalSpanner() {
        val parsed = parseMuseScore(PEDAL_MSCX)
        val note = parsed.notes.single()

        assertTrue(note.endTick >= 900)
    }

    @Test
    fun appliesHairpinVelocityRampToFollowingNotes() {
        val parsed = parseMuseScore(HAIRPIN_MSCX)

        assertEquals(2, parsed.notes.size)
        assertTrue(parsed.notes[1].velocity > parsed.notes[0].velocity)
    }

    @Test
    fun extendsFermataChordPlayback() {
        val parsed = parseMuseScore(FERMATA_MSCX)
        val note = parsed.notes.single()

        assertTrue(note.endTick > 600)
    }

    @Test
    fun unrollsSimpleStartAndEndRepeats() {
        val parsed = parseMuseScore(REPEAT_MSCX)

        assertEquals(listOf(60, 62, 60, 62), parsed.notes.map { it.midiPitch })
        assertEquals(listOf(0L, 1920L, 3840L, 5760L), parsed.notes.map { it.startTick })
    }

    private fun msczBytes(
        rootPath: String = "score.mscx",
        entryPath: String = rootPath
    ): ByteArray {
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            zip.putNextEntry(ZipEntry("META-INF/container.xml"))
            zip.write(
                """
                <?xml version="1.0" encoding="UTF-8"?>
                <container>
                  <rootfiles>
                    <rootfile full-path="$rootPath"/>
                  </rootfiles>
                </container>
                """.trimIndent().toByteArray()
            )
            zip.closeEntry()

            zip.putNextEntry(ZipEntry(entryPath))
            zip.write(MINIMAL_MSCX.toByteArray())
            zip.closeEntry()
        }
        return output.toByteArray()
    }

    private fun ByteArray.containsMidxOffsetMarker(): Boolean {
        val marker = byteArrayOf(
            0xFF.toByte(),
            0x7F,
            0x07,
            0x7D,
            0x58,
            0x54,
            0x03
        )
        return asList().windowed(marker.size).any { it == marker.asList() }
    }

    private fun parseMuseScore(xml: String) = MidiWaterfallParser.detectAndParse(
        MsczToMidx.convert(xml.toByteArray(), "test.mscx"),
        "test.midx"
    )

    private companion object {
        val MINIMAL_MSCX = """
            <?xml version="1.0" encoding="UTF-8"?>
            <museScore version="4.0">
              <Score>
                <Division>480</Division>
                <Part>
                  <Staff id="1"/>
                  <Instrument>
                    <trackName>Piano</trackName>
                    <Channel>
                      <program value="0"/>
                    </Channel>
                  </Instrument>
                </Part>
                <Staff id="1">
                  <Measure>
                    <voice>
                      <TimeSig>
                        <sigN>4</sigN>
                        <sigD>4</sigD>
                      </TimeSig>
                      <Tempo>
                        <tempo>2</tempo>
                      </Tempo>
                      <Chord>
                        <durationType>quarter</durationType>
                        <Note>
                          <pitch>60</pitch>
                          <tuning>12.5</tuning>
                        </Note>
                      </Chord>
                    </voice>
                  </Measure>
                </Staff>
              </Score>
            </museScore>
        """.trimIndent()

        val CHANNEL_MSCX = """
            <?xml version="1.0" encoding="UTF-8"?>
            <museScore version="3.6">
              <Score>
                <Division>480</Division>
                <Part>
                  <Staff id="1"/>
                  <Instrument>
                    <trackName>Strings</trackName>
                    <Channel name="normal">
                      <controller ctrl="0" value="3"/>
                      <controller ctrl="32" value="12"/>
                      <program value="40"/>
                      <midiChannel>5</midiChannel>
                    </Channel>
                  </Instrument>
                </Part>
                <Staff id="1">
                  <Measure>
                    <voice>
                      <Chord>
                        <durationType>quarter</durationType>
                        <Note>
                          <pitch>67</pitch>
                        </Note>
                      </Chord>
                    </voice>
                  </Measure>
                </Staff>
              </Score>
            </museScore>
        """.trimIndent()

        val TUPLET_AND_TICK_MSCX = """
            <?xml version="1.0" encoding="UTF-8"?>
            <museScore version="3.6">
              <Score>
                <Division>480</Division>
                <Part>
                  <Staff id="1"/>
                  <Instrument>
                    <trackName>Piano</trackName>
                    <Channel>
                      <program value="0"/>
                    </Channel>
                  </Instrument>
                </Part>
                <Staff id="1">
                  <Measure>
                    <voice>
                      <Tuplet id="1">
                        <normalNotes>2</normalNotes>
                        <actualNotes>3</actualNotes>
                        <baseNote>quarter</baseNote>
                      </Tuplet>
                      <Chord>
                        <Tuplet>1</Tuplet>
                        <durationType>quarter</durationType>
                        <Note>
                          <pitch>60</pitch>
                        </Note>
                      </Chord>
                      <Chord>
                        <Tuplet>1</Tuplet>
                        <durationType>quarter</durationType>
                        <Note>
                          <pitch>62</pitch>
                        </Note>
                      </Chord>
                      <tick>700</tick>
                      <Chord>
                        <durationType>quarter</durationType>
                        <Note>
                          <pitch>64</pitch>
                        </Note>
                      </Chord>
                    </voice>
                  </Measure>
                </Staff>
              </Score>
            </museScore>
        """.trimIndent()

        val GRACE_MSCX = """
            <?xml version="1.0" encoding="UTF-8"?>
            <museScore version="3.6">
              <Score>
                <Division>480</Division>
                <Part>
                  <Staff id="1"/>
                  <Instrument><Channel><program value="0"/></Channel></Instrument>
                </Part>
                <Staff id="1">
                  <Measure>
                    <voice>
                      <Chord>
                        <durationType>eighth</durationType>
                        <acciaccatura/>
                        <Note><pitch>62</pitch></Note>
                      </Chord>
                      <Chord>
                        <durationType>quarter</durationType>
                        <Note><pitch>64</pitch></Note>
                      </Chord>
                    </voice>
                  </Measure>
                </Staff>
              </Score>
            </museScore>
        """.trimIndent()

        val ORNAMENT_MSCX = """
            <?xml version="1.0" encoding="UTF-8"?>
            <museScore version="3.6">
              <Score>
                <Division>480</Division>
                <Part>
                  <Staff id="1"/>
                  <Instrument><Channel><program value="0"/></Channel></Instrument>
                </Part>
                <Staff id="1">
                  <Measure>
                    <voice>
                      <Chord>
                        <durationType>quarter</durationType>
                        <Articulation>
                          <subtype>ornamentTurn</subtype>
                        </Articulation>
                        <Note><pitch>60</pitch></Note>
                      </Chord>
                    </voice>
                  </Measure>
                </Staff>
              </Score>
            </museScore>
        """.trimIndent()

        val TRILL_SPANNER_MSCX = """
            <?xml version="1.0" encoding="UTF-8"?>
            <museScore version="4.0">
              <Score>
                <Division>480</Division>
                <Part>
                  <Staff id="1"/>
                  <Instrument><Channel><program value="0"/></Channel></Instrument>
                </Part>
                <Staff id="1">
                  <Measure>
                    <voice>
                      <Spanner type="Trill">
                        <Trill>
                          <subtype>trill</subtype>
                        </Trill>
                        <next>
                          <location>
                            <fractions>1/2</fractions>
                          </location>
                        </next>
                      </Spanner>
                      <Chord>
                        <durationType>half</durationType>
                        <Note><pitch>60</pitch></Note>
                      </Chord>
                    </voice>
                  </Measure>
                </Staff>
              </Score>
            </museScore>
        """.trimIndent()

        val TWO_CHORD_TREMOLO_MSCX = """
            <?xml version="1.0" encoding="UTF-8"?>
            <museScore version="4.0">
              <Score>
                <Division>480</Division>
                <Part>
                  <Staff id="1"/>
                  <Instrument><Channel><program value="0"/></Channel></Instrument>
                </Part>
                <Staff id="1">
                  <Measure>
                    <voice>
                      <Chord>
                        <durationType>half</durationType>
                        <duration>1/4</duration>
                        <Note><pitch>60</pitch></Note>
                        <Tremolo>
                          <subtype>c16</subtype>
                        </Tremolo>
                      </Chord>
                      <Chord>
                        <durationType>half</durationType>
                        <duration>1/4</duration>
                        <Note><pitch>67</pitch></Note>
                      </Chord>
                    </voice>
                  </Measure>
                </Staff>
              </Score>
            </museScore>
        """.trimIndent()

        val GLISSANDO_MSCX = """
            <?xml version="1.0" encoding="UTF-8"?>
            <museScore version="4.0">
              <Score>
                <Division>480</Division>
                <Part>
                  <Staff id="1"/>
                  <Instrument><Channel><program value="0"/></Channel></Instrument>
                </Part>
                <Staff id="1">
                  <Measure>
                    <voice>
                      <Chord>
                        <durationType>half</durationType>
                        <Note>
                          <pitch>60</pitch>
                          <Spanner type="Glissando">
                            <Glissando>
                              <subtype>1</subtype>
                            </Glissando>
                            <next>
                              <location>
                                <fractions>1/2</fractions>
                              </location>
                            </next>
                          </Spanner>
                        </Note>
                      </Chord>
                      <Chord>
                        <durationType>half</durationType>
                        <Note>
                          <pitch>64</pitch>
                          <Spanner type="Glissando">
                            <prev>
                              <location>
                                <fractions>-1/2</fractions>
                              </location>
                            </prev>
                          </Spanner>
                        </Note>
                      </Chord>
                    </voice>
                  </Measure>
                </Staff>
              </Score>
            </museScore>
        """.trimIndent()

        val BEND_MSCX = """
            <?xml version="1.0" encoding="UTF-8"?>
            <museScore version="3.6">
              <Score>
                <Division>480</Division>
                <Part>
                  <Staff id="1"/>
                  <Instrument><Channel><program value="0"/></Channel></Instrument>
                </Part>
                <Staff id="1">
                  <Measure>
                    <voice>
                      <Chord>
                        <durationType>whole</durationType>
                        <Note>
                          <Bend>
                            <point time="0" pitch="0" vibrato="0"/>
                            <point time="60" pitch="100" vibrato="0"/>
                          </Bend>
                          <pitch>62</pitch>
                        </Note>
                      </Chord>
                    </voice>
                  </Measure>
                </Staff>
              </Score>
            </museScore>
        """.trimIndent()

        val ORNAMENT_INTERVAL_MSCX = """
            <?xml version="1.0" encoding="UTF-8"?>
            <museScore version="4.0">
              <Score>
                <Division>480</Division>
                <Part>
                  <Staff id="1"/>
                  <Instrument><Channel><program value="0"/></Channel></Instrument>
                </Part>
                <Staff id="1">
                  <Measure>
                    <voice>
                      <Chord>
                        <durationType>quarter</durationType>
                        <Articulation>
                          <subtype>ornamentTrill</subtype>
                          <intervalAbove>third,minor</intervalAbove>
                          <startOnUpperNote>1</startOnUpperNote>
                        </Articulation>
                        <Note><pitch>60</pitch></Note>
                      </Chord>
                    </voice>
                  </Measure>
                </Staff>
              </Score>
            </museScore>
        """.trimIndent()

        val SWING_MSCX = """
            <?xml version="1.0" encoding="UTF-8"?>
            <museScore version="4.0">
              <Score>
                <Division>480</Division>
                <Part>
                  <Staff id="1"/>
                  <Instrument><Channel><program value="0"/></Channel></Instrument>
                </Part>
                <Staff id="1">
                  <Measure>
                    <voice>
                      <StaffText>
                        <swing unit="eighth" ratio="70"/>
                      </StaffText>
                      <Chord><durationType>eighth</durationType><Note><pitch>60</pitch></Note></Chord>
                      <Chord><durationType>eighth</durationType><Note><pitch>62</pitch></Note></Chord>
                      <Chord><durationType>quarter</durationType><Note><pitch>64</pitch></Note></Chord>
                    </voice>
                  </Measure>
                </Staff>
              </Score>
            </museScore>
        """.trimIndent()

        val ARPEGGIO_DOWN_MSCX = """
            <?xml version="1.0" encoding="UTF-8"?>
            <museScore version="4.0">
              <Score>
                <Division>480</Division>
                <Part>
                  <Staff id="1"/>
                  <Instrument><Channel><program value="0"/></Channel></Instrument>
                </Part>
                <Staff id="1">
                  <Measure>
                    <voice>
                      <Chord>
                        <durationType>quarter</durationType>
                        <Note><pitch>65</pitch></Note>
                        <Note><pitch>69</pitch></Note>
                        <Note><pitch>74</pitch></Note>
                        <Arpeggio>
                          <subtype>2</subtype>
                          <timeStretch>3</timeStretch>
                        </Arpeggio>
                      </Chord>
                    </voice>
                  </Measure>
                </Staff>
              </Score>
            </museScore>
        """.trimIndent()

        val OTTAVA_MSCX = """
            <?xml version="1.0" encoding="UTF-8"?>
            <museScore version="4.0">
              <Score>
                <Division>480</Division>
                <Part>
                  <Staff id="1"/>
                  <Instrument><Channel><program value="0"/></Channel></Instrument>
                </Part>
                <Staff id="1">
                  <Measure>
                    <voice>
                      <Spanner type="Ottava">
                        <Ottava>
                          <subtype>15mb</subtype>
                        </Ottava>
                        <next>
                          <location>
                            <fractions>1/4</fractions>
                          </location>
                        </next>
                      </Spanner>
                      <Chord>
                        <durationType>quarter</durationType>
                        <Note><pitch>95</pitch></Note>
                      </Chord>
                    </voice>
                  </Measure>
                </Staff>
              </Score>
            </museScore>
        """.trimIndent()

        val LET_RING_MSCX = """
            <?xml version="1.0" encoding="UTF-8"?>
            <museScore version="4.0">
              <Score>
                <Division>480</Division>
                <Part>
                  <Staff id="1"/>
                  <Instrument><Channel><program value="0"/></Channel></Instrument>
                </Part>
                <Staff id="1">
                  <Measure>
                    <voice>
                      <Spanner type="LetRing">
                        <LetRing/>
                        <next>
                          <location>
                            <fractions>1/2</fractions>
                          </location>
                        </next>
                      </Spanner>
                      <Chord>
                        <durationType>quarter</durationType>
                        <Note><pitch>60</pitch></Note>
                      </Chord>
                    </voice>
                  </Measure>
                </Staff>
              </Score>
            </museScore>
        """.trimIndent()

        val PALM_MUTE_MSCX = """
            <?xml version="1.0" encoding="UTF-8"?>
            <museScore version="4.0">
              <Score>
                <Division>480</Division>
                <Part>
                  <Staff id="1"/>
                  <Instrument><Channel><program value="0"/></Channel></Instrument>
                </Part>
                <Staff id="1">
                  <Measure>
                    <voice>
                      <Spanner type="PalmMute">
                        <PalmMute/>
                        <next>
                          <location>
                            <fractions>1/4</fractions>
                          </location>
                        </next>
                      </Spanner>
                      <Chord>
                        <durationType>quarter</durationType>
                        <Note><pitch>60</pitch></Note>
                      </Chord>
                    </voice>
                  </Measure>
                </Staff>
              </Score>
            </museScore>
        """.trimIndent()

        val SLUR_MSCX = """
            <?xml version="1.0" encoding="UTF-8"?>
            <museScore version="4.0">
              <Score>
                <Division>480</Division>
                <Part>
                  <Staff id="1"/>
                  <Instrument><Channel><program value="0"/></Channel></Instrument>
                </Part>
                <Staff id="1">
                  <Measure>
                    <voice>
                      <Spanner type="Slur">
                        <Slur/>
                        <next>
                          <location>
                            <fractions>1/2</fractions>
                          </location>
                        </next>
                      </Spanner>
                      <Chord><durationType>quarter</durationType><Note><pitch>60</pitch></Note></Chord>
                      <Chord><durationType>quarter</durationType><Note><pitch>62</pitch></Note></Chord>
                    </voice>
                  </Measure>
                </Staff>
              </Score>
            </museScore>
        """.trimIndent()

        val VIBRATO_MSCX = """
            <?xml version="1.0" encoding="UTF-8"?>
            <museScore version="4.0">
              <Score>
                <Division>480</Division>
                <Part>
                  <Staff id="1"/>
                  <Instrument><Channel><program value="0"/></Channel></Instrument>
                </Part>
                <Staff id="1">
                  <Measure>
                    <voice>
                      <Spanner type="Vibrato">
                        <Vibrato>
                          <subtype>guitarVibratoWide</subtype>
                        </Vibrato>
                        <next>
                          <location>
                            <fractions>1/4</fractions>
                          </location>
                        </next>
                      </Spanner>
                      <Chord>
                        <durationType>quarter</durationType>
                        <Note><pitch>60</pitch></Note>
                      </Chord>
                    </voice>
                  </Measure>
                </Staff>
              </Score>
            </museScore>
        """.trimIndent()

        val GUITAR_BEND_MSCX = """
            <?xml version="1.0" encoding="UTF-8"?>
            <museScore version="5.0">
              <Score>
                <Division>480</Division>
                <Part>
                  <Staff id="1"/>
                  <Instrument><Channel><program value="27"/></Channel></Instrument>
                </Part>
                <Staff id="1">
                  <Measure>
                    <voice>
                      <Chord>
                        <durationType>quarter</durationType>
                        <Note>
                          <pitch>60</pitch>
                          <Spanner type="GuitarBend">
                            <GuitarBend>
                              <guitarBendType>bend</guitarBendType>
                              <bendStartTimeFactor>0</bendStartTimeFactor>
                              <bendEndTimeFactor>1</bendEndTimeFactor>
                            </GuitarBend>
                            <next>
                              <location>
                                <fractions>1/4</fractions>
                              </location>
                            </next>
                          </Spanner>
                        </Note>
                      </Chord>
                      <Chord>
                        <durationType>quarter</durationType>
                        <Note>
                          <pitch>64</pitch>
                          <Spanner type="GuitarBend">
                            <prev>
                              <location>
                                <fractions>-1/4</fractions>
                              </location>
                            </prev>
                          </Spanner>
                        </Note>
                      </Chord>
                    </voice>
                  </Measure>
                </Staff>
              </Score>
            </museScore>
        """.trimIndent()

        val CHORD_LINE_MSCX = """
            <?xml version="1.0" encoding="UTF-8"?>
            <museScore version="5.0">
              <Score>
                <Division>480</Division>
                <Part>
                  <Staff id="1"/>
                  <Instrument><Channel><program value="0"/></Channel></Instrument>
                </Part>
                <Staff id="1">
                  <Measure>
                    <voice>
                      <Chord>
                        <durationType>quarter</durationType>
                        <Note><pitch>60</pitch></Note>
                        <ChordLine>
                          <subtype>4</subtype>
                        </ChordLine>
                      </Chord>
                    </voice>
                  </Measure>
                </Staff>
              </Score>
            </museScore>
        """.trimIndent()

        val ARTICULATION_MSCX = """
            <?xml version="1.0" encoding="UTF-8"?>
            <museScore version="3.6">
              <Score>
                <Division>480</Division>
                <Part>
                  <Staff id="1"/>
                  <Instrument><Channel><program value="0"/></Channel></Instrument>
                </Part>
                <Staff id="1">
                  <Measure>
                    <voice>
                      <Chord>
                        <durationType>quarter</durationType>
                        <Articulation>
                          <subtype>articAccentStaccatoAbove</subtype>
                        </Articulation>
                        <Note><pitch>60</pitch></Note>
                      </Chord>
                    </voice>
                  </Measure>
                </Staff>
              </Score>
            </museScore>
        """.trimIndent()

        val PEDAL_MSCX = """
            <?xml version="1.0" encoding="UTF-8"?>
            <museScore version="3.6">
              <Score>
                <Division>480</Division>
                <Part>
                  <Staff id="1"/>
                  <Instrument><Channel><program value="0"/></Channel></Instrument>
                </Part>
                <Staff id="1">
                  <Measure>
                    <voice>
                      <Spanner type="Pedal">
                        <Pedal/>
                        <next>
                          <location>
                            <fractions>1/2</fractions>
                          </location>
                        </next>
                      </Spanner>
                      <Chord>
                        <durationType>quarter</durationType>
                        <Note><pitch>60</pitch></Note>
                      </Chord>
                    </voice>
                  </Measure>
                </Staff>
              </Score>
            </museScore>
        """.trimIndent()

        val HAIRPIN_MSCX = """
            <?xml version="1.0" encoding="UTF-8"?>
            <museScore version="3.6">
              <Score>
                <Division>480</Division>
                <Part>
                  <Staff id="1"/>
                  <Instrument><Channel><program value="0"/></Channel></Instrument>
                </Part>
                <Staff id="1">
                  <Measure>
                    <voice>
                      <HairPin id="1">
                        <subtype>0</subtype>
                        <veloChange>24</veloChange>
                      </HairPin>
                      <Chord>
                        <durationType>quarter</durationType>
                        <Note><pitch>60</pitch></Note>
                      </Chord>
                      <Chord>
                        <durationType>quarter</durationType>
                        <Note><pitch>62</pitch></Note>
                      </Chord>
                    </voice>
                  </Measure>
                  <Measure>
                    <endSpanner id="1"/>
                    <voice>
                      <Rest>
                        <durationType>measure</durationType>
                      </Rest>
                    </voice>
                  </Measure>
                </Staff>
              </Score>
            </museScore>
        """.trimIndent()

        val FERMATA_MSCX = """
            <?xml version="1.0" encoding="UTF-8"?>
            <museScore version="3.6">
              <Score>
                <Division>480</Division>
                <Part>
                  <Staff id="1"/>
                  <Instrument><Channel><program value="0"/></Channel></Instrument>
                </Part>
                <Staff id="1">
                  <Measure>
                    <voice>
                      <Fermata>
                        <subtype>fermataAbove</subtype>
                      </Fermata>
                      <Chord>
                        <durationType>quarter</durationType>
                        <Note><pitch>60</pitch></Note>
                      </Chord>
                    </voice>
                  </Measure>
                </Staff>
              </Score>
            </museScore>
        """.trimIndent()

        val REPEAT_MSCX = """
            <?xml version="1.0" encoding="UTF-8"?>
            <museScore version="3.6">
              <Score>
                <Division>480</Division>
                <Part>
                  <Staff id="1"/>
                  <Instrument><Channel><program value="0"/></Channel></Instrument>
                </Part>
                <Staff id="1">
                  <Measure>
                    <startRepeat/>
                    <voice>
                      <TimeSig>
                        <sigN>4</sigN>
                        <sigD>4</sigD>
                      </TimeSig>
                      <Chord>
                        <durationType>quarter</durationType>
                        <Note><pitch>60</pitch></Note>
                      </Chord>
                    </voice>
                  </Measure>
                  <Measure>
                    <endRepeat>2</endRepeat>
                    <voice>
                      <Chord>
                        <durationType>quarter</durationType>
                        <Note><pitch>62</pitch></Note>
                      </Chord>
                    </voice>
                  </Measure>
                </Staff>
              </Score>
            </museScore>
        """.trimIndent()
    }
}
