/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.alibaba.dubbo.remoting.transport.netty;

import com.alibaba.dubbo.common.Constants;
import com.alibaba.dubbo.common.URL;
import com.alibaba.dubbo.common.Version;
import com.alibaba.dubbo.common.logger.Logger;
import com.alibaba.dubbo.common.logger.LoggerFactory;
import com.alibaba.dubbo.common.utils.NamedThreadFactory;
import com.alibaba.dubbo.common.utils.NetUtils;
import com.alibaba.dubbo.remoting.ChannelHandler;
import com.alibaba.dubbo.remoting.RemotingException;
import com.alibaba.dubbo.remoting.transport.AbstractClient;

import org.jboss.netty.bootstrap.ClientBootstrap;
import org.jboss.netty.channel.Channel;
import org.jboss.netty.channel.ChannelDownstreamHandler;
import org.jboss.netty.channel.ChannelFactory;
import org.jboss.netty.channel.ChannelFuture;
import org.jboss.netty.channel.ChannelPipeline;
import org.jboss.netty.channel.ChannelPipelineFactory;
import org.jboss.netty.channel.ChannelUpstreamHandler;
import org.jboss.netty.channel.Channels;
import org.jboss.netty.channel.socket.nio.NioClientSocketChannelFactory;

import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * NettyClient.
 */
@SuppressWarnings("Duplicates")
public class NettyClient extends AbstractClient {

    private static final Logger logger = LoggerFactory.getLogger(NettyClient.class);

    // ChannelFactory's closure has a DirectMemory leak, using static to avoid
    // https://issues.jboss.org/browse/NETTY-424
    private static final ChannelFactory channelFactory = new NioClientSocketChannelFactory(
        Executors.newCachedThreadPool(new NamedThreadFactory("NettyClientBoss", true)),
        Executors.newCachedThreadPool(new NamedThreadFactory("NettyClientWorker", true)),
        Constants.DEFAULT_IO_THREADS);

    private ClientBootstrap bootstrap;

    private volatile org.jboss.netty.channel.Channel channel; // volatile, please copy reference to use

    /**
     * 实例化ChannelHandler,此类间接实现了ChannelHandler的所有方法,用于信息的处理
     *
     * @param url
     * @param handler
     * @throws RemotingException
     */
    public NettyClient(final URL url, final ChannelHandler handler) throws RemotingException {
        super(url, wrapChannelHandler(url, handler));
    }

    @Override
    protected void doOpen() {
        // 设置日志工厂
        NettyHelper.setNettyLoggerFactory();

        // 实例化 ServerBootstrap
        bootstrap = new ClientBootstrap(channelFactory);
        // 设置可选项
        // config
        // @see org.jboss.netty.channel.socket.SocketChannelConfig
        bootstrap.setOption("keepAlive", true);
        bootstrap.setOption("tcpNoDelay", true);
        bootstrap.setOption("connectTimeoutMillis", getTimeout());

        /**创建 NettyHandler 对象,其作为Netty的一个ChannelHandler，参与Channel内数据的处理
         * NettyHandler extends {@link org.jboss.netty.channel.SimpleChannelHandler} , 间接实现了 {@link ChannelUpstreamHandler},{@link ChannelDownstreamHandler}
         * ##################### 其将Netty的Channel里获取到的数据导入Dubbo体系中,至关重要 #####################
         */
        final NettyHandler nettyHandler = new NettyHandler(getUrl(), this);

        // 设置责任链路
        bootstrap.setPipelineFactory(new ChannelPipelineFactory() {
            @Override
            public ChannelPipeline getPipeline() {
                // 创建 NettyCodecAdapter 对象
                NettyCodecAdapter adapter = new NettyCodecAdapter(getCodec(), getUrl(), NettyClient.this);
                ChannelPipeline pipeline = Channels.pipeline();
                pipeline.addLast("decoder", adapter.getDecoder());  // 解码 ChannelHandler
                pipeline.addLast("encoder", adapter.getEncoder());  // 编码 ChannelHandler
                //  ##################### 处理器  , 将NettyHandler(url,this)作为Netty的一个ChannelHandler, 用于处理数据的交互  #####################
                pipeline.addLast("handler", nettyHandler);
                return pipeline;
            }
        });
    }

    /**
     * 执行连接工作,生成{@link Channel} 数据通道
     *
     * @throws Throwable
     */
    @Override
    protected void doConnect() throws Throwable {
        long start = System.currentTimeMillis();
        // 连接服务器,也就是连接Provider; 连接时仅有IP地址和端口起作用
        ChannelFuture future = bootstrap.connect(getConnectAddress());
        try {
            // 等待连接成功或者超时
            boolean ret = future.awaitUninterruptibly(getConnectTimeout(), TimeUnit.MILLISECONDS);
            // 连接成功
            if (ret && future.isSuccess()) {
                Channel newChannel = future.getChannel();
                // 设置Channel仅对可读和可写事件感兴趣 , operation_read || operation_write
                newChannel.setInterestOps(Channel.OP_READ_WRITE);
                try {
                    // 关闭老的连接
                    // Close old channel
                    Channel oldChannel = NettyClient.this.channel; // copy reference
                    if (oldChannel != null) {
                        try {
                            if (logger.isInfoEnabled()) {
                                logger.info("Close old netty channel " + oldChannel + " on create new netty channel " + newChannel);
                            }
                            oldChannel.close();
                        } finally {
                            NettyChannel.removeChannelIfDisconnected(oldChannel);
                        }
                    }
                } finally {
                    // 若 NettyClient 被关闭，关闭连接
                    if (NettyClient.this.isClosed()) {
                        try {
                            if (logger.isInfoEnabled()) {
                                logger.info("Close new netty channel " + newChannel + ", because the client closed.");
                            }
                            newChannel.close();
                        } finally {
                            NettyClient.this.channel = null;
                            NettyChannel.removeChannelIfDisconnected(newChannel);
                        }
                        // 设置新连接
                    } else {
                        NettyClient.this.channel = newChannel;
                    }
                }
                // 发生异常，抛出 RemotingException 异常
            } else if (future.getCause() != null) {
                throw new RemotingException(this, "client(url: " + getUrl() + ") failed to connect to server "
                    + getRemoteAddress() + ", error message is:" + future.getCause().getMessage(), future.getCause());
                // 无结果（连接超时），抛出 RemotingException 异常
            } else {
                throw new RemotingException(this, "client(url: " + getUrl() + ") failed to connect to server "
                    + getRemoteAddress() + " client-side timeout "
                    + getConnectTimeout() + "ms (elapsed: " + (System.currentTimeMillis() - start) + "ms) from netty client "
                    + NetUtils.getLocalHost() + " using dubbo version " + Version.getVersion());
            }
        } finally {
            // 未连接，取消任务
            if (!isConnected()) {
                future.cancel();
            }
        }
    }

    @Override
    protected void doDisConnect() {
        try {
            NettyChannel.removeChannelIfDisconnected(channel);
        } catch (Throwable t) {
            logger.warn(t.getMessage());
        }
    }

    @Override
    protected void doClose() {
        /*try {
            bootstrap.releaseExternalResources();
        } catch (Throwable t) {
            logger.warn(t.getMessage());
        }*/
    }

    /**
     * 将 {@link #channel} 封装成Dubbo提起内的Channel {@link NettyChannel}
     *
     * @return
     */
    @Override
    protected com.alibaba.dubbo.remoting.Channel getChannel() {
        Channel c = channel;
        if (c == null || !c.isConnected()) { return null; }
        return NettyChannel.getOrAddChannel(c, getUrl(), this);
    }

}