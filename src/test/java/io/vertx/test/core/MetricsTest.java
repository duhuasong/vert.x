/*
 * Copyright (c) 2011-2013 The original author or authors
 *  ------------------------------------------------------
 *  All rights reserved. This program and the accompanying materials
 *  are made available under the terms of the Eclipse Public License v1.0
 *  and Apache License v2.0 which accompanies this distribution.
 *
 *      The Eclipse Public License is available at
 *      http://www.eclipse.org/legal/epl-v10.html
 *
 *      The Apache License v2.0 is available at
 *      http://www.opensource.org/licenses/apache2.0.php
 *
 *  You may elect to redistribute this code under either of these licenses.
 */

package io.vertx.test.core;

import io.vertx.core.*;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.datagram.DatagramSocket;
import io.vertx.core.eventbus.DeliveryOptions;
import io.vertx.core.eventbus.EventBus;
import io.vertx.core.eventbus.MessageConsumer;
import io.vertx.core.eventbus.ReplyFailure;
import io.vertx.core.file.FileSystem;
import io.vertx.core.http.*;
import io.vertx.core.metrics.MetricsOptions;
import io.vertx.core.net.NetSocket;
import io.vertx.core.spi.metrics.PoolMetrics;
import io.vertx.test.fakemetrics.*;
import org.junit.Test;

import java.net.InetAddress;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.hamcrest.core.Is.is;

/**
 * @author <a href="mailto:julien@julienviet.com">Julien Viet</a>
 */
public class MetricsTest extends VertxTestBase {

  private static final String ADDRESS1 = "some-address1";

  @Override
  protected VertxOptions getOptions() {
    VertxOptions options = super.getOptions();
    options.setMetricsOptions(new MetricsOptions().setEnabled(true).setFactory(new FakeMetricsFactory()));
    return options;
  }

  @Test
  public void testSendMessage() {
    testBroadcastMessage(vertx, new Vertx[]{vertx}, false, true, false);
  }

  @Test
  public void testSendMessageInCluster() {
    startNodes(2);
    testBroadcastMessage(vertices[0], new Vertx[]{vertices[1]}, false, false, true);
  }

  @Test
  public void testEventBusInitializedWithCluster() {
    startNodes(1);
    waitUntil(() -> FakeVertxMetrics.eventBus.get() != null);
  }

  @Test
  public void testEventBusInitializedLocal() {
    waitUntil(() -> FakeVertxMetrics.eventBus.get() != null);
  }

  @Test
  public void testPublishMessageToSelf() {
    testBroadcastMessage(vertx, new Vertx[]{vertx}, true, true, false);
  }

  @Test
  public void testPublishMessageToRemote() {
    startNodes(2);
    testBroadcastMessage(vertices[0], new Vertx[]{vertices[1]}, true, false, true);
  }

  @Test
  public void testPublishMessageToCluster() {
    startNodes(2);
    testBroadcastMessage(vertices[0], vertices, true, true, true);
  }

  private void testBroadcastMessage(Vertx from, Vertx[] to, boolean publish, boolean expectedLocal, boolean expectedRemote) {
    FakeEventBusMetrics eventBusMetrics = FakeMetricsBase.getMetrics(from.eventBus());
    AtomicInteger broadcastCount = new AtomicInteger();
    AtomicInteger receiveCount = new AtomicInteger();
    for (Vertx vertx : to) {
      MessageConsumer<Object> consumer = vertx.eventBus().consumer(ADDRESS1);
      consumer.completionHandler(done -> {
        assertTrue(done.succeeded());
        if (broadcastCount.incrementAndGet() == to.length) {
          String msg = TestUtils.randomAlphaString(10);
          if (publish) {
            from.eventBus().publish(ADDRESS1, msg);
          } else {
            from.eventBus().send(ADDRESS1, msg);
          }
        }
      });
      consumer.handler(msg -> {
        if (receiveCount.incrementAndGet() == to.length) {
          assertEquals(Arrays.asList(new SentMessage(ADDRESS1, publish, expectedLocal, expectedRemote)), eventBusMetrics.getSentMessages());
          testComplete();
        }
      });
    }
    await();
  }

  @Test
  public void testReceiveSentMessageFromSelf() {
    testReceiveMessageSent(vertx, vertx, true, 1);
  }

  @Test
  public void testReceiveMessageSentFromRemote() {
    startNodes(2);
    testReceiveMessageSent(vertices[0], vertices[1], false, 1);
  }

  private void testReceiveMessageSent(Vertx from, Vertx to, boolean expectedLocal, int expectedHandlers) {
    FakeEventBusMetrics eventBusMetrics = FakeMetricsBase.getMetrics(to.eventBus());
    MessageConsumer<Object> consumer = to.eventBus().consumer(ADDRESS1);
    consumer.completionHandler(done -> {
      assertTrue(done.succeeded());
      String msg = TestUtils.randomAlphaString(10);
      from.eventBus().send(ADDRESS1, msg);
    });
    consumer.handler(msg -> {
      assertEquals(Arrays.asList(new ReceivedMessage(ADDRESS1, false, expectedLocal, expectedHandlers)), eventBusMetrics.getReceivedMessages());
      testComplete();
    });
    await();
  }

  @Test
  public void testReceivePublishedMessageFromSelf() {
    testReceiveMessagePublished(vertx, vertx, true, 3);
  }

  @Test
  public void testReceiveMessagePublishedFromRemote() {
    startNodes(2);
    testReceiveMessagePublished(vertices[0], vertices[1], false, 3);
  }

  private void testReceiveMessagePublished(Vertx from, Vertx to, boolean expectedLocal, int expectedHandlers) {
    FakeEventBusMetrics eventBusMetrics = FakeMetricsBase.getMetrics(to.eventBus());
    AtomicInteger count = new AtomicInteger();
    for (int i = 0; i < expectedHandlers; i++) {
      MessageConsumer<Object> consumer = to.eventBus().consumer(ADDRESS1);
      consumer.completionHandler(done -> {
        assertTrue(done.succeeded());
        if (count.incrementAndGet() == expectedHandlers) {
          String msg = TestUtils.randomAlphaString(10);
          from.eventBus().publish(ADDRESS1, msg);
        }
      });
      int index = i;
      consumer.handler(msg -> {
        if (index == 0) {
          assertEquals(Arrays.asList(new ReceivedMessage(ADDRESS1, true, expectedLocal, expectedHandlers)), eventBusMetrics.getReceivedMessages());
          testComplete();
        }
      });
    }
    await();
  }

  @Test
  public void testReplyMessageFromSelf() {
    testReply(vertx, vertx, true, false);
  }

  @Test
  public void testReplyMessageFromRemote() {
    startNodes(2);
    testReply(vertices[0], vertices[1], false, true);
  }

  private void testReply(Vertx from, Vertx to, boolean expectedLocal, boolean expectedRemote) {
    FakeEventBusMetrics fromMetrics = FakeMetricsBase.getMetrics(from.eventBus());
    FakeEventBusMetrics toMetrics = FakeMetricsBase.getMetrics(to.eventBus());
    MessageConsumer<Object> consumer = to.eventBus().consumer(ADDRESS1);
    consumer.completionHandler(done -> {
      assertTrue(done.succeeded());
      String msg = TestUtils.randomAlphaString(10);
      from.eventBus().send(ADDRESS1, msg, reply -> {
        assertEquals(1, fromMetrics.getReceivedMessages().size());
        ReceivedMessage receivedMessage = fromMetrics.getReceivedMessages().get(0);
        assertEquals(false, receivedMessage.publish);
        assertEquals(expectedLocal, receivedMessage.local);
        assertEquals(1, receivedMessage.handlers);
        assertEquals(1, toMetrics.getSentMessages().size());
        SentMessage sentMessage = toMetrics.getSentMessages().get(0);
        assertEquals(false, sentMessage.publish);
        assertEquals(expectedLocal, sentMessage.local);
        assertEquals(expectedRemote, sentMessage.remote);
        assertEquals(sentMessage.address, receivedMessage.address);
        testComplete();
      });
    });
    consumer.handler(msg -> {
      toMetrics.getReceivedMessages().clear();
      toMetrics.getSentMessages().clear();
      msg.reply(TestUtils.randomAlphaString(10));
    });
    await();
  }

  @Test
  public void testHandlerRegistration() throws Exception {
    FakeEventBusMetrics metrics = FakeMetricsBase.getMetrics(vertx.eventBus());
    MessageConsumer<Object> consumer = vertx.eventBus().consumer(ADDRESS1, msg -> {
    });
    CountDownLatch latch = new CountDownLatch(1);
    consumer.completionHandler(ar -> {
      assertTrue(ar.succeeded());
      latch.countDown();
    });
    awaitLatch(latch);
    assertEquals(1, metrics.getRegistrations().size());
    HandlerMetric registration = metrics.getRegistrations().get(0);
    assertEquals(ADDRESS1, registration.address);
    assertEquals(null, registration.repliedAddress);
    consumer.unregister(ar -> {
      assertTrue(ar.succeeded());
      assertEquals(0, metrics.getRegistrations().size());
      testComplete();
    });
    await();
  }

  @Test
  public void testHandlerProcessMessage() {
    testHandlerProcessMessage(vertx, vertx, 1);
  }

  @Test
  public void testHandlerProcessMessageFromRemote() {
    startNodes(2);
    testHandlerProcessMessage(vertices[0], vertices[1], 0);
  }

  private HandlerMetric assertRegistration(FakeEventBusMetrics metrics) {
    Optional<HandlerMetric> registration = metrics.getRegistrations().stream().filter(reg -> reg.address.equals(ADDRESS1)).findFirst();
    assertTrue(registration.isPresent());
    return registration.get();
  }

  private void testHandlerProcessMessage(Vertx from, Vertx to, int expectedLocalCount) {
    FakeEventBusMetrics metrics = FakeMetricsBase.getMetrics(to.eventBus());
    CountDownLatch latch1 = new CountDownLatch(1);
    CountDownLatch latch2 = new CountDownLatch(1);
    to.runOnContext(v -> {
      to.eventBus().consumer(ADDRESS1, msg -> {
        HandlerMetric registration = assertRegistration(metrics);
        assertEquals(ADDRESS1, registration.address);
        assertEquals(null, registration.repliedAddress);
        assertEquals(1, registration.scheduleCount.get());
        assertEquals(expectedLocalCount, registration.localScheduleCount.get());
        assertEquals(1, registration.beginCount.get());
        assertEquals(0, registration.endCount.get());
        assertEquals(0, registration.failureCount.get());
        assertEquals(expectedLocalCount, registration.localBeginCount.get());
        msg.reply("pong");
      }).completionHandler(onSuccess(v2 -> {
        to.runOnContext(v3 -> {
          latch1.countDown();
          try {
            awaitLatch(latch2);
          } catch (InterruptedException e) {
            fail(e);
          }
        });
      }));
    });
    try {
      awaitLatch(latch1);
    } catch (InterruptedException e) {
      fail(e);
      return;
    }
    HandlerMetric registration = assertRegistration(metrics);
    assertEquals(ADDRESS1, registration.address);
    assertEquals(null, registration.repliedAddress);
    from.eventBus().send(ADDRESS1, "ping", reply -> {
      assertEquals(1, registration.scheduleCount.get());
      assertEquals(1, registration.beginCount.get());
      // This might take a little time
      waitUntil(() -> 1 == registration.endCount.get());
      assertEquals(0, registration.failureCount.get());
      assertEquals(expectedLocalCount, registration.localBeginCount.get());
      testComplete();
    });
    waitUntil(() -> registration.scheduleCount.get() == 1);
    assertEquals(0, registration.beginCount.get());
    latch2.countDown();
    await();
  }

  @Test
  public void testHandlerProcessMessageFailure() throws Exception {
    FakeEventBusMetrics metrics = FakeMetricsBase.getMetrics(vertx.eventBus());
    MessageConsumer<Object> consumer = vertx.eventBus().consumer(ADDRESS1, msg -> {
      assertEquals(1, metrics.getReceivedMessages().size());
      HandlerMetric registration = metrics.getRegistrations().get(0);
      assertEquals(1, registration.scheduleCount.get());
      assertEquals(1, registration.beginCount.get());
      assertEquals(0, registration.endCount.get());
      assertEquals(0, registration.failureCount.get());
      throw new RuntimeException();
    });
    CountDownLatch latch = new CountDownLatch(1);
    consumer.completionHandler(ar -> {
      assertTrue(ar.succeeded());
      latch.countDown();
    });
    awaitLatch(latch);
    vertx.eventBus().send(ADDRESS1, "ping");
    assertEquals(1, metrics.getReceivedMessages().size());
    HandlerMetric registration = metrics.getRegistrations().get(0);
    long now = System.currentTimeMillis();
    while (registration.failureCount.get() < 1 && (System.currentTimeMillis() - now) < 10 * 1000) {
      Thread.sleep(10);
    }
    assertEquals(1, registration.scheduleCount.get());
    assertEquals(1, registration.beginCount.get());
    assertEquals(1, registration.endCount.get());
    assertEquals(1, registration.failureCount.get());
  }

  @Test
  public void testHandlerMetricReply() throws Exception {
    CountDownLatch latch = new CountDownLatch(1);
    FakeEventBusMetrics metrics = FakeMetricsBase.getMetrics(vertx.eventBus());
    vertx.eventBus().consumer(ADDRESS1, msg -> {
      assertEquals(ADDRESS1, metrics.getRegistrations().get(0).address);
      waitUntil(() -> metrics.getRegistrations().size() == 2);
      HandlerMetric registration = metrics.getRegistrations().get(1);
      assertEquals(ADDRESS1, registration.repliedAddress);
      assertEquals(0, registration.scheduleCount.get());
      assertEquals(0, registration.beginCount.get());
      assertEquals(0, registration.endCount.get());
      assertEquals(0, registration.localBeginCount.get());
      msg.reply("pong");
    }).completionHandler(ar -> {
      assertTrue(ar.succeeded());
      latch.countDown();
    });
    awaitLatch(latch);
    vertx.eventBus().send(ADDRESS1, "ping", reply -> {
      assertEquals(ADDRESS1, metrics.getRegistrations().get(0).address);
      HandlerMetric registration = metrics.getRegistrations().get(1);
      assertEquals(ADDRESS1, registration.repliedAddress);
      assertEquals(1, registration.scheduleCount.get());
      assertEquals(1, registration.beginCount.get());
      assertEquals(0, registration.endCount.get());
      assertEquals(1, registration.localBeginCount.get());
      vertx.runOnContext(v -> {
        assertEquals(ADDRESS1, metrics.getRegistrations().get(0).address);
        assertEquals(ADDRESS1, registration.repliedAddress);
        assertEquals(1, registration.scheduleCount.get());
        assertEquals(1, registration.beginCount.get());
        assertEquals(1, registration.endCount.get());
        assertEquals(1, registration.localBeginCount.get());
      });
      testComplete();
    });
    await();
  }

  @Test
  public void testBytesCodec() throws Exception {
    startNodes(2);
    FakeEventBusMetrics fromMetrics = FakeMetricsBase.getMetrics(vertices[0].eventBus());
    FakeEventBusMetrics toMetrics = FakeMetricsBase.getMetrics(vertices[1].eventBus());
    vertices[1].eventBus().consumer(ADDRESS1, msg -> {
      int encoded = fromMetrics.getEncodedBytes(ADDRESS1);
      int decoded = toMetrics.getDecodedBytes(ADDRESS1);
      assertTrue("Expected to have more " + encoded + " > 1000 encoded bytes", encoded > 1000);
      assertTrue("Expected to have more " + decoded + " > 1000 decoded bytes", decoded > 1000);
      testComplete();
    }).completionHandler(ar -> {
      assertTrue(ar.succeeded());
      assertEquals(0, fromMetrics.getEncodedBytes(ADDRESS1));
      assertEquals(0, toMetrics.getDecodedBytes(ADDRESS1));
      vertices[0].eventBus().send(ADDRESS1, Buffer.buffer(new byte[1000]));
    });
    await();
  }

  @Test
  public void testReplyFailureNoHandlers() throws Exception {
    CountDownLatch latch = new CountDownLatch(1);
    EventBus eb = vertx.eventBus();
    eb.send(ADDRESS1, "bar", new DeliveryOptions().setSendTimeout(10), ar -> {
      assertTrue(ar.failed());
      latch.countDown();
    });
    awaitLatch(latch);
    FakeEventBusMetrics metrics = FakeMetricsBase.getMetrics(eb);
    assertEquals(Collections.singletonList(ADDRESS1), metrics.getReplyFailureAddresses());
    assertEquals(Collections.singletonList(ReplyFailure.NO_HANDLERS), metrics.getReplyFailures());
  }

  @Test
  public void testReplyFailureTimeout1() throws Exception {
    CountDownLatch latch = new CountDownLatch(1);
    EventBus eb = vertx.eventBus();
    FakeEventBusMetrics metrics = FakeMetricsBase.getMetrics(eb);
    eb.consumer(ADDRESS1, msg -> {
      // Do not reply
    });
    eb.send(ADDRESS1, "bar", new DeliveryOptions().setSendTimeout(10), ar -> {
      assertTrue(ar.failed());
      latch.countDown();
    });
    awaitLatch(latch);
    assertEquals(1, metrics.getReplyFailureAddresses().size());
    assertEquals(Collections.singletonList(ReplyFailure.TIMEOUT), metrics.getReplyFailures());
  }

  @Test
  public void testReplyFailureTimeout2() throws Exception {
    CountDownLatch latch = new CountDownLatch(1);
    EventBus eb = vertx.eventBus();
    eb.consumer(ADDRESS1, msg -> {
      msg.reply("juu", new DeliveryOptions().setSendTimeout(10), ar -> {
        assertTrue(ar.failed());
        latch.countDown();
      });
    });
    eb.send(ADDRESS1, "bar", ar -> {
      // Do not reply
    });
    awaitLatch(latch);
    FakeEventBusMetrics metrics = FakeMetricsBase.getMetrics(eb);
    assertEquals(1, metrics.getReplyFailureAddresses().size());
    assertEquals(Collections.singletonList(ReplyFailure.TIMEOUT), metrics.getReplyFailures());
  }

  @Test
  public void testReplyFailureRecipientFailure() throws Exception {
    CountDownLatch latch = new CountDownLatch(1);
    EventBus eb = vertx.eventBus();
    FakeEventBusMetrics metrics = FakeMetricsBase.getMetrics(eb);
    AtomicReference<String> replyAddress = new AtomicReference<>();
    CountDownLatch regLatch = new CountDownLatch(1);
    eb.consumer("foo", msg -> {
      replyAddress.set(msg.replyAddress());
      msg.fail(0, "whatever");
    }).completionHandler(onSuccess(v -> {
      regLatch.countDown();
    }));
    awaitLatch(regLatch);
    eb.send("foo", "bar", new DeliveryOptions(), ar -> {
      assertTrue(ar.failed());
      latch.countDown();
    });
    awaitLatch(latch);
    assertEquals(Collections.singletonList(replyAddress.get()), metrics.getReplyFailureAddresses());
    assertEquals(Collections.singletonList(ReplyFailure.RECIPIENT_FAILURE), metrics.getReplyFailures());
  }

  @Test
  public void testServerWebSocket() throws Exception {
    HttpServer server = vertx.createHttpServer();
    server.websocketHandler(ws -> {
      FakeHttpServerMetrics metrics = FakeMetricsBase.getMetrics(server);
      WebSocketMetric metric = metrics.getMetric(ws);
      assertNotNull(metric);
      assertNotNull(metric.soMetric);
      ws.handler(buffer -> {
        ws.close();
      });
      ws.closeHandler(closed -> {
        assertNull(metrics.getMetric(ws));
        testComplete();
      });
    });
    server.listen(HttpTestBase.DEFAULT_HTTP_PORT, HttpTestBase.DEFAULT_HTTP_HOST, ar -> {
      assertTrue(ar.succeeded());
      HttpClient client = vertx.createHttpClient();
      client.websocket(HttpTestBase.DEFAULT_HTTP_PORT, HttpTestBase.DEFAULT_HTTP_HOST, "/", ws -> {
        ws.write(Buffer.buffer("wibble"));
      });
    });
    await();
  }

  @Test
  public void testServerWebSocketUpgrade() throws Exception {
    HttpServer server = vertx.createHttpServer();
    server.requestHandler(req -> {
      FakeHttpServerMetrics metrics = FakeMetricsBase.getMetrics(server);
      assertNotNull(metrics.getMetric(req));
      ServerWebSocket ws = req.upgrade();
      assertNull(metrics.getMetric(req));
      WebSocketMetric metric = metrics.getMetric(ws);
      assertNotNull(metric);
      assertNotNull(metric.soMetric);
      ws.handler(buffer -> {
        ws.close();
      });
      ws.closeHandler(closed -> {
        assertNull(metrics.getMetric(ws));
        testComplete();
      });
    });
    server.listen(HttpTestBase.DEFAULT_HTTP_PORT, HttpTestBase.DEFAULT_HTTP_HOST, ar -> {
      assertTrue(ar.succeeded());
      HttpClient client = vertx.createHttpClient();
      client.websocket(HttpTestBase.DEFAULT_HTTP_PORT, HttpTestBase.DEFAULT_HTTP_HOST, "/", ws -> {
        ws.write(Buffer.buffer("wibble"));
      });
    });
    await();
  }

  @Test
  public void testWebSocket() throws Exception {
    HttpServer server = vertx.createHttpServer();
    server.websocketHandler(ws -> {
      ws.write(Buffer.buffer("wibble"));
    });
    server.listen(HttpTestBase.DEFAULT_HTTP_PORT, HttpTestBase.DEFAULT_HTTP_HOST, ar -> {
      assertTrue(ar.succeeded());
      HttpClient client = vertx.createHttpClient();
      client.websocket(HttpTestBase.DEFAULT_HTTP_PORT, HttpTestBase.DEFAULT_HTTP_HOST, "/", ws -> {
        FakeHttpClientMetrics metrics = FakeMetricsBase.getMetrics(client);
        WebSocketMetric metric = metrics.getMetric(ws);
        assertNotNull(metric);
        assertNotNull(metric.soMetric);
        ws.closeHandler(closed -> {
          assertNull(metrics.getMetric(ws));
          testComplete();
        });
        ws.handler(buffer -> {
          ws.close();
        });
      });
    });
    await();
  }

  @Test
  public void testHttpClientName() throws Exception {
    HttpClient client1 = vertx.createHttpClient();
    FakeHttpClientMetrics metrics1 = FakeMetricsBase.getMetrics(client1);
    assertEquals("", metrics1.getName());
    String name = TestUtils.randomAlphaString(10);
    HttpClient client2 = vertx.createHttpClient(new HttpClientOptions().setMetricsName(name));
    FakeHttpClientMetrics metrics2 = FakeMetricsBase.getMetrics(client2);
    assertEquals(name, metrics2.getName());
  }

  @Test
  public void testHttpClientMetricsQueueLength() throws Exception {
    HttpServer server = vertx.createHttpServer();
    List<Runnable> requests = Collections.synchronizedList(new ArrayList<>());
    server.requestHandler(req -> {
      requests.add(() -> {
        vertx.runOnContext(v -> {
          req.response().end();
        });
      });
    });
    CountDownLatch listenLatch = new CountDownLatch(1);
    server.listen(8080, "localhost", onSuccess(s -> { listenLatch.countDown(); }));
    awaitLatch(listenLatch);
    HttpClient client = vertx.createHttpClient();
    FakeHttpClientMetrics metrics = FakeHttpClientMetrics.getMetrics(client);
    CountDownLatch responsesLatch = new CountDownLatch(5);
    for (int i = 0;i < 5;i++) {
      client.getNow(8080, "localhost", "/somepath", resp -> {
        responsesLatch.countDown();
      });
    }
    waitUntil(() -> requests.size() == 5);
    assertEquals(Collections.singleton("localhost:8080"), metrics.endpoints());
    assertEquals(0, (int)metrics.queueSize("localhost:8080"));
    assertEquals(5, (int)metrics.connectionCount("localhost:8080"));
    for (int i = 0;i < 8;i++) {
      client.getNow(8080, "localhost", "/somepath", resp -> {
      });
    }
    assertEquals(Collections.singleton("localhost:8080"), metrics.endpoints());
    assertEquals(8, (int)metrics.queueSize("localhost:8080"));
    assertEquals(5, (int)metrics.connectionCount("localhost:8080"));
    ArrayList<Runnable> copy = new ArrayList<>(requests);
    requests.clear();
    copy.forEach(Runnable::run);
    awaitLatch(responsesLatch);
    waitUntil(() -> requests.size() == 5);
    assertEquals(Collections.singleton("localhost:8080"), metrics.endpoints());
    assertEquals(3, (int)metrics.queueSize("localhost:8080"));
    assertEquals(5, (int)metrics.connectionCount("localhost:8080"));
    copy = new ArrayList<>(requests);
    requests.clear();
    copy.forEach(Runnable::run);
    waitUntil(() -> requests.size() == 3);
    assertEquals(Collections.singleton("localhost:8080"), metrics.endpoints());
    assertEquals(0, (int)metrics.queueSize("localhost:8080"));
    assertEquals(5, (int)metrics.connectionCount("localhost:8080"));
  }

  @Test
  public void testHttpClientMetricsQueueClose() throws Exception {
    HttpServer server = vertx.createHttpServer();
    List<Runnable> requests = Collections.synchronizedList(new ArrayList<>());
    server.requestHandler(req -> {
      requests.add(() -> {
        vertx.runOnContext(v -> {
          req.connection().close();
        });
      });
    });
    CountDownLatch listenLatch = new CountDownLatch(1);
    server.listen(8080, "localhost", onSuccess(s -> { listenLatch.countDown(); }));
    awaitLatch(listenLatch);
    HttpClient client = vertx.createHttpClient();
    FakeHttpClientMetrics metrics = FakeHttpClientMetrics.getMetrics(client);
    for (int i = 0;i < 5;i++) {
      client.getNow(8080, "localhost", "/somepath", resp -> {
      });
    }
    waitUntil(() -> requests.size() == 5);
    EndpointMetric endpoint = metrics.endpoint("localhost:8080");
    assertEquals(5, endpoint.connectionCount.get());
    ArrayList<Runnable> copy = new ArrayList<>(requests);
    requests.clear();
    copy.forEach(Runnable::run);
    waitUntil(() -> metrics.endpoints().isEmpty());
    assertEquals(0, endpoint.connectionCount.get());
  }

  @Test
  public void testHttpClientConnectionCloseAfterRequestEnd() throws Exception {
    CountDownLatch started = new CountDownLatch(1);
    HttpClient client = vertx.createHttpClient();
    AtomicReference<EndpointMetric> endpointMetrics = new AtomicReference<>();
    vertx.createHttpServer().requestHandler(req -> {
      endpointMetrics.set(((FakeHttpClientMetrics)FakeHttpClientMetrics.getMetrics(client)).endpoint("localhost:8080"));
      req.response().end();
    }).listen(8080, "localhost", ar -> {
      assertTrue(ar.succeeded());
      started.countDown();
    });
    awaitLatch(started);
    CountDownLatch closed = new CountDownLatch(1);
    HttpClientRequest req = client.get(8080, "localhost", "/somepath");
    req.handler(resp -> {
      resp.endHandler(v1 -> {
        HttpConnection conn = req.connection();
        conn.closeHandler(v2 -> {
          closed.countDown();
        });
        conn.close();
      });
    });
    req.end();
    awaitLatch(closed);
    EndpointMetric val = endpointMetrics.get();
    waitUntil(() -> val.connectionCount.get() == 0);
    assertEquals(0, val.queueSize.get());
    assertEquals(0, val.requests.get());
  }

  @Test
  public void testMulti() {
    HttpServer s1 = vertx.createHttpServer();
    s1.requestHandler(req -> {
    });
    s1.listen(8080, ar1 -> {
      assertTrue(ar1.succeeded());
      HttpServer s2 = vertx.createHttpServer();
      s2.requestHandler(req -> {
        req.response().end();
      });
      s2.listen(8080, ar2 -> {
        assertTrue(ar2.succeeded());
        FakeHttpServerMetrics metrics1 = FakeMetricsBase.getMetrics(ar1.result());
        assertSame(ar1.result(), metrics1.server);
        FakeHttpServerMetrics metrics2 = FakeMetricsBase.getMetrics(ar2.result());
        assertSame(ar2.result(), metrics2.server);
        testComplete();
      });
    });
    await();
  }

  @Test
  public void testHttpConnect1() throws Exception {
    testHttpConnect("localhost", socketMetric -> assertEquals("localhost", socketMetric.remoteName));
  }

  @Test
  public void testHttpConnect2() throws Exception {
    testHttpConnect(InetAddress.getLocalHost().getHostAddress(), socketMetric -> assertEquals(socketMetric.remoteAddress.host(), socketMetric.remoteName));
  }

  private void testHttpConnect(String host, Consumer<SocketMetric> checker) {
    AtomicReference<HttpClientMetric> clientMetric = new AtomicReference<>();
    HttpServer server = vertx.createHttpServer();
    server.requestHandler(req -> {
      FakeHttpServerMetrics metrics = FakeMetricsBase.getMetrics(server);
      HttpServerMetric serverMetric = metrics.getMetric(req);
      assertNotNull(serverMetric);
      req.response().setStatusCode(200);
      req.response().setStatusMessage("Connection established");
      req.response().end();
      NetSocket so = req.netSocket();
      so.handler(req.netSocket()::write);
      so.closeHandler(v -> {
        assertNull(metrics.getMetric(req));
        assertFalse(serverMetric.socket.connected.get());
        assertEquals(5, serverMetric.socket.bytesRead.get());
        assertEquals(5, serverMetric.socket.bytesWritten.get());
        assertEquals(serverMetric.socket.remoteAddress.host(), serverMetric.socket.remoteName);
        assertFalse(clientMetric.get().socket.connected.get());
        assertEquals(5, clientMetric.get().socket.bytesRead.get());
        assertEquals(5, clientMetric.get().socket.bytesWritten.get());
        checker.accept(clientMetric.get().socket);
        testComplete();
      });
    }).listen(8080, ar1 -> {
      assertTrue(ar1.succeeded());
      HttpClient client = vertx.createHttpClient();
      HttpClientRequest request = client.request(HttpMethod.CONNECT, 8080, host, "/");
      FakeHttpClientMetrics metrics = FakeMetricsBase.getMetrics(client);
      request.handler(resp -> {
        assertEquals(200, resp.statusCode());
        clientMetric.set(metrics.getMetric(request));
        assertNotNull(clientMetric.get());
        NetSocket socket = resp.netSocket();
        socket.write(Buffer.buffer("hello"));
        socket.handler(buf -> {
          assertEquals("hello", buf.toString());
          assertNull(metrics.getMetric(request));
          socket.close();
        });
      }).end();
    });
    await();

  }

  @Test
  public void testDatagram1() throws Exception {
    testDatagram("127.0.0.1", packet -> {
      assertEquals("127.0.0.1", packet.remoteAddress.host());
      assertEquals(1234, packet.remoteAddress.port());
      assertEquals(5, packet.numberOfBytes);
    });
  }

  @Test
  public void testDatagram2() throws Exception {
    testDatagram("localhost", packet -> {
      assertEquals("localhost", packet.remoteAddress.host());
      assertEquals(1234, packet.remoteAddress.port());
      assertEquals(5, packet.numberOfBytes);
    });
  }

  private void testDatagram(String host, Consumer<PacketMetric> checker) throws Exception {
    DatagramSocket peer1 = vertx.createDatagramSocket();
    DatagramSocket peer2 = vertx.createDatagramSocket();
    CountDownLatch latch = new CountDownLatch(1);
    peer1.handler(packet -> {
      FakeDatagramSocketMetrics peer1Metrics = FakeMetricsBase.getMetrics(peer1);
      FakeDatagramSocketMetrics peer2Metrics = FakeMetricsBase.getMetrics(peer2);
      assertEquals(host, peer1Metrics.getLocalName());
      assertEquals("127.0.0.1", peer1Metrics.getLocalAddress().host());
      assertNull(peer2Metrics.getLocalAddress());
      assertEquals(1, peer1Metrics.getReads().size());
      PacketMetric read = peer1Metrics.getReads().get(0);
      assertEquals(5, read.numberOfBytes);
      assertEquals(0, peer1Metrics.getWrites().size());
      assertEquals(0, peer2Metrics.getReads().size());
      assertEquals(1, peer2Metrics.getWrites().size());
      checker.accept(peer2Metrics.getWrites().get(0));
      testComplete();
    });
    peer1.listen(1234, host, ar -> {
      assertTrue(ar.succeeded());
      latch.countDown();
    });
    awaitLatch(latch);
    peer2.send("hello", 1234, host, ar -> {
      assertTrue(ar.succeeded());
    });
    await();
  }

  @Test
  public void testThreadPoolMetricsWithExecuteBlocking() {
    Map<String, PoolMetrics> all = FakePoolMetrics.getPoolMetrics();

    FakePoolMetrics metrics = (FakePoolMetrics) all.get("vert.x-worker-thread");

    assertThat(metrics.getPoolSize(), is(getOptions().getInternalBlockingPoolSize()));
    assertThat(metrics.numberOfIdleThreads(), is(getOptions().getWorkerPoolSize()));

    Handler<Future<Void>> job = getSomeDumbTask();

    AtomicInteger counter = new AtomicInteger();
    AtomicBoolean hadWaitingQueue = new AtomicBoolean();
    AtomicBoolean hadIdle = new AtomicBoolean();
    AtomicBoolean hadRunning = new AtomicBoolean();
    for (int i = 0; i < 100; i++) {
      vertx.executeBlocking(
          job,
          ar -> {
            if (metrics.numberOfWaitingTasks() > 0) {
              hadWaitingQueue.set(true);
            }
            if (metrics.numberOfIdleThreads() > 0) {
              hadIdle.set(true);
            }
            if (metrics.numberOfRunningTasks() > 0) {
              hadRunning.set(true);
            }
            if (counter.incrementAndGet() == 100) {
              testComplete();
            }
          }
      );
    }

    await();

    assertEquals(metrics.numberOfSubmittedTask(), 100);
    assertEquals(metrics.numberOfCompletedTasks(), 100);
    assertTrue(hadIdle.get());
    assertTrue(hadWaitingQueue.get());
    assertTrue(hadRunning.get());

    assertEquals(metrics.numberOfIdleThreads(), getOptions().getWorkerPoolSize());
    assertEquals(metrics.numberOfRunningTasks(), 0);
    assertEquals(metrics.numberOfWaitingTasks(), 0);
  }

  @Test
  public void testThreadPoolMetricsWithInternalExecuteBlocking() {
    // Internal blocking thread pool is used by blocking file system actions.

    Map<String, PoolMetrics> all = FakePoolMetrics.getPoolMetrics();
    FakePoolMetrics metrics = (FakePoolMetrics) all.get("vert.x-internal-blocking");

    assertThat(metrics.getPoolSize(), is(getOptions().getInternalBlockingPoolSize()));
    assertThat(metrics.numberOfIdleThreads(), is(getOptions().getInternalBlockingPoolSize()));

    AtomicInteger counter = new AtomicInteger();
    AtomicBoolean hadWaitingQueue = new AtomicBoolean();
    AtomicBoolean hadIdle = new AtomicBoolean();
    AtomicBoolean hadRunning = new AtomicBoolean();

    FileSystem system = vertx.fileSystem();
    for (int i = 0; i < 100; i++) {
      vertx.executeBlocking(
          fut -> {
            system.readFile("afile.html", buffer -> {
              fut.complete(null);
            });
          },
          ar -> {
            if (metrics.numberOfWaitingTasks() > 0) {
              hadWaitingQueue.set(true);
            }
            if (metrics.numberOfIdleThreads() > 0) {
              hadIdle.set(true);
            }
            if (metrics.numberOfRunningTasks() > 0) {
              hadRunning.set(true);
            }
            if (counter.incrementAndGet() == 100) {
              testComplete();
            }
          }
      );
    }

    await();

    assertEquals(metrics.numberOfSubmittedTask(), 100);
    assertEquals(metrics.numberOfCompletedTasks(), 100);
    assertTrue(hadIdle.get());
    assertTrue(hadWaitingQueue.get());
    assertTrue(hadRunning.get());

    assertEquals(metrics.numberOfIdleThreads(), getOptions().getWorkerPoolSize());
    assertEquals(metrics.numberOfRunningTasks(), 0);
    assertEquals(metrics.numberOfWaitingTasks(), 0);
  }

  @Test
  public void testThreadPoolMetricsWithWorkerVerticle() {
    testWithWorkerVerticle(new DeploymentOptions().setWorker(true));
  }

  @Test
  public void testThreadPoolMetricsWithWorkerVerticleAndMultiThread() {
    testWithWorkerVerticle(new DeploymentOptions().setWorker(true).setMultiThreaded(true));
  }

  private void testWithWorkerVerticle(DeploymentOptions options) {
    AtomicInteger counter = new AtomicInteger();
    Map<String, PoolMetrics> all = FakePoolMetrics.getPoolMetrics();
    FakePoolMetrics metrics = (FakePoolMetrics) all.get("vert.x-worker-thread");

    assertThat(metrics.getPoolSize(), is(getOptions().getInternalBlockingPoolSize()));
    assertThat(metrics.numberOfIdleThreads(), is(getOptions().getWorkerPoolSize()));

    AtomicBoolean hadWaitingQueue = new AtomicBoolean();
    AtomicBoolean hadIdle = new AtomicBoolean();
    AtomicBoolean hadRunning = new AtomicBoolean();

    int count = 100;

    Verticle worker = new AbstractVerticle() {
      @Override
      public void start(Future<Void> done) throws Exception {
        vertx.eventBus().localConsumer("message", d -> {
              try {
                Thread.sleep(10);

                if (metrics.numberOfWaitingTasks() > 0) {
                  hadWaitingQueue.set(true);
                }
                if (metrics.numberOfIdleThreads() > 0) {
                  hadIdle.set(true);
                }
                if (metrics.numberOfRunningTasks() > 0) {
                  hadRunning.set(true);
                }

                if (counter.incrementAndGet() == count) {
                  testComplete();
                }

              } catch (InterruptedException e) {
                Thread.currentThread().isInterrupted();
              }
            }
        );
        done.complete();
      }
    };


    vertx.deployVerticle(worker, options, s -> {
      for (int i = 0; i < count; i++) {
        vertx.eventBus().send("message", i);
      }
    });

    await();

    // The verticle deployment is also executed on the worker thread pool
    assertEquals(metrics.numberOfSubmittedTask(), count + 1);
    assertEquals(metrics.numberOfCompletedTasks(), count + 1);
    assertTrue(hadIdle.get());
    assertTrue(hadWaitingQueue.get());
    assertTrue(hadRunning.get());

    assertEquals(metrics.numberOfIdleThreads(), getOptions().getWorkerPoolSize());
    assertEquals(metrics.numberOfRunningTasks(), 0);
    assertEquals(metrics.numberOfWaitingTasks(), 0);
  }

  @Test
  public void testThreadPoolMetricsWithNamedExecuteBlocking() {
    vertx.close(); // Close the instance automatically created
    vertx = Vertx.vertx(new VertxOptions().setMetricsOptions(new MetricsOptions().setEnabled(true).setFactory(new FakeMetricsFactory())));

    WorkerExecutor workerExec = vertx.createSharedWorkerExecutor("my-pool", 10);

    Map<String, PoolMetrics> all = FakePoolMetrics.getPoolMetrics();

    FakePoolMetrics metrics = (FakePoolMetrics) all.get("my-pool");

    assertThat(metrics.getPoolSize(), is(10));
    assertThat(metrics.numberOfIdleThreads(), is(10));

    Handler<Future<Void>> job = getSomeDumbTask();

    AtomicInteger counter = new AtomicInteger();
    AtomicBoolean hadWaitingQueue = new AtomicBoolean();
    AtomicBoolean hadIdle = new AtomicBoolean();
    AtomicBoolean hadRunning = new AtomicBoolean();
    for (int i = 0; i < 100; i++) {
      workerExec.executeBlocking(
          job,
          false,
          ar -> {
            if (metrics.numberOfWaitingTasks() > 0) {
              hadWaitingQueue.set(true);
            }
            if (metrics.numberOfIdleThreads() > 0) {
              hadIdle.set(true);
            }
            if (metrics.numberOfRunningTasks() > 0) {
              hadRunning.set(true);
            }
            if (counter.incrementAndGet() == 100) {
              testComplete();
            }
          });
    }

    await();

    assertEquals(metrics.numberOfSubmittedTask(), 100);
    assertEquals(metrics.numberOfCompletedTasks(), 100);
    assertTrue(hadIdle.get());
    assertTrue(hadWaitingQueue.get());
    assertTrue(hadRunning.get());

    assertEquals(metrics.numberOfIdleThreads(), 10);
    assertEquals(metrics.numberOfRunningTasks(), 0);
    assertEquals(metrics.numberOfWaitingTasks(), 0);
  }

  @Test
  public void testWorkerPoolClose() {
    WorkerExecutor ex1 = vertx.createSharedWorkerExecutor("ex1");
    WorkerExecutor ex1_ = vertx.createSharedWorkerExecutor("ex1");
    WorkerExecutor ex2 = vertx.createSharedWorkerExecutor("ex2");
    Map<String, PoolMetrics> all = FakePoolMetrics.getPoolMetrics();
    FakePoolMetrics metrics1 = (FakePoolMetrics) all.get("ex1");
    FakePoolMetrics metrics2 = (FakePoolMetrics) all.get("ex2");
    assertNotNull(metrics1);
    assertNotNull(metrics2);
    assertNotSame(metrics1, metrics2);
    assertFalse(metrics1.isClosed());
    assertFalse(metrics2.isClosed());
    ex1_.close();
    assertFalse(metrics1.isClosed());
    assertFalse(metrics2.isClosed());
    ex1.close();
    assertTrue(metrics1.isClosed());
    assertFalse(metrics2.isClosed());
    ex2.close();
    assertTrue(metrics1.isClosed());
    assertTrue(metrics2.isClosed());
  }

  private Handler<Future<Void>> getSomeDumbTask() {
    return (future) -> {
      try {
        Thread.sleep(50);
      } catch (InterruptedException e) {
        Thread.currentThread().isInterrupted();
      }
      future.complete(null);
    };
  }
}