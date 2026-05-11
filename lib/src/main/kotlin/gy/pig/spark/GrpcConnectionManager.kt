package gy.pig.spark

import io.grpc.ManagedChannel
import io.grpc.okhttp.OkHttpChannelBuilder
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.net.URI
import java.util.concurrent.TimeUnit

class GrpcConnectionManager(private val addresses: List<String>,) {
    private val channels = mutableMapOf<String, ManagedChannel>()
    private val mutex = Mutex()

    val allAddresses: List<String> get() = addresses

    suspend fun getChannel(address: String): ManagedChannel = mutex.withLock {
        channels.getOrPut(address) { createChannel(address) }
    }

    private fun createChannel(address: String): ManagedChannel {
        val uri = URI(address)
        val host = uri.host ?: throw SparkError.GrpcError("Invalid SO address: $address")
        val useTLS = uri.scheme == "https"
        val port = if (uri.port > 0) {
            uri.port
        } else if (useTLS) {
            443
        } else {
            80
        }

        val builder = OkHttpChannelBuilder.forAddress(host, port)
        if (useTLS) {
            builder.useTransportSecurity()
        } else {
            builder.usePlaintext()
        }
        return builder.build()
    }

    suspend fun close() = mutex.withLock {
        for ((_, channel) in channels) {
            channel.shutdown().awaitTermination(5, TimeUnit.SECONDS)
        }
        channels.clear()
    }
}
