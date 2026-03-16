package com.abdownloadmanager.shared.util

import kotlinx.coroutines.flow.StateFlow
import org.conscrypt.Conscrypt
import java.security.Security
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.X509TrustManager

/**
 * Uses Conscrypt (Google's BoringSSL) as TLS provider.
 * This produces browser-like TLS fingerprints that pass Cloudflare checks.
 */
class AppSSLFactoryProvider(
    private val ignoreSSLCertificates: StateFlow<Boolean>,
) {
    init {
        // Install Conscrypt as the highest-priority security provider
        if (Security.getProvider("Conscrypt") == null) {
            Security.insertProviderAt(Conscrypt.newProvider(), 1)
        }
    }

    val trustManager: X509TrustManager by lazy {
        ToggleableTrustManager(
            trustManager = createDefaultTrustManager(),
            shouldCheck = { !ignoreSSLCertificates.value }
        )
    }

    fun createSSLSocketFactory(): SSLSocketFactory {
        val sslContext = SSLContext.getInstance("TLS", "Conscrypt")
        sslContext.init(null, arrayOf(trustManager), null)
        return sslContext.socketFactory
    }

    private fun createDefaultTrustManager(): X509TrustManager {
        val factory = javax.net.ssl.TrustManagerFactory.getInstance(
            javax.net.ssl.TrustManagerFactory.getDefaultAlgorithm()
        )
        factory.init(null as java.security.KeyStore?)
        return factory.trustManagers.first { it is X509TrustManager } as X509TrustManager
    }
}


private class ToggleableTrustManager(
    private val trustManager: X509TrustManager,
    private val shouldCheck: () -> Boolean,
) : X509TrustManager {
    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {
        if (shouldCheck()) {
            trustManager.checkClientTrusted(chain, authType)
        }
    }

    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
        if (shouldCheck()) {
            trustManager.checkServerTrusted(chain, authType)
        }
    }

    override fun getAcceptedIssuers(): Array<X509Certificate> {
        return trustManager.acceptedIssuers
    }
}
