/*
 *  Licensed to the Apache Software Foundation (ASF) under one or more
 *  contributor license agreements.  See the NOTICE file distributed with
 *  this work for additional information regarding copyright ownership.
 *  The ASF licenses this file to You under the Apache License, Version 2.0
 *  (the "License"); you may not use this file except in compliance with
 *  the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package okhttp3.internal.connection

import java.io.IOException
import java.lang.ref.Reference
import java.net.ConnectException
import java.net.HttpURLConnection.HTTP_OK
import java.net.HttpURLConnection.HTTP_PROXY_AUTH
import java.net.ProtocolException
import java.net.Proxy
import java.net.Socket
import java.net.SocketException
import java.net.UnknownServiceException
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit.MILLISECONDS
import javax.net.ssl.SSLPeerUnverifiedException
import javax.net.ssl.SSLSocket
import okhttp3.Address
import okhttp3.Call
import okhttp3.CertificatePinner
import okhttp3.Connection
import okhttp3.ConnectionSpec
import okhttp3.EventListener
import okhttp3.Handshake
import okhttp3.Handshake.Companion.handshake
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.Route
import okhttp3.internal.EMPTY_RESPONSE
import okhttp3.internal.assertThreadDoesntHoldLock
import okhttp3.internal.closeQuietly
import okhttp3.internal.concurrent.TaskRunner
import okhttp3.internal.http.ExchangeCodec
import okhttp3.internal.http.RealInterceptorChain
import okhttp3.internal.http1.Http1ExchangeCodec
import okhttp3.internal.http2.ConnectionShutdownException
import okhttp3.internal.http2.ErrorCode
import okhttp3.internal.http2.Http2Connection
import okhttp3.internal.http2.Http2ExchangeCodec
import okhttp3.internal.http2.Http2Stream
import okhttp3.internal.http2.Settings
import okhttp3.internal.http2.StreamResetException
import okhttp3.internal.isHealthy
import okhttp3.internal.platform.Platform
import okhttp3.internal.tls.OkHostnameVerifier
import okhttp3.internal.toHostHeader
import okhttp3.internal.userAgent
import okhttp3.internal.ws.RealWebSocket
import okio.BufferedSink
import okio.BufferedSource
import okio.buffer
import okio.sink
import okio.source

class RealConnection(
  val connectionPool: RealConnectionPool,
  private val route: Route
) : Http2Connection.Listener(), Connection {

  // The fields below are initialized by connect() and never reassigned.

  /** Tips!!!! The low-level TCP socket. */
  private var rawSocket: Socket? = null

  /**
   *  Tips!!!! The application layer socket. Either an [SSLSocket] layered over [rawSocket], or [rawSocket]
   * itself if this connection does not use SSL.
   */
  private var socket: Socket? = null
  private var handshake: Handshake? = null
  private var protocol: Protocol? = null
  private var http2Connection: Http2Connection? = null
  private var source: BufferedSource? = null
  private var sink: BufferedSink? = null

  // The fields below track connection state and are guarded by connectionPool.

  /**
   * If true, no new exchanges can be created on this connection. Once true this is always true.
   * Guarded by [connectionPool].
   */
  var noNewExchanges = false

  /**
   * If true, this connection may not be used for coalesced requests. These are requests that could
   * share the same connection without sharing the same hostname.
   */
  private var noCoalescedConnections = false

  /**
   * The number of times there was a problem establishing a stream that could be due to route
   * chosen. Guarded by [connectionPool].
   */
  internal var routeFailureCount = 0

  internal var successCount = 0
  private var refusedStreamCount = 0

  /**
   * The maximum number of concurrent streams that can be carried by this connection. If
   * `allocations.size() < allocationLimit` then new streams can be created on this connection.
   */
  private var allocationLimit = 1

  /** Current calls carried by this connection. */
  val calls = mutableListOf<Reference<RealCall>>()

  /** Timestamp when `allocations.size()` reached zero. Also assigned upon initial connection. */
  internal var idleAtNs = Long.MAX_VALUE

  /**
   * Returns true if this is an HTTP/2 connection. Such connections can be used in multiple HTTP
   * requests simultaneously.
   */
  val isMultiplexed: Boolean
    get() = http2Connection != null

  /** Prevent further exchanges from being created on this connection. */
  fun noNewExchanges() {
    connectionPool.assertThreadDoesntHoldLock()

    synchronized(connectionPool) {
      noNewExchanges = true
    }
  }

  /** Prevent this connection from being used for hosts other than the one in [route]. */
  fun noCoalescedConnections() {
    connectionPool.assertThreadDoesntHoldLock()

    synchronized(connectionPool) {
      noCoalescedConnections = true
    }
  }

  fun connect(
    connectTimeout: Int,
    readTimeout: Int,
    writeTimeout: Int,
    pingIntervalMillis: Int,
    connectionRetryEnabled: Boolean,
    call: Call,
    eventListener: EventListener
  ) {

    var routeException: RouteException? = null
    val connectionSpecs = route.address.connectionSpecs
    val connectionSpecSelector = ConnectionSpecSelector(connectionSpecs)

    if (route.address.sslSocketFactory == null) {
      // 不是HTTPS 地址
      if (ConnectionSpec.CLEARTEXT !in connectionSpecs) {
        throw RouteException(UnknownServiceException(
            "CLEARTEXT communication not enabled for client"))
      }
      val host = route.address.url.host
      if (!Platform.get().isCleartextTrafficPermitted(host)) {
        throw RouteException(UnknownServiceException(
            "CLEARTEXT communication to $host not permitted by network security policy"))
      }
    } else {
      // 明文传输的HTTP2
      if (Protocol.H2_PRIOR_KNOWLEDGE in route.address.protocols) {
        throw RouteException(UnknownServiceException(
            "H2_PRIOR_KNOWLEDGE cannot be used with HTTPS"))
      }
    }

    while (true) {
      try {
        // Returns true if this route tunnels HTTPS through an HTTP proxy.
        if (route.requiresTunnel()) { // HTTP转HTTPS
          // 创建Http Tunnel
          connectTunnel(connectTimeout, readTimeout, writeTimeout, call, eventListener)
          if (rawSocket == null) {
            // We were unable to connect the tunnel but properly closed down our resources.
            break
          }
        } else {
          connectSocket(connectTimeout, readTimeout, call, eventListener)
        }
        // 确定连接协议
        establishProtocol(connectionSpecSelector, pingIntervalMillis, call, eventListener)
        break
      } catch (e: IOException) {
        socket?.closeQuietly()
        rawSocket?.closeQuietly()
        socket = null
        rawSocket = null
        source = null
        sink = null
        handshake = null
        protocol = null
        http2Connection = null
        allocationLimit = 1

        eventListener.connectFailed(call, route.socketAddress, route.proxy, null, e)

        if (routeException == null) {
          routeException = RouteException(e)
        } else {
          routeException.addConnectException(e)
        }

        if (!connectionRetryEnabled || !connectionSpecSelector.connectionFailed(e)) {
          throw routeException
        }
      }
    }

    if (route.requiresTunnel() && rawSocket == null) {
      throw RouteException(ProtocolException(
          "Too many tunnel connections attempted: $MAX_TUNNEL_ATTEMPTS"))
    }

    idleAtNs = System.nanoTime()
  }

  /**
   * Does all the work to build an HTTPS connection over a proxy tunnel. The catch here is that a
   * proxy server can issue an auth challenge and then close the connection.
   */
  @Throws(IOException::class)
  private fun connectTunnel(
    connectTimeout: Int,
    readTimeout: Int,
    writeTimeout: Int,
    call: Call,
    eventListener: EventListener
  ) {
    var tunnelRequest: Request = createTunnelRequest()
    val url = tunnelRequest.url
    for (i in 0 until MAX_TUNNEL_ATTEMPTS) {
      connectSocket(connectTimeout, readTimeout, call, eventListener)
      // Http Tunnel
      tunnelRequest = createTunnel(readTimeout, writeTimeout, tunnelRequest, url)
          ?: break // Tunnel successfully created.

      // The proxy decided to close the connection after an auth challenge. We need to create a new
      // connection, but this time with the auth credentials.
      rawSocket?.closeQuietly()
      rawSocket = null
      sink = null
      source = null
      eventListener.connectEnd(call, route.socketAddress, route.proxy, null)
    }
  }

  /** Does all the work necessary to build a full HTTP or HTTPS connection on a raw socket. */
  @Throws(IOException::class)
  private fun connectSocket(
    connectTimeout: Int,
    readTimeout: Int,
    call: Call,
    eventListener: EventListener
  ) {
    val proxy = route.proxy
    val address = route.address

    val rawSocket = when (proxy.type()) {
      Proxy.Type.DIRECT, Proxy.Type.HTTP -> address.socketFactory.createSocket()!!
      else -> Socket(proxy)
    }
    // tcp连接
    this.rawSocket = rawSocket

    rawSocket.soTimeout = readTimeout
    try {
      Platform.get().connectSocket(rawSocket, route.socketAddress, connectTimeout)
    } catch (e: ConnectException) {
      throw ConnectException("Failed to connect to ${route.socketAddress}").apply {
        initCause(e)
      }
    }

    // The following try/catch block is a pseudo hacky way to get around a crash on Android 7.0
    // More details:
    // https://github.com/square/okhttp/issues/3245
    // https://android-review.googlesource.com/#/c/271775/
    try {
      source = rawSocket.source().buffer()
      sink = rawSocket.sink().buffer()
    } catch (npe: NullPointerException) {
      if (npe.message == NPE_THROW_WITH_NULL) {
        throw IOException(npe)
      }
    }
  }

  @Throws(IOException::class)
  private fun establishProtocol(
    connectionSpecSelector: ConnectionSpecSelector,
    pingIntervalMillis: Int,
    call: Call,
    eventListener: EventListener
  ) {
    if (route.address.sslSocketFactory == null) {
      // 不需要加密连接
      if (Protocol.H2_PRIOR_KNOWLEDGE in route.address.protocols) {
        // 明文 HTTP2连接
        socket = rawSocket
        protocol = Protocol.H2_PRIOR_KNOWLEDGE
        startHttp2(pingIntervalMillis)
        return
      }
      // 普通HTTP1.1协议连接
      socket = rawSocket
      protocol = Protocol.HTTP_1_1
      return
    }
    // 建立加密连接
    connectTls(connectionSpecSelector)
    // 加密的HTTP2连接
    if (protocol === Protocol.HTTP_2) {
      startHttp2(pingIntervalMillis)
    }
  }

  @Throws(IOException::class)
  private fun startHttp2(pingIntervalMillis: Int) {
    val socket = this.socket!!
    val source = this.source!!
    val sink = this.sink!!
    socket.soTimeout = 0 // HTTP/2 connection timeouts are set per-stream.
    val http2Connection = Http2Connection.Builder(client = true, taskRunner = TaskRunner.INSTANCE)
        .socket(socket, route.address.url.host, source, sink)
        .listener(this)
        .pingIntervalMillis(pingIntervalMillis)
        .build()
    this.http2Connection = http2Connection
    this.allocationLimit = Http2Connection.DEFAULT_SETTINGS.getMaxConcurrentStreams()
    http2Connection.start()
  }

  @Throws(IOException::class)
  private fun connectTls(connectionSpecSelector: ConnectionSpecSelector) {
    val address = route.address
    val sslSocketFactory = address.sslSocketFactory
    var success = false
    var sslSocket: SSLSocket? = null
    try {
      // Create the wrapper over the connected socket.
      // 在rawSocket基础上在建立一层ssl连接
      sslSocket = sslSocketFactory!!.createSocket(
          rawSocket, address.url.host, address.url.port, true /* autoClose */) as SSLSocket

      // Configure the socket's ciphers, TLS versions, and extensions.
      val connectionSpec = connectionSpecSelector.configureSecureSocket(sslSocket)
      if (connectionSpec.supportsTlsExtensions) {
        Platform.get().configureTlsExtensions(sslSocket, address.url.host, address.protocols)
      }

      // Force handshake. This can throw!
      // 开始建立SSL握手
      sslSocket.startHandshake()
      // block for session establishment
      val sslSocketSession = sslSocket.session
      val unverifiedHandshake = sslSocketSession.handshake()
      // 验证socket的证书
      // Verify that the socket's certificates are acceptable for the target host.
      if (!address.hostnameVerifier!!.verify(address.url.host, sslSocketSession)) {
        val peerCertificates = unverifiedHandshake.peerCertificates
        if (peerCertificates.isNotEmpty()) {
          val cert = peerCertificates[0] as X509Certificate
          throw SSLPeerUnverifiedException("""
              |Hostname ${address.url.host} not verified:
              |    certificate: ${CertificatePinner.pin(cert)}
              |    DN: ${cert.subjectDN.name}
              |    subjectAltNames: ${OkHostnameVerifier.allSubjectAltNames(cert)}
              """.trimMargin())
        } else {
          throw SSLPeerUnverifiedException(
              "Hostname ${address.url.host} not verified (no certificates)")
        }
      }

      val certificatePinner = address.certificatePinner!!

      handshake = Handshake(unverifiedHandshake.tlsVersion, unverifiedHandshake.cipherSuite,
          unverifiedHandshake.localCertificates) {
        certificatePinner.certificateChainCleaner!!.clean(unverifiedHandshake.peerCertificates,
            address.url.host)
      }

      // Check that the certificate pinner is satisfied by the certificates presented.
      // 验证certificate pinner
      certificatePinner.check(address.url.host) {
        handshake!!.peerCertificates.map { it as X509Certificate }
      }

      // 握手成功 Save the handshake and the ALPN protocol.
      val maybeProtocol = if (connectionSpec.supportsTlsExtensions) {
        Platform.get().getSelectedProtocol(sslSocket)
      } else {
        null
      }
      // 这里给socket赋值
      socket = sslSocket
      source = sslSocket.source().buffer()
      sink = sslSocket.sink().buffer()
      protocol = if (maybeProtocol != null) Protocol.get(maybeProtocol) else Protocol.HTTP_1_1
      success = true
    } finally {
      if (sslSocket != null) {
        Platform.get().afterHandshake(sslSocket)
      }
      if (!success) {
        sslSocket?.closeQuietly()
      }
    }
  }

  /**
   * To make an HTTPS connection over an HTTP proxy, send an unencrypted CONNECT request to create
   * the proxy connection. This may need to be retried if the proxy requires authorization.
   */
  @Throws(IOException::class)
  private fun createTunnel(
    readTimeout: Int,
    writeTimeout: Int,
    tunnelRequest: Request,
    url: HttpUrl
  ): Request? {
    var nextRequest = tunnelRequest
    // Make an SSL Tunnel on the first message pair of each SSL + proxy connection.
    val requestLine = "CONNECT ${url.toHostHeader(includeDefaultPort = true)} HTTP/1.1"
    while (true) {
      val source = this.source!!
      val sink = this.sink!!
      val tunnelCodec = Http1ExchangeCodec(null, this, source, sink)
      source.timeout().timeout(readTimeout.toLong(), MILLISECONDS)
      sink.timeout().timeout(writeTimeout.toLong(), MILLISECONDS)
      tunnelCodec.writeRequest(nextRequest.headers, requestLine)
      tunnelCodec.finishRequest()
      val response = tunnelCodec.readResponseHeaders(false)!!
          .request(nextRequest)
          .build()
      tunnelCodec.skipConnectBody(response)

      when (response.code) {
        HTTP_OK -> {
          // Assume the server won't send a TLS ServerHello until we send a TLS ClientHello. If
          // that happens, then we will have buffered bytes that are needed by the SSLSocket!
          // This check is imperfect: it doesn't tell us whether a handshake will succeed, just
          // that it will almost certainly fail because the proxy has sent unexpected data.
          if (!source.buffer.exhausted() || !sink.buffer.exhausted()) {
            throw IOException("TLS tunnel buffered too many bytes!")
          }
          return null
        }

        HTTP_PROXY_AUTH -> {
          nextRequest = route.address.proxyAuthenticator.authenticate(route, response)
              ?: throw IOException("Failed to authenticate with proxy")

          if ("close".equals(response.header("Connection"), ignoreCase = true)) {
            return nextRequest
          }
        }

        else -> throw IOException("Unexpected response code for CONNECT: ${response.code}")
      }
    }
  }

  /**
   * Returns a request that creates a TLS tunnel via an HTTP proxy. Everything in the tunnel request
   * is sent unencrypted to the proxy server, so tunnels include only the minimum set of headers.
   * This avoids sending potentially sensitive data like HTTP cookies to the proxy unencrypted.
   *
   * In order to support preemptive authentication we pass a fake "Auth Failed" response to the
   * authenticator. This gives the authenticator the option to customize the CONNECT request. It can
   * decline to do so by returning null, in which case OkHttp will use it as-is.
   */
  @Throws(IOException::class)
  private fun createTunnelRequest(): Request {
    val proxyConnectRequest = Request.Builder()
        .url(route.address.url)
        .method("CONNECT", null)
        .header("Host", route.address.url.toHostHeader(includeDefaultPort = true))
        .header("Proxy-Connection", "Keep-Alive") // For HTTP/1.0 proxies like Squid.
        .header("User-Agent", userAgent)
        .build()

    val fakeAuthChallengeResponse = Response.Builder()
        .request(proxyConnectRequest)
        .protocol(Protocol.HTTP_1_1)
        .code(HTTP_PROXY_AUTH)
        .message("Preemptive Authenticate")
        .body(EMPTY_RESPONSE)
        .sentRequestAtMillis(-1L)
        .receivedResponseAtMillis(-1L)
        .header("Proxy-Authenticate", "OkHttp-Preemptive")
        .build()

    val authenticatedRequest = route.address.proxyAuthenticator
        .authenticate(route, fakeAuthChallengeResponse)

    return authenticatedRequest ?: proxyConnectRequest
  }

  /**
   * Returns true if this connection can carry a stream allocation to `address`. If non-null
   * `route` is the resolved route for a connection.
   */
  internal fun isEligible(address: Address, routes: List<Route>?): Boolean {
    // If this connection is not accepting new exchanges, we're done.
    // 请求数量不超过限制
    if (calls.size >= allocationLimit || noNewExchanges) return false

    // 协议 代理和对应的TCP端口等等都需要一样
    // If the non-host fields of the address don't overlap, we're done.
    if (!this.route.address.equalsNonHost(address)) return false

    // If the host exactly matches, we're done: this connection can carry the address.
    // 主机名一样  
    if (address.url.host == this.route().address.url.host) {
      return true // This connection is a perfect match.
    }

    // At this point we don't have a hostname match. But we still be able to carry the request if
    // our connection coalescing requirements are met. See also:
    // https://hpbn.co/optimizing-application-delivery/#eliminate-domain-sharding
    // https://daniel.haxx.se/blog/2016/08/18/http2-connection-coalescing/

    //  HTTP/2 连接合并 connection coalescing
    // 1. This connection must be HTTP/2.
    if (http2Connection == null) return false

    // 2. The routes must share an IP address.
    // 路由必须共享IP地址
    if (routes == null || !routeMatchesAny(routes)) return false

    // 3. This connection's server certificate's must cover the new host.
    // 验证证书一致
    if (address.hostnameVerifier !== OkHostnameVerifier) return false
    if (!supportsUrl(address.url)) return false

    // 4. Certificate pinning must match the host.
    // 证书锁定
    try {
      address.certificatePinner!!.check(address.url.host, handshake()!!.peerCertificates)
    } catch (_: SSLPeerUnverifiedException) {
      return false
    }

    return true // The caller's address can be carried by this connection.
  }

  /**
   * Returns true if this connection's route has the same address as any of [candidates]. This
   * requires us to have a DNS address for both hosts, which only happens after route planning. We
   * can't coalesce connections that use a proxy, since proxies don't tell us the origin server's IP
   * address.
   */
  private fun routeMatchesAny(candidates: List<Route>): Boolean {
    return candidates.any {
      it.proxy.type() == Proxy.Type.DIRECT &&
          route.proxy.type() == Proxy.Type.DIRECT &&
          route.socketAddress == it.socketAddress
    }
  }

  fun supportsUrl(url: HttpUrl): Boolean {
    val routeUrl = route.address.url

    if (url.port != routeUrl.port) {
      return false // Port mismatch.
    }

    if (url.host == routeUrl.host) {
      return true // Host match. The URL is supported.
    }

    // We have a host mismatch. But if the certificate matches, we're still good.
    return !noCoalescedConnections && handshake != null && certificateSupportHost(url, handshake!!)
  }

  private fun certificateSupportHost(url: HttpUrl, handshake: Handshake): Boolean {
    val peerCertificates = handshake.peerCertificates

    return peerCertificates.isNotEmpty() && OkHostnameVerifier.verify(url.host,
        peerCertificates[0] as X509Certificate)
  }

  @Throws(SocketException::class)
  internal fun newCodec(client: OkHttpClient, chain: RealInterceptorChain): ExchangeCodec {
    val socket = this.socket!!
    val source = this.source!!
    val sink = this.sink!!
    val http2Connection = this.http2Connection

    return if (http2Connection != null) {
      Http2ExchangeCodec(client, this, chain, http2Connection)
    } else {
      socket.soTimeout = chain.readTimeoutMillis()
      source.timeout().timeout(chain.readTimeoutMillis.toLong(), MILLISECONDS)
      sink.timeout().timeout(chain.writeTimeoutMillis.toLong(), MILLISECONDS)
      Http1ExchangeCodec(client, this, source, sink)
    }
  }

  @Throws(SocketException::class)
  internal fun newWebSocketStreams(exchange: Exchange): RealWebSocket.Streams {
    val socket = this.socket!!
    val source = this.source!!
    val sink = this.sink!!

    socket.soTimeout = 0
    noNewExchanges()
    return object : RealWebSocket.Streams(true, source, sink) {
      override fun close() {
        exchange.bodyComplete<IOException?>(-1L, responseDone = true, requestDone = true, e = null)
      }
    }
  }

  override fun route(): Route = route

  fun cancel() {
    // Close the raw socket so we don't end up doing synchronous I/O.
    rawSocket?.closeQuietly()
  }

  override fun socket(): Socket = socket!!

  /** Returns true if this connection is ready to host new streams. */
  fun isHealthy(doExtensiveChecks: Boolean): Boolean {
    val nowNs = System.nanoTime()
    // socket当前的状态 
    val socket = this.socket!!
    val source = this.source!!
    if (socket.isClosed || socket.isInputShutdown || socket.isOutputShutdown) {
      return false
    }

    val http2Connection = this.http2Connection
    if (http2Connection != null) {
      return http2Connection.isHealthy(nowNs)
    }

    val idleDurationNs = nowNs - idleAtNs
    if (idleDurationNs >= IDLE_CONNECTION_HEALTHY_NS && doExtensiveChecks) {
      return socket.isHealthy(source)
    }

    return true
  }

  /** Refuse incoming streams. */
  @Throws(IOException::class)
  override fun onStream(stream: Http2Stream) {
    stream.close(ErrorCode.REFUSED_STREAM, null)
  }

  /** When settings are received, adjust the allocation limit. */
  override fun onSettings(connection: Http2Connection, settings: Settings) {
    synchronized(connectionPool) {
      allocationLimit = settings.getMaxConcurrentStreams()
    }
  }

  override fun handshake(): Handshake? = handshake

  /** Track a bad route in the route database. Other routes will be attempted first. */
  internal fun connectFailed(client: OkHttpClient, failedRoute: Route, failure: IOException) {
    // Tell the proxy selector when we fail to connect on a fresh connection.
    if (failedRoute.proxy.type() != Proxy.Type.DIRECT) {
      val address = failedRoute.address
      address.proxySelector.connectFailed(
          address.url.toUri(), failedRoute.proxy.address(), failure)
    }
    // 放入Database
    client.routeDatabase.failed(failedRoute)
  }

  /**
   * Track a failure using this connection. This may prevent both the connection and its route from
   * being used for future exchanges.
   */
  internal fun trackFailure(call: RealCall, e: IOException?) {
    connectionPool.assertThreadDoesntHoldLock()

    synchronized(connectionPool) {
      if (e is StreamResetException) {
        when {
          e.errorCode == ErrorCode.REFUSED_STREAM -> {
            // Stop using this connection on the 2nd REFUSED_STREAM error.
            refusedStreamCount++
            if (refusedStreamCount > 1) {
              noNewExchanges = true
              routeFailureCount++
            }
          }

          e.errorCode == ErrorCode.CANCEL && call.isCanceled() -> {
            // Permit any number of CANCEL errors on locally-canceled calls.
          }

          else -> {
            // Everything else wants a fresh connection.
            noNewExchanges = true
            routeFailureCount++
          }
        }
      } else if (!isMultiplexed || e is ConnectionShutdownException) {
        noNewExchanges = true

        // If this route hasn't completed a call, avoid it for new connections.
        if (successCount == 0) {
          if (e != null) {
            connectFailed(call.client, route, e)
          }
          routeFailureCount++
        }
      }
      return@synchronized // Keep synchronized {} happy.
    }
  }

  override fun protocol(): Protocol = protocol!!

  override fun toString(): String {
    return "Connection{${route.address.url.host}:${route.address.url.port}," +
        " proxy=${route.proxy}" +
        " hostAddress=${route.socketAddress}" +
        " cipherSuite=${handshake?.cipherSuite ?: "none"}" +
        " protocol=$protocol}"
  }

  companion object {
    private const val NPE_THROW_WITH_NULL = "throw with null exception"
    private const val MAX_TUNNEL_ATTEMPTS = 21
    internal const val IDLE_CONNECTION_HEALTHY_NS = 10_000_000_000 // 10 seconds.

    fun newTestConnection(
      connectionPool: RealConnectionPool,
      route: Route,
      socket: Socket,
      idleAtNanos: Long
    ): RealConnection {
      val result = RealConnection(connectionPool, route)
      result.socket = socket
      result.idleAtNs = idleAtNanos
      return result
    }
  }
}
