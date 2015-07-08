package com.hubspot.imap;

import com.google.common.base.Throwables;
import com.google.common.net.HostAndPort;
import com.hubspot.imap.imap.response.ListResponse;
import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.PooledByteBufAllocator;
import io.netty.channel.Channel;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.util.concurrent.DefaultEventExecutorGroup;
import io.netty.util.concurrent.EventExecutorGroup;
import io.netty.util.concurrent.Future;

import javax.net.ssl.SSLException;
import javax.net.ssl.TrustManagerFactory;
import java.io.IOException;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.util.concurrent.ExecutionException;

public class NioImapClient {
  public static void main(String[] args) throws InterruptedException, IOException, ExecutionException {

    final SslContext context;
    try {
      TrustManagerFactory trustManagerFactory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
      trustManagerFactory.init(((KeyStore) null));

      context = SslContextBuilder.forClient()
          .trustManager(trustManagerFactory)
          .build();
    } catch (NoSuchAlgorithmException|SSLException|KeyStoreException e) {
      throw Throwables.propagate(e);
    }

    EventLoopGroup eventLoopGroup = new NioEventLoopGroup();
    EventExecutorGroup eventExecutor = new DefaultEventExecutorGroup(5);

    try {
      Bootstrap bootstrap = new Bootstrap();
      bootstrap.group(eventLoopGroup)
          .channel(NioSocketChannel.class)
          .option(ChannelOption.SO_KEEPALIVE, true)
          .option(ChannelOption.ALLOCATOR, PooledByteBufAllocator.DEFAULT)
          .handler(new ImapChannelInitializer(context, HostAndPort.fromParts("imap.gmail.com", 993), 10));

      Channel channel = bootstrap.connect("imap.gmail.com", 993).sync().channel();
      ImapClient client = new ImapClient(channel, eventExecutor.next(), "zklapow@hubspot.com", "");

      Future<ListResponse> future = null;

      client.login();
      client.awaitLogin();
      client.noop();

      future = client.list("", "[Gmail]/%");
      ListResponse response = ((ListResponse) future.get());

      if (future != null) {
        future.sync();
      }

      while (Thread.currentThread().isAlive()) {
        Thread.sleep(500);
      }
    } finally {
      eventLoopGroup.shutdownGracefully();
    }
  }
}
