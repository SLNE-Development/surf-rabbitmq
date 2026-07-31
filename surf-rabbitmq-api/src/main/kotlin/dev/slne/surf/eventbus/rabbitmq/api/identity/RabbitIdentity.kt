package dev.slne.surf.eventbus.rabbitmq.api.identity

import java.util.concurrent.ThreadLocalRandom

/**
 * Who this process is on the broker.
 *
 * Replaces the former `pluginName`, which acted as connection name, request queue name and
 * callback queue prefix at once and therefore limited a process to a single peer.
 *
 * @property serviceName the logical service, shared by every instance, e.g. `surf-factions`
 * @property instanceId unique to this process, e.g. `surf-factions-3f9a1c07`
 */
class RabbitIdentity(
    val serviceName: String,
    val instanceId: String
) {
    override fun toString(): String =
        "RabbitIdentity(serviceName='$serviceName', instanceId='$instanceId')"

    companion object {
        /**
         * Builds an identity for [serviceName].
         *
         * Without [instanceName], the instance id is the service name plus a random suffix —
         * readable in broker tooling, but unknowable to other processes. Pass a stable
         * [instanceName] (e.g. `lobby-3` from the server's config) when this process must be
         * addressable via `InstanceTarget`: instance targeting is only possible when the
         * caller can predict the id. A duplicated stable name fails loudly at connect time,
         * because the instance queues are exclusive.
         */
        fun create(serviceName: String, instanceName: String? = null): RabbitIdentity {
            require(serviceName.isNotBlank()) { "serviceName must not be blank" }

            if (instanceName != null) {
                require(instanceName.isNotBlank()) { "instanceName must not be blank" }
                return RabbitIdentity(serviceName = serviceName, instanceId = instanceName)
            }

            val suffix = ThreadLocalRandom.current()
                .nextInt()
                .toLong()
                .and(0xFFFFFFFFL)
                .toString(16)
                .padStart(8, '0')

            return RabbitIdentity(
                serviceName = serviceName,
                instanceId = "$serviceName-$suffix"
            )
        }
    }
}
