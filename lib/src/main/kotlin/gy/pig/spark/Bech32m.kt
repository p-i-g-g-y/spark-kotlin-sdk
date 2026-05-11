package gy.pig.spark

object Bech32m {
    private const val CHARSET = "qpzry9x8gf2tvdw0s3jn54khce6mua7l"
    private val CHARSET_MAP: Map<Char, Int> = CHARSET.withIndex().associate { (i, c) -> c to i }
    private const val BECH32M_CONST = 0x2bc830a3u

    private fun polymod(values: List<Int>): UInt {
        val gen = uintArrayOf(0x3b6a57b2u, 0x26508e6du, 0x1ea119fau, 0x3d4233ddu, 0x2a1462b3u)
        var chk = 1u
        for (v in values) {
            val b = chk shr 25
            chk = ((chk and 0x1ffffffu) shl 5) xor v.toUInt()
            for (i in 0 until 5) {
                if (((b shr i) and 1u) != 0u) {
                    chk = chk xor gen[i]
                }
            }
        }
        return chk
    }

    private fun hrpExpand(hrp: String): List<Int> {
        val result = mutableListOf<Int>()
        for (c in hrp) result.add(c.code shr 5)
        result.add(0)
        for (c in hrp) result.add(c.code and 31)
        return result
    }

    private fun createChecksum(hrp: String, data: List<Int>): List<Int> {
        val values = hrpExpand(hrp) + data + listOf(0, 0, 0, 0, 0, 0)
        val polymodValue = polymod(values) xor BECH32M_CONST
        return (0 until 6).map { ((polymodValue shr (5 * (5 - it))) and 31u).toInt() }
    }

    private fun verifyChecksum(hrp: String, data: List<Int>): Boolean = polymod(hrpExpand(hrp) + data) == BECH32M_CONST

    fun encode(hrp: String, data: List<Int>): String {
        val checksum = createChecksum(hrp, data)
        val combined = data + checksum
        val sb = StringBuilder(hrp)
        sb.append('1')
        for (d in combined) sb.append(CHARSET[d])
        return sb.toString()
    }

    fun decodeBech32m(str: String): Pair<String, List<Int>> {
        val lower = str.lowercase()
        val sepIndex = lower.lastIndexOf('1')
        if (sepIndex < 0) throw SparkError.InvalidResponse("No separator in bech32m string")

        val hrp = lower.substring(0, sepIndex)
        val dataStr = lower.substring(sepIndex + 1)
        if (dataStr.length < 6) throw SparkError.InvalidResponse("Bech32m data too short")

        val data = dataStr.map { c ->
            CHARSET_MAP[c] ?: throw SparkError.InvalidResponse("Invalid bech32m character: $c")
        }

        if (!verifyChecksum(hrp, data)) {
            throw SparkError.InvalidResponse("Invalid bech32m checksum")
        }

        return hrp to data.dropLast(6)
    }

    fun convertBits(data: List<Int>, fromBits: Int, toBits: Int, pad: Boolean): List<Int>? {
        var acc = 0
        var bits = 0
        val result = mutableListOf<Int>()
        val maxv = (1 shl toBits) - 1
        for (value in data) {
            acc = (acc shl fromBits) or value
            bits += fromBits
            while (bits >= toBits) {
                bits -= toBits
                result.add((acc shr bits) and maxv)
            }
        }
        if (pad) {
            if (bits > 0) {
                result.add((acc shl (toBits - bits)) and maxv)
            }
        } else if (bits >= fromBits || ((acc shl (toBits - bits)) and maxv) != 0) {
            return null
        }
        return result
    }

    fun toWords(data: ByteArray): List<Int> = convertBits(data.map { it.toInt() and 0xFF }, fromBits = 8, toBits = 5, pad = true) ?: emptyList()

    fun fromWords(words: List<Int>): ByteArray? = convertBits(words, fromBits = 5, toBits = 8, pad = false)?.let { ints ->
        ByteArray(ints.size) { ints[it].toByte() }
    }

    fun decode(address: String): Pair<Int, ByteArray> {
        val (_, data) = decodeBech32m(address)
        if (data.isEmpty()) throw SparkError.InvalidResponse("Empty bech32m data")
        val witnessVersion = data[0]
        val program = fromWords(data.drop(1))
            ?: throw SparkError.InvalidResponse("Invalid witness program")
        return witnessVersion to program
    }
}
