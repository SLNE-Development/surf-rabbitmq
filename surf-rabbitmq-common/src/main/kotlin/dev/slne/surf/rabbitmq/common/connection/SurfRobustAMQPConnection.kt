package dev.slne.surf.rabbitmq.common.connection

import dev.kourier.amqp.AMQPException
import dev.kourier.amqp.AMQPResponse
import dev.kourier.amqp.ChannelId
import dev.kourier.amqp.Frame
import dev.kourier.amqp.InternalAmqpApi
import dev.kourier.amqp.channel.AMQPChannel
import dev.kourier.amqp.connection.AMQPConfig
import dev.kourier.amqp.connection.AMQPConnection
import dev.kourier.amqp.connection.ConnectionState
import dev.kourier.amqp.robust.RobustAMQPChannel
import dev.kourier.amqp.robust.RobustAMQPConnection
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * A robust AMQP connection that fixes two critical bugs in kourier's [RobustAMQPConnection]:
 *
 * 1. **Reconnect loop never stops unexpectedly** – uses a [permanentlyClosed] flag as the loop
 *    termination condition instead of [connectionClosed][dev.kourier.amqp.connection.AMQPConnection.connectionClosed]`.isCompleted`,
 *    which could be completed by error paths and permanently kill the reconnect loop.
 *
 * 2. **Exponential backoff on failure** – when the broker is unreachable the retry interval
 *    starts at [INITIAL_RETRY_DELAY] and doubles up to [MAX_RETRY_DELAY], preventing log spam.
 */
@OptIn(InternalAmqpApi::class)
open class SurfRobustAMQPConnection(
    config: AMQPConfig,
    messageListeningScope: CoroutineScope,
) : RobustAMQPConnection(config, messageListeningScope) {

    companion object {
        private val INITIAL_RETRY_DELAY: Duration = 1.seconds
        private val MAX_RETRY_DELAY: Duration = 30.seconds
        private const val BACKOFF_MULTIPLIER: Double = 2.0

        suspend fun create(
            coroutineScope: CoroutineScope,
            config: AMQPConfig,
        ): AMQPConnection {
            val amqpScope = CoroutineScope(coroutineScope.coroutineContext + SupervisorJob())
            val instance = SurfRobustAMQPConnection(config, amqpScope)
            instance.connect()
            return instance
        }
    }

    private var reconnectJob: Job? = null

    @Volatile
    private var permanentlyClosed = false

    /**
     * Overridden as a resettable [var] so that the inherited [connectionFactory]'s loop
     * condition `while (!connectionClosed.isCompleted)` can be controlled per reconnect
     * iteration. We reset it at the start of each iteration and complete it inside
     * [closeFromBroker] to signal that one connection lifecycle has ended cleanly.
     */
    override var connectionClosed: CompletableDeferred<AMQPException.ConnectionClosed> =
        CompletableDeferred()

    override suspend fun connect() {
        reconnectJob?.cancel()
        permanentlyClosed = false
        connectionOpened = CompletableDeferred()

        reconnectJob = messageListeningScope.launch {
            surfConnectionFactory()
        }

        withTimeout(config.server.timeout.inWholeMilliseconds) {
            connectionResponses.filterIsInstance<AMQPResponse.Connection.Connected>().first()
        }
        connectionOpened.await()
    }

    private suspend fun surfConnectionFactory() {
        var retryDelay = INITIAL_RETRY_DELAY

        while (!permanentlyClosed) {
            // Reset so the inherited connectionFactory()'s while loop runs for exactly
            // one connection lifecycle (connect → work → drop → exit).
            connectionClosed = CompletableDeferred()

            // Launch the inherited connectionFactory() as a controlled sub-job.
            // Inside connectionFactory(), super.connect() is an invokespecial to
            // DefaultAMQPConnection.connect(), so it always performs the real TCP + AMQP
            // handshake regardless of our overrides.
            val innerJob = messageListeningScope.launch { connectionFactory() }

            var connected = false
            var connectError: Exception? = null

            try {
                connected = withTimeoutOrNull(config.server.timeout.inWholeMilliseconds) {
                    connectionResponses.filterIsInstance<AMQPResponse.Connection.Connected>().first()
                    true
                } == true
            } catch (e: CancellationException) {
                withContext(NonCancellable) {
                    innerJob.cancel()
                    innerJob.join()
                }
                throw e
            } catch (e: Exception) {
                connectError = e
            }

            if (connected) {
                retryDelay = INITIAL_RETRY_DELAY
                try {
                    // Wait for the connection to drop.  closeFromBroker will complete
                    // connectionClosed, causing connectionFactory()'s while loop to exit.
                    closedResponses.first()
                } catch (e: CancellationException) {
                    withContext(NonCancellable) {
                        innerJob.cancel()
                        innerJob.join()
                    }
                    throw e
                } catch (e: Exception) {
                    logger.error("Unexpected error while waiting for connection close, will retry in $retryDelay", e)
                }
            } else if (!permanentlyClosed) {
                if (connectError != null) {
                    logger.error("Connection factory failed, will retry in $retryDelay", connectError)
                } else {
                    logger.error("Connection factory failed (no connection established), will retry in $retryDelay")
                }
            }

            withContext(NonCancellable) {
                innerJob.cancel()
                innerJob.join()
            }

            if (!permanentlyClosed && !connected) {
                delay(retryDelay)
                retryDelay = (retryDelay * BACKOFF_MULTIPLIER).coerceAtMost(MAX_RETRY_DELAY)
            }
        }
    }

    override fun createChannel(id: ChannelId, frameMax: UInt): AMQPChannel =
        RobustAMQPChannel(this, id, frameMax)

    /**
     * Overridden to avoid calling [cancelAll][dev.kourier.amqp.connection.DefaultAMQPConnection.cancelAll],
     * which would complete the [connectionClosed] deferred permanently and break reconnection.
     * Instead we close the socket, complete *our* per-iteration [connectionClosed] deferred to
     * let [connectionFactory]'s while loop exit cleanly, and then emit the closed response so
     * the reconnect loop picks up immediately.
     */
    override suspend fun closeFromBroker(payload: Frame.Method.Connection.Close) {
        this.state = ConnectionState.SHUTTING_DOWN
        socket?.close()
        socket = null
        // Complete before emitting so connectionFactory()'s while condition sees
        // isCompleted = true before it can start another iteration.
        connectionClosed.complete(
            AMQPException.ConnectionClosed(
                replyCode = payload.replyCode,
                replyText = payload.replyText,
            )
        )
        connectionResponses.emit(AMQPResponse.Connection.Closed)
    }

    override suspend fun close(reason: String, code: UShort): AMQPResponse.Connection.Closed {
        permanentlyClosed = true
        reconnectJob?.cancel()
        reconnectJob = null
        return super.close(reason, code)
    }
}
