package com.scamshield.analyzer.url

import com.scamshield.core.model.DomainReputationIndex
import java.math.BigInteger
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest

/**
 * Reads `reputation.bin`, `rulepack/build_rulepack.py`'s Bloom filter of established domains
 * plus an exact-match allowlist, design.md section 3.1.
 *
 * The wire format (see the Python builder's own doc comment for the authoritative spec):
 * ```
 * 7 bytes   magic "SSREPv1"
 * u32 LE    m               bloom bit-array size, in bits
 * u32 LE    k                number of hash rounds
 * u32 LE    bloomByteLen     = ceil(m / 8)
 * bytes[]   bloom bit array
 * u32 LE    allowlistCount
 * repeated: u16 LE domainByteLen, then that many UTF-8 bytes
 * ```
 *
 * Membership uses SHA-256-derived double hashing (Kirsch-Mitzenmacher), not a bloom-filter
 * library, so this side and the Python builder can share the exact same construction with no
 * shared code: both just call SHA-256 and do the same arithmetic on the digest bytes.
 *
 * The index arithmetic (`h1 + i * h2`) is done in [BigInteger], not `Long`, and this is not
 * an abundance of caution -- it is load-bearing. `h1`/`h2` are signed 64-bit hash halves near
 * the edges of that range, `i * h2` overflows `Long` for essentially every real domain once
 * `i >= 1`, and Python's `%` operates on its own arbitrary-precision integers with no such
 * overflow. A `Long`-only port here would silently disagree with the Python-built filter for
 * every domain requiring more than one hash round -- verified against real SHA-256 output
 * before writing this comment, not assumed.
 *
 * Public, not `internal`: `:core:data` owns the fallback decision described on [parse] below
 * (constructing the pack as a whole, deciding what happens when this returns null), and needs
 * to call this constructor across the module boundary to do it -- see that module's
 * `RulePackLoader`.
 */
class BloomDomainReputationIndex private constructor(
    private val bitArray: ByteArray,
    private val m: Int,
    private val k: Int,
    private val allowlist: Set<String>,
) : DomainReputationIndex {

    private val bigM = BigInteger.valueOf(m.toLong())

    override fun isEstablished(registrableDomain: String): Boolean {
        if (m <= 0 || k <= 0) return false
        val (h1, h2) = domainHashes(registrableDomain)
        for (i in 0 until k) {
            val bit = h1.add(BigInteger.valueOf(i.toLong()).multiply(h2)).mod(bigM).toInt()
            if (!getBit(bit)) return false
        }
        return true
    }

    override fun isAllowlisted(registrableDomain: String): Boolean =
        registrableDomain.lowercase() in allowlist

    private fun getBit(index: Int): Boolean {
        val byte = bitArray[index / 8].toInt()
        return (byte shr (index % 8)) and 1 == 1
    }

    /** Matches the Python builder's `struct.unpack(">qq", digest[:16])` exactly. */
    private fun domainHashes(domain: String): Pair<BigInteger, BigInteger> {
        val digest = SHA_256.digest(domain.lowercase().toByteArray(Charsets.UTF_8))
        val buffer = ByteBuffer.wrap(digest, 0, 16).order(ByteOrder.BIG_ENDIAN)
        val h1 = BigInteger.valueOf(buffer.long)
        val h2 = BigInteger.valueOf(buffer.long)
        return h1 to h2
    }

    companion object {
        private val MAGIC = "SSREPv1".toByteArray(Charsets.US_ASCII)
        private val SHA_256 get() = MessageDigest.getInstance("SHA-256")

        /**
         * Returns null for anything that does not parse as a well-formed pack -- callers must
         * treat that the same as a missing asset (architecture.md section 11: an invalid pack
         * falls back to the bundled default, never runs partially loaded). This class has no
         * "bundled default" of its own to fall back to; that decision belongs to whatever
         * constructs the pack as a whole in `:core:data`.
         */
        fun parse(bytes: ByteArray): BloomDomainReputationIndex? {
            return try {
                val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
                val magic = ByteArray(MAGIC.size).also { buffer.get(it) }
                if (!magic.contentEquals(MAGIC)) return null

                val m = buffer.int
                val k = buffer.int
                val bloomByteLen = buffer.int
                if (m <= 0 || k <= 0 || bloomByteLen < 0) return null

                val bitArray = ByteArray(bloomByteLen).also { buffer.get(it) }

                val allowlistCount = buffer.int
                if (allowlistCount < 0) return null
                val allowlist = buildSet {
                    repeat(allowlistCount) {
                        val len = buffer.short.toInt() and 0xFFFF
                        val domainBytes = ByteArray(len).also { buffer.get(it) }
                        add(String(domainBytes, Charsets.UTF_8).lowercase())
                    }
                }

                BloomDomainReputationIndex(bitArray, m, k, allowlist)
            } catch (e: Exception) {
                null
            }
        }
    }
}
