package dev.slne.surf.eventbus.rabbitmq.packet

data class PacketChunk(
    val kind: RabbitPacketChunking.PacketChunkKind,
    val seriesId: Long,
    val totalChunks: Int,
    val chunkIndex: Int,
    val originalSize: Int,
    val payload: ByteArray
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is PacketChunk) return false

        if (seriesId != other.seriesId) return false
        if (totalChunks != other.totalChunks) return false
        if (chunkIndex != other.chunkIndex) return false
        if (originalSize != other.originalSize) return false
        if (kind != other.kind) return false
        if (!payload.contentEquals(other.payload)) return false

        return true
    }

    override fun hashCode(): Int {
        var result = seriesId.hashCode()
        result = 31 * result + totalChunks
        result = 31 * result + chunkIndex
        result = 31 * result + originalSize
        result = 31 * result + kind.hashCode()
        result = 31 * result + payload.contentHashCode()
        return result
    }
}
