package com.amazonaws.samples.msf.taxi.consumer.flink_sql;

import com.amazonaws.samples.msf.taxi.consumer.utils.IntelliJRunHelper;
import com.amazonaws.samples.msf.taxi.consumer.events.EventDeserializationSchema;
import com.amazonaws.samples.msf.taxi.consumer.events.kinesis.TripEvent;
import com.amazonaws.samples.msf.taxi.consumer.flink_sql.udf.GeoHashFunction;
import com.amazonaws.samples.msf.taxi.consumer.flink_sql.udf.WeightedAvgFunction;
import com.amazonaws.samples.msf.taxi.consumer.flink_sql.udf.ZoneSplitterFunction;
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
import java.util.Map;
import java.util.Properties;

/**
 * Example 8: User-Defined Functions (UDFs)
 *
 * Demonstrates how to create and register three types of UDFs for use in Flink SQL:
 * - ScalarFunction  (GeoHashFunction)   — one input row → one output value
 * - TableFunction   (ZoneSplitterFunction) — one input row → zero or more output rows
 * - AggregateFunction (WeightedAvgFunction) — many input rows → one output value
 *
 * All three UDFs are registered programmatically via createTemporaryFunction().
 * The equivalent CREATE FUNCTION DDL syntax is shown in comments for reference.
 *
 * IMPORTANT: Flink streaming SQL does not support multiple independent sinks
 * in a single job, so one query runs per job.
 * Choose which query runs with the --query program argument (geohash, zone-split, weighted-avg).
 */
public class UdfExample {
    private static final Logger LOG = LoggerFactory.getLogger(UdfExample.class);

    public static void main(String[] args) throws Exception {
        // 1. Create StreamExecutionEnvironment via IntelliJRunHelper
        StreamExecutionEnvironment env = IntelliJRunHelper.createEnvironment("sql-udf");

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

        // =================================================================
        // 6. Register UDFs programmatically via createTemporaryFunction()
        // =================================================================
        // ScalarFunction: computes a geohash from lat/lon coordinates
        tableEnv.createTemporaryFunction("geohash", GeoHashFunction.class);

        // TableFunction: splits a trip into pickup and dropoff zone events
        tableEnv.createTemporaryFunction("zone_splitter", ZoneSplitterFunction.class);

        // AggregateFunction: computes a weighted average of (value, weight) pairs
        tableEnv.createTemporaryFunction("weighted_avg", WeightedAvgFunction.class);

        LOG.info("Registered UDFs: geohash (scalar), zone_splitter (table), weighted_avg (aggregate)");

        // =================================================================
        // CREATE FUNCTION DDL syntax — documented reference
        // =================================================================
        // The programmatic registration above is equivalent to the following
        // DDL statements. These are useful when defining UDFs in pure-SQL
        // environments or when registering functions in a catalog.
        //
        // CREATE TEMPORARY FUNCTION geohash
        //   AS 'com.amazonaws.samples.msf.taxi.consumer.flink_sql.udf.GeoHashFunction';
        //
        // CREATE TEMPORARY FUNCTION zone_splitter
        //   AS 'com.amazonaws.samples.msf.taxi.consumer.flink_sql.udf.ZoneSplitterFunction';
        //
        // CREATE TEMPORARY FUNCTION weighted_avg
        //   AS 'com.amazonaws.samples.msf.taxi.consumer.flink_sql.udf.WeightedAvgFunction';

        // Pick the query to run with --query <name> (default: geohash).
        // Valid values: geohash, zone-split, weighted-avg. One query runs per job.
        String query = parameter.get("query", "geohash");
        LOG.info("Running query '{}'", query);
        switch (query) {
            // ---------------------------------------------------------------
            // --query geohash : ScalarFunction — GeoHash
            // ---------------------------------------------------------------
            case "geohash": {
                // Uses the geohash() scalar UDF to compute a geohash string from
                // pickup coordinates. Each input row produces exactly one output value.
                Table geohashResult = tableEnv.sqlQuery(
                    "SELECT tripId, "
                    + "  pickupLatitude, "
                    + "  pickupLongitude, "
                    + "  geohash(pickupLatitude, pickupLongitude) AS pickup_hash "
                    + "FROM trips");
                tableEnv.toDataStream(geohashResult).print("GeoHash UDF");
                break;
            }
            // ---------------------------------------------------------------
            // --query zone-split : TableFunction — ZoneSplitter with LATERAL TABLE
            // ---------------------------------------------------------------
            case "zone-split": {
                // Uses the zone_splitter() table UDF with LATERAL TABLE to expand
                // each trip into two rows: one for the pickup zone and one for the
                // dropoff zone. This is the standard SQL syntax for table functions.
                Table zoneSplitResult = tableEnv.sqlQuery(
                    "SELECT t.tripId, z.zone_type, z.zone_name "
                    + "FROM trips t, "
                    + "LATERAL TABLE(zone_splitter(t.pickupLatitude, t.pickupLongitude, "
                    + "  t.dropoffLatitude, t.dropoffLongitude)) AS z(zone_type, zone_name)");
                tableEnv.toDataStream(zoneSplitResult).print("Zone Splitter UDF");
                break;
            }
            // ---------------------------------------------------------------
            // --query weighted-avg : AggregateFunction — Weighted Average
            // ---------------------------------------------------------------
            case "weighted-avg": {
                // Uses the weighted_avg() aggregate UDF to compute a distance-weighted
                // average fare per geographic zone. The weight is the trip duration in
                // minutes (longer trips contribute more to the average).
                //
                // Note: This is an unbounded aggregation — state grows indefinitely.
                // In production, configure state TTL (see SqlOptimizationExample).
                Table weightedAvgResult = tableEnv.sqlQuery(
                    "SELECT "
                    + "  CAST(pickupLatitude * 10 AS INT) AS lat_zone, "
                    + "  weighted_avg(totalAmount, "
                    + "    CAST(GREATEST(TIMESTAMPDIFF(MINUTE, pickupDatetime, dropoffDatetime), 1) AS DOUBLE)) "
                    + "    AS weighted_fare "
                    + "FROM trips "
                    + "GROUP BY CAST(pickupLatitude * 10 AS INT)");
                tableEnv.toChangelogStream(weightedAvgResult).print("Weighted Avg UDF");
                break;
            }
            default:
                throw new IllegalArgumentException(
                    "Unknown --query '" + query + "'. Valid values: geohash, zone-split, weighted-avg");
        }

        LOG.info("Starting UDF Example");
        env.execute("UDF Example");
    }
}
