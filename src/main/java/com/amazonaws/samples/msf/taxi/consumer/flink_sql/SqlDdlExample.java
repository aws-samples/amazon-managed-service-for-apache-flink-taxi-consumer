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
 * Example 2: SQL DDL Statements
 *
 * Demonstrates how to:
 * - Define a CREATE TABLE DDL with Kinesis connector (documented reference)
 * - Use computed columns (tripDurationSeconds, farePerMinute)
 * - Declare WATERMARK FOR clause on event-time columns
 * - Create temporary views for reusable query fragments
 *
 * Note: The CREATE TABLE DDL with Kinesis connector is shown as a documented
 * reference string. In practice, we use the DataStream-to-Table bridge since
 * the Kinesis SQL connector has different packaging requirements. The DDL
 * illustrates the syntax you would use in a production MSF deployment with
 * the flink-connector-kinesis SQL connector.
 */
public class SqlDdlExample {
    private static final Logger LOG = LoggerFactory.getLogger(SqlDdlExample.class);

    /**
     * Computes trip duration in seconds from pickup and dropoff instants.
     * This is the pure-function equivalent of the SQL computed column:
     *   TIMESTAMPDIFF(SECOND, pickup_datetime, dropoff_datetime)
     *
     * @param pickup  the pickup timestamp
     * @param dropoff the dropoff timestamp
     * @return duration in seconds (dropoff epoch seconds minus pickup epoch seconds)
     */
    public static long computeTripDurationSeconds(Instant pickup, Instant dropoff) {
        return dropoff.getEpochSecond() - pickup.getEpochSecond();
    }

    public static void main(String[] args) throws Exception {
        // 1. Create StreamExecutionEnvironment via IntelliJRunHelper
        StreamExecutionEnvironment env = IntelliJRunHelper.createEnvironment("sql-ddl-example");

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

        // =====================================================================
        // DDL Reference: CREATE TABLE with Kinesis connector
        // =====================================================================
        // This DDL is shown as a documented reference for production MSF
        // deployments. It is NOT executed here because we use the DataStream
        // bridge above. In a pure SQL deployment on MSF, you would execute
        // this DDL to create a source table directly from Kinesis.
        //
        // Key DDL features demonstrated:
        //   - Kinesis connector configuration with parameterized stream ARN
        //   - Computed column: trip_duration_seconds via TIMESTAMPDIFF
        //   - WATERMARK FOR clause on the event-time column
        String createTableDdl = "CREATE TABLE trips_kinesis (\n"
            + "  trip_id BIGINT,\n"
            + "  pickup_latitude DOUBLE,\n"
            + "  pickup_longitude DOUBLE,\n"
            + "  dropoff_latitude DOUBLE,\n"
            + "  dropoff_longitude DOUBLE,\n"
            + "  total_amount DOUBLE,\n"
            + "  pickup_datetime TIMESTAMP(3),\n"
            + "  dropoff_datetime TIMESTAMP(3),\n"
            + "  trip_duration_seconds AS TIMESTAMPDIFF(SECOND, pickup_datetime, dropoff_datetime),\n"
            + "  WATERMARK FOR dropoff_datetime AS dropoff_datetime - INTERVAL '30' SECOND\n"
            + ") WITH (\n"
            + "  'connector' = 'kinesis',\n"
            + "  'stream.arn' = '" + streamArn + "',\n"
            + "  'format' = 'json'\n"
            + ")";
        LOG.info("CREATE TABLE DDL reference (not executed):\n{}", createTableDdl);

        // =====================================================================
        // CREATE TEMPORARY VIEW: manhattan_trips
        // =====================================================================
        // Geographic filter for Manhattan pickups only.
        // Latitude range 40.70–40.82, longitude range -74.02 to -73.93.
        tableEnv.executeSql(
            "CREATE TEMPORARY VIEW manhattan_trips AS "
            + "SELECT * FROM trips "
            + "WHERE pickupLatitude BETWEEN 40.70 AND 40.82 "
            + "AND pickupLongitude BETWEEN -74.02 AND -73.93");
        LOG.info("Created 'manhattan_trips' temporary view");

        // =====================================================================
        // CREATE TEMPORARY VIEW: enriched_trips
        // =====================================================================
        // Adds computed columns:
        //   - tripDurationSeconds: trip duration via TIMESTAMPDIFF
        //   - farePerMinute: fare rate per minute (guards against zero-minute trips)
        tableEnv.executeSql(
            "CREATE TEMPORARY VIEW enriched_trips AS "
            + "SELECT *, "
            + "  TIMESTAMPDIFF(SECOND, pickupDatetime, dropoffDatetime) AS tripDurationSeconds, "
            + "  totalAmount / GREATEST(TIMESTAMPDIFF(MINUTE, pickupDatetime, dropoffDatetime), 1) AS farePerMinute "
            + "FROM trips");
        LOG.info("Created 'enriched_trips' temporary view");

        // =====================================================================
        // Query the views and print results
        // =====================================================================

        // Query manhattan_trips view
        Table manhattanResult = tableEnv.sqlQuery(
            "SELECT tripId, totalAmount, pickupLatitude, pickupLongitude "
            + "FROM manhattan_trips");
        tableEnv.toDataStream(manhattanResult).print("Manhattan Trips");

        // Query enriched_trips view — show computed columns
        Table enrichedResult = tableEnv.sqlQuery(
            "SELECT tripId, totalAmount, tripDurationSeconds, farePerMinute "
            + "FROM enriched_trips");
        tableEnv.toDataStream(enrichedResult).print("Enriched Trips");

        LOG.info("Starting SQL DDL Example");
        env.execute("SQL DDL Example");
    }
}
