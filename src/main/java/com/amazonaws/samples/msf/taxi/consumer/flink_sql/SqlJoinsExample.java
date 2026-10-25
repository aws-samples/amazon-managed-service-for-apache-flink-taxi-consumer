package com.amazonaws.samples.msf.taxi.consumer.flink_sql;

import com.amazonaws.samples.msf.taxi.consumer.utils.IntelliJRunHelper;
import com.amazonaws.samples.msf.taxi.consumer.events.EventDeserializationSchema;
import com.amazonaws.samples.msf.taxi.consumer.events.kinesis.TripEvent;
import com.amazonaws.samples.msf.taxi.consumer.utils.GeoUtils;
import com.amazonaws.samples.msf.taxi.consumer.utils.ParameterToolUtils;
import com.amazonaws.services.kinesisanalytics.runtime.KinesisAnalyticsRuntime;

import org.apache.flink.api.common.eventtime.WatermarkStrategy;
import org.apache.flink.api.common.typeinfo.Types;
import org.apache.flink.api.java.utils.ParameterTool;
import org.apache.flink.connector.kinesis.source.KinesisStreamsSource;
import org.apache.flink.streaming.api.datastream.DataStream;
import org.apache.flink.streaming.api.environment.LocalStreamEnvironment;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;
import org.apache.flink.table.api.DataTypes;
import org.apache.flink.table.api.Schema;
import org.apache.flink.table.api.Table;
import org.apache.flink.table.api.bridge.java.StreamTableEnvironment;
import org.apache.flink.types.Row;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Properties;

/**
 * Example 5: SQL Joins
 *
 * Demonstrates four join types in Flink SQL:
 * - INNER JOIN: Match pickup and dropoff events by tripId
 * - LEFT OUTER JOIN: Find pickups without matching dropoffs
 * - Interval Join: Time-bounded correlation (dropoff within 2 hours of pickup)
 * - Temporal Join: Lookup against a versioned zone_pricing reference table
 *
 * The example creates two derived views from the trips table:
 * - pickup_events: tripId, pickupLatitude, pickupLongitude, pickupDatetime
 * - dropoff_events: tripId, dropoffLatitude, dropoffLongitude, dropoffDatetime, totalAmount
 *
 * IMPORTANT: Flink streaming SQL does not support multiple independent sinks
 * in a single job, so one query runs per job.
 * Choose which query runs with the --query program argument (inner, left-outer, interval, temporal).
 */
public class SqlJoinsExample {
    private static final Logger LOG = LoggerFactory.getLogger(SqlJoinsExample.class);

    public static void main(String[] args) throws Exception {
        // 1. Create StreamExecutionEnvironment via IntelliJRunHelper
        StreamExecutionEnvironment env = IntelliJRunHelper.createEnvironment("sql-joins");

        // 2. Create StreamTableEnvironment
        StreamTableEnvironment tableEnv;
        try {
            tableEnv = StreamTableEnvironment.create(env);
        } catch (NoClassDefFoundError e) {
            LOG.error("Failed to create StreamTableEnvironment. "
                + "Ensure the following dependencies are on the classpath: "
                + "flink-table-api-java, flink-table-api-java-bridge, "
                + "flink-table-planner-loader, flink-table-runtime. "
                + "If running from IntelliJ, verify the 'add-dependencies-for-IDEA' "
                + "Maven profile is active.", e);
            return;
        }

        // 3. Resolve configuration — local (IntelliJ) vs MSF
        ParameterTool parameter;
        if (env instanceof LocalStreamEnvironment) {
            parameter = ParameterTool.fromArgs(args);
        } else {
            Map<String, Properties> applicationProperties = KinesisAnalyticsRuntime.getApplicationProperties();
            Properties flinkProperties = applicationProperties.get("FlinkApplicationProperties");
            if (flinkProperties == null) {
                throw new RuntimeException("Unable to load FlinkApplicationProperties");
            }
            parameter = ParameterToolUtils.fromApplicationProperties(flinkProperties);
        }

        String streamArn = parameter.getRequired("InputStreamArn");

        // 4. Read TripEvent DataStream from Kinesis source
        KinesisStreamsSource<TripEvent> kinesisSource = KinesisStreamsSource.<TripEvent>builder()
            .setStreamArn(streamArn)
            .setDeserializationSchema(new EventDeserializationSchema())
            .build();

        DataStream<TripEvent> tripStream = env
            .fromSource(kinesisSource,
                WatermarkStrategy.<TripEvent>forBoundedOutOfOrderness(Duration.ofSeconds(30))
                    .withTimestampAssigner((event, timestamp) -> event.dropoffDatetime.toEpochMilli()),
                "Kinesis Source")
            .returns(TripEvent.class)
            .filter(GeoUtils::hasValidCoordinates)
            .filter(trip -> trip.totalAmount > 0);

        // 5. Convert DataStream<TripEvent> to DataStream<Row> to avoid structured type
        //    conversion issues with java.time.Instant fields.
        DataStream<Row> rowStream = tripStream.map(trip -> Row.of(
                trip.tripId,
                trip.pickupLatitude,
                trip.pickupLongitude,
                trip.dropoffLatitude,
                trip.dropoffLongitude,
                trip.totalAmount,
                trip.pickupDatetime,
                trip.dropoffDatetime
            ))
            .returns(Types.ROW_NAMED(
                new String[]{"tripId", "pickupLatitude", "pickupLongitude",
                    "dropoffLatitude", "dropoffLongitude", "totalAmount",
                    "pickupDatetime", "dropoffDatetime"},
                Types.LONG, Types.DOUBLE, Types.DOUBLE,
                Types.DOUBLE, Types.DOUBLE, Types.DOUBLE,
                Types.INSTANT, Types.INSTANT));

        Table tripTable = tableEnv.fromDataStream(rowStream,
            Schema.newBuilder()
                .column("tripId", DataTypes.BIGINT())
                .column("pickupLatitude", DataTypes.DOUBLE())
                .column("pickupLongitude", DataTypes.DOUBLE())
                .column("dropoffLatitude", DataTypes.DOUBLE())
                .column("dropoffLongitude", DataTypes.DOUBLE())
                .column("totalAmount", DataTypes.DOUBLE())
                .column("pickupDatetime", DataTypes.TIMESTAMP_LTZ(3))
                .column("dropoffDatetime", DataTypes.TIMESTAMP_LTZ(3))
                .watermark("dropoffDatetime", "dropoffDatetime - INTERVAL '30' SECOND")
                .build());

        tableEnv.createTemporaryView("trips", tripTable);
        LOG.info("Registered 'trips' temporary view");

        // 6. Create derived views for join demonstrations
        //    pickup_events: one row per trip with pickup-side fields
        //    dropoff_events: one row per trip with dropoff-side fields
        //    In a real scenario these might come from separate Kinesis streams.
        tableEnv.executeSql(
            "CREATE TEMPORARY VIEW pickup_events AS "
            + "SELECT tripId, pickupLatitude, pickupLongitude, pickupDatetime "
            + "FROM trips");

        tableEnv.executeSql(
            "CREATE TEMPORARY VIEW dropoff_events AS "
            + "SELECT tripId, dropoffLatitude, dropoffLongitude, dropoffDatetime, totalAmount "
            + "FROM trips");

        LOG.info("Created pickup_events and dropoff_events derived views");

        // 7. Create a versioned zone_pricing reference table via DataStream
        //    This simulates a slowly-changing dimension table with pricing by zone.
        //    Each row has a zone_id, price_per_mile, and an update_time for versioning.
        //    The timestamps must predate the trip events (which are from ~2010) so that
        //    the temporal join can find a matching version for each trip.
        DataStream<Row> zonePricingStream = env.fromElements(
            Row.of(1, 2.50, Instant.parse("2009-01-01T00:00:00Z")),
            Row.of(2, 3.00, Instant.parse("2009-01-01T00:00:00Z")),
            Row.of(3, 2.75, Instant.parse("2009-01-01T00:00:00Z")),
            Row.of(4, 3.50, Instant.parse("2009-01-01T00:00:00Z")),
            Row.of(1, 2.75, Instant.parse("2010-01-01T00:00:00Z")),
            Row.of(2, 3.25, Instant.parse("2010-01-01T00:00:00Z")),
                Row.of(4, 8.50, Instant.parse("2010-01-04T00:00:00Z"))

                ).returns(Types.ROW_NAMED(
            new String[]{"zone_id", "price_per_mile", "update_time"},
            Types.INT, Types.DOUBLE, Types.INSTANT));

        Table zonePricingTable = tableEnv.fromDataStream(zonePricingStream,
            Schema.newBuilder()
                .column("zone_id", DataTypes.INT().notNull())
                .column("price_per_mile", DataTypes.DOUBLE())
                .column("update_time", DataTypes.TIMESTAMP_LTZ(3))
                .watermark("update_time", "update_time - INTERVAL '1' SECOND")
                .primaryKey("zone_id")
                .build());

        tableEnv.createTemporaryView("zone_pricing", zonePricingTable);
        LOG.info("Created zone_pricing versioned reference table");

        // Pick the query to run with --query <name> (default: inner).
        // Valid values: inner, left-outer, interval, temporal. One query runs per job.
        String query = parameter.get("query", "inner");
        LOG.info("Running query '{}'", query);
        switch (query) {
            // ---------------------------------------------------------------
            // --query inner : INNER JOIN — match pickups with dropoffs
            // ---------------------------------------------------------------
            case "inner": {
                // Joins pickup_events and dropoff_events on tripId. Only trips that
                // appear in both views are included in the result.
                //
                // Note: In streaming mode, a regular join keeps all state for both sides
                // indefinitely. For production use, configure state TTL to bound memory
                // (see SqlOptimizationExample).
                //
                // IMPORTANT: Regular joins on streaming tables produce update/retract
                // streams, so we use toChangelogStream() instead of toDataStream().
                Table innerJoinResult = tableEnv.sqlQuery(
                    "SELECT "
                    + "  p.tripId, "
                    + "  p.pickupLatitude, "
                    + "  p.pickupLongitude, "
                    + "  p.pickupDatetime, "
                    + "  d.dropoffLatitude, "
                    + "  d.dropoffLongitude, "
                    + "  d.dropoffDatetime, "
                    + "  d.totalAmount "
                    + "FROM pickup_events p "
                    + "INNER JOIN dropoff_events d ON p.tripId = d.tripId");
                tableEnv.toChangelogStream(innerJoinResult).print("Inner Join");
                break;
            }
            // ---------------------------------------------------------------
            // --query left-outer : LEFT OUTER JOIN — find pickups without matching dropoffs
            // ---------------------------------------------------------------
            case "left-outer": {
                // Returns all pickup events. If a pickup has no matching dropoff,
                // the dropoff-side columns will be NULL. Useful for detecting
                // incomplete trips or in-progress rides.
                Table leftJoinResult = tableEnv.sqlQuery(
                    "SELECT "
                    + "  p.tripId, "
                    + "  p.pickupLatitude, "
                    + "  p.pickupLongitude, "
                    + "  p.pickupDatetime, "
                    + "  d.dropoffLatitude, "
                    + "  d.dropoffLongitude, "
                    + "  d.dropoffDatetime, "
                    + "  d.totalAmount "
                    + "FROM pickup_events p "
                    + "LEFT OUTER JOIN dropoff_events d ON p.tripId = d.tripId");
                tableEnv.toChangelogStream(leftJoinResult).print("Left Outer Join");
                break;
            }
            // ---------------------------------------------------------------
            // --query interval : Interval Join — time-bounded correlation
            // ---------------------------------------------------------------
            case "interval": {
                // Joins pickup and dropoff events where the dropoff occurs within
                // 2 hours after the pickup. This bounds the state Flink must keep,
                // making it more efficient than a regular join for time-correlated
                // streams.
                //
                // The BETWEEN clause on timestamps defines the interval window:
                //   d.dropoffDatetime BETWEEN p.pickupDatetime
                //       AND p.pickupDatetime + INTERVAL '2' HOUR
                Table intervalJoinResult = tableEnv.sqlQuery(
                    "SELECT "
                    + "  p.tripId, "
                    + "  p.pickupLatitude, "
                    + "  p.pickupLongitude, "
                    + "  p.pickupDatetime, "
                    + "  d.dropoffLatitude, "
                    + "  d.dropoffLongitude, "
                    + "  d.dropoffDatetime, "
                    + "  d.totalAmount "
                    + "FROM pickup_events p, dropoff_events d "
                    + "WHERE p.tripId = d.tripId "
                    + "AND d.dropoffDatetime BETWEEN p.pickupDatetime "
                    + "    AND p.pickupDatetime + INTERVAL '2' HOUR");
                tableEnv.toChangelogStream(intervalJoinResult).print("Interval Join");
                break;
            }
            // ---------------------------------------------------------------
            // --query temporal : Temporal Join — lookup against versioned zone_pricing
            // ---------------------------------------------------------------
            case "temporal": {
                // Joins each trip with the zone_pricing table as of the trip's
                // dropoff time. This is a temporal join: it looks up the price
                // that was effective at the time of the event, not the latest price.
                //
                // The zone is derived from the pickup latitude (simplified mapping).
                // FOR SYSTEM_TIME AS OF correlates the event time with the
                // versioned reference table.
                Table temporalJoinResult = tableEnv.sqlQuery(
                    "SELECT "
                    + "  t.tripId, "
                    + "  t.totalAmount, "
                    + "  t.dropoffDatetime, "
                    + "  CAST(t.pickupLatitude * 10 AS INT) % 4 + 1 AS zone_id, "
                    + "  zp.price_per_mile "
                    + "FROM trips t "
                    + "JOIN zone_pricing FOR SYSTEM_TIME AS OF t.dropoffDatetime AS zp "
                    + "  ON CAST(t.pickupLatitude * 10 AS INT) % 4 + 1 = zp.zone_id");
                tableEnv.toChangelogStream(temporalJoinResult).print("Temporal Join");
                break;
            }
            default:
                throw new IllegalArgumentException(
                    "Unknown --query '" + query + "'. Valid values: inner, left-outer, interval, temporal");
        }

        LOG.info("Starting SQL Joins Example");
        env.execute("SQL Joins Example");
    }
}
