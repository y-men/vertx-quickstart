package io.vertx.kstream;

import io.vertx.core.AbstractVerticle;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import io.vertx.core.logging.Logger;
import io.vertx.core.logging.LoggerFactory;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.handler.StaticHandler;
import io.vertx.ext.web.handler.sockjs.SockJSHandler;
import io.vertx.ext.web.handler.sockjs.SockJSHandlerOptions;
import io.vertx.ext.web.handler.sockjs.SockJSSocket;
import io.vertx.ext.web.templ.ThymeleafTemplateEngine;
import io.vertx.kafka.client.consumer.KafkaConsumer;
import io.vertx.kafka.client.consumer.KafkaConsumerRecord;
import io.vertx.kafka.client.consumer.KafkaReadStream;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.common.serialization.Serdes;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.streams.KafkaStreams;
import org.apache.kafka.streams.KeyValue;
import org.apache.kafka.streams.StreamsBuilder;
import org.apache.kafka.streams.StreamsConfig;
import org.apache.kafka.streams.errors.InvalidStateStoreException;
import org.apache.kafka.streams.kstream.KStream;
import org.apache.kafka.streams.kstream.KTable;
import org.apache.kafka.streams.state.KeyValueIterator;
import org.apache.kafka.streams.state.QueryableStoreType;
import org.apache.kafka.streams.state.QueryableStoreTypes;
import org.apache.kafka.streams.state.ReadOnlyKeyValueStore;
import org.thymeleaf.Thymeleaf;

import java.util.*;

public class WebVerticle extends AbstractVerticle {
  private SockJSSocket socket;
  private static final Logger logger = LoggerFactory.getLogger(TimeOnSiteByInventoryVerticle.class);
  private KafkaStreams streams;


//  public static void main(String[] args) {
//
  //TODO Global configuration for all vertices to start together
  //TODO Start with different jvm
//    Vertx vertx = Vertx.vertx();
//    vertx.deployVerticle(new WebVerticle());
//
//  }


  @Override
  public void stop() throws Exception {
    streams.close();
  }

  @Override
  public void start(Future<Void> startFuture) throws Exception {
    logger.info(String.format(">> %s started", getClass().getTypeName()));

    // initializekafkaKTableConsumer();
    initializeKafkaConsumer();

    // Setup context
    Router router = Router.router(vertx);
    ThymeleafTemplateEngine engine = ThymeleafTemplateEngine.create();

    // --- Routing ---------------------------

    SockJSHandlerOptions options = new SockJSHandlerOptions().setHeartbeatInterval(2000);
    SockJSHandler sockJSHandler = SockJSHandler.create(vertx, options);
    sockJSHandler.socketHandler(sockJSSocket -> {

      // Create a client socker=t reference
      socket = sockJSSocket;
      sockJSSocket.write(" SockJS Ready");
      //sockJSSocket.handler(data -> sockJSSocket.write(data));
    });
    router.route("/rt/*").handler(sockJSHandler);

    // Thymeleaf templating routing
    router.route("/yo").handler(context -> {

        // Process the context
        context.put("mykey", 111);
        engine.render(context, "webroot/yo.html", result -> {

          // Handle processing result
          if (result.succeeded()) {
            context.response().end(result.result());
          } else context.fail(result.cause());
        });

      }
    );

    //Default
    router.route().handler(StaticHandler.create().setCachingEnabled(false));

    // --- Server ---------------------------
    vertx.createHttpServer()
      .requestHandler(router::accept)
      .listen(8080, http -> {
        if (http.succeeded()) {
          startFuture.complete();
          System.out.println("HTTP server started on http://localhost:8080");
        } else {
          startFuture.fail(http.cause());
        }
      });


    // --- Event Bus  ----------------------------
//    vertx.eventBus().consumer("bringg", msg ->{
//      socket.write("")
//    });

  }


  public static <T> T waitUntilStoreIsQueryable(final String storeName,
                                                final QueryableStoreType<T> queryableStoreType,
                                                final KafkaStreams streams) throws InterruptedException {
    while (true) {
      try {
        return streams.store(storeName, queryableStoreType);
      } catch (InvalidStateStoreException ignored) {
        // store not yet ready for querying
        Thread.sleep(100);
      }
    }
  }

  private void initializeKafkaConsumer() {
    // Initialize Kafka streams
    Properties properties = new Properties();
    properties.put(StreamsConfig.APPLICATION_ID_CONFIG, "bringg");
    properties.put(StreamsConfig.CLIENT_ID_CONFIG, "bringg-client");

    properties.put(ConsumerConfig.GROUP_ID_CONFIG, "bringg-group");
    properties.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");

    properties.put(StreamsConfig.BOOTSTRAP_SERVERS_CONFIG, "localhost:9092");
    properties.put(StreamsConfig.DEFAULT_KEY_SERDE_CLASS_CONFIG, Serdes.StringSerde.class);
    properties.put(StreamsConfig.DEFAULT_VALUE_SERDE_CLASS_CONFIG, io.vertx.kafka.client.serialization.VertxSerdes.JsonObjectSerde.class);
    properties.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
    properties.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, io.vertx.kafka.client.serialization.JsonObjectDeserializer.class);

//    StreamsBuilder streamsBuilder = new StreamsBuilder();

    // properties.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false");

//    streams = new KafkaStreams(streamsBuilder.build(), properties);
//    streams.setUncaughtExceptionHandler((t, th) ->
//    {
//      logger.error(" Uncaught exception" + th.getMessage());
//      th.printStackTrace();
//    });
//    streams.start();
//    System.out.println(streams.toString());

    KafkaConsumer consumer = KafkaConsumer.create(vertx, properties);
    Set<String> set = new HashSet<>();
    set.add("items-avg-table");
    set.add("tasks");
    //set.add("shifts");

    consumer.subscribe(set, ar -> {
      System.out.println("Subscribed:" + consumer);
    });


    consumer.handler(record -> {
      KafkaConsumerRecord<String, JsonObject> r = (KafkaConsumerRecord<String, JsonObject>) record;
      System.out.println(" #################################### " + r.topic());
      System.out.println("Processing key=" + r.key() + ",value=" + r.value() +
        ",partition=" + r.partition() + ",offset=" + r.offset());

      // Call bus
      vertx.eventBus().send("bringg", "queryTable", reply -> {
        if (reply.succeeded()) {
          Object body = reply.result().body();
          System.out.println("Reply >>>>> " + body);
          if (body != null && socket !=null){

            // TODO Format duration to mm:ss min:sec before writing
            socket.write(body.toString());
          }
        }

      });
    });

  }


}
