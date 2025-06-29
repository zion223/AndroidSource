/*
 * Copyright (C) 2015 Square, Inc.
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
package okhttp3.internal.connection

import java.io.IOException
import java.net.Socket
import okhttp3.Address
import okhttp3.EventListener
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Route
import okhttp3.internal.assertThreadDoesntHoldLock
import okhttp3.internal.assertThreadHoldsLock
import okhttp3.internal.canReuseConnectionFor
import okhttp3.internal.closeQuietly
import okhttp3.internal.http.ExchangeCodec
import okhttp3.internal.http.RealInterceptorChain
import okhttp3.internal.http2.ConnectionShutdownException
import okhttp3.internal.http2.ErrorCode
import okhttp3.internal.http2.StreamResetException

/**
 * Attempts to find the connections for an exchange and any retries that follow. This uses the
 * following strategies:
 *
 *  1. If the current call already has a connection that can satisfy the request it is used. Using
 *     the same connection for an initial exchange and its follow-ups may improve locality.
 *
 *  2. If there is a connection in the pool that can satisfy the request it is used. Note that it is
 *     possible for shared exchanges to make requests to different host names! See
 *     [RealConnection.isEligible] for details.
 *
 *  3. If there's no existing connection, make a list of routes (which may require blocking DNS
 *     lookups) and attempt a new connection them. When failures occur, retries iterate the list of
 *     available routes.
 *
 * If the pool gains an eligible connection while DNS, TCP, or TLS work is in flight, this finder
 * will prefer pooled connections. Only pooled HTTP/2 connections are used for such de-duplication.
 *
 * It is possible to cancel the finding process.
 */
class ExchangeFinder(
  private val connectionPool: RealConnectionPool,
  internal val address: Address,
  private val call: RealCall,
  private val eventListener: EventListener
) {
  private var routeSelection: RouteSelector.Selection? = null

  // State guarded by connectionPool.
  private var routeSelector: RouteSelector? = null
  private var connectingConnection: RealConnection? = null
  private var refusedStreamCount = 0
  private var connectionShutdownCount = 0
  private var otherFailureCount = 0
  private var nextRouteToTry: Route? = null

  fun find(
    client: OkHttpClient,
    chain: RealInterceptorChain
  ): ExchangeCodec {
    try {
      // 找到可用的连接
      val resultConnection = findHealthyConnection(
          connectTimeout = chain.connectTimeoutMillis,
          readTimeout = chain.readTimeoutMillis,
          writeTimeout = chain.writeTimeoutMillis,
          pingIntervalMillis = client.pingIntervalMillis,
          connectionRetryEnabled = client.retryOnConnectionFailure,
          doExtensiveHealthChecks = chain.request.method != "GET"
      )
      // 创建codec
      return resultConnection.newCodec(client, chain)
    } catch (e: RouteException) {
      trackFailure(e.lastConnectException)
      throw e
    } catch (e: IOException) {
      trackFailure(e)
      throw RouteException(e)
    }
  }

  /**
   * Finds a connection and returns it if it is healthy. If it is unhealthy the process is repeated
   * until a healthy connection is found.
   */
  @Throws(IOException::class)
  private fun findHealthyConnection(
    connectTimeout: Int,
    readTimeout: Int,
    writeTimeout: Int,
    pingIntervalMillis: Int,
    connectionRetryEnabled: Boolean,
    doExtensiveHealthChecks: Boolean
  ): RealConnection {
    while (true) {
      // 不断的死循环查找可用的连接
      val candidate = findConnection(
          connectTimeout = connectTimeout,
          readTimeout = readTimeout,
          writeTimeout = writeTimeout,
          pingIntervalMillis = pingIntervalMillis,
          connectionRetryEnabled = connectionRetryEnabled
      )

      // Confirm that the connection is good. If it isn't, take it out of the pool and start again.
      // 判断连接是否健康
      if (!candidate.isHealthy(doExtensiveHealthChecks)) {
        candidate.noNewExchanges()
        continue
      }

      return candidate
    }
  }

  /**
   * Returns a connection to host a new stream. This prefers the existing connection if it exists,
   * then the pool, finally building a new connection.
   */
  @Throws(IOException::class)
  private fun findConnection(
    connectTimeout: Int,
    readTimeout: Int,
    writeTimeout: Int,
    pingIntervalMillis: Int,
    connectionRetryEnabled: Boolean
  ): RealConnection {
    var foundPooledConnection = false
    var result: RealConnection? = null
    var selectedRoute: Route? = null
    var releasedConnection: RealConnection?
    val toClose: Socket?
    synchronized(connectionPool) {
      // 判断call是否被取消
      if (call.isCanceled()) throw IOException("Canceled")

      val callConnection = call.connection // changes within this overall method
      releasedConnection = callConnection
      // 1. 要关闭的Socket 有可用的连接 但是无法使用
      toClose = if (callConnection != null && (callConnection.noNewExchanges ||
              !sameHostAndPort(callConnection.route().address.url))) {
        // 将连接释放
        call.releaseConnectionNoEvents()
      } else {
        null
      }

      if (call.connection != null) {
        // We had an already-allocated connection and it's good.
        // 有可用的连接
        result = call.connection
        releasedConnection = null
      }

      if (result == null) {
        // The connection hasn't had any problems for this call.
        refusedStreamCount = 0
        connectionShutdownCount = 0
        otherFailureCount = 0

        // Attempt to get a connection from the pool. 
        // 2. 尝试从连接池拿连接 非多路复用
        if (connectionPool.callAcquirePooledConnection(address, call, null, false)) {
          foundPooledConnection = true
          result = call.connection
        } else if (nextRouteToTry != null) {
          // 使用暂时可用的缓存
          selectedRoute = nextRouteToTry
          nextRouteToTry = null
        }
      }
    }
    toClose?.closeQuietly()


    if (result != null) {
      // If we found an already-allocated or pooled connection, we're done.
      return result!!
    }

    // If we need a route selection, make one. This is a blocking operation.
    // RouteSelector -> Selection(val routes: List<Route>) -> Route 层级遍历
    var newRouteSelection = false
    if (selectedRoute == null && (routeSelection == null || !routeSelection!!.hasNext())) {
      var localRouteSelector = routeSelector
      if (localRouteSelector == null) {
        localRouteSelector = RouteSelector(address, call.client.routeDatabase, call, eventListener)
        this.routeSelector = localRouteSelector
      }
      newRouteSelection = true
      // 查找下一个RouteSelection
      // Selection 同一个端口 同样的代理类型 不同的Ip地址的Route集合
      /**
       * 直连类型
       *  1.2.3.4:443
       *  5.6.7.8:443
       */
      routeSelection = localRouteSelector.next()
    }

    // route  = address + proxy
    var routes: List<Route>? = null
    synchronized(connectionPool) {
      if (call.isCanceled()) throw IOException("Canceled")

      if (newRouteSelection) {
        // Now that we have a set of IP addresses, make another attempt at getting a connection from
        // the pool. This could match due to connection coalescing.

        // RouteSelector > Selection(routeSelection) > Route
        routes = routeSelection!!.routes
        // 3. 再次尝试拿取连接 可多路复用 可以拿到http2连接合并的connection
        if (connectionPool.callAcquirePooledConnection(address, call, routes, false)) {
          foundPooledConnection = true
          result = call.connection
        }
      }

      if (!foundPooledConnection) {
        if (selectedRoute == null) {
          selectedRoute = routeSelection!!.next()
        }

        // Create a connection and assign it to this allocation immediately. This makes it possible
        // for an asynchronous cancel() to interrupt the handshake we're about to do.
        // 创建连接
        result = RealConnection(connectionPool, selectedRoute!!)
        connectingConnection = result
      }
    }

    // If we found a pooled connection on the 2nd time around, we're done.
    if (foundPooledConnection) {
      return result!!
    }

    // Do TCP + TLS handshakes. This is a blocking operation.
    // 4. 手动创建连接 TCP和TLS握手
    result!!.connect(
        connectTimeout,
        readTimeout,
        writeTimeout,
        pingIntervalMillis,
        connectionRetryEnabled,
        call,
        eventListener
    )
    // 放入Database
    call.client.routeDatabase.connected(result!!.route())

    var socket: Socket? = null
    // 线程安全
    synchronized(connectionPool) {
      connectingConnection = null
      // Last attempt at connection coalescing, which only occurs if we attempted multiple
      // concurrent connections to the same host.
      // 5. 只多路复用模式再次尝试获取连接 HTTP2的连接
      if (connectionPool.callAcquirePooledConnection(address, call, routes, true)) {
        // We lost the race! Close the connection we created and return the pooled connection.
        // 销毁第四步刚创建的socket，节省资源
        result!!.noNewExchanges = true
        socket = result!!.socket()
        // 使用获取到的connection
        result = call.connection

        // It's possible for us to obtain a coalesced connection that is immediately unhealthy. In
        // that case we will retry the route we just successfully connected with.
        // 暂时缓存，下一次创建连接时 优先使用
        nextRouteToTry = selectedRoute
      } else {
        // 放入连接池
        connectionPool.put(result!!)
        call.acquireConnectionNoEvents(result!!)
      }
    }
    socket?.closeQuietly()

    return result!!
  }

  fun connectingConnection(): RealConnection? {
    connectionPool.assertThreadHoldsLock()
    return connectingConnection
  }

  fun trackFailure(e: IOException) {
    connectionPool.assertThreadDoesntHoldLock()

    synchronized(connectionPool) {
      nextRouteToTry = null
      if (e is StreamResetException && e.errorCode == ErrorCode.REFUSED_STREAM) {
        refusedStreamCount++
      } else if (e is ConnectionShutdownException) {
        connectionShutdownCount++
      } else {
        otherFailureCount++
      }
    }
  }

  /**
   * Returns true if the current route has a failure that retrying could fix, and that there's
   * a route to retry on.
   */
  fun retryAfterFailure(): Boolean {
    synchronized(connectionPool) {
      if (refusedStreamCount == 0 && connectionShutdownCount == 0 && otherFailureCount == 0) {
        return false // Nothing to recover from.
      }

      if (nextRouteToTry != null) {
        return true
      }

      if (retryCurrentRoute()) {
        // Lock in the route because retryCurrentRoute() is racy and we don't want to call it twice.
        nextRouteToTry = call.connection!!.route()
        return true
      }

      // If we have a routes left, use 'em.
      if (routeSelection?.hasNext() == true) return true

      // If we haven't initialized the route selector yet, assume it'll have at least one route.
      val localRouteSelector = routeSelector ?: return true

      // If we do have a route selector, use its routes.
      return localRouteSelector.hasNext()
    }
  }

  /**
   * Return true if the route used for the current connection should be retried, even if the
   * connection itself is unhealthy. The biggest gotcha here is that we shouldn't reuse routes from
   * coalesced connections.
   */
  private fun retryCurrentRoute(): Boolean {
    if (refusedStreamCount > 1 || connectionShutdownCount > 1 || otherFailureCount > 0) {
      return false // This route has too many problems to retry.
    }

    val connection = call.connection
    return connection != null &&
        connection.routeFailureCount == 0 &&
        connection.route().address.url.canReuseConnectionFor(address.url)
  }

  /**
   * Returns true if the host and port are unchanged from when this was created. This is used to
   * detect if followups need to do a full connection-finding process including DNS resolution, and
   * certificate pin checks.
   */
  fun sameHostAndPort(url: HttpUrl): Boolean {
    val routeUrl = address.url
    return url.port == routeUrl.port && url.host == routeUrl.host
  }
}
