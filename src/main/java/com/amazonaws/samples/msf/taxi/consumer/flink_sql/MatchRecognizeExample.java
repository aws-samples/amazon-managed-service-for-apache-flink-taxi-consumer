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
 * Example 6: Pattern Matching with MATCH_RECOGNIZE
 *
 * Demonstrates complex event processing (CEP) using Flink SQL's MATCH_RECOGNIZE clause
 * to detect various patterns in the taxi trip stream.
 *
 * Five different patterns are provided, each showcasing a different MATCH_RECOGNIZE
 * capability. One query runs per job; choose it with --query
 * (surge, v-shape, high-value-streak, spike-plateau, fare-drop).
 *
 * Patterns demonstrated:
 * 1. Fare surge — 3+ consecutive increasing fares (single variable, reluctant quantifier)
 * 2. V-shaped recovery — high fare, drop, then recovery (multi-variable: A B+ C)
 * 3. High-value streak — 5+ consecutive trips above $30 (simple threshold pattern)
 * 4. Spike and plateau — sharp increase then stable range (3 variables with BETWEEN)
 * 5. Fare drop alert — 3+ consecutive decreasing fares (mirror of surge pattern)
 *
 * Key MATCH_RECOGNIZE concepts demonstrated across all queries:
 * - PARTITION BY: Groups events by geographic zone (must be a simple column reference)
 * - ORDER BY: Defines event ordering (by dropoff time) within each partition
 * - PATTERN: Regular expression-like syntax with quantifiers and multi-variable sequences
 * - DEFINE: Conditions that pattern variables must satisfy
 * - MEASURES: Extracts business metrics from matched sequences
 * - ONE ROW PER MATCH: Outputs a single summary row per detected pattern
 * - AFTER MATCH SKIP PAST LAST ROW / SKIP TO NEXT ROW: Controls overlap behavior
 *
 * Flink streaming limitations:
 * - ALL ROWS PER MATCH is not supported in streaming mode (Flink 1.20)
 * - Greedy quantifiers (A+, A*, A{3,}) cannot be the last element of a pattern;
 *   use reluctant quantifiers (A+?, A*?, A{3,}?) instead
 * - PARTITION BY only accepts simple column references, not expressions

 */
public class MatchRecognizeExample {
    private static final Logger LOG = LoggerFactory.getLogger(MatchRecognizeExample.class);

    public static void main(String[] args) throws Exception {
        // 1. Create StreamExecutionEnvironment via IntelliJRunHelper
        StreamExecutionEnvironment env = IntelliJRunHelper.createEnvironment("sql-match-recognize");

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

        // Create a view with a pre-computed lat_zone column.
        // MATCH_RECOGNIZE requires simple column references in PARTITION BY —
        // expressions like CAST(pickupLatitude * 10 AS INT) are not supported there.
        tableEnv.executeSql(
            "CREATE TEMPORARY VIEW trips_with_zone AS "
            + "SELECT *, CAST(pickupLatitude * 10 AS INT) AS lat_zone FROM trips");

        // Pick the query to run with --query <name> (default: surge).
        // Valid values: surge, v-shape, high-value-streak, spike-plateau, fare-drop. One query runs per job.
        String query = parameter.get("query", "surge");
        LOG.info("Running query '{}'", query);
        switch (query) {
            // ---------------------------------------------------------------
            // --query surge : Fare Surge — 3+ consecutive increasing fares
            // ---------------------------------------------------------------
            case "surge": {
                // Detects sequences of 3 or more consecutive trips with strictly
                // increasing fares within the same geographic zone.
                //
                // Pattern: A{3,}? (reluctant — Flink requires non-greedy at end)
                // Each A must have a higher fare than the previous event.
                // MEASURES extracts start/end fares, sequence length, and time range.
                Table fareSergeResult = tableEnv.sqlQuery(
                    "SELECT * FROM trips_with_zone "
                    + "MATCH_RECOGNIZE ("
                    + "  PARTITION BY lat_zone "
                    + "  ORDER BY dropoffDatetime "
                    + "  MEASURES "
                    + "    FIRST(A.totalAmount) AS start_fare, "
                    + "    LAST(A.totalAmount) AS end_fare, "
                    + "    COUNT(A.tripId) AS surge_length, "
                    + "    FIRST(A.dropoffDatetime) AS surge_start, "
                    + "    LAST(A.dropoffDatetime) AS surge_end "
                    + "  ONE ROW PER MATCH "
                    + "  AFTER MATCH SKIP PAST LAST ROW "
                    + "  PATTERN (A{3,}?) "
                    + "  DEFINE "
                    + "    A AS A.totalAmount > LAST(A.totalAmount, 1) OR LAST(A.totalAmount, 1) IS NULL"
                    + ")");
                tableEnv.toDataStream(fareSergeResult).print("Fare Surge");
                break;
            }
            // ---------------------------------------------------------------
            // --query v-shape : V-Shaped Recovery — fare drops then recovers
            // ---------------------------------------------------------------
            case "v-shape": {
                // Multi-variable pattern: A B+? C
                // - A: a trip with fare > $15 (the "high" starting point)
                // - B: one or more trips where fare keeps dropping
                // - C: a trip where fare recovers above the last drop
                // This detects dips in demand followed by recovery within a zone.
                Table vShapeResult = tableEnv.sqlQuery(
                    "SELECT * FROM trips_with_zone "
                    + "MATCH_RECOGNIZE ("
                    + "  PARTITION BY lat_zone "
                    + "  ORDER BY dropoffDatetime "
                    + "  MEASURES "
                    + "    A.totalAmount AS peak_fare, "
                    + "    LAST(B.totalAmount) AS trough_fare, "
                    + "    C.totalAmount AS recovery_fare, "
                    + "    A.dropoffDatetime AS peak_time, "
                    + "    C.dropoffDatetime AS recovery_time "
                    + "  ONE ROW PER MATCH "
                    + "  AFTER MATCH SKIP PAST LAST ROW "
                    + "  PATTERN (A B+? C) "
                    + "  DEFINE "
                    + "    A AS A.totalAmount > 15, "
                    + "    B AS B.totalAmount < LAST(B.totalAmount, 1) "
                    + "         OR (LAST(B.totalAmount, 1) IS NULL AND B.totalAmount < A.totalAmount), "
                    + "    C AS C.totalAmount > LAST(B.totalAmount)"
                    + ")");
                tableEnv.toDataStream(vShapeResult).print("V-Shape Recovery");
                break;
            }
            // ---------------------------------------------------------------
            // --query high-value-streak : High-Value Streak — 5+ consecutive trips above $30
            // ---------------------------------------------------------------
            case "high-value-streak": {
                // Simple threshold pattern: A{5,}?
                // Every event in the sequence must have totalAmount > 30.
                // Useful for detecting sustained high-demand periods in a zone.
                Table highValueResult = tableEnv.sqlQuery(
                    "SELECT * FROM trips_with_zone "
                    + "MATCH_RECOGNIZE ("
                    + "  PARTITION BY lat_zone "
                    + "  ORDER BY dropoffDatetime "
                    + "  MEASURES "
                    + "    COUNT(A.tripId) AS streak_length, "
                    + "    SUM(A.totalAmount) AS total_revenue, "
                    + "    AVG(A.totalAmount) AS avg_fare, "
                    + "    FIRST(A.dropoffDatetime) AS streak_start, "
                    + "    LAST(A.dropoffDatetime) AS streak_end "
                    + "  ONE ROW PER MATCH "
                    + "  AFTER MATCH SKIP PAST LAST ROW "
                    + "  PATTERN (A{5,}?) "
                    + "  DEFINE "
                    + "    A AS A.totalAmount > 30"
                    + ")");
                tableEnv.toDataStream(highValueResult).print("High-Value Streak");
                break;
            }
            // ---------------------------------------------------------------
            // --query spike-plateau : Spike and Plateau — sharp increase then stable fares
            // ---------------------------------------------------------------
            case "spike-plateau": {
                // Three-variable pattern: A B C+?
                // - A: a low-fare trip (< $15)
                // - B: a spike — fare more than doubles compared to A
                // - C: one or more trips within ±20% of the spike fare (plateau)
                // Detects sudden demand jumps that stabilize at a new price level.
                Table spikeResult = tableEnv.sqlQuery(
                    "SELECT * FROM trips_with_zone "
                    + "MATCH_RECOGNIZE ("
                    + "  PARTITION BY lat_zone "
                    + "  ORDER BY dropoffDatetime "
                    + "  MEASURES "
                    + "    A.totalAmount AS base_fare, "
                    + "    B.totalAmount AS spike_fare, "
                    + "    COUNT(C.tripId) AS plateau_length, "
                    + "    AVG(C.totalAmount) AS plateau_avg, "
                    + "    A.dropoffDatetime AS base_time, "
                    + "    LAST(C.dropoffDatetime) AS plateau_end "
                    + "  ONE ROW PER MATCH "
                    + "  AFTER MATCH SKIP PAST LAST ROW "
                    + "  PATTERN (A B C+?) "
                    + "  DEFINE "
                    + "    A AS A.totalAmount < 15, "
                    + "    B AS B.totalAmount > 2 * A.totalAmount, "
                    + "    C AS C.totalAmount BETWEEN B.totalAmount * 0.8 AND B.totalAmount * 1.2"
                    + ")");
                tableEnv.toDataStream(spikeResult).print("Spike and Plateau");
                break;
            }
            // ---------------------------------------------------------------
            // --query fare-drop : Fare Drop Alert — 3+ consecutive decreasing fares
            // ---------------------------------------------------------------
            case "fare-drop": {
                // Mirror of Query 1 — detects falling fares instead of rising.
                // Uses SKIP TO NEXT ROW to detect overlapping drops (every possible
                // starting point), unlike SKIP PAST LAST ROW which skips ahead.
                Table fareDropResult = tableEnv.sqlQuery(
                    "SELECT * FROM trips_with_zone "
                    + "MATCH_RECOGNIZE ("
                    + "  PARTITION BY lat_zone "
                    + "  ORDER BY dropoffDatetime "
                    + "  MEASURES "
                    + "    FIRST(A.totalAmount) AS start_fare, "
                    + "    LAST(A.totalAmount) AS end_fare, "
                    + "    COUNT(A.tripId) AS drop_length, "
                    + "    FIRST(A.dropoffDatetime) AS drop_start, "
                    + "    LAST(A.dropoffDatetime) AS drop_end "
                    + "  ONE ROW PER MATCH "
                    + "  AFTER MATCH SKIP TO NEXT ROW "
                    + "  PATTERN (A{3,}?) "
                    + "  DEFINE "
                    + "    A AS A.totalAmount < LAST(A.totalAmount, 1) OR LAST(A.totalAmount, 1) IS NULL"
                    + ")");
                tableEnv.toDataStream(fareDropResult).print("Fare Drop Alert");
                break;
            }
            default:
                throw new IllegalArgumentException(
                    "Unknown --query '" + query + "'. Valid values: surge, v-shape, high-value-streak, spike-plateau, fare-drop");
        }

        // =====================================================================
        // Note: ALL ROWS PER MATCH
        // =====================================================================
        // ALL ROWS PER MATCH is not supported in Flink's streaming mode as of
        // Flink 1.20. It would output every individual trip in the matched
        // sequence instead of a single summary. For now, only ONE ROW PER MATCH
        // is available. If you need per-event detail, extract the match
        // boundaries from the summary and join back to the original stream.

        LOG.info("Starting MATCH_RECOGNIZE Example");
        env.execute("MATCH_RECOGNIZE Example");
    }
}
