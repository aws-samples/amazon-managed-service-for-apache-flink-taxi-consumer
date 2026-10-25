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
 * Example 7: Top-N Queries and Deduplication
 *
 * Demonstrates two powerful SQL patterns built on ROW_NUMBER():
 *
 * 1. Top-N ranking — finds the top 3 highest fares per geographic zone.
 *    Uses ROW_NUMBER() OVER (PARTITION BY zone ORDER BY totalAmount DESC)
 *    with a WHERE row_num <= 3 filter. This is the standard Flink SQL
 *    pattern for leaderboards, "best of" lists, and ranked results.
 *
 * 2. Deduplication — keeps only the first occurrence of each tripId,
 *    ordered by dropoff time. Uses ROW_NUMBER() OVER (PARTITION BY tripId
 *    ORDER BY dropoffDatetime ASC) with WHERE row_num = 1. This is the
 *    standard Flink SQL pattern for removing duplicate events from a stream.
 *
 * Both patterns rely on Flink's optimized Top-N operator, which maintains
 * per-partition state rather than materializing the full window. This makes
 * them efficient for streaming use cases with high-cardinality keys.
 *
 * IMPORTANT: Flink streaming SQL does not support multiple independent sinks
 * in a single job, so one query runs per job.
 * Choose which query runs with the --query program argument (top-n, dedup).
 */
public class TopNDeduplicationExample {
    private static final Logger LOG = LoggerFactory.getLogger(TopNDeduplicationExample.class);

    public static void main(String[] args) throws Exception {
        // 1. Create StreamExecutionEnvironment via IntelliJRunHelper
        StreamExecutionEnvironment env = IntelliJRunHelper.createEnvironment("sql-topn-dedup");

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

        // Pick the query to run with --query <name> (default: top-n).
        // Valid values: top-n, dedup. One query runs per job.
        String query = parameter.get("query", "top-n");
        LOG.info("Running query '{}'", query);
        switch (query) {
            // ---------------------------------------------------------------
            // --query top-n : Top-N — Top 3 highest fares per geographic zone
            // ---------------------------------------------------------------
            case "top-n": {
                // Finds the 3 most expensive trips in each latitude-based zone.
                //
                // How it works:
                // - The inner SELECT assigns a row number to each trip within its zone,
                //   ordered by totalAmount descending (highest fare first).
                // - PARTITION BY CAST(pickupLatitude * 10 AS INT) groups trips into
                //   coarse latitude zones (e.g., 407 for ~40.7° N).
                // - The outer WHERE row_num <= 3 keeps only the top 3 per zone.
                //
                // Flink optimizes this into a streaming Top-N operator that maintains
                // a small heap per partition rather than buffering all rows.
                // IMPORTANT: Top-N queries produce update/retract streams (rankings change
                // as new events arrive), so we use toChangelogStream() instead of toDataStream().
                Table topNResult = tableEnv.sqlQuery(
                    "SELECT * FROM ("
                    + "  SELECT *, ROW_NUMBER() OVER ("
                    + "    PARTITION BY CAST(pickupLatitude * 10 AS INT) "
                    + "    ORDER BY totalAmount DESC"
                    + "  ) AS row_num "
                    + "  FROM trips"
                    + ") WHERE row_num <= 3");
                tableEnv.toChangelogStream(topNResult).print("Top-3 Fares Per Zone");
                break;
            }
            // ---------------------------------------------------------------
            // --query dedup : Deduplication — keep first occurrence per tripId
            // ---------------------------------------------------------------
            case "dedup": {
                // Removes duplicate trip events by keeping only the first occurrence
                // of each tripId, determined by the earliest dropoffDatetime.
                //
                // How it works:
                // - ROW_NUMBER() partitions by tripId and orders by dropoffDatetime ASC,
                //   so the first event for each trip gets row_num = 1.
                // - The outer WHERE row_num = 1 discards all subsequent duplicates.
                //
                // Flink recognizes this as a deduplication pattern and optimizes it
                // into a stateful operator that stores only the first-seen row per key,
                // making it very efficient for high-throughput deduplication.
                Table dedupResult = tableEnv.sqlQuery(
                    "SELECT * FROM ("
                    + "  SELECT *, ROW_NUMBER() OVER ("
                    + "    PARTITION BY tripId "
                    + "    ORDER BY dropoffDatetime ASC"
                    + "  ) AS row_num "
                    + "  FROM trips"
                    + ") WHERE row_num = 1");
                tableEnv.toChangelogStream(dedupResult).print("Deduplicated Trips");
                break;
            }
            default:
                throw new IllegalArgumentException(
                    "Unknown --query '" + query + "'. Valid values: top-n, dedup");
        }

        LOG.info("Starting Top-N and Deduplication Example");
        env.execute("Top-N and Deduplication Example");
    }
}
