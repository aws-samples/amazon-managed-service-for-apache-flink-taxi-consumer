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
import java.util.Map;
import java.util.Properties;

/**
 * Example 3: SQL DML Queries and Aggregations
 *
 * Demonstrates how to:
 * - SELECT with WHERE filters (fare thresholds, geographic bounds)
 * - GROUP BY with aggregate functions (COUNT, SUM, AVG, MIN, MAX)
 * - HAVING clause to filter aggregated groups
 * - DISTINCT for deduplication of trip records
 *
 * IMPORTANT: Flink streaming SQL does not support multiple independent sinks
 * in a single job, so one query runs per job.
 * Choose which query runs with the --query program argument (filter, group-by, having, distinct).
 */
public class SqlDmlAggregationsExample {
    private static final Logger LOG = LoggerFactory.getLogger(SqlDmlAggregationsExample.class);

    public static void main(String[] args) throws Exception {
        // 1. Create StreamExecutionEnvironment via IntelliJRunHelper
        StreamExecutionEnvironment env = IntelliJRunHelper.createEnvironment("sql-dml-aggregations");

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

        // Pick the query to run with --query <name> (default: filter).
        // Valid values: filter, group-by, having, distinct. One query runs per job.
        String query = parameter.get("query", "filter");
        LOG.info("Running query '{}'", query);
        switch (query) {
            // ---------------------------------------------------------------
            // --query filter : SELECT with WHERE filters
            // ---------------------------------------------------------------
            case "filter": {
                // Filter for high-fare trips (totalAmount > 50) within Manhattan bounds.
                // Demonstrates combining a fare threshold with geographic bounding box.
                Table filteredResult = tableEnv.sqlQuery(
                    "SELECT tripId, totalAmount, pickupLatitude, pickupLongitude "
                    + "FROM trips "
                    + "WHERE totalAmount > 50 "
                    + "AND pickupLatitude BETWEEN 40.70 AND 40.82 "
                    + "AND pickupLongitude BETWEEN -74.02 AND -73.93");
                tableEnv.toDataStream(filteredResult).print("Filtered Trips");
                break;
            }
            // ---------------------------------------------------------------
            // --query group-by : GROUP BY with aggregate functions
            // ---------------------------------------------------------------
            case "group-by": {
                // Groups trips by a latitude-based geographic zone and computes
                // COUNT, SUM, AVG, MIN, MAX on totalAmount.
                //
                // Note: This is an unbounded aggregation — state grows indefinitely.
                // In production, configure state TTL (see SqlOptimizationExample).
                //
                // IMPORTANT: GROUP BY produces an update stream (counts change as new
                // events arrive), so we use toChangelogStream() instead of toDataStream().
                // toDataStream() only supports append-only results.
                Table aggResult = tableEnv.sqlQuery(
                    "SELECT "
                    + "  CAST(pickupLatitude * 10 AS INT) AS lat_zone, "
                    + "  COUNT(*) AS trip_count, "
                    + "  SUM(totalAmount) AS total_fare, "
                    + "  AVG(totalAmount) AS avg_fare, "
                    + "  MIN(totalAmount) AS min_fare, "
                    + "  MAX(totalAmount) AS max_fare "
                    + "FROM trips "
                    + "GROUP BY CAST(pickupLatitude * 10 AS INT)");
                tableEnv.toChangelogStream(aggResult).print("Aggregated by Zone");
                break;
            }
            // ---------------------------------------------------------------
            // --query having : HAVING clause — filter groups by trip count
            // ---------------------------------------------------------------
            case "having": {
                // Same grouping as Query 2, but only retains zones with more than
                // 10 trips. Useful for filtering out low-activity areas.
                Table havingResult = tableEnv.sqlQuery(
                    "SELECT "
                    + "  CAST(pickupLatitude * 10 AS INT) AS lat_zone, "
                    + "  COUNT(*) AS trip_count, "
                    + "  SUM(totalAmount) AS total_fare, "
                    + "  AVG(totalAmount) AS avg_fare, "
                    + "  MIN(totalAmount) AS min_fare, "
                    + "  MAX(totalAmount) AS max_fare "
                    + "FROM trips "
                    + "GROUP BY CAST(pickupLatitude * 10 AS INT) "
                    + "HAVING COUNT(*) > 10");
                tableEnv.toChangelogStream(havingResult).print("Zones with >10 Trips");
                break;
            }
            // ---------------------------------------------------------------
            // --query distinct : DISTINCT for deduplication
            // ---------------------------------------------------------------
            case "distinct": {
                // Selects distinct tripIds to deduplicate the stream. In a real
                // scenario, duplicate events can arrive from Kinesis retries or
                // upstream producer issues.
                Table distinctResult = tableEnv.sqlQuery(
                    "SELECT DISTINCT tripId FROM trips");
                tableEnv.toChangelogStream(distinctResult).print("Distinct Trip IDs");
                break;
            }
            default:
                throw new IllegalArgumentException(
                    "Unknown --query '" + query + "'. Valid values: filter, group-by, having, distinct");
        }

        LOG.info("Starting SQL DML Aggregations Example");
        env.execute("SQL DML Aggregations Example");
    }
}
