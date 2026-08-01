package dev.slne.surf.eventbus.redis.event

import dev.slne.surf.eventbus.core.envelope.EventEnvelope
import dev.slne.surf.eventbus.event.BusEvent
import dev.slne.surf.eventbus.event.BusEventCodec
import dev.slne.surf.eventbus.event.SurfBusEvent
import dev.slne.surf.eventbus.redis.bus.BinaryFrame
import dev.slne.surf.eventbus.redis.codec.readString
import dev.slne.surf.eventbus.redis.codec.readVarInt
import dev.slne.surf.eventbus.redis.codec.writeString
import dev.slne.surf.eventbus.redis.codec.writeVarInt
import io.netty.buffer.ByteBuf
import io.netty.buffer.ByteBufUtil
import io.netty.buffer.Unpooled
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNamingStrategy
import org.openjdk.jmh.annotations.*
import org.openjdk.jmh.infra.Blackhole
import org.redisson.client.codec.ByteArrayCodec
import org.redisson.client.codec.StringCodec
import java.util.*

/**
 * Compares the JSON event envelope with a `BusEventCodec` on the binary channel.
 *
 * Every benchmark uses the same logical event and includes the transport envelope, mirroring
 * exactly what [dev.slne.surf.eventbus.redis.bus.RedisEventTransport] puts on the wire. Encode,
 * decode, and round-trip costs are separated so regressions are easier to attribute. Payloads
 * contain only ASCII characters so [payloadBytes] is also the UTF-8 payload size for both
 * transports.
 */
open class EventTransportBenchmark {
    @Benchmark
    open fun jsonEncode(state: EventTransportBenchmarkState, blackhole: Blackhole): Int =
        state.encodeJson(blackhole)

    @Benchmark
    open fun binaryEncode(state: EventTransportBenchmarkState, blackhole: Blackhole): Int =
        state.encodeBinary(blackhole)

    @Benchmark
    open fun jsonDecode(state: EventTransportBenchmarkState): BenchmarkEvent = state.decodeJson()

    @Benchmark
    open fun binaryDecode(state: EventTransportBenchmarkState): BenchmarkEvent = state.decodeBinary()

    @Benchmark
    open fun jsonRoundTrip(state: EventTransportBenchmarkState): BenchmarkEvent =
        state.roundTripJson()

    @Benchmark
    open fun binaryRoundTrip(state: EventTransportBenchmarkState): BenchmarkEvent =
        state.roundTripBinary()
}

@State(Scope.Thread)
@OptIn(ExperimentalSerializationApi::class)
open class EventTransportBenchmarkState {
    @Param("0", "32", "256", "1024", "4096", "16384", "65536")
    @JvmField
    var payloadBytes: Int = 0

    private lateinit var event: BenchmarkEvent
    private lateinit var jsonEnvelopeMessage: String
    private lateinit var jsonPacket: ByteArray
    private lateinit var binaryPacket: ByteArray

    private val json = Json {
        namingStrategy = JsonNamingStrategy.SnakeCase
        encodeDefaults = true
    }
    private val eventSerializer: KSerializer<BenchmarkEvent> = BenchmarkEvent.serializer()

    val jsonWireBytes: Int
        get() = jsonPacket.size

    val binaryWireBytes: Int
        get() = binaryPacket.size

    @Setup(Level.Trial)
    fun setup() {
        val payload = buildString(payloadBytes) {
            repeat(payloadBytes) { append(('a'.code + it % 26).toChar()) }
        }
        event = BenchmarkEvent(
            aggregateId = 0x1020_3040_5060_7080L,
            sequence = 42,
            active = true,
            payload = payload,
        )

        jsonEnvelopeMessage = jsonEnvelope().encodeToString(json)
        jsonPacket = encodeJsonWireBytes()
        binaryPacket = encodeBinaryWireBytes()

        check(decodeJson() == event) { "JSON benchmark fixture does not round-trip" }
        check(decodeBinary() == event) { "Binary benchmark fixture does not round-trip" }
    }

    fun encodeJson(blackhole: Blackhole): Int {
        val wire = StringCodec.INSTANCE.valueEncoder.encode(jsonEnvelope().encodeToString(json))
        try {
            blackhole.consume(wire)
            return wire.readableBytes()
        } finally {
            wire.release()
        }
    }

    fun encodeBinary(blackhole: Blackhole): Int {
        val wire = ByteArrayCodec.INSTANCE.valueEncoder.encode(
            BinaryFrame.encode(binaryEnvelope(), BenchmarkEventCodec.encodeToByteArray(event), json)
        )
        try {
            blackhole.consume(wire)
            return wire.readableBytes()
        } finally {
            wire.release()
        }
    }

    fun decodeJson(): BenchmarkEvent {
        val wire = Unpooled.wrappedBuffer(jsonPacket)
        val message = try {
            StringCodec.INSTANCE.valueDecoder.decode(wire, null) as String
        } finally {
            wire.release()
        }
        return decodeJsonMessage(message)
    }

    fun decodeBinary(): BenchmarkEvent {
        val wire = Unpooled.wrappedBuffer(binaryPacket)
        val frame = try {
            ByteArrayCodec.INSTANCE.valueDecoder.decode(wire, null) as ByteArray
        } finally {
            wire.release()
        }
        val (_, payload) = BinaryFrame.decode(frame, json)
        return BenchmarkEventCodec.decodeFromByteArray(payload)
    }

    fun roundTripJson(): BenchmarkEvent {
        val wire = StringCodec.INSTANCE.valueEncoder.encode(jsonEnvelope().encodeToString(json))
        val message = try {
            StringCodec.INSTANCE.valueDecoder.decode(wire, null) as String
        } finally {
            wire.release()
        }
        return decodeJsonMessage(message)
    }

    fun roundTripBinary(): BenchmarkEvent {
        val wire = ByteArrayCodec.INSTANCE.valueEncoder.encode(
            BinaryFrame.encode(binaryEnvelope(), BenchmarkEventCodec.encodeToByteArray(event), json)
        )
        val frame = try {
            ByteArrayCodec.INSTANCE.valueDecoder.decode(wire, null) as ByteArray
        } finally {
            wire.release()
        }
        val (_, payload) = BinaryFrame.decode(frame, json)
        return BenchmarkEventCodec.decodeFromByteArray(payload)
    }

    private fun jsonEnvelope() = EventEnvelope(
        topic = "benchmark.event",
        type = BenchmarkEvent::class.java.name,
        originInstanceId = "bench",
        publishedAtEpochMs = 0L,
        payload = json.encodeToString(eventSerializer, event),
    )

    private fun binaryEnvelope() = EventEnvelope(
        topic = "benchmark.event",
        type = BenchmarkEvent::class.java.name,
        originInstanceId = "bench",
        publishedAtEpochMs = 0L,
        payload = null,
    )

    private fun encodeJsonWireBytes(): ByteArray {
        val wire = StringCodec.INSTANCE.valueEncoder.encode(jsonEnvelopeMessage)
        try {
            return ByteBufUtil.getBytes(wire, wire.readerIndex(), wire.readableBytes(), false)
        } finally {
            wire.release()
        }
    }

    private fun decodeJsonMessage(message: String): BenchmarkEvent {
        val envelope = EventEnvelope.decodeFromString(json, message)
        return json.decodeFromString(eventSerializer, envelope.payload!!)
    }

    private fun encodeBinaryWireBytes(): ByteArray {
        val wire = ByteArrayCodec.INSTANCE.valueEncoder.encode(
            BinaryFrame.encode(binaryEnvelope(), BenchmarkEventCodec.encodeToByteArray(event), json)
        )
        try {
            return ByteBufUtil.getBytes(wire, wire.readerIndex(), wire.readableBytes(), false)
        } finally {
            wire.release()
        }
    }
}

/** Exercises the codec with payload sizes that change between operations. */
open class VariableEventPacketBenchmark {
    @Benchmark
    open fun binaryVariableEncode(
        state: VariableEventPacketBenchmarkState,
        blackhole: Blackhole
    ): Int = state.encodeNext(blackhole)
}

@State(Scope.Thread)
open class VariableEventPacketBenchmarkState {
    @Param("alternating", "bursty", "mixed")
    @JvmField
    var payloadPattern: String = "alternating"

    private lateinit var events: Array<BenchmarkEvent>
    private var index = 0

    @Setup(Level.Trial)
    fun setup() {
        val payloadSizes = when (payloadPattern) {
            "alternating" -> IntArray(256) { if (it and 1 == 0) 256 else 65_536 }
            "bursty" -> IntArray(256) { if (it % 32 == 31) 65_536 else 1_024 }
            "mixed" -> {
                val sizes = intArrayOf(0, 32, 256, 1_024, 4_096, 16_384, 65_536)
                IntArray(256) { sizes[(it * 73 + 19) % sizes.size] }
            }

            else -> error("Unknown payload pattern: $payloadPattern")
        }
        events = Array(payloadSizes.size) { eventIndex ->
            BenchmarkEvent(
                aggregateId = eventIndex.toLong(),
                sequence = eventIndex,
                active = true,
                payload = benchmarkPayload(payloadSizes[eventIndex]),
            )
        }
    }

    fun encodeNext(blackhole: Blackhole): Int {
        val event = events[index]
        index = (index + 1) and (events.size - 1)
        val wire = ByteArrayCodec.INSTANCE.valueEncoder.encode(BenchmarkEventCodec.encodeToByteArray(event))
        try {
            blackhole.consume(wire)
            return wire.readableBytes()
        } finally {
            wire.release()
        }
    }
}

@Serializable
@BusEvent("benchmark.event")
data class BenchmarkEvent(
    val aggregateId: Long,
    val sequence: Int,
    val active: Boolean,
    val payload: String,
) : SurfBusEvent()

private object BenchmarkEventCodec : BusEventCodec<BenchmarkEvent> {
    private const val MAX_PAYLOAD_BYTES = 1024 * 1024

    override fun encode(buffer: ByteBuf, value: BenchmarkEvent) {
        buffer.writeLong(value.aggregateId)
        buffer.writeVarInt(value.sequence)
        buffer.writeBoolean(value.active)
        buffer.writeString(value.payload, MAX_PAYLOAD_BYTES)
    }

    override fun decode(buffer: ByteBuf) = BenchmarkEvent(
        aggregateId = buffer.readLong(),
        sequence = buffer.readVarInt(),
        active = buffer.readBoolean(),
        payload = buffer.readString(MAX_PAYLOAD_BYTES),
    )

    fun encodeToByteArray(value: BenchmarkEvent): ByteArray {
        val buffer = Unpooled.buffer()
        try {
            encode(buffer, value)
            val bytes = ByteArray(buffer.readableBytes())
            buffer.readBytes(bytes)
            return bytes
        } finally {
            buffer.release()
        }
    }

    fun decodeFromByteArray(bytes: ByteArray): BenchmarkEvent {
        val buffer = Unpooled.wrappedBuffer(bytes)
        try {
            return decode(buffer)
        } finally {
            buffer.release()
        }
    }
}

private fun benchmarkPayload(size: Int) = buildString(size) {
    repeat(size) { append(('a'.code + it % 26).toChar()) }
}

/** Prints exact wire sizes without mixing size calculation into timed JMH operations. */
object EventWireSizeReport {
    @JvmStatic
    fun main(args: Array<String>) {
        println("payload_bytes\tjson_wire_bytes\tbinary_wire_bytes\tjson_over_binary")
        for (payloadBytes in PAYLOAD_SIZES) {
            val state = EventTransportBenchmarkState().apply {
                this.payloadBytes = payloadBytes
                setup()
            }
            println(
                "$payloadBytes\t${state.jsonWireBytes}\t${state.binaryWireBytes}\t" +
                        "%.3f".format(
                            Locale.ROOT,
                            state.jsonWireBytes.toDouble() / state.binaryWireBytes,
                        ),
            )
        }
    }

    private val PAYLOAD_SIZES = intArrayOf(0, 32, 256, 1024, 4096, 16384, 65536)
}
