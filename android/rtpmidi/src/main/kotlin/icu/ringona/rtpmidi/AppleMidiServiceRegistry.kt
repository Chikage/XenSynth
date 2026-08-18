package icu.ringona.rtpmidi

import java.net.InetAddress
import java.util.Base64

internal data class AppleMidiServiceEndpoint(
    val hostAddress: String,
    val controlPort: Int,
) {
    companion object {
        fun from(service: ResolvedAppleMidiService): AppleMidiServiceEndpoint =
            AppleMidiServiceEndpoint(service.host.hostAddress.orEmpty(), service.controlPort)
    }
}

internal data class AppleMidiServiceSnapshot(
    val id: String,
    val service: ResolvedAppleMidiService,
    val instanceIds: Set<String>,
)

/** Keeps exact DNS-SD instances while exposing one stable peer for conflict-renamed aliases. */
internal class AppleMidiServiceRegistry {
    private data class GroupKey(
        val logicalName: String,
        val normalizedType: String,
        val hostAddress: String,
    )

    private data class Record(
        val service: ResolvedAppleMidiService,
        val groupKey: GroupKey,
        val conflictOrdinal: Int,
        val resolutionOrder: Long,
    )

    private val recordsByInstanceId = LinkedHashMap<String, Record>()
    private var nextResolutionOrder = 0L

    fun upsert(service: ResolvedAppleMidiService): String {
        val parsedName = parseLogicalName(service.name)
        val groupKey = GroupKey(
            logicalName = parsedName.first,
            normalizedType = normalizeType(service.type),
            hostAddress = service.host.hostAddress.orEmpty(),
        )
        recordsByInstanceId[service.id] = Record(
            service = service,
            groupKey = groupKey,
            conflictOrdinal = parsedName.second,
            resolutionOrder = ++nextResolutionOrder,
        )
        return groupIdentity(groupKey)
    }

    fun remove(instanceId: String): String? {
        val removed = recordsByInstanceId.remove(instanceId) ?: return null
        return groupIdentity(removed.groupKey)
    }

    fun peerIdForInstance(instanceId: String): String? =
        recordsByInstanceId[instanceId]?.groupKey?.let(::groupIdentity)

    fun snapshots(
        connectedEndpoints: Set<AppleMidiServiceEndpoint> = emptySet(),
    ): List<AppleMidiServiceSnapshot> = recordsByInstanceId.values
        .groupBy(Record::groupKey)
        .map { (key, records) -> snapshot(key, records, connectedEndpoints) }

    fun snapshot(
        peerId: String,
        connectedEndpoints: Set<AppleMidiServiceEndpoint> = emptySet(),
    ): AppleMidiServiceSnapshot? {
        val records = recordsByInstanceId.values.filter { groupIdentity(it.groupKey) == peerId }
        return records.firstOrNull()?.let { snapshot(it.groupKey, records, connectedEndpoints) }
    }

    fun findByEndpoint(
        host: InetAddress,
        controlPort: Int,
        connectedEndpoints: Set<AppleMidiServiceEndpoint> = emptySet(),
    ): AppleMidiServiceSnapshot? {
        val record = recordsByInstanceId.values.firstOrNull {
            it.service.host.sameNetworkHost(host) && it.service.controlPort == controlPort
        } ?: return null
        val peerId = groupIdentity(record.groupKey)
        return snapshot(peerId, connectedEndpoints)
    }

    fun clear() = recordsByInstanceId.clear()

    private fun snapshot(
        key: GroupKey,
        records: List<Record>,
        connectedEndpoints: Set<AppleMidiServiceEndpoint>,
    ): AppleMidiServiceSnapshot {
        val chosen = records.maxWithOrNull(
            compareBy<Record> {
                AppleMidiServiceEndpoint.from(it.service) in connectedEndpoints
            }.thenBy {
                it.service.controlPort == UdpPortPair.FIXED_CONTROL_PORT
            }.thenBy(Record::conflictOrdinal)
                .thenBy(Record::resolutionOrder),
        ) ?: error("A service group must contain at least one record")
        return AppleMidiServiceSnapshot(
            id = groupIdentity(key),
            service = chosen.service,
            instanceIds = records.mapTo(LinkedHashSet()) { it.service.id },
        )
    }

    private fun groupIdentity(key: GroupKey): String {
        val canonical = "${key.logicalName}\u0000${key.normalizedType}\u0000${key.hostAddress}"
        val encoded = Base64.getUrlEncoder().withoutPadding()
            .encodeToString(canonical.toByteArray(Charsets.UTF_8))
        return "applemidi:$encoded"
    }

    private fun normalizeType(type: String): String = type.trimEnd('.').lowercase()

    companion object {
        private val CONFLICT_SUFFIX = Regex("^(.*) \\((\\d+)\\)$")

        internal fun logicalName(name: String): String = parseLogicalName(name).first

        private fun parseLogicalName(name: String): Pair<String, Int> {
            val match = CONFLICT_SUFFIX.matchEntire(name)
            val ordinal = match?.groupValues?.get(2)?.toIntOrNull()
            return if (match != null && ordinal != null && ordinal >= 2) {
                match.groupValues[1] to ordinal
            } else {
                name to 1
            }
        }
    }
}
