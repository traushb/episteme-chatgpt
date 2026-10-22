@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.aryan.reader.shared.ios

import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import platform.Foundation.HTTPMethod
import platform.Foundation.NSData
import platform.Foundation.NSError
import platform.Foundation.NSHTTPURLResponse
import platform.Foundation.NSMutableData
import platform.Foundation.NSMutableURLRequest
import platform.Foundation.NSURL
import platform.Foundation.NSURLRequestReloadIgnoringLocalCacheData
import platform.Foundation.NSURLResponse
import platform.Foundation.NSURLSession
import platform.Foundation.NSURLSessionConfiguration
import platform.Foundation.NSURLSessionDataDelegateProtocol
import platform.Foundation.NSURLSessionDataTask
import platform.Foundation.NSURLSessionResponseAllow
import platform.Foundation.NSURLSessionTask
import platform.Foundation.dataWithLength
import platform.Foundation.setHTTPBody
import platform.Foundation.setValue
import platform.darwin.NSObject
import platform.posix.memcpy

/**
 * HTTP for OpenAI calls made with the user's ChatGPT token. Kept apart from
 * [IosReaderAiHttpClient] on purpose: that client attaches Episteme's App
 * Check attestation, which must never be sent to a third party, and it
 * buffers whole responses, while answer checks stream into the sheet.
 */
internal sealed interface IosChatGptHttpEvent {
    data class Status(val code: Int) : IosChatGptHttpEvent
    class Body(val bytes: ByteArray) : IosChatGptHttpEvent
}

internal class IosChatGptTransportException(message: String) : Exception(message)

/** One HTTP exchange as a stream of [IosChatGptHttpEvent]s; injectable for tests. */
internal typealias IosChatGptHttpTransport =
    (method: String, url: String, headers: Map<String, String>, body: String?) -> Flow<IosChatGptHttpEvent>

/** Streams answer checks for up to five minutes; metadata calls give up sooner. */
internal val IosChatGptUrlSessionTransport: IosChatGptHttpTransport = { method, url, headers, body ->
    iosChatGptHttpRequest(method, url, headers, body, timeoutSeconds = if (body == null) 60.0 else 300.0)
}

/**
 * Emits the status once, then body chunks as they arrive. Cancelling the
 * collector cancels the request. Fails with [IosChatGptTransportException]
 * when the connection fails before or during the response.
 */
internal fun iosChatGptHttpRequest(
    method: String,
    url: String,
    headers: Map<String, String>,
    body: String? = null,
    timeoutSeconds: Double = 300.0,
): Flow<IosChatGptHttpEvent> = callbackFlow {
    val nsUrl = NSURL.URLWithString(url) ?: throw IosChatGptTransportException("Invalid URL")
    val request = NSMutableURLRequest.requestWithURL(
        URL = nsUrl,
        cachePolicy = NSURLRequestReloadIgnoringLocalCacheData,
        timeoutInterval = timeoutSeconds,
    ).apply {
        HTTPMethod = method
        headers.forEach { (name, value) -> setValue(value, forHTTPHeaderField = name) }
        if (body != null) setHTTPBody(body.encodeToByteArray().toIosNSData())
    }
    val delegate = IosChatGptHttpDelegate(
        onStatus = { code -> trySend(IosChatGptHttpEvent.Status(code)) },
        onData = { bytes -> trySend(IosChatGptHttpEvent.Body(bytes)) },
        onComplete = { error ->
            if (error == null) close() else close(IosChatGptTransportException(error))
        },
    )
    // Ephemeral: no cookies, cache or credentials persist from these calls.
    val session = NSURLSession.sessionWithConfiguration(
        NSURLSessionConfiguration.ephemeralSessionConfiguration,
        delegate = delegate,
        delegateQueue = null,
    )
    val task = session.dataTaskWithRequest(request)
    task.resume()
    awaitClose {
        task.cancel()
        session.invalidateAndCancel()
    }
}.buffer(Channel.UNLIMITED)

internal class IosChatGptHttpResult(val status: Int, val body: String)

/** Collects a whole (small) response; null when the connection failed. */
internal suspend fun collectIosChatGptResponse(events: Flow<IosChatGptHttpEvent>): IosChatGptHttpResult? {
    var status = 0
    val chunks = mutableListOf<ByteArray>()
    return try {
        events.collect { event ->
            when (event) {
                is IosChatGptHttpEvent.Status -> status = event.code
                is IosChatGptHttpEvent.Body -> chunks += event.bytes
            }
        }
        val bytes = ByteArray(chunks.sumOf { it.size })
        var offset = 0
        chunks.forEach { chunk ->
            chunk.copyInto(bytes, offset)
            offset += chunk.size
        }
        IosChatGptHttpResult(status, bytes.decodeToString())
    } catch (error: IosChatGptTransportException) {
        null
    }
}

private class IosChatGptHttpDelegate(
    private val onStatus: (Int) -> Unit,
    private val onData: (ByteArray) -> Unit,
    private val onComplete: (String?) -> Unit,
) : NSObject(), NSURLSessionDataDelegateProtocol {
    private var completed = false

    override fun URLSession(
        session: NSURLSession,
        dataTask: NSURLSessionDataTask,
        didReceiveResponse: NSURLResponse,
        completionHandler: (Long) -> Unit,
    ) {
        onStatus((didReceiveResponse as? NSHTTPURLResponse)?.statusCode?.toInt() ?: 0)
        completionHandler(NSURLSessionResponseAllow)
    }

    override fun URLSession(session: NSURLSession, dataTask: NSURLSessionDataTask, didReceiveData: NSData) {
        onData(didReceiveData.iosToByteArray())
    }

    override fun URLSession(session: NSURLSession, task: NSURLSessionTask, didCompleteWithError: NSError?) {
        if (completed) return
        completed = true
        onComplete(didCompleteWithError?.localizedDescription)
        session.finishTasksAndInvalidate()
    }
}

private fun ByteArray.toIosNSData(): NSData {
    val data = NSMutableData.dataWithLength(size.toULong()) ?: NSMutableData()
    if (isNotEmpty()) {
        usePinned { pinned -> memcpy(data.mutableBytes, pinned.addressOf(0), size.toULong()) }
    }
    return data
}
