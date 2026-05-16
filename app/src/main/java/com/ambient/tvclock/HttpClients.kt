package com.ambient.tvclock

import okhttp3.ConnectionPool
import okhttp3.Dispatcher
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * Single shared OkHttpClient for the whole app.
 *
 * Reusing one client (and therefore one dispatcher, connection pool and thread pool)
 * keeps connections warm to the Spotify / Google domains we hit repeatedly and avoids
 * the per-call socket churn we used to pay with three independent clients.
 */
object HttpClients {

    private val sharedDispatcher = Dispatcher().apply {
        maxRequests = 16
        maxRequestsPerHost = 6
    }

    private val sharedConnectionPool = ConnectionPool(
        maxIdleConnections = 8,
        keepAliveDuration = 5,
        timeUnit = TimeUnit.MINUTES
    )

    val shared: OkHttpClient = OkHttpClient.Builder()
        .dispatcher(sharedDispatcher)
        .connectionPool(sharedConnectionPool)
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .retryOnConnectionFailure(true)
        .build()
}
