/*
 * Copyright (C) 2013 Square, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package okhttp3.internal.cache

import java.net.HttpURLConnection.HTTP_BAD_METHOD
import java.net.HttpURLConnection.HTTP_GONE
import java.net.HttpURLConnection.HTTP_MOVED_PERM
import java.net.HttpURLConnection.HTTP_MOVED_TEMP
import java.net.HttpURLConnection.HTTP_MULT_CHOICE
import java.net.HttpURLConnection.HTTP_NOT_AUTHORITATIVE
import java.net.HttpURLConnection.HTTP_NOT_FOUND
import java.net.HttpURLConnection.HTTP_NOT_IMPLEMENTED
import java.net.HttpURLConnection.HTTP_NO_CONTENT
import java.net.HttpURLConnection.HTTP_OK
import java.net.HttpURLConnection.HTTP_REQ_TOO_LONG
import java.util.Date
import java.util.concurrent.TimeUnit.SECONDS
import okhttp3.Request
import okhttp3.Response
import okhttp3.internal.http.StatusLine
import okhttp3.internal.http.toHttpDateOrNull
import okhttp3.internal.toNonNegativeInt

/**
 * Given a request and cached response, this figures out whether to use the network, the cache, or
 * both.
 *
 * Selecting a cache strategy may add conditions to the request (like the "If-Modified-Since" header
 * for conditional GETs) or warnings to the cached response (if the cached data is potentially
 * stale).
 */
class CacheStrategy internal constructor(
  /** The request to send on the network, or null if this call doesn't use the network. */
  val networkRequest: Request?,
  /** The cached response to return or validate; or null if this call doesn't use a cache. */
  val cacheResponse: Response?
) {

  class Factory(
    private val nowMillis: Long,
    internal val request: Request,
    private val cacheResponse: Response?
  ) {
    /** The server's time when the cached response was served, if known. */
    private var servedDate: Date? = null
    private var servedDateString: String? = null

    /** The last modified date of the cached response, if known. */
    private var lastModified: Date? = null
    private var lastModifiedString: String? = null

    /**
     * The expiration date of the cached response, if known. If both this field and the max age are
     * set, the max age is preferred.
     */
    private var expires: Date? = null

    /**
     * Extension header set by OkHttp specifying the timestamp when the cached HTTP request was
     * first initiated.
     */
    private var sentRequestMillis = 0L

    /**
     * Extension header set by OkHttp specifying the timestamp when the cached HTTP response was
     * first received.
     */
    private var receivedResponseMillis = 0L

    /** Etag of the cached response. */
    private var etag: String? = null

    /** Age of the cached response. */
    private var ageSeconds = -1

    /**
     * Returns true if computeFreshnessLifetime used a heuristic. If we used a heuristic to serve a
     * cached response older than 24 hours, we are required to attach a warning.
     */
    private fun isFreshnessLifetimeHeuristic(): Boolean {
      return cacheResponse!!.cacheControl.maxAgeSeconds == -1 && expires == null
    }

    init {
      if (cacheResponse != null) {
        this.sentRequestMillis = cacheResponse.sentRequestAtMillis
        this.receivedResponseMillis = cacheResponse.receivedResponseAtMillis
        val headers = cacheResponse.headers
        // 解析缓存的响应header
        for (i in 0 until headers.size) {
          val fieldName = headers.name(i)
          val value = headers.value(i)
          when {
            // Date 通用 HTTP 报头包含在该消息起源的日期和时间。eg. Date: Wed, 21 Oct 2015 07:28:00 GMT 
            fieldName.equals("Date", ignoreCase = true) -> {
              servedDate = value.toHttpDateOrNull()
              servedDateString = value
            }
            // Expires 标头包含的日期/时间之后，响应被视为失效。 eg. Expires: Wed, 21 Oct 2015 07:28:00 GMT
            fieldName.equals("Expires", ignoreCase = true) -> {
              expires = value.toHttpDateOrNull()
            }
            // Last-Modified 响应HTTP报头包含在其原始服务器认为该资源的最后修改日期和时间 eg. Last-Modified: Wed, 21 Oct 2015 07:28:00 GMT 
            fieldName.equals("Last-Modified", ignoreCase = true) -> {
              lastModified = value.toHttpDateOrNull()
              lastModifiedString = value
            }
            // ETag HTTP 响应报头为资源的特定版本的标识符  eg. ETag: "33a64df551425fcc55e4d42a148795d9f25f89d4"  ETag: W/"0815"
            fieldName.equals("ETag", ignoreCase = true) -> {
              etag = value
            }
            // Age header 包含以秒计的对象一直在代理缓存的时间。 Age: 24
            fieldName.equals("Age", ignoreCase = true) -> {
              ageSeconds = value.toNonNegativeInt(-1)
            }
          }
        }
      }
    }

    /** Returns a strategy to satisfy [request] using [cacheResponse]. */
    fun compute(): CacheStrategy {
      val candidate = computeCandidate()

      // We're forbidden from using the network and the cache is insufficient.
      // 禁止使用网络
      if (candidate.networkRequest != null && request.cacheControl.onlyIfCached) {
        // 不使用网络 不使用缓存 返回504状态码：网关超时
        return CacheStrategy(null, null)
      }

      return candidate
    }

    /** Returns a strategy to use assuming the request can use the network. */
    private fun computeCandidate(): CacheStrategy {
      // No cached response. 没有缓存响应
      if (cacheResponse == null) {
        // 不使用缓存
        return CacheStrategy(request, null)
      }

      // Drop the cached response if it's missing a required handshake. Https的缓存需要有握手连接
      if (request.isHttps && cacheResponse.handshake == null) {
        // 不使用缓存
        return CacheStrategy(request, null)
      }

      // If this response shouldn't have been stored, it should never be used as a response source.
      // This check should be redundant as long as the persistence store is well-behaved and the
      // rules are constant.
      // 判断是否可以被缓存  isCacheable() -> Returns true if [response] can be stored to later serve another request.
      if (!isCacheable(cacheResponse, request)) {
        // 不适用缓存
        return CacheStrategy(request, null)
      }

      val requestCaching = request.cacheControl
      // 请求头中的header关于Cache的信息 eg. Cache-Control: no-cache 或者请求header中的If-Modified-Since不为空
      if (requestCaching.noCache || hasConditions(request)) {
        // 不使用缓存
        return CacheStrategy(request, null)
      }

      val responseCaching = cacheResponse.cacheControl

      val ageMillis = cacheResponseAge()
      var freshMillis = computeFreshnessLifetime()


      /**
       *  判断缓存是否失效
       *  maxAgeSeconds ：缓存的内容将在 xxx 秒后失效，缓存有效期
       *  minFreshSeconds ：min-fresh 要求缓存服务器返回 min-fresh 时间内的缓存数据。比如，有个资源在缓存里面已经存了7s了，其中 “max-age=10”，那么“7+1<10”，在 1s 之后还是新鲜的，因此是有效的
       *  maxStaleSeconds ：表示客户端愿意接受超过其新鲜度生命周期的响应，只超过了maxAgeSeconds之后额外的时间，一般缓存生效时间（maxAgeSeconds + maxStaleSeconds）
       */
      if (requestCaching.maxAgeSeconds != -1) {
        freshMillis = minOf(freshMillis, SECONDS.toMillis(requestCaching.maxAgeSeconds.toLong()))
      }

      var minFreshMillis: Long = 0
      if (requestCaching.minFreshSeconds != -1) {
        minFreshMillis = SECONDS.toMillis(requestCaching.minFreshSeconds.toLong())
      }

      var maxStaleMillis: Long = 0
      if (!responseCaching.mustRevalidate && requestCaching.maxStaleSeconds != -1) {
        maxStaleMillis = SECONDS.toMillis(requestCaching.maxStaleSeconds.toLong())
      }

      // 缓存是否在有效期内
      if (!responseCaching.noCache && ageMillis + minFreshMillis < freshMillis + maxStaleMillis) {
        val builder = cacheResponse.newBuilder()
        if (ageMillis + minFreshMillis >= freshMillis) {
          builder.addHeader("Warning", "110 HttpURLConnection \"Response is stale\"")
        }
        val oneDayMillis = 24 * 60 * 60 * 1000L
        if (ageMillis > oneDayMillis && isFreshnessLifetimeHeuristic()) {
          builder.addHeader("Warning", "113 HttpURLConnection \"Heuristic expiration\"")
        }
        // 使用缓存
        return CacheStrategy(null, builder.build())
      }

      // Find a condition to add to the request. If the condition is satisfied, the response body
      // will not be transmitted.
      val conditionName: String
      val conditionValue: String?
      when {
        etag != null -> {
          // 客户端发送etag给后端  服务器会比对这个客户端发送过来的Etag是否与服务器的相同，
          conditionName = "If-None-Match"
          conditionValue = etag
        }

        lastModified != null -> {
          // 把浏览器端缓存页面的最后修改时间发到服务器去，服务器把这个时间与服务器上实际文件的最后修改时间进行比较。
          // 如果时间一致，那么返回HTTP状态码304（不返回文件内容），客户端接到之后，就直接把本地缓存文件显示到浏览器中。
          // 如果时间不一致，就返回HTTP状态码200和新的文件内容，客户端接到之后，会丢弃旧文件，把新文件缓存起来，并显示到浏览器中。
          conditionName = "If-Modified-Since"
          conditionValue = lastModifiedString
        }

        servedDate != null -> {
          conditionName = "If-Modified-Since"
          conditionValue = servedDateString
        }
        // 其他情况下 使用网络 不使用缓存
        else -> return CacheStrategy(request, null) // No condition! Make a regular request.
      }

      val conditionalRequestHeaders = request.headers.newBuilder()
      conditionalRequestHeaders.addLenient(conditionName, conditionValue!!)

      val conditionalRequest = request.newBuilder()
          .headers(conditionalRequestHeaders.build())
          .build()
      // 使用网络 
      return CacheStrategy(conditionalRequest, cacheResponse)
    }

    /**
     * Returns the number of milliseconds that the response was fresh for, starting from the served
     * date.
     */
    private fun computeFreshnessLifetime(): Long {
      val responseCaching = cacheResponse!!.cacheControl
      if (responseCaching.maxAgeSeconds != -1) {
        return SECONDS.toMillis(responseCaching.maxAgeSeconds.toLong())
      }

      val expires = this.expires
      if (expires != null) {
        val servedMillis = servedDate?.time ?: receivedResponseMillis
        val delta = expires.time - servedMillis
        return if (delta > 0L) delta else 0L
      }

      if (lastModified != null && cacheResponse.request.url.query == null) {
        // As recommended by the HTTP RFC and implemented in Firefox, the max age of a document
        // should be defaulted to 10% of the document's age at the time it was served. Default
        // expiration dates aren't used for URIs containing a query.
        val servedMillis = servedDate?.time ?: sentRequestMillis
        val delta = servedMillis - lastModified!!.time
        return if (delta > 0L) delta / 10 else 0L
      }

      return 0L
    }

    /**
     * Returns the current age of the response, in milliseconds. The calculation is specified by RFC
     * 7234, 4.2.3 Calculating Age.
     */
    private fun cacheResponseAge(): Long {
      val servedDate = this.servedDate
      val apparentReceivedAge = if (servedDate != null) {
        maxOf(0, receivedResponseMillis - servedDate.time)
      } else {
        0
      }

      val receivedAge = if (ageSeconds != -1) {
        maxOf(apparentReceivedAge, SECONDS.toMillis(ageSeconds.toLong()))
      } else {
        apparentReceivedAge
      }

      val responseDuration = receivedResponseMillis - sentRequestMillis
      val residentDuration = nowMillis - receivedResponseMillis
      return receivedAge + responseDuration + residentDuration
    }

    /**
     * Returns true if the request contains conditions that save the server from sending a response
     * that the client has locally. When a request is enqueued with its own conditions, the built-in
     * response cache won't be used.
     */
    private fun hasConditions(request: Request): Boolean =
        request.header("If-Modified-Since") != null || request.header("If-None-Match") != null
  }

  companion object {
    /** Returns true if [response] can be stored to later serve another request. */
    fun isCacheable(response: Response, request: Request): Boolean {
      // Always go to network for uncacheable response codes (RFC 7231 section 6.1), This
      // implementation doesn't support caching partial content.
      when (response.code) {
        HTTP_OK,
        HTTP_NOT_AUTHORITATIVE,
        HTTP_NO_CONTENT,
        HTTP_MULT_CHOICE,
        HTTP_MOVED_PERM,
        HTTP_NOT_FOUND,
        HTTP_BAD_METHOD,
        HTTP_GONE,
        HTTP_REQ_TOO_LONG,
        HTTP_NOT_IMPLEMENTED,
        StatusLine.HTTP_PERM_REDIRECT -> {
          // These codes can be cached unless headers forbid it.
          // 这些状态码可以缓存除非在header中禁止缓存
        }

        HTTP_MOVED_TEMP,
        StatusLine.HTTP_TEMP_REDIRECT -> {
          // These codes can only be cached with the right response headers.
          // http://tools.ietf.org/html/rfc7234#section-3
          // s-maxage is not checked because OkHttp is a private cache that should ignore s-maxage.
          if (response.header("Expires") == null &&
              response.cacheControl.maxAgeSeconds == -1 &&
              !response.cacheControl.isPublic &&
              !response.cacheControl.isPrivate) {
            return false
          }
        }

        else -> {
          // All other codes cannot be cached.
          // 其他状态码的响应不可以缓存
          return false
        }
      }

      // A 'no-store' directive on request or response prevents the response from being cached.
      return !response.cacheControl.noStore && !request.cacheControl.noStore
    }
  }
}
