package io.muun.apollo.domain.action.lnurl

import io.muun.apollo.data.external.Globals
import io.muun.apollo.data.preferences.ForwardingPoliciesRepository
import io.muun.apollo.data.preferences.KeysRepository
import io.muun.apollo.domain.action.base.BaseAsyncAction1
import io.muun.apollo.domain.action.incoming_swap.RegisterInvoicesAction
import io.muun.apollo.domain.libwallet.DecodedInvoice
import io.muun.apollo.domain.libwallet.Invoice
import io.muun.apollo.domain.libwallet.toLibwalletModel
import io.muun.apollo.domain.model.Sha256Hash
import io.muun.apollo.domain.model.lnurl.LnUrlError
import io.muun.apollo.domain.model.lnurl.LnUrlEvent
import io.muun.apollo.domain.model.lnurl.LnUrlState
import io.muun.apollo.domain.selector.WaitForIncomingLnPaymentSelector
import io.muun.common.utils.Encodings
import libwallet.LNURLEvent
import libwallet.LNURLListener
import libwallet.Libwallet
import libwallet.RouteHints
import rx.Observable
import timber.log.Timber
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.validation.constraints.NotNull

class LnUrlWithdrawAction @Inject constructor(
    private val keysRepo: KeysRepository,
    private val waitForIncomingLnPaymentSel: WaitForIncomingLnPaymentSelector,
    private val forwardingPoliciesRepo: ForwardingPoliciesRepository,
    private val registerInvoices: RegisterInvoicesAction,
): BaseAsyncAction1<String, LnUrlState>() {

    lateinit var paymentHash: Sha256Hash

    override fun action(@NotNull lnurlContent: String): Observable<LnUrlState> =
        Observable.defer { lnUrlWithdraw(lnurlContent) }

    private fun lnUrlWithdraw(lnurlContent: String): Observable<LnUrlState> =
        registerInvoices.action()
            .flatMap {
                Observable.merge(processLNURL(lnurlContent), waitForPayment())
            }

    private fun processLNURL(lnurlContent: String): Observable<LnUrlState> {
        return Observable.defer {

            val basePrivateKey = keysRepo.basePrivateKey.toBlocking().first()
            val forwardingPolicy = forwardingPoliciesRepo.fetchOne().random()

            val routeHints = RouteHints()
            routeHints.cltvExpiryDelta = forwardingPolicy.cltvExpiryDelta.toInt()
            routeHints.feeBaseMsat = forwardingPolicy.feeBaseMsat
            routeHints.feeProportionalMillionths = forwardingPolicy.feeProportionalMillionths
            routeHints.pubkey = Encodings.bytesToHex(forwardingPolicy.identityKey)

            val listener = RxListener(lnurlContent)

            Libwallet.lnurlWithdraw(
                Globals.INSTANCE.network.toLibwalletModel(),
                basePrivateKey.toLibwalletModel(Globals.INSTANCE.network),
                routeHints,
                lnurlContent,
                listener
            )

            return@defer listener.asObservable()
                .doOnNext { state ->

                    when (state) {

                        is LnUrlState.InvoiceCreated ->
                            this.paymentHash = decodeInvoice(state.invoice).paymentHash

                        else -> {
                            // ignore
                        }
                    }
                }
                .flatMap { state ->
                    when (state) {
                        // schedule payment taking too long message 15 seconds after "receiving" msg
                        is LnUrlState.Receiving ->
                            return@flatMap Observable.just<LnUrlState>(LnUrlState.TakingTooLong(state.domain))
                                .delay(15, TimeUnit.SECONDS)
                                .startWith(state)

                        // schedule invoice expired error message after invoice expiration
                        is LnUrlState.InvoiceCreated -> {

                            val invoice = decodeInvoice(state.invoice)
                            val safeMargin = 60 // One minute
                            val remainingSeconds = invoice.remainingMillis() / 1000
                            val secondsToExpiration = remainingSeconds + safeMargin

                            val failed = LnUrlState.Failed(
                                LnUrlError.ExpiredInvoice(state.domain, state.invoice)
                            )
                            return@flatMap Observable.just<LnUrlState>(failed)
                                .delay(secondsToExpiration, TimeUnit.SECONDS)
                                .startWith(state)
                        }

                        // pass-through for other states
                        else -> {
                            return@flatMap Observable.just(state)
                        }
                    }
                }
        }
    }

    private fun waitForPayment(): Observable<LnUrlState> {
        return waitForIncomingLnPaymentSel.watch { newOpPaymentHash ->
            ::paymentHash.isInitialized && this.paymentHash == newOpPaymentHash
        }.map { LnUrlState.Success }
    }

    private fun decodeInvoice(invoice: String): DecodedInvoice =
        Invoice.decodeInvoice(Globals.INSTANCE.network, invoice)

    private class RxListener(val lnUrl: String): LNURLListener {

        private val subject = rx.subjects.ReplaySubject.create<LnUrlState>(25)

        override fun onUpdate(event: LNURLEvent?) {
            if (event != null) {

                when (event.code) {

                    Libwallet.LNURLStatusContacting ->
                        subject.onNext(LnUrlState.Contacting(domain = event.metadata.host))

                    Libwallet.LNURLStatusInvoiceCreated ->
                        subject.onNext(LnUrlState.InvoiceCreated(
                            domain = event.metadata.host,
                            invoice = event.metadata.invoice
                        ))

                    Libwallet.LNURLStatusReceiving ->
                        subject.onNext(LnUrlState.Receiving(
                            domain = event.metadata.host,
                            invoice = event.metadata.invoice
                        ))

                    else -> {
                        // ignore
                    }
                }
            }
        }

        override fun onError(event: LNURLEvent?) {
            if (event != null) {

                val error: LnUrlError = when (event.code) {

                    Libwallet.LNURLErrDecode, Libwallet.LNURLErrUnsafeURL ->
                        LnUrlError.InvalidCode(lnUrl)

                    Libwallet.LNURLErrUnreachable ->
                        LnUrlError.Unresponsive(domain = event.metadata.host)

                    Libwallet.LNURLErrWrongTag ->
                        LnUrlError.InvalidLnUrlTag(lnUrl)

                    Libwallet.LNURLErrNoAvailableBalance ->
                        LnUrlError.NoWithdrawBalance(event.message, event.metadata.host)

                    Libwallet.LNURLErrRequestExpired ->
                        LnUrlError.ExpiredLnUrl(event.message, lnUrl)

                    Libwallet.LNURLErrNoRoute ->
                        LnUrlError.NoRoute(event.message, event.metadata.host)

                    Libwallet.LNURLErrForbidden ->
                        LnUrlError.Forbidden(event.message, event.metadata.host)

                    Libwallet.LNURLErrAlreadyUsed ->
                        LnUrlError.AlreadyUsed(event.message, event.metadata.host)

                    else -> {
                        LnUrlError.Unknown(
                            LnUrlEvent(event.code.toInt(), event.message, event.metadata.toString())
                        )
                    }
                }

                // TODO: we may want to log only specific errors or add some to our crashlytics
                //  noise reducing blacklist
                Timber.e(error.toMuunError())
                subject.onNext(LnUrlState.Failed(error = error))
            }
        }

        fun asObservable() : Observable<LnUrlState> {
            return subject
        }
    }
}