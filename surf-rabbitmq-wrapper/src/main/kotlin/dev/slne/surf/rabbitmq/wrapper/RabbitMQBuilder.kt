package dev.slne.surf.rabbitmq.wrapper

import dev.slne.surf.rabbitmq.wrapper.queue.RabbitMQQueue
import kotlinx.coroutines.CoroutineScope
import java.nio.file.Path
import kotlin.io.path.Path

class RabbitMQBuilder {
    private var configPath: Path? = null
    private var coroutineScope: CoroutineScope? = null

    private var queues: MutableSet<RabbitMQQueue> = mutableSetOf()

    fun queue(queueName: RabbitMQQueue): RabbitMQBuilder {
        queues.add(queueName)
        return this
    }

    fun queues(vararg queueNames: RabbitMQQueue): RabbitMQBuilder {
        queues.addAll(queueNames)
        return this
    }

    fun queues(queues: Collection<RabbitMQQueue>): RabbitMQBuilder {
        this.queues.addAll(queues)
        return this
    }

    fun clearQueues(): RabbitMQBuilder {
        queues.clear()
        return this
    }

    fun configPath(path: Path): RabbitMQBuilder {
        configPath = path

        return this
    }

    fun globalConfigPath(): RabbitMQBuilder {
        configPath = Path(GLOBAL_CONFIG_PATH)
        return this
    }

    fun coroutineScope(scope: CoroutineScope): RabbitMQBuilder {
        coroutineScope = scope

        return this
    }

    private fun ensureSet() {
        require(configPath != null) { "configPath can not be null" }
        require(coroutineScope != null) { "coroutineScope can not be null" }
    }

    fun build(): RabbitMQApiImpl {
        ensureSet()

        return RabbitMQApiImpl(coroutineScope!!, configPath!!, queues)
    }

    companion object {
        const val GLOBAL_CONFIG_PATH = "surf-rabbitmq"
    }
}