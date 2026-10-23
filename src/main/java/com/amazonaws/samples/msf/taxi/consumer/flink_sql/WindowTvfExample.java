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
 * Example 4: Window Table-Valued Functions (TVFs)
 *
 * Demonstrates the three modern window TVFs in Flink SQL:
 * - TUMBLE: Fixed-size, non-overlapping windows (10-minute trip stats per area)
 * - HOP: Sliding windows with configurable size and slide (15-min window, 5-min slide)
 * - CUMULATE: Expanding windows within a period for running totals (10-min step, 1-hour max)
 *
 * Window TVFs are the recommended approach for windowed aggregations in Flink SQL,
 * replacing the legacy GROUP BY TUMBLE/HOP syntax. They use window_start and window_end
 * columns in GROUP BY clauses for time-based grouping.
 *
 * IMPORTANT: Flink streaming SQL does not support multiple independent sinks
 * in a single job, so one query runs per job.
 * Choose which query runs with the --query program argument (tumble, hop, cumulate).
 */
public class WindowTvfExample {
    private static final Logger LOG = LoggerFactory.getLogger(WindowTvfExample.class);

    public static void main(String[] args) throws Exception {
        // 1. Create StreamExecutionEnvironment via IntelliJRunHelper
        StreamExecutionEnvironment env = IntelliJRunHelper.createEnvironment("sql-window-tvf");

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

        // Pick the query to run with --query <name> (default: tumble).
        // Valid values: tumble, hop, cumulate. One query runs per job.
        String query = parameter.get("query", "tumble");
        LOG.info("Running query '{}'", query);
        switch (query) {
            // ---------------------------------------------------------------
            // --query tumble : TUMBLE window — fixed 10-minute windows
            // ---------------------------------------------------------------
            case "tumble": {
                // Groups trips into non-overlapping 10-minute windows based on
                // dropoffDatetime. Each event belongs to exactly one window.
                // Computes trip count and average fare per latitude-based zone.
                //
                // TUMBLE is the simplest window type — ideal for periodic reporting
                // (e.g., "how many trips per area every 10 minutes?").
                Table tumbleResult = tableEnv.sqlQuery(
                    "SELECT window_start, window_end, "
                    + "  CAST(pickupLatitude * 10 AS INT) AS lat_zone, "
                    + "  COUNT(*) AS trip_count, "
                    + "  AVG(totalAmount) AS avg_fare "
                    + "FROM TABLE(TUMBLE(TABLE trips, DESCRIPTOR(dropoffDatetime), INTERVAL '10' MINUTES)) "
                    + "GROUP BY window_start, window_end, CAST(pickupLatitude * 10 AS INT)");
                tableEnv.toDataStream(tumbleResult).print("Tumble Window");
                break;
            }
            // ---------------------------------------------------------------
            // --query hop : HOP window — 15-min windows sliding every 5 min
            // ---------------------------------------------------------------
            case "hop": {
                // Creates overlapping windows: each event appears in multiple windows.
                // A 15-minute window slides forward every 5 minutes, so each event
                // belongs to 3 windows (ceil(15/5) = 3).
                //
                // HOP windows are useful for smoothing out spikes and detecting trends
                // — e.g., "what's the rolling 15-minute average fare, updated every 5 min?"
                Table hopResult = tableEnv.sqlQuery(
                    "SELECT window_start, window_end, "
                    + "  COUNT(*) AS trip_count, "
                    + "  AVG(totalAmount) AS avg_fare "
                    + "FROM TABLE(HOP(TABLE trips, DESCRIPTOR(dropoffDatetime), "
                    + "  INTERVAL '5' MINUTES, INTERVAL '15' MINUTES)) "
                    + "GROUP BY window_start, window_end");
                tableEnv.toDataStream(hopResult).print("Hop Window");
                break;
            }
            // ---------------------------------------------------------------
            // --query cumulate : CUMULATE window — running totals within 1-hour periods
            // ---------------------------------------------------------------
            case "cumulate": {
                // Expands in steps within a maximum period. With a 10-minute step and
                // 1-hour max, you get windows: [0,10), [0,20), [0,30), ... [0,60).
                // Each step includes all events from the period start up to that point.
                //
                // CUMULATE is ideal for running totals — e.g., "how much total fare
                // revenue have we accumulated so far this hour, updated every 10 min?"
                Table cumulateResult = tableEnv.sqlQuery(
                    "SELECT window_start, window_end, "
                    + "  SUM(totalAmount) AS cumulative_fare, "
                    + "  COUNT(*) AS cumulative_trips "
                    + "FROM TABLE(CUMULATE(TABLE trips, DESCRIPTOR(dropoffDatetime), "
                    + "  INTERVAL '10' MINUTES, INTERVAL '1' HOUR)) "
                    + "GROUP BY window_start, window_end");
                tableEnv.toDataStream(cumulateResult).print("Cumulate Window");
                break;
            }
            default:
                throw new IllegalArgumentException(
                    "Unknown --query '" + query + "'. Valid values: tumble, hop, cumulate");
        }

        LOG.info("Starting Window TVF Example");
        env.execute("Window TVF Example");
    }
}
