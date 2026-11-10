package com.antidip

/**
 * Strict validation for the configurable question-bank download URL
 * (batch 2-b, T11 / M4 + batch 3, A30). Pure JVM: no Android dependency, so QA
 * can unit-test it directly.
 *
 * Two levels:
 *  - [isValid]: structural rules only (well-formed http/https URL, non-empty
 *    host, no credentials, valid port, no whitespace).
 *  - [isAcceptable]: structural rules PLUS the transport policy — plaintext
 *    HTTP is accepted only for hosts that are unambiguously local/private;
 *    anything reachable from the public internet must use HTTPS, otherwise a
 *    man-in-the-middle could tamper with the question bank on the wire.
 *    [Constants.DEFAULT_QUIZ_URL] (private LAN, HTTP) stays acceptable.
 */
object UrlValidator {

    /**
     * Returns true only when [raw] is a well-formed http/https URL with a
     * non-empty host, no embedded credentials, a valid port and no whitespace.
     */
    fun isValid(raw: String): Boolean {
        if (raw.isBlank()) return false
        if (raw.any { it.isWhitespace() }) return false

        val uri = runCatching { java.net.URI(raw) }.getOrNull() ?: return false

        val scheme = uri.scheme
        if (scheme == null ||
            !(scheme.equals("http", ignoreCase = true) ||
                scheme.equals("https", ignoreCase = true))
        ) {
            return false
        }
        if (uri.host.isNullOrEmpty()) return false
        if (uri.userInfo != null) return false

        // URI.getPort() returns -1 when the port is unspecified. A port out of
        // range makes URI parsing fail (getOrNull() above) or yields a bogus
        // value, so guard the numeric range explicitly as well.
        val port = uri.port
        if (port != -1 && (port < 1 || port > 65535)) return false

        return true
    }

    /**
     * Batch 3 (A30): structural validity PLUS the transport policy.
     *
     * - `https://` is always acceptable (TLS protects the download).
     * - `http://` is acceptable ONLY when the host cannot be routed over the
     *   public internet: loopback, RFC1918 private IPv4, IPv4 link-local
     *   169.254/16, a single-label hostname (LAN NetBIOS / hosts-file name) or
     *   an mDNS `.local` / `.lan` / `.internal` / `.home` name.
     * - A public http URL is rejected: on such a network the question bank
     *   could be replaced in flight (see [isPrivateOrLocalHost]).
     *
     * The default LAN address [Constants.DEFAULT_QUIZ_URL] must keep passing.
     */
    fun isAcceptable(raw: String): Boolean {
        if (!isValid(raw)) return false
        val uri = runCatching { java.net.URI(raw) }.getOrNull() ?: return false
        val scheme = uri.scheme?.lowercase() ?: return false
        if (scheme == "https") return true
        // isValid() already guaranteed a non-null, non-empty host.
        val host = uri.host?.lowercase() ?: return false
        return isPrivateOrLocalHost(host)
    }

    /**
     * True only for hosts that are unambiguously local/private:
     *  - "localhost", "*.localhost", the IPv6 loopback "[::1]"
     *  - IPv4 literals in 127/8, 10/8, 172.16/12, 192.168/16, 169.254/16
     *  - single-label hostnames (resolved by the LAN, never globally unique)
     *  - ".local"/".lan"/".internal"/".home" mDNS/LAN-style names
     *
     * Everything else — public domains AND public/other IPv6 literals —
     * returns false so plaintext HTTP cannot be used for them.
     */
    fun isPrivateOrLocalHost(host: String): Boolean {
        val h = host.trim().lowercase()
        if (h.isEmpty()) return false
        if (h == "localhost" || h.endsWith(".localhost")) return true
        if (h == "[::1]") return true
        // Bracketed IPv6 literal: only the loopback is local enough. Any other
        // IPv6 address is globally routable and must use HTTPS.
        if (h.startsWith("[")) return false

        val labels = h.split('.')
        // IPv4 dotted-quad literal (reject octal/hex tricks by digits-only).
        if (labels.size == 4 &&
            labels.all { it.isNotEmpty() && it.length <= 3 && it.all { c -> c.isDigit() } }
        ) {
            val octets = labels.map { it.toInt() }
            if (octets.any { it > 255 }) return false
            val a = octets[0]
            val b = octets[1]
            return a == 127 || a == 10 || (a == 192 && b == 168) ||
                (a == 172 && b in 16..31) || (a == 169 && b == 254)
        }
        // Single-label hostname ("mypc"): can only be resolved by the LAN.
        if (labels.size == 1) return true
        // mDNS / LAN-style suffixes.
        return h.endsWith(".local") || h.endsWith(".lan") ||
            h.endsWith(".internal") || h.endsWith(".home")
    }
}
