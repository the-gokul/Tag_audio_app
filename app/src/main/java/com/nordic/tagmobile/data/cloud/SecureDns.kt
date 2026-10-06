package com.nordic.tagmobile.data.cloud

import com.nordic.tagmobile.log.LogCategory
import com.nordic.tagmobile.log.TagLogger
import okhttp3.Dns
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.dnsoverhttps.DnsOverHttps
import java.net.InetAddress
import java.util.concurrent.TimeUnit

/**
 * System DNS on some office/ISP Wi‑Fi (e.g. ACT Fibernet / Aruba) returns a hijacked
 * address for *.supabase.co → TLS "Connection closed by peer". Resolve via DNS-over-HTTPS
 * to Cloudflare/Google using their raw IPs so lookup bypasses poisoned resolvers.
 */
object SecureDns {
    private val lock = Any()
    @Volatile private var cached: Dns? = null

    fun get(): Dns {
        cached?.let { return it }
        synchronized(lock) {
            cached?.let { return it }
            val dns = build()
            cached = dns
            return dns
        }
    }

    /** Resolve + log for diagnostics (TagSync / Log Viewer). */
    fun lookupLogged(hostname: String): List<InetAddress> {
        return try {
            val addrs = get().lookup(hostname)
            TagLogger.log(
                LogCategory.APP,
                "DNS_LOOKUP_OK",
                "host=$hostname ips=${addrs.joinToString { it.hostAddress ?: "?" }} via=DoH",
            )
            addrs
        } catch (e: Exception) {
            TagLogger.log(
                LogCategory.ERRORS,
                "DNS_LOOKUP_FAIL",
                "host=$hostname err=${e.message}",
            )
            // Last resort: system DNS (may be poisoned).
            val fallback = Dns.SYSTEM.lookup(hostname)
            TagLogger.log(
                LogCategory.APP,
                "DNS_LOOKUP_SYSTEM",
                "host=$hostname ips=${fallback.joinToString { it.hostAddress ?: "?" }}",
            )
            fallback
        }
    }

    fun systemLookupLogged(hostname: String): String {
        return try {
            Dns.SYSTEM.lookup(hostname).joinToString { it.hostAddress ?: "?" }
        } catch (e: Exception) {
            "err=${e.message}"
        }
    }

    private fun build(): Dns {
        val bootstrap = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .build()
        // Hit resolvers by IP so we never ask the poisoned LAN DNS for their hostname.
        val cloudflare = DnsOverHttps.Builder()
            .client(bootstrap)
            .url("https://1.1.1.1/dns-query".toHttpUrl())
            .bootstrapDnsHosts(InetAddress.getByName("1.1.1.1"), InetAddress.getByName("1.0.0.1"))
            .includeIPv6(false)
            .build()
        val google = DnsOverHttps.Builder()
            .client(bootstrap)
            .url("https://8.8.8.8/dns-query".toHttpUrl())
            .bootstrapDnsHosts(InetAddress.getByName("8.8.8.8"), InetAddress.getByName("8.8.4.4"))
            .includeIPv6(false)
            .build()
        return object : Dns {
            override fun lookup(hostname: String): List<InetAddress> {
                return try {
                    cloudflare.lookup(hostname)
                } catch (e: Exception) {
                    TagLogger.log(
                        LogCategory.APP,
                        "DNS_DOH_CF_FAIL",
                        "host=$hostname err=${e.message}; trying Google DoH",
                    )
                    google.lookup(hostname)
                }
            }
        }
    }
}
