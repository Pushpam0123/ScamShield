package com.scamshield.core.model.net

/**
 * Is [host] a bare IP-address literal (IPv4 dotted-quad, or bracketed/plain IPv6)?
 *
 * design.md's fixtures include `http://192.168.1.1/login` — a host with no registrable domain at
 * all — and both the PSL parser (`:core:analysis`) and the URL analyzer (`:analyzer:url`) need to
 * recognise that shape. This used to be copy-pasted in both (DECISIONS.md D-… flagged it for a
 * shared home once a *third* caller appeared); Phase 4's cleanup gave it one here in `:core:model`,
 * the innermost module both already depend on.
 *
 * Detection is plain regex, never `InetAddress`: the latter can trigger a DNS lookup for
 * non-literal input, and a module handling raw scam-message text must not touch a network API even
 * by accident (`architecture.md` §10.1).
 */
fun isIpLiteralHost(host: String): Boolean {
    val stripped = host.trim().removeSurrounding("[", "]")
    if (IPV4_LITERAL.matches(stripped)) {
        return stripped.split(".").all { (it.toIntOrNull() ?: -1) in 0..255 }
    }
    return stripped.contains(':') && IPV6_LITERAL.matches(stripped)
}

private val IPV4_LITERAL = Regex("^\\d{1,3}(\\.\\d{1,3}){3}$")
private val IPV6_LITERAL = Regex("^[0-9a-fA-F:]+$")
