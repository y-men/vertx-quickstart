package io.vertx.kstream;

import io.vertx.core.AbstractVerticle;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.core.logging.Logger;
import io.vertx.core.logging.LoggerFactory;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.common.serialization.Serdes;
import org.apache.kafka.streams.*;
import org.apache.kafka.streams.kstream.KStream;
import org.apache.kafka.streams.kstream.Materialized;
import org.apache.kafka.streams.kstream.Serialized;
import org.apache.kafka.streams.state.KeyValueIterator;
import org.apache.kafka.streams.state.QueryableStoreTypes;
import org.apache.kafka.streams.state.ReadOnlyKeyValueStore;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;
import java.util.stream.Collectors;

/**
 * Implement the following query to extract time on side information
 * Done for a specific merchang
 * TODO Add merchant as paramemter
 * <p>
 * select tasks.merchant_id
 * , tasks.id task_id
 * , tasks.started_time
 * , way_points.id way_point_id
 * , EXTRACT (EPOCH FROM (checkout_time - checkin_time)) / 60 AS  time_on_site_wp1
 * , count(distinct task_inventories.inventory_id) task_inventories
 * , array_agg(task_inventories.name) inventory_names
 * <p>
 * from tasks
 * join way_points
 * on way_points.task_id = tasks.id
 * join task_inventories
 * on task_inventories.way_point_id = way_points.id
 * where tasks.merchant_id = 11462 and way_points.merchant_id = 11462 and task_inventories.merchant_id = 11462
 * and tasks.created_at > now() - interval '2 week'
 * <p>
 * and way_points.position=1 --only inspecting pickups
 * group by 1, 2, 3,4
 * having count(distinct task_inventories.inventory_id) >0
 * limit 100
 */
public class TimeOnSiteByInventoryVerticle extends AbstractVerticle {

  //TODO Implement sll4j logging via lombok
  private static final Logger logger = LoggerFactory.getLogger(TimeOnSiteByInventoryVerticle.class);
  private KafkaStreams streams;

  //TODO, move to config
  public static String queryableStoreName;
  public static ReadOnlyKeyValueStore readOnlyKeyValueStore;

  public static void main(String[] args) {
    Vertx vertx = Vertx.vertx();
    vertx.deployVerticle(new TimeOnSiteByInventoryVerticle());
    vertx.deployVerticle(new WebVerticle());

  }


  @Override
  public void start() throws Exception {
    //TODO Use AspectJ weaving
    logger.info(String.format(">> %s started", getClass().getTypeName()));

    // Initialize Kafka streams
    Properties properties = new Properties();
    properties.put(StreamsConfig.APPLICATION_ID_CONFIG, "bringg");
    properties.put(StreamsConfig.CLIENT_ID_CONFIG, "bringg-client");
    properties.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
    //properties.put(ConsumerConfig.GROUP_ID_CONFIG, "bringg-group");

    properties.put(StreamsConfig.BOOTSTRAP_SERVERS_CONFIG, "localhost:9092");
    properties.put(StreamsConfig.DEFAULT_KEY_SERDE_CLASS_CONFIG, Serdes.StringSerde.class);
    properties.put(StreamsConfig.DEFAULT_VALUE_SERDE_CLASS_CONFIG, io.vertx.kafka.client.serialization.VertxSerdes.JsonObjectSerde.class);
    StreamsBuilder streamsBuilder = new StreamsBuilder();

    //Consume Tasks
    KStream<String, JsonObject> tasks = streamsBuilder.stream("tasks");
    queryableStoreName =
      tasks
//      .peek((k, jsonObject) -> System.out.println(String.format("----- Task IN >>-----------------------------\n " +
//        "key: %s\n value: %s", k, jsonObject.encodePrettily())))

        // Map to each of th inventory items
        .filter((k, json) ->
          json.getJsonObject("task") != null
            &&
            json.getJsonObject("task").getJsonArray("way_points") != null
            &&
            json.getJsonObject("task").getJsonArray("task_inventories") != null
            &&
            json.getJsonObject("task").getJsonArray("way_points").getJsonObject(0).getString("checkin_time") != null
            &&
            json.getJsonObject("task").getJsonArray("way_points").getJsonObject(0).getString("checkout_time") != null


        )

        .flatMapValues((json) -> {
          String checkInTime = json.getJsonObject("task").getJsonArray("way_points").getJsonObject(0).getString("checkin_time");
          String checkOutTime = json.getJsonObject("task").getJsonArray("way_points").getJsonObject(0).getString("checkout_time");

          String inventory = "";
          try {
            inventory = json.getJsonObject("task").getJsonArray("task_inventories").getJsonObject(0).getString("name");

            // In case of empty inventory data
          } catch (IndexOutOfBoundsException e) {
          }

          // Create entry for each item
          List<JsonObject> l =
            Arrays.asList(inventory.split(",")).stream().map((s) -> {
              JsonObject j = new JsonObject();
              j.put("checkInTime", checkInTime)
                .put("checkOutTime", checkOutTime)
                .put("inventoryItem", s);
              return j;

            }).collect(Collectors.toList());
          return l;
        })
        .peek((k, jsonObject) -> System.out.println(String.format("----- Before aggregation --------------------\n " +
          "key: %s\n value: %s", k, jsonObject.encodePrettily())))

        .map((k, j) -> new KeyValue<>(j.getString("inventoryItem"),
          j.getInstant("checkOutTime").getEpochSecond() - j.getInstant("checkInTime").getEpochSecond()
        ))
        .groupByKey(Serialized.with(Serdes.String(), Serdes.Long()))
        //    .windowedBy(TimeWindows.of(1000*60*60*24*4))
        .aggregate(() -> JsonObject.mapFrom(new Quadruple(0L, 0L, 0L, "")), (k, v, t) -> {
            long sum = t.getLong("sum") + v;
            long count = t.getLong("count");
            t.put("sum", sum).put("count", ++count).put("average", sum / count).put("key", k);
            // queryTable();
            return t;
          },
          Materialized.as("items-table")
        )
        .queryableStoreName()
    ;

    streams = new KafkaStreams(streamsBuilder.build(), properties);
    streams.setUncaughtExceptionHandler((t, th) ->
    {
      logger.error(" Uncaught exception" + th.getMessage());
      th.printStackTrace();
    });
    streams.start();
    System.out.println(streams.toString());

    // Register Vert.x event bus

    vertx.eventBus().consumer("bringg", msg -> {
      System.out.println(">> TimeOnSiteByInventoryVerticle  Consumer +++++++++++++++++++++++++++++++ ");
      System.out.println(">> msg.body(): " + msg.body());
      try {
        msg.reply(queryTable());
      }catch ( Exception e){
        System.out.println("e: " + e.getMessage());
        msg.reply(">>>> error");
      }

    });
  }

  public JsonArray queryTable() {
    JsonArray jsonArray = new JsonArray();
    ReadOnlyKeyValueStore<String, JsonObject> keyValueStore =
      streams.store(queryableStoreName, QueryableStoreTypes.keyValueStore());
    System.out.println("!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!");
    KeyValueIterator<String, JsonObject> range = keyValueStore.all();
    while (range.hasNext()) {
      KeyValue<String, JsonObject> next = range.next();

      // Format the duration to human readble
      Long averageSeconds = next.value.getLong("average");
      Duration d = Duration.ofSeconds(averageSeconds);
      final long l = d.toMinutes();
      String durationReadable = String.format("%02d:%02d", l, d.minusMinutes(l).toMillis()/1000);
      next.value.put("average",durationReadable);
      System.out.println("Query entry for " + next.key + ": " + next.value);
      jsonArray.add(next.value);
    }
// close the iterator to release resources
    range.close();
    return jsonArray;
  }


  @Override
  public void stop() throws Exception {
    logger.info(String.format(">> stopping %s ", getClass().getTypeName()));

    //TODO cleanUp ?
    streams.close();
    //Terminate stream

  }


  private class Quadruple {
    private long sum, average, count;
    private String key;

    public String getKey() {
      return key;
    }

    public void setKey(String key) {
      this.key = key;
    }

    public Quadruple(long sum, long average, long count, String key) {
      this.sum = sum;
      this.average = average;
      this.count = count;
      this.key = key;
    }

    public long getCount() {
      return count;
    }

    public void setCount(long count) {
      this.count = count;
    }


    public Quadruple(long l1, long l2) {
      this.sum = l1;
      this.average = l2;
    }

    public void setSum(long sum) {
      this.sum = sum;
    }

    public void setAverage(long average) {
      this.average = average;
    }

    public long getSum() {
      return sum;
    }

    public long getAverage() {
      return average;
    }
  }
}
