package icu.ringona.rtpmidi

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress

class AppleMidiServiceRegistryTest {
    @Test
    fun conflictRenamedServicesOnOneHostPreferFixedPortOnce() {
        val registry = AppleMidiServiceRegistry()
        val base = service("base", "JustPiano Android", "192.168.1.20", 5_004)
        val renamed = service("renamed", "JustPiano Android (2)", "192.168.1.20", 51_000)

        val stableId = registry.upsert(base)
        assertEquals(stableId, registry.upsert(renamed))

        val snapshot = registry.snapshots().single()
        assertEquals(stableId, snapshot.id)
        assertEquals(5_004, snapshot.service.controlPort)
        assertEquals(setOf("base", "renamed"), snapshot.instanceIds)
    }

    @Test
    fun fixedPortWinsWhenBothAliasesAreDisconnected() {
        val registry = AppleMidiServiceRegistry()
        registry.upsert(service("random", "XenSynth Android (2)", "192.168.1.20", 51_000))
        registry.upsert(service("fixed", "XenSynth Android", "192.168.1.20", 5_004))

        assertEquals(5_004, registry.snapshots().single().service.controlPort)
    }

    @Test
    fun losingEitherAliasKeepsOrFallsBackWithinTheGroup() {
        val registry = AppleMidiServiceRegistry()
        val base = service("base", "JustPiano Android", "192.168.1.20", 5_004)
        val renamed = service("renamed", "JustPiano Android (2)", "192.168.1.20", 51_000)
        registry.upsert(base)
        registry.upsert(renamed)

        registry.remove("base")
        assertEquals(51_000, registry.snapshots().single().service.controlPort)

        registry.upsert(base)
        registry.remove("renamed")
        assertEquals(5_004, registry.snapshots().single().service.controlPort)
    }

    @Test
    fun differentNamesOnOneHostRemainSeparate() {
        val registry = AppleMidiServiceRegistry()
        registry.upsert(service("jp", "JustPiano Android", "192.168.1.20", 5_004))
        registry.upsert(service("xen", "XenSynth Android", "192.168.1.20", 5_004))

        assertEquals(2, registry.snapshots().size)
    }

    @Test
    fun equivalentNamesOnDifferentHostsRemainSeparate() {
        val registry = AppleMidiServiceRegistry()
        val firstId = registry.upsert(
            service("first", "JustPiano Android", "192.168.1.20", 5_004),
        )
        val secondId = registry.upsert(
            service("second", "JustPiano Android (2)", "192.168.1.21", 5_004),
        )

        assertNotEquals(firstId, secondId)
        assertEquals(2, registry.snapshots().size)
    }

    @Test
    fun exactInstanceReresolutionReplacesItsEndpoint() {
        val registry = AppleMidiServiceRegistry()
        val peerId = registry.upsert(
            service("base", "JustPiano Android", "192.168.1.20", 5_004),
        )
        registry.upsert(service("base", "JustPiano Android", "192.168.1.20", 6_004))

        val snapshot = registry.snapshot(peerId)!!
        assertEquals(6_004, snapshot.service.controlPort)
        assertEquals(setOf("base"), snapshot.instanceIds)
    }

    @Test
    fun connectedEndpointWinsOverConflictOrdinal() {
        val registry = AppleMidiServiceRegistry()
        val base = service("base", "JustPiano Android", "192.168.1.20", 5_004)
        val renamed = service("renamed", "JustPiano Android (2)", "192.168.1.20", 51_000)
        val peerId = registry.upsert(base)
        registry.upsert(renamed)

        val connected = setOf(AppleMidiServiceEndpoint.from(base))
        val snapshot = registry.snapshot(peerId, connected)!!

        assertEquals(5_004, snapshot.service.controlPort)
        assertTrue("base" in snapshot.instanceIds)
    }

    @Test
    fun logicalNameRemovesOnlyAndroidConflictSuffixes() {
        assertEquals(
            "JustPiano Android",
            AppleMidiServiceRegistry.logicalName("JustPiano Android (4)"),
        )
        assertEquals(
            "JustPiano Android (1)",
            AppleMidiServiceRegistry.logicalName("JustPiano Android (1)"),
        )
        assertEquals(
            "JustPiano Android (Live)",
            AppleMidiServiceRegistry.logicalName("JustPiano Android (Live)"),
        )
    }

    private fun service(
        id: String,
        name: String,
        host: String,
        port: Int,
    ) = ResolvedAppleMidiService(
        id = id,
        name = name,
        type = NsdDirectory.SERVICE_TYPE,
        host = InetAddress.getByName(host),
        controlPort = port,
        model = "Test Model",
    )
}
