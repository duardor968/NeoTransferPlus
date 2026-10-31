package dev.duardo.neotransfer.plus.data.enzona

import java.io.IOException
import java.net.SocketTimeoutException
import javax.net.ssl.SSLHandshakeException
import okhttp3.HttpUrl.Companion.toHttpUrl
import kotlin.test.Test
import kotlin.test.assertEquals

/** The diagnostic line keeps the route and the kind of failure, never identifiers or messages. */
class EnzonaLogTest {
    @Test fun endpointsKeepTheRouteAndMaskEveryIdentifier() {
        fun get(url: String) = EnzonaLog.endpoint("GET", url.toHttpUrl())
        assertEquals("GET api.enzona.net/coremz/1.0.2/card/username/*", get("https://api.enzona.net/coremz/1.0.2/card/username/ana@carbon.super"))
        assertEquals("GET api.enzona.net/coremz/1.0.2/api/balance/lastOperations/*",
            get("https://api.enzona.net/coremz/1.0.2/api/balance/lastOperations/00000000-0000-4000-8000-0000000000c1"))
        assertEquals("GET api.enzona.net/qr/v1.0.0/qr/*", get("https://api.enzona.net/qr/v1.0.0/qr/05PRIVATE?cash_advance=false"))
        assertEquals("GET api.enzona.net/account/v1.0.0/account", get("https://api.enzona.net/account/v1.0.0/account?username=PRIVATE"))
        assertEquals("POST identity.enzona.net/oauth2/token", EnzonaLog.endpoint("POST", "https://identity.enzona.net/oauth2/token".toHttpUrl()))
        assertEquals("GET api.enzona.net/nomenclator/v1.0.0/nomenclator/coordinates", get("https://api.enzona.net/nomenclator/v1.0.0/nomenclator/coordinates"))
        assertEquals("GET api.enzona.net/nomenclator/v1.0.0/nomenclator/banks", get("https://api.enzona.net/nomenclator/v1.0.0/nomenclator/banks"))
        assertEquals("POST api.enzona.net/coremz/1.0.2/card", EnzonaLog.endpoint("POST", "https://api.enzona.net/coremz/1.0.2/card".toHttpUrl()))
        assertEquals("POST api.enzona.net/cards/v1.0.0/cards/default", EnzonaLog.endpoint("POST", "https://api.enzona.net/cards/v1.0.0/cards/default".toHttpUrl()))
        assertEquals("DELETE api.enzona.net/cards/v1.0.0/cards/*/delete",
            EnzonaLog.endpoint("DELETE", "https://api.enzona.net/cards/v1.0.0/cards/00000000-0000-4000-8000-0000000000c1/delete".toHttpUrl()))
    }

    @Test fun errorsAreClassNamesWithoutTheirMessages() {
        assertEquals("SocketTimeoutException", EnzonaLog.error(SocketTimeoutException("PRIVATE https://api.enzona.net")))
        assertEquals("IOException<-SSLHandshakeException", EnzonaLog.error(IOException("PRIVATE", SSLHandshakeException("PRIVATE"))))
        assertEquals(" http=400 oauth=invalid_grant", EnzonaLog.reply(EnzonaApiException(400, EnzonaOAuthFailure.INVALID_GRANT)))
        assertEquals(" http=503", EnzonaLog.reply(EnzonaApiException(503)))
    }

    @Test fun accessTokenStateAndDurationsReadAtAGlance() {
        assertEquals("access_expires_in=45s", EnzonaLog.access(syntheticTokens(expiry = 1045), 1000))
        assertEquals("access_expired_ago=25h12m", EnzonaLog.access(syntheticTokens(expiry = 1000), 1000 + 25 * 3600 + 12 * 60))
        assertEquals("access_rejected", EnzonaLog.access(syntheticTokens(expiry = 0), 1000))
        assertEquals("12m0s", EnzonaLog.duration(720))
    }
}
