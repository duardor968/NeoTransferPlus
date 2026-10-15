package dev.duardo.neotransfer.plus.data

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dev.duardo.neotransfer.plus.R
import dev.duardo.neotransfer.plus.core.crypto.EnzonaCipher
import dev.duardo.neotransfer.plus.core.payment.EnzonaPaymentRequests
import dev.duardo.neotransfer.plus.data.enzona.EnzonaDeviceInfo
import dev.duardo.neotransfer.plus.data.enzona.EnzonaHttp
import dev.duardo.neotransfer.plus.data.enzona.EnzonaLoginWeb
import dev.duardo.neotransfer.plus.data.enzona.EnzonaPaymentApi
import dev.duardo.neotransfer.plus.data.enzona.EnzonaPaymentCoordinator
import dev.duardo.neotransfer.plus.data.enzona.EnzonaQrApi
import dev.duardo.neotransfer.plus.data.enzona.EnzonaReadApi
import dev.duardo.neotransfer.plus.data.enzona.EnzonaServiceApi
import dev.duardo.neotransfer.plus.data.enzona.EnzonaSession
import dev.duardo.neotransfer.plus.data.enzona.EnzonaSessionApi
import dev.duardo.neotransfer.plus.data.enzona.EnzonaSessionStore
import dev.duardo.neotransfer.plus.data.enzona.SessionNetwork
import dev.duardo.neotransfer.plus.data.enzona.db.ACCOUNT_RESOURCE
import dev.duardo.neotransfer.plus.data.enzona.db.EnzonaDatabase
import dev.duardo.neotransfer.plus.data.enzona.db.EnzonaReadKind
import dev.duardo.neotransfer.plus.data.enzona.db.RoomEnzonaStore
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/** Process-wide wiring: one HTTP client, one session, one database. */
class EnzonaDataGraph private constructor(context: Context) {
    private val app = context.applicationContext
    private val http = EnzonaHttp()
    private val store = RoomEnzonaStore(EnzonaDatabase.open(app))
    private val sessionApi = EnzonaSessionApi(http::execute)
    private val session = EnzonaSession(EnzonaSessionStore(app), SessionNetwork(sessionApi), EnzonaDeviceInfo(app)::fields,
        onAccount = { owner, account -> store.cache(owner, EnzonaReadKind.ACCOUNT, ACCOUNT_RESOURCE, account.json, account.observedAt) })
    // The public RSA key is read on the first payment, on the coordinator's IO dispatcher.
    private val encoder by lazy {
        val pem = app.resources.openRawResource(R.raw.enzona_public_key).bufferedReader().use { it.readText() }
        EnzonaPaymentRequests(EnzonaCipher(EnzonaCipher.publicKeyFromPem(pem)))
    }
    private val payments = EnzonaPaymentCoordinator(session::usableTokens, store, EnzonaPaymentApi(http::execute), { encoder })

    val repository = EnzonaRepository(session, sessionApi, EnzonaReadApi(http::execute), EnzonaQrApi(http::execute),
        EnzonaServiceApi(http::execute), payments, store, WorkManagerCreditScheduler(app),
        CoroutineScope(SupervisorJob() + Dispatchers.Default), EnzonaLoginWeb::clearWebSession)

    companion object {
        @Volatile private var instance: EnzonaDataGraph? = null

        fun get(context: Context): EnzonaDataGraph = instance ?: synchronized(this) {
            instance ?: EnzonaDataGraph(context).also { instance = it }
        }
    }
}

/** Reads the last 7 days of history and queues new incoming credits (see EnzonaRepository.creditNotices). */
class EnzonaCreditWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    // Failures wait for the next period: no backoff bursts against ENZONA.
    override suspend fun doWork(): Result {
        EnzonaDataGraph.get(applicationContext).repository.syncCredits()
        return Result.success()
    }
}

internal class WorkManagerCreditScheduler(context: Context) : EnzonaCreditScheduler {
    private val app = context.applicationContext

    override fun schedule() {
        val request = PeriodicWorkRequestBuilder<EnzonaCreditWorker>(15, TimeUnit.MINUTES)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        WorkManager.getInstance(app).enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
    }

    override fun cancel() {
        WorkManager.getInstance(app).cancelUniqueWork(WORK_NAME)
    }

    private companion object { const val WORK_NAME = "enzona-credit-check" }
}
