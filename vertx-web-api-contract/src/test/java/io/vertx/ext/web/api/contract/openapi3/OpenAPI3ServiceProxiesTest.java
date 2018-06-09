package io.vertx.ext.web.api.contract.openapi3;

import io.vertx.core.Handler;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.eventbus.MessageConsumer;
import io.vertx.core.http.HttpClientOptions;
import io.vertx.core.http.HttpHeaders;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpServerOptions;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import io.vertx.ext.web.WebTestWithWebClientBase;
import io.vertx.ext.web.api.contract.RouterFactoryOptions;
import io.vertx.ext.web.api.contract.openapi3.impl.OpenApi3Utils;
import io.vertx.ext.web.api.validation.ValidationException;
import io.vertx.serviceproxy.ServiceBinder;
import org.junit.Test;

import java.util.concurrent.CountDownLatch;

/**
 * This tests are about OpenAPI3RouterFactory and Service Proxy integrations
 * @author Francesco Guardiani @slinkydeveloper
 */
public class OpenAPI3ServiceProxiesTest extends WebTestWithWebClientBase {

  private OpenAPI3RouterFactory routerFactory;

  private RouterFactoryOptions HANDLERS_TESTS_OPTIONS = new RouterFactoryOptions().setRequireSecurityHandlers(false);

  private Handler<RoutingContext> generateFailureHandler(boolean expected) {
    return routingContext -> {
      Throwable failure = routingContext.failure();
      if (failure instanceof ValidationException) {
        if (!expected) {
          failure.printStackTrace();
        }
        routingContext.response().setStatusCode(400).setStatusMessage("failure:" + ((ValidationException) failure)
          .type().name()).end();
      } else {
        failure.printStackTrace();
        routingContext.response().setStatusCode(500).setStatusMessage("unknownfailure:" + failure.toString()).end();
      }
    };
  }

  private void startServer() throws InterruptedException {
    Router router = routerFactory.getRouter();
    server = vertx.createHttpServer(new HttpServerOptions().setPort(8080).setHost("localhost"));
    CountDownLatch latch = new CountDownLatch(1);
    server.requestHandler(router::accept).listen(onSuccess(res -> {
      latch.countDown();
    }));
    awaitLatch(latch);
  }

  private void stopServer() throws Exception {
    routerFactory = null;
    if (server != null) {
      CountDownLatch latch = new CountDownLatch(1);
      server.close((asyncResult) -> {
        assertTrue(asyncResult.succeeded());
        latch.countDown();
      });
      awaitLatch(latch);
    }
  }

  private void assertThrow(Runnable r, Class exception) {
    try {
      r.run();
      assertTrue(exception.getName() + " not thrown", false);
    } catch (Exception e) {
      assertTrue(exception.getName() + " not thrown. Thrown: " + e.getClass().getName(), e.getClass().equals(exception));
    }
  }

  private void assertNotThrow(Runnable r, Class exception) {
    try {
      r.run();
    } catch (Exception e) {
      assertFalse(exception.getName() + " not thrown. Thrown: " + e.getClass().getName(), e.getClass().equals(exception));
    }
  }

  private void assertNotThrow(Runnable r) {
    try {
      r.run();
    } catch (Exception e) {
      assertTrue("Exception " + e + " is thrown", false);
    }
  }

  @Override
  public void setUp() throws Exception {
    super.setUp();
    stopServer(); // Have to stop default server of WebTestBase
    client = vertx.createHttpClient(new HttpClientOptions().setDefaultPort(8080));
  }

  @Override
  public void tearDown() throws Exception {
    stopServer();
    if (client != null) {
      try {
        client.close();
      } catch (IllegalStateException e) {
      }
    }
    super.tearDown();
  }

  @Test
  public void testOperationIdSanitizer() {
    assertEquals("operationId", OpenApi3Utils.sanitizeOperationId("operationId"));
    assertEquals("operationId", OpenApi3Utils.sanitizeOperationId("operation id"));
    assertEquals("operationId", OpenApi3Utils.sanitizeOperationId("operation Id"));
    assertEquals("operationId", OpenApi3Utils.sanitizeOperationId("operation-id"));
    assertEquals("operationId", OpenApi3Utils.sanitizeOperationId("operation_id"));
    assertEquals("operationId", OpenApi3Utils.sanitizeOperationId("operation__id-"));
    assertEquals("operationId", OpenApi3Utils.sanitizeOperationId("operation_- id "));
    assertEquals("operationAB", OpenApi3Utils.sanitizeOperationId("operation_- A B"));
  }

  @Test
  public void serviceProxyManualTest() throws Exception {
    TestService service = new TestServiceImpl(vertx);

    final ServiceBinder serviceBinder = new ServiceBinder(vertx).setAddress("someAddress");
    MessageConsumer<JsonObject> consumer = serviceBinder.register(TestService.class, service);

    CountDownLatch latch = new CountDownLatch(1);
    OpenAPI3RouterFactory.create(this.vertx, "src/test/resources/swaggers/service_proxy_test.yaml",
      openAPI3RouterFactoryAsyncResult -> {
        routerFactory = openAPI3RouterFactoryAsyncResult.result();
        routerFactory.setOptions(HANDLERS_TESTS_OPTIONS);

        routerFactory.mountOperationToEventBus("testA", "someAddress");

        latch.countDown();
      });
    awaitLatch(latch);

    startServer();

    testRequestWithJSON(
      HttpMethod.POST,
      "/testA",
      new JsonObject().put("hello", "Ciao").put("name", "Francesco"),
      200,
      "OK",
      new JsonObject().put("result", "Ciao Francesco!")
    );

    consumer.unregister();
  }

  @Test
  public void serviceProxyWithReflectionsTest() throws Exception {
    TestService service = new TestServiceImpl(vertx);

    final ServiceBinder serviceBinder = new ServiceBinder(vertx).setAddress("someAddress");
    MessageConsumer<JsonObject> consumer = serviceBinder.register(TestService.class, service);

    CountDownLatch latch = new CountDownLatch(1);
    OpenAPI3RouterFactory.create(this.vertx, "src/test/resources/swaggers/service_proxy_test.yaml",
      openAPI3RouterFactoryAsyncResult -> {
        routerFactory = openAPI3RouterFactoryAsyncResult.result();
        routerFactory.setOptions(HANDLERS_TESTS_OPTIONS.setMountValidationFailureHandler(true));

        routerFactory.mountServiceProxy(service.getClass(), "someAddress");

        latch.countDown();
      });
    awaitLatch(latch);

    startServer();

    testRequestWithJSON(
      HttpMethod.POST,
      "/testA",
      new JsonObject().put("hello", "Ciao").put("name", "Francesco"),
      200,
      "OK",
      new JsonObject().put("result", "Ciao Francesco!")
    );

    testRequestWithJSON(
      HttpMethod.POST,
      "/testB",
      new JsonObject().put("hello", "Ciao").put("name", "Francesco"),
      200,
      "OK",
      new JsonObject().put("result", "Ciao Francesco?")
    );

    testRequestWithJSON(HttpMethod.POST, "/testB", new JsonObject().put("hello", "Ciao"), 400, "Bad Request", null);

    consumer.unregister();
  }

  @Test
  public void serviceProxyWithTagsTest() throws Exception {
    TestService service = new TestServiceImpl(vertx);
    final ServiceBinder serviceBinder = new ServiceBinder(vertx).setAddress("address");
    MessageConsumer<JsonObject> serviceConsumer = serviceBinder.register(TestService.class, service);

    AnotherTestService anotherService = AnotherTestService.create(vertx);
    final ServiceBinder anotherServiceBinder = new ServiceBinder(vertx).setAddress("anotherAddress");
    MessageConsumer<JsonObject> anotherServiceConsumer = anotherServiceBinder.register(AnotherTestService.class, anotherService);

    CountDownLatch latch = new CountDownLatch(1);
    OpenAPI3RouterFactory.create(this.vertx, "src/test/resources/swaggers/service_proxy_test.yaml",
      openAPI3RouterFactoryAsyncResult -> {
        routerFactory = openAPI3RouterFactoryAsyncResult.result();
        routerFactory.setOptions(HANDLERS_TESTS_OPTIONS);

        routerFactory.mountTaggedOperationsToEventBus("test", "address");
        routerFactory.mountTaggedOperationsToEventBus("anotherTest", "anotherAddress");

        latch.countDown();
      });
    awaitLatch(latch);

    startServer();

    testRequestWithJSON(
      HttpMethod.POST,
      "/testA",
      new JsonObject().put("hello", "Ciao").put("name", "Francesco"),
      200,
      "OK",
      new JsonObject().put("result", "Ciao Francesco!")
    );

    testRequestWithJSON(
      HttpMethod.POST,
      "/testB",
      new JsonObject().put("hello", "Ciao").put("name", "Francesco"),
      200,
      "OK",
      new JsonObject().put("result", "Ciao Francesco?")
    );

    testRequestWithJSON(
      HttpMethod.POST,
      "/testC",
      new JsonObject().put("hello", "Ciao").put("name", "Francesco"),
      200,
      "OK",
      new JsonObject().put("anotherResult", "Francesco Ciao!")
    );

    testRequestWithJSON(
      HttpMethod.POST,
      "/testD",
      new JsonObject().put("hello", "Ciao").put("name", "Francesco"),
      200,
      "OK",
      new JsonObject().put("content-type", "application/json").put("anotherResult", "Francesco Ciao?")
    );

    serviceConsumer.unregister();
    anotherServiceConsumer.unregister();
  }

  @Test
  public void serviceProxyWithExtensionsTest() throws Exception {
    TestService service = new TestServiceImpl(vertx);
    final ServiceBinder serviceBinder = new ServiceBinder(vertx).setAddress("address");
    MessageConsumer<JsonObject> serviceConsumer = serviceBinder.register(TestService.class, service);

    AnotherTestService anotherService = AnotherTestService.create(vertx);
    final ServiceBinder anotherServiceBinder = new ServiceBinder(vertx).setAddress("anotherAddress");
    MessageConsumer<JsonObject> anotherServiceConsumer = anotherServiceBinder.register(AnotherTestService.class, anotherService);

    CountDownLatch latch = new CountDownLatch(1);
    OpenAPI3RouterFactory.create(this.vertx, "src/test/resources/swaggers/service_proxy_test.yaml",
      openAPI3RouterFactoryAsyncResult -> {
        routerFactory = openAPI3RouterFactoryAsyncResult.result();
        routerFactory.setOptions(HANDLERS_TESTS_OPTIONS);

        routerFactory.mountOperationsToEventBusFromExtensions();

        latch.countDown();
      });
    awaitLatch(latch);

    startServer();

    testRequestWithJSON(
      HttpMethod.POST,
      "/testA",
      new JsonObject().put("hello", "Ciao").put("name", "Francesco"),
      200,
      "OK",
      new JsonObject().put("result", "Ciao Francesco!")
    );

    testRequestWithJSON(
      HttpMethod.POST,
      "/testB",
      new JsonObject().put("hello", "Ciao").put("name", "Francesco"),
      200,
      "OK",
      new JsonObject().put("result", "Ciao Francesco?")
    );

    testRequestWithJSON(
      HttpMethod.POST,
      "/testC",
      new JsonObject().put("hello", "Ciao").put("name", "Francesco"),
      200,
      "OK",
      new JsonObject().put("content-type", "application/json").put("anotherResult", "Francesco Ciao?")
    );

    testRequestWithJSON(
      HttpMethod.POST,
      "/testD",
      new JsonObject().put("hello", "Ciao").put("name", "Francesco"),
      200,
      "OK",
      new JsonObject().put("content-type", "application/json").put("anotherResult", "Francesco Ciao?")
    );

    serviceConsumer.unregister();
    anotherServiceConsumer.unregister();
  }

  @Test
  public void serviceProxyManualFailureTest() throws Exception {
    FailureTestService service = new FailureTestServiceImpl(vertx);

    final ServiceBinder serviceBinder = new ServiceBinder(vertx).setAddress("someAddress");
    MessageConsumer<JsonObject> consumer = serviceBinder.register(FailureTestService.class, service);

    CountDownLatch latch = new CountDownLatch(1);
    OpenAPI3RouterFactory.create(this.vertx, "src/test/resources/swaggers/service_proxy_test.yaml",
      openAPI3RouterFactoryAsyncResult -> {
        routerFactory = openAPI3RouterFactoryAsyncResult.result();
        routerFactory.setOptions(HANDLERS_TESTS_OPTIONS);

        routerFactory.mountOperationToEventBus("testFailure", "someAddress");
        routerFactory.addFailureHandlerByOperationId("testFailure", routingContext -> {
          routingContext.response().setStatusCode(501).setStatusMessage(routingContext.failure().getMessage()).end();
        });
        routerFactory.mountOperationToEventBus("testException", "someAddress");

        latch.countDown();
      });
    awaitLatch(latch);

    startServer();

    testRequestWithJSON(
      HttpMethod.POST,
      "/testFailure",
      new JsonObject().put("hello", "Ciao").put("name", "Francesco"),
      501,
      "error for Francesco",
      null
    );

    testRequestWithJSON(
      HttpMethod.POST,
      "/testException",
      new JsonObject().put("hello", "Ciao").put("name", "Francesco"),
      500,
      "Internal Server Error",
      null
    );

    consumer.unregister();
  }

  @Test
  public void serviceProxyTypedTest() throws Exception {
    AnotherTestService service = new AnotherTestServiceImpl(vertx);

    final ServiceBinder serviceBinder = new ServiceBinder(vertx).setAddress("someAddress");
    MessageConsumer<JsonObject> consumer = serviceBinder.register(AnotherTestService.class, service);

    CountDownLatch latch = new CountDownLatch(1);
    OpenAPI3RouterFactory.create(this.vertx, "src/test/resources/swaggers/service_proxy_test.yaml",
      openAPI3RouterFactoryAsyncResult -> {
        routerFactory = openAPI3RouterFactoryAsyncResult.result();
        routerFactory.setOptions(HANDLERS_TESTS_OPTIONS);

        routerFactory.mountServiceProxy(AnotherTestService.class, "someAddress");

        latch.countDown();
      });
    awaitLatch(latch);

    startServer();

    testRequestWithJSON(
      HttpMethod.POST,
      "/testE/123",
      new JsonObject().put("value", 1),
      200,
      "OK",
      new JsonObject().put("id", 123).put("value", 1)
    );

    testRequestWithJSONArray(
      HttpMethod.POST,
      "/testF/123",
      new JsonArray().add(1).add(2).add(3),
      200,
      "OK",
      new JsonArray().add(1 + 123).add(2 + 123).add(3 + 123)
    );

    testRequestWithJSON(
      HttpMethod.POST,
      "/testF/123",
      new JsonObject().put("value", 1),
      200,
      "OK",
      new JsonObject().put("id", 123).put("value", 1)
    );

    consumer.unregister();
  }

  @Test
  public void serviceProxyDataObjectTest() throws Exception {
    AnotherTestService service = new AnotherTestServiceImpl(vertx);

    final ServiceBinder serviceBinder = new ServiceBinder(vertx).setAddress("someAddress");
    MessageConsumer<JsonObject> consumer = serviceBinder.register(AnotherTestService.class, service);

    CountDownLatch latch = new CountDownLatch(1);
    OpenAPI3RouterFactory.create(this.vertx, "src/test/resources/swaggers/service_proxy_test.yaml",
      openAPI3RouterFactoryAsyncResult -> {
        routerFactory = openAPI3RouterFactoryAsyncResult.result();
        routerFactory.setOptions(HANDLERS_TESTS_OPTIONS);

        routerFactory.mountServiceProxy(AnotherTestService.class, "someAddress");

        latch.countDown();
      });
    awaitLatch(latch);

    startServer();

    FilterData data = FilterData.generate();

    JsonObject result = data.toJson().copy();
    result.remove("message");

    testRequestWithJSON(
      HttpMethod.POST,
      "/testDataObject",
      data.toJson(),
      200,
      "OK",
      result
    );

    consumer.unregister();
  }
}
