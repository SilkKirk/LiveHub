package com.livehub.core.network

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Minimal suspend wrapper around [OkHttpClient] used by every platform adapter. */
interface HttpClient {
  suspend fun execute(request: Request): HttpResponse

  suspend fun get(url: String, headers: Map<String, String> = emptyMap()): HttpResponse = execute(
    Request.Builder().url(url).apply { headers.forEach { (k, v) -> header(k, v) } }.get().build(),
  )

  suspend fun postJson(
    url: String,
    body: String,
    headers: Map<String, String> = emptyMap(),
  ): HttpResponse = execute(
    Request.Builder()
      .url(url)
      .apply { headers.forEach { (k, v) -> header(k, v) } }
      .post(body.toRequestBody(HttpClients.JSON_MEDIA_TYPE))
      .build(),
  )

  suspend fun postForm(
    url: String,
    body: String,
    headers: Map<String, String> = emptyMap(),
  ): HttpResponse = execute(
    Request.Builder()
      .url(url)
      .apply { headers.forEach { (k, v) -> header(k, v) } }
      .post(body.toRequestBody(HttpClients.FORM_MEDIA_TYPE))
      .build(),
  )
}

/** Fully buffered HTTP response; streaming bodies are not needed by any adapter. */
class HttpResponse(
  val code: Int,
  val body: String,
  val headers: Map<String, List<String>>,
  val url: String,
) {
  val isSuccessful: Boolean get() = code in 200..299

  fun header(name: String): String? =
    headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value?.firstOrNull()

  fun headersNamed(name: String): List<String> =
    headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value.orEmpty()

  /** Throws [NetworkException] when the status is not 2xx. */
  fun requireSuccess(): HttpResponse {
    if (!isSuccessful) throw NetworkException(code, "HTTP $code for $url")
    return this
  }
}

class NetworkException(val code: Int, message: String, cause: Throwable? = null) :
  IOException(message, cause)

/** [HttpClient] backed by a shared [OkHttpClient]. */
class OkHttpHttpClient(private val client: OkHttpClient) : HttpClient {
  override suspend fun execute(request: Request): HttpResponse =
    suspendCancellableCoroutine { cont ->
      val call = client.newCall(request)
      cont.invokeOnCancellation { call.cancel() }
      call.enqueue(
        object : Callback {
          override fun onFailure(call: Call, e: IOException) {
            if (!cont.isCancelled) cont.resumeWithException(e)
          }

          override fun onResponse(call: Call, response: Response) {
            response.use {
              val body = try {
                it.body?.string().orEmpty()
              } catch (io: IOException) {
                if (!cont.isCancelled) cont.resumeWithException(io)
                return
              }
              if (cont.isCancelled) return
              cont.resume(
                HttpResponse(
                  code = it.code,
                  body = body,
                  headers = it.headers.toMultimap(),
                  url = it.request.url.toString(),
                ),
              )
            }
          }
        },
      )
    }
}

/** Convenience for building a request body without repeating the media type import. */
fun String.asJsonBody(): RequestBody = toRequestBody("application/json; charset=utf-8".toMediaType())

fun String.asFormBody(): RequestBody =
  toRequestBody("application/x-www-form-urlencoded".toMediaType())
