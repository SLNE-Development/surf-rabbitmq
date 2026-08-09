@file:Suppress("unused")

package dev.slne.surf.eventbus.redis.codec

import dev.slne.surf.eventbus.InternalEventBusApi
import io.netty.buffer.ByteBuf
import it.unimi.dsi.fastutil.ints.IntArrayList
import it.unimi.dsi.fastutil.ints.IntList
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap
import it.unimi.dsi.fastutil.objects.ObjectArrayList
import net.kyori.adventure.key.Key
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer
import java.time.Instant
import java.util.*
import kotlin.enums.enumEntries

private val MAX_CONTAINER_ELEMENTS = java.lang.Long.getLong("surf.redis.max-container-elements", 1_000_000)

private fun checkContainerSize(type: String, size: Int) {
    checkDecoding(size >= 0) { "$type size must not be negative: $size" }
    checkDecoding(size <= MAX_CONTAINER_ELEMENTS) {
        "$type size exceeds safety limit: $size > $MAX_CONTAINER_ELEMENTS"
    }
}

@InternalEventBusApi
fun ByteBuf.writeVarInt(value: Int) {
    RedisVarInt.write(this, value)
}

@InternalEventBusApi
fun ByteBuf.readVarInt(): Int {
    return RedisVarInt.read(this)
}

@InternalEventBusApi
fun ByteBuf.writeVarLong(value: Long) {
    RedisVarLong.write(this, value)
}

@InternalEventBusApi
fun ByteBuf.readVarLong(): Long {
    return RedisVarLong.read(this)
}

@InternalEventBusApi
fun ByteBuf.writeString(value: CharSequence, maxLength: Int = RedisUtf8String.MAX_STRING_LENGTH) {
    RedisUtf8String.write(this, value, maxLength)
}

@InternalEventBusApi
fun ByteBuf.readString(maxLength: Int = RedisUtf8String.MAX_STRING_LENGTH): String {
    return RedisUtf8String.read(this, maxLength)
}

@InternalEventBusApi
fun <T> ByteBuf.writeNullable(value: T?, writer: (ByteBuf, T) -> Unit) {
    if (value == null) {
        writeBoolean(false)
    } else {
        writeBoolean(true)
        writer(this, value)
    }
}

@InternalEventBusApi
fun <T> ByteBuf.readNullable(reader: (ByteBuf) -> T): T? {
    if (!readBoolean()) return null
    return reader(this)
}

@InternalEventBusApi
fun <T> ByteBuf.writeCollection(collection: Collection<T>, writer: (ByteBuf, T) -> Unit) {
    writeVarInt(collection.size)
    collection.forEach { writer(this, it) }
}

@InternalEventBusApi
fun <T, C : MutableCollection<T>> ByteBuf.readCollection(creator: (Int) -> C, reader: (ByteBuf) -> T): C {
    val size = readVarInt()
    checkContainerSize("Collection", size)

    val collection = creator(size)
    repeat(size) {
        collection.add(reader(this))
    }
    return collection
}

@InternalEventBusApi
fun <T> ByteBuf.readList(reader: (ByteBuf) -> T): ObjectArrayList<T> = readCollection(::ObjectArrayList, reader)

@InternalEventBusApi
fun <T> ByteBuf.writeArray(array: Array<T>, writer: (ByteBuf, T) -> Unit) {
    writeVarInt(array.size)
    array.forEach { writer(this, it) }
}

@InternalEventBusApi
fun <T> ByteBuf.readArray(type: Class<T>, reader: (ByteBuf) -> T): Array<T> {
    val length = readVarInt()
    checkContainerSize("Array", length)

    @Suppress("UNCHECKED_CAST")
    val array = java.lang.reflect.Array.newInstance(type, length) as Array<T>

    for (i in 0 until length) {
        array[i] = reader(this)
    }

    return array
}

@InternalEventBusApi
inline fun <reified T> ByteBuf.readArray(noinline reader: (ByteBuf) -> T): Array<T> {
    return readArray(T::class.java, reader)
}

private fun ByteBuf.readPrimitiveArrayLength(type: String): Int {
    val length = readVarInt()
    checkContainerSize(type, length)
    return length
}

private fun ByteBuf.checkReadableArrayBytes(
    type: String,
    length: Int,
    bytesPerElement: Int
) {
    val neededBytes = length.toLong() * bytesPerElement

    checkDecoding(neededBytes <= Int.MAX_VALUE) {
        "$type byte size too big: $neededBytes"
    }

    checkDecoding(readableBytes() >= neededBytes) {
        "Not enough readable bytes for $type: need $neededBytes, have ${readableBytes()}"
    }
}

@InternalEventBusApi
fun ByteBuf.writeByteArray(array: ByteArray) {
    writeVarInt(array.size)
    writeBytes(array)
}

@InternalEventBusApi
fun ByteBuf.readByteArray(): ByteArray {
    val length = readPrimitiveArrayLength("ByteArray")
    checkReadableArrayBytes("ByteArray", length, Byte.SIZE_BYTES)

    val array = ByteArray(length)
    readBytes(array)
    return array
}

@InternalEventBusApi
fun ByteBuf.writeBooleanArray(array: BooleanArray) {
    writeVarInt(array.size)

    for (value in array) {
        writeBoolean(value)
    }
}

@InternalEventBusApi
fun ByteBuf.readBooleanArray(): BooleanArray {
    val length = readPrimitiveArrayLength("BooleanArray")
    checkReadableArrayBytes("BooleanArray", length, Byte.SIZE_BYTES)

    return BooleanArray(length) {
        readBoolean()
    }
}

@InternalEventBusApi
fun ByteBuf.writeShortArray(array: ShortArray) {
    writeVarInt(array.size)

    for (value in array) {
        writeShort(value.toInt())
    }
}

@InternalEventBusApi
fun ByteBuf.readShortArray(): ShortArray {
    val length = readPrimitiveArrayLength("ShortArray")
    checkReadableArrayBytes("ShortArray", length, Short.SIZE_BYTES)

    return ShortArray(length) {
        readShort()
    }
}

@InternalEventBusApi
fun ByteBuf.writeCharArray(array: CharArray) {
    writeVarInt(array.size)

    for (value in array) {
        writeChar(value.code)
    }
}

@InternalEventBusApi
fun ByteBuf.readCharArray(): CharArray {
    val length = readPrimitiveArrayLength("CharArray")
    checkReadableArrayBytes("CharArray", length, Char.SIZE_BYTES)

    return CharArray(length) {
        readChar()
    }
}

@InternalEventBusApi
fun ByteBuf.writeIntArray(array: IntArray) {
    writeVarInt(array.size)

    for (value in array) {
        writeInt(value)
    }
}

@InternalEventBusApi
fun ByteBuf.readIntArray(): IntArray {
    val length = readPrimitiveArrayLength("IntArray")
    checkReadableArrayBytes("IntArray", length, Int.SIZE_BYTES)

    return IntArray(length) {
        readInt()
    }
}

@InternalEventBusApi
fun ByteBuf.writeLongArray(array: LongArray) {
    writeVarInt(array.size)

    for (value in array) {
        writeLong(value)
    }
}

@InternalEventBusApi
fun ByteBuf.readLongArray(): LongArray {
    val length = readPrimitiveArrayLength("LongArray")
    checkReadableArrayBytes("LongArray", length, Long.SIZE_BYTES)

    return LongArray(length) {
        readLong()
    }
}

@InternalEventBusApi
fun ByteBuf.writeFloatArray(array: FloatArray) {
    writeVarInt(array.size)

    for (value in array) {
        writeFloat(value)
    }
}

@InternalEventBusApi
fun ByteBuf.readFloatArray(): FloatArray {
    val length = readPrimitiveArrayLength("FloatArray")
    checkReadableArrayBytes("FloatArray", length, Float.SIZE_BYTES)

    return FloatArray(length) {
        readFloat()
    }
}

@InternalEventBusApi
fun ByteBuf.writeDoubleArray(array: DoubleArray) {
    writeVarInt(array.size)

    for (value in array) {
        writeDouble(value)
    }
}

@InternalEventBusApi
fun ByteBuf.readDoubleArray(): DoubleArray {
    val length = readPrimitiveArrayLength("DoubleArray")
    checkReadableArrayBytes("DoubleArray", length, Double.SIZE_BYTES)

    return DoubleArray(length) {
        readDouble()
    }
}

@InternalEventBusApi
fun ByteBuf.writeVarIntArray(array: IntArray) {
    writeVarInt(array.size)

    for (value in array) {
        writeVarInt(value)
    }
}

@InternalEventBusApi
fun ByteBuf.readVarIntArray(): IntArray {
    val length = readPrimitiveArrayLength("VarIntArray")

    return IntArray(length) {
        readVarInt()
    }
}

@InternalEventBusApi
fun ByteBuf.writeInstant(instant: Instant) {
    writeLong(instant.toEpochMilli())
}

@InternalEventBusApi
fun ByteBuf.readInstant(): Instant {
    return Instant.ofEpochMilli(readLong())
}

@InternalEventBusApi
fun <K, V> ByteBuf.writeMap(
    map: Map<K, V>,
    keyWriter: (ByteBuf, K) -> Unit,
    valueWriter: (ByteBuf, V) -> Unit
) {
    writeVarInt(map.size)

    for ((key, value) in map) {
        keyWriter(this, key)
        valueWriter(this, value)
    }
}

@InternalEventBusApi
fun <K, V, M : MutableMap<K, V>> ByteBuf.readMap(
    creator: (Int) -> M,
    keyReader: (ByteBuf) -> K,
    valueReader: (ByteBuf) -> V
): M {
    val size = readVarInt()
    checkContainerSize("Map", size)

    val map = creator(size)

    repeat(size) {
        val key = keyReader(this)
        val value = valueReader(this)
        map[key] = value
    }

    return map
}

@InternalEventBusApi
fun <K, V> ByteBuf.readMap(
    keyReader: (ByteBuf) -> K,
    valueReader: (ByteBuf) -> V
): Object2ObjectOpenHashMap<K, V> {
    return readMap(::Object2ObjectOpenHashMap, keyReader, valueReader)
}

@InternalEventBusApi
fun ByteBuf.writeUuid(uuid: UUID) {
    writeLong(uuid.mostSignificantBits)
    writeLong(uuid.leastSignificantBits)
}

@InternalEventBusApi
fun ByteBuf.readUuid(): UUID {
    return UUID(readLong(), readLong())
}

@InternalEventBusApi
fun ByteBuf.writeEnum(value: Enum<*>) {
    writeVarInt(value.ordinal)
}

@InternalEventBusApi
inline fun <reified E : Enum<E>> ByteBuf.readEnum(): E {
    val ordinal = readVarInt()
    val values = enumEntries<E>()

    checkDecoding(ordinal in values.indices) {
        "Invalid enum ordinal for ${E::class.simpleName}: $ordinal"
    }

    return values[ordinal]
}

@InternalEventBusApi
inline fun <reified E : Enum<E>> ByteBuf.writeEnumSet(set: Set<E>) {
    val values = enumEntries<E>()
    val bitSet = BitSet(values.size)

    for (i in values.indices) {
        bitSet.set(i, values[i] in set)
    }

    writeFixedBitSet(bitSet, values.size)
}

@InternalEventBusApi
inline fun <reified E : Enum<E>> ByteBuf.readEnumSet(): EnumSet<E> {
    val values = enumEntries<E>()
    val bitSet = readFixedBitSet(values.size)
    val result = EnumSet.noneOf(E::class.java)

    for (i in values.indices) {
        if (bitSet.get(i)) {
            result.add(values[i])
        }
    }

    return result
}

@InternalEventBusApi
fun ByteBuf.writeBitSet(bitSet: BitSet) {
    writeLongArray(bitSet.toLongArray())
}

@InternalEventBusApi
fun ByteBuf.readBitSet(): BitSet {
    return BitSet.valueOf(readLongArray())
}

@InternalEventBusApi
fun ByteBuf.writeFixedBitSet(bitSet: BitSet, size: Int) {
    check(bitSet.length() <= size) {
        "BitSet is larger than expected size (${bitSet.length()} > $size)"
    }

    val byteSize = (size + Byte.SIZE_BITS - 1) / Byte.SIZE_BITS
    val bytes = bitSet.toByteArray().copyOf(byteSize)
    writeBytes(bytes)
}

@InternalEventBusApi
fun ByteBuf.readFixedBitSet(size: Int): BitSet {
    checkDecoding(size >= 0) { "BitSet size must not be negative: $size" }
    checkDecoding(size <= MAX_CONTAINER_ELEMENTS * Byte.SIZE_BITS) {
        "BitSet size exceeds safety limit: $size"
    }
    val byteSize = (size + Byte.SIZE_BITS - 1) / Byte.SIZE_BITS
    val bytes = ByteArray(byteSize)
    readBytes(bytes)
    return BitSet.valueOf(bytes)
}

@InternalEventBusApi
fun ByteBuf.readWithCount(reader: (ByteBuf) -> Unit) {
    val count = readVarInt()
    checkContainerSize("Count", count)

    repeat(count) {
        reader(this)
    }
}

@InternalEventBusApi
fun <T> ByteBuf.writeById(value: T, idGetter: (T) -> Int) {
    writeVarInt(idGetter(value))
}

@InternalEventBusApi
fun <T> ByteBuf.readById(resolver: (Int) -> T): T {
    return resolver(readVarInt())
}

@InternalEventBusApi
fun ByteBuf.writeVarIntList(list: IntList) {
    writeVarInt(list.size)

    list.iterator().forEachRemaining { value ->
        writeVarInt(value)
    }
}

@InternalEventBusApi
fun ByteBuf.readVarIntList(): IntArrayList {
    val size = readVarInt()
    checkContainerSize("IntList", size)

    val list = IntArrayList(size)

    repeat(size) {
        list.add(readVarInt())
    }

    return list
}

@InternalEventBusApi
fun ByteBuf.writeKey(key: Key) {
    writeString(key.asMinimalString())
}

@InternalEventBusApi
fun ByteBuf.readKey(): Key {
    return Key.key(readString())
}

@InternalEventBusApi
fun ByteBuf.writeComponent(component: Component) {
    val string = GsonComponentSerializer.gson().serialize(component)
    writeString(string, Int.MAX_VALUE)
}

@InternalEventBusApi
fun ByteBuf.readComponent(): Component {
    return GsonComponentSerializer.gson().deserialize(readString(Int.MAX_VALUE))
}
