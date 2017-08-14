package com.hubspot.imap;

import java.io.Closeable;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.util.concurrent.CompletableFuture;

import javax.net.ssl.SSLException;
import javax.net.ssl.TrustManagerFactory;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.base.Throwables;
import com.hubspot.imap.client.ImapClient;

import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.PooledByteBufAllocator;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelOption;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;

public class ImapClientFactory implements Closeable {
  private static final Logger LOGGER = LoggerFactory.getLogger(ImapClientFactory.class);

  private final ImapClientFactoryConfiguration configuration;
  private final SslContext sslContext;

  public ImapClientFactory() {
    this(ImapClientFactoryConfiguration.builder().build());
  }

  public ImapClientFactory(ImapClientFactoryConfiguration configuration) {
    this(configuration, null);
  }

  public ImapClientFactory(ImapClientFactoryConfiguration configuration, KeyStore keyStore) {
    this.configuration = configuration;

    try {
      TrustManagerFactory trustManagerFactory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
      trustManagerFactory.init(keyStore);

      sslContext = SslContextBuilder.forClient()
          .trustManager(trustManagerFactory)
          .build();
    } catch (NoSuchAlgorithmException | SSLException | KeyStoreException e) {
      throw Throwables.propagate(e);
    }
  }

  public CompletableFuture<ImapClient> connect(ImapClientConfiguration clientConfiguration) {
    return connect("unknown-client", clientConfiguration);
  }

  public CompletableFuture<ImapClient> connect(String clientName, ImapClientConfiguration clientConfiguration) {
    Bootstrap bootstrap = new Bootstrap().group(configuration.eventLoopGroup())
        .option(ChannelOption.SO_LINGER, clientConfiguration.soLinger())
        .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, clientConfiguration.connectTimeoutMillis())
        .option(ChannelOption.SO_KEEPALIVE, false)
        .option(ChannelOption.ALLOCATOR, PooledByteBufAllocator.DEFAULT)
        .channel(configuration.channelClass())
        .handler(clientConfiguration.useSsl() ? new ImapChannelInitializer(sslContext, clientConfiguration) : new ImapChannelInitializer(clientConfiguration));

    CompletableFuture<ImapClient> connectFuture = new CompletableFuture<>();

    bootstrap.connect(clientConfiguration.hostAndPort().getHost(), clientConfiguration.hostAndPort().getPort()).addListener(f -> {
      if (f.isSuccess()) {
        Channel channel = ((ChannelFuture) f).channel();

        ImapClient client = new ImapClient(clientConfiguration, channel, sslContext, configuration.executor(), clientName);
        configuration.executor().execute(() -> {
          connectFuture.complete(client);
        });
      } else {
        configuration.executor().execute(() -> connectFuture.completeExceptionally(f.cause()));
      }
    });

    return connectFuture;
  }

  @Override
  public void close() {
    configuration.eventLoopGroup().shutdownGracefully();
    configuration.executor().shutdownGracefully();
  }
}
