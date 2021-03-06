/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.fiware.cosmos.orion.spark.connector

import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicBoolean

import io.netty.bootstrap.ServerBootstrap
import io.netty.channel.nio.NioEventLoopGroup
import io.netty.channel.socket.SocketChannel
import io.netty.channel.socket.nio.NioServerSocketChannel
import io.netty.channel.{Channel, ChannelInitializer, ChannelOption}
import io.netty.handler.codec.http.{HttpObjectAggregator, HttpServerCodec}
import io.netty.handler.logging.{LogLevel, LoggingHandler}
import org.slf4j.LoggerFactory

/**
 * Netty HTTP server
 *
 * @param callback
 * @param threadNum cpu number used by netty epoll
 * @param logLevel  netty log level
 */
class OrionHttpServer(callback: NgsiEvent => Unit,
  threadNum: Int = Runtime.getRuntime.availableProcessors(),
  logLevel: LogLevel = LogLevel.INFO
) extends ServerTrait {
  private lazy val logger = LoggerFactory.getLogger(getClass)
  private lazy val bossGroup = new NioEventLoopGroup(threadNum)
  private lazy val workerGroup = new NioEventLoopGroup
  private lazy val isRunning = new AtomicBoolean(false)
  private final val CHANNEL_OPTION = 1024
  private final val HOA = 1048576
  private var currentAddr: InetSocketAddress = _

  override def close(): Unit = {
    bossGroup.shutdownGracefully()
    workerGroup.shutdownGracefully()
    logger.info("successfully close netty server source")
  }

  def startNettyServer(
    portNotInUse: Int,
    callbackUrl: Option[String]
  ): InetSocketAddress = synchronized {

    if (!isRunning.get()) {
      val b: ServerBootstrap = new ServerBootstrap
      b
        .option[java.lang.Integer](ChannelOption.SO_BACKLOG, CHANNEL_OPTION)
        .group(bossGroup, workerGroup)
        .channel(classOf[NioServerSocketChannel])
        .handler(new LoggingHandler(logLevel))
        .childHandler(new ChannelInitializer[SocketChannel] {
          override def initChannel(ch: SocketChannel): Unit = {
            val p = ch.pipeline()
            p.addLast(new HttpServerCodec)
            p.addLast(new HttpObjectAggregator(HOA))
            p.addLast(new OrionHttpHandler(callback))
          }
        })
      val f = b.bind(portNotInUse)
      f.syncUninterruptibly()
      val ch: Channel = f.channel()
      isRunning.set(true)
      currentAddr = ch.localAddress().asInstanceOf[InetSocketAddress]
      register(currentAddr, callbackUrl)
      ch.closeFuture().sync()
      currentAddr
    } else {
      currentAddr
    }
  }
}
