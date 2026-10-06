package com.chardidathing.litehub.core.model

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

// packed argb int, written in json as "#rrggbb" or "#aarrggbb"
typealias Argb = @Serializable(with = ArgbSerializer::class) Int

object ArgbSerializer : KSerializer<Int> {
    override val descriptor = PrimitiveSerialDescriptor("Argb", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: Int) {
        val hex = Integer.toHexString(value).padStart(8, '0')
        encoder.encodeString(if (hex.startsWith("ff")) "#${hex.drop(2)}" else "#$hex")
    }

    override fun deserialize(decoder: Decoder): Int {
        val raw = decoder.decodeString()
        val hex = raw.removePrefix("#")
        val digits = when (hex.length) {
            6 -> "ff$hex"
            8 -> hex
            else -> throw SerializationException("bad colour \"$raw\", expected #rrggbb or #aarrggbb")
        }
        return digits.toLongOrNull(16)?.toInt()
            ?: throw SerializationException("bad colour \"$raw\", expected #rrggbb or #aarrggbb")
    }
}
