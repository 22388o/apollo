package io.muun.apollo.domain.model.lnurl

import io.muun.apollo.domain.errors.*
import io.muun.apollo.domain.errors.lnurl.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
sealed class LnUrlError {
    @Serializable data class InvalidCode(val lnUrl: String) : LnUrlError()
    @Serializable data class InvalidLnUrlTag(val lnUrl: String) : LnUrlError()
    @Serializable data class Unresponsive(val domain: String) : LnUrlError()
    @Serializable data class Unknown(val event: LnUrlEvent) : LnUrlError()
    @Serializable data class ExpiredInvoice(val domain: String, val invoice: String) : LnUrlError()

    fun toMuunError(): MuunError {
        return when (this) {
            is InvalidCode -> InvalidLnUrlError(lnUrl)
            is InvalidLnUrlTag -> InvalidLnUrlTagError(lnUrl)
            is Unresponsive -> LnUrlServiceUnresponsiveError(domain)
            is Unknown -> UnknownLnUrlError(event)
            is ExpiredInvoice -> ExpiredLnUrlInvoiceError(domain, invoice)
        }
     }

    fun serialize() =
        Json.encodeToString(this)

    companion object {
        fun deserialize(serialization: String): LnUrlError =
            Json.decodeFromString(serialization)
    }
}