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
 * Example 9: SQL Hints and Query Optimization
 *
 * Demonstrates how to tune Flink SQL query performance for production workloads:
 * - State TTL configuration to manage state size for unbounded aggregations
 * - Mini-batch aggregation to improve throughput at the cost of latency
 * - Comprehensive documentation of known Flink SQL limitations on MSF
 * - Workarounds for common issues in streaming SQL
 *
 * IMPORTANT: Flink streaming SQL does not support multiple independent sinks
 * in a single job, so one query runs per job.
 * Choose which query runs with the --query program argument (global-ttl-minibatch, ttl-hint, ttl-hint-join, no-minibatch).
 *
 * ============================================================================
 * KNOWN FLINK SQL LIMITATIONS ON AMAZON MANAGED SERVICE FOR APACHE FLINK (MSF)
 * ============================================================================
 *
 * 1. NO CREATE TABLE AS SELECT (CTAS) IN STREAMING MODE
 *    Flink SQL's CTAS statement is only supported in batch mode. In streaming
 *    mode, you must CREATE TABLE first, then INSERT INTO separately.
 *
 * 2. LIMITED DDL SUPPORT FOR SOME CONNECTORS
 *    Not all connectors support full DDL. For example, the Kinesis SQL connector
 *    requires specific packaging (flink-sql-connector-kinesis) that differs from
 *    the DataStream connector. When DDL-based table creation is not feasible,
 *    use the DataStream-to-Table bridge as shown in these examples.
 *
 * 3. SINGLE SINK PER JOB (STREAMING)
 *    A streaming Flink SQL job can only write to one sink. If you need multiple
 *    outputs, use STATEMENT SET or split the logic into separate jobs.
 *
 * 4. NO INTERACTIVE QUERIES
 *    Flink SQL on MSF runs as a long-lived streaming job, not an interactive
 *    query engine. You cannot run ad-hoc queries against a running application.
 *
 * 5. CATALOG PERSISTENCE
 *    The default in-memory catalog is lost on job restart. For persistent
 *    metadata, consider using AWS Glue Data Catalog or Hive Metastore, though
 *    support varies by Flink version on MSF.
 *
 * 6. STATE BACKEND CONSIDERATIONS
 *    MSF uses RocksDB as the default state backend. Unbounded aggregations
 *    (GROUP BY without windows) accumulate state indefinitely. Always configure
 *    state TTL for production workloads to prevent out-of-memory failures.
 *
 * 7. CHECKPOINT INTERVAL
 *    MSF manages checkpointing configuration. The default interval may not be
 *    optimal for all workloads. Adjust via application properties if needed.
 *
 * 8. UDF DEPLOYMENT
 *    User-defined functions must be packaged in the application JAR. There is
 *    no dynamic UDF loading on MSF — all functions must be available at deploy
 *    time.
 * ============================================================================
 */
public class SqlOptimizationExample {
    private static final Logger LOG = LoggerFactory.getLogger(SqlOptimizationExample.class);

    public static void main(String[] args) throws Exception {
        // 1. Create StreamExecutionEnvironment via IntelliJRunHelper
        StreamExecutionEnvironment env = IntelliJRunHelper.createEnvironment("sql-optimization");

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
        // OPTIMIZATION 1: State TTL (Time-To-Live)
        // =====================================================================
        //
        // WHY: Unbounded aggregations (GROUP BY without a window) keep state
        // for every key forever. Over time, this causes state to grow without
        // bound, eventually leading to out-of-memory errors or degraded
        // checkpoint performance on MSF.
        //
        // HOW IT WORKS: State TTL tells Flink to expire state entries that
        // have not been accessed within the configured duration. Once expired,
        // the key's accumulated state is discarded and any future event for
        // that key starts fresh — as if it were seen for the first time.
        //
        // TRADEOFF: Setting TTL too low causes premature state eviction,
        // leading to incorrect aggregation results (counters reset, averages
        // restart). Setting it too high defeats the purpose of bounding state.
        // Choose a TTL that matches your business semantics — e.g., if you
        // only care about the last hour of activity per zone, use 1 hour.
        //
        // PRODUCTION TIP: Monitor the "Checkpoint Size" metric in the MSF console.
        // If state grows linearly over time, you likely need TTL or should
        // switch to windowed aggregations.
        //
        tableEnv.getConfig().set("table.exec.state.ttl", "3600000"); // 1 hour in milliseconds
        LOG.info("Configured state TTL: 1 hour (3600000 ms)");

        // =====================================================================
        // OPTIMIZATION 2: Mini-Batch Aggregation
        // =====================================================================
        //
        // WHY: By default, Flink processes each record individually, which
        // means every incoming event triggers a state read, update, and
        // downstream emit. For high-throughput aggregations, this per-record
        // overhead is significant.
        //
        // HOW IT WORKS: Mini-batch buffers incoming records and processes them
        // in batches. This reduces the number of state accesses and output
        // records, improving throughput. The batch is flushed when either:
        //   - The buffer reaches the configured size (mini-batch.size), OR
        //   - The allowed latency timer fires (mini-batch.allow-latency)
        //
        // TRADEOFF: Mini-batch introduces additional latency — results are
        // delayed by up to the allow-latency value. For real-time dashboards
        // requiring sub-second updates, keep allow-latency low or disable
        // mini-batch entirely. For analytics workloads where a few seconds
        // of delay is acceptable, mini-batch can significantly reduce load.
        //
        // PRODUCTION TIP: Start with allow-latency=5s and size=1000, then
        // tune based on your throughput and latency requirements. Monitor
        // the "Records Per Second" and "Backpressure" metrics in MSF.
        //
        tableEnv.getConfig().set("table.exec.mini-batch.enabled", "true");
        tableEnv.getConfig().set("table.exec.mini-batch.allow-latency", "5s");
        tableEnv.getConfig().set("table.exec.mini-batch.size", "1000");
        LOG.info("Configured mini-batch aggregation: enabled=true, allow-latency=5s, size=1000");

        // Pick the query to run with --query <name> (default: ttl-hint).
        // Valid values: global-ttl-minibatch, ttl-hint, ttl-hint-join, no-minibatch. One query runs per job.
        String query = parameter.get("query", "ttl-hint");
        LOG.info("Running query '{}'", query);
        switch (query) {
            // ---------------------------------------------------------------
            // --query global-ttl-minibatch : Aggregation with global State TTL + Mini-Batch
            // ---------------------------------------------------------------
            case "global-ttl-minibatch": {
                // This query groups trips by a latitude-based geographic zone and
                // computes aggregate statistics. With the optimizations above:
                //
                // - State TTL ensures that zones with no activity for 1 hour have
                //   their state cleaned up, preventing unbounded state growth.
                //
                // - Mini-batch reduces the number of state accesses by buffering
                //   up to 1000 records or 5 seconds of data before processing.
                //
                // STATE SIZE CONSIDERATIONS:
                // Without TTL, this query's state grows proportionally to the number
                // of distinct lat_zone values seen over the entire job lifetime.
                // For NYC taxi data with lat_zone = CAST(pickupLatitude * 10 AS INT),
                // there are roughly 10-20 distinct zones, so state is manageable.
                // However, if you partition by a high-cardinality key (e.g., tripId),
                // state can grow to millions of entries — TTL becomes essential.
                //
                // WORKAROUND FOR UNBOUNDED STATE:
                // If TTL-based eviction is not acceptable (you need exact lifetime
                // aggregates), consider these alternatives:
                //   1. Use windowed aggregations (TUMBLE, HOP) to bound state naturally
                //   2. Use an external store (e.g., DynamoDB) as a sink and aggregate
                //      there, keeping Flink state minimal
                //   3. Increase MSF TaskManager memory to accommodate larger state,
                //      but this only delays the problem for truly unbounded keys
                Table optimizedAggResult = tableEnv.sqlQuery(
                    "SELECT "
                    + "  CAST(pickupLatitude * 10 AS INT) AS lat_zone, "
                    + "  COUNT(*) AS trip_count, "
                    + "  SUM(totalAmount) AS total_fare, "
                    + "  AVG(totalAmount) AS avg_fare, "
                    + "  MIN(totalAmount) AS min_fare, "
                    + "  MAX(totalAmount) AS max_fare "
                    + "FROM trips "
                    + "GROUP BY CAST(pickupLatitude * 10 AS INT)");
                tableEnv.toChangelogStream(optimizedAggResult).print("Optimized Aggregation");
                break;
            }
            // ---------------------------------------------------------------
            // --query ttl-hint : STATE_TTL hint — per-operator TTL on GROUP BY
            // ---------------------------------------------------------------
            case "ttl-hint": {
                // Instead of the global table.exec.state.ttl, you can set TTL
                // per-operator using the STATE_TTL SQL hint. This is useful when
                // a single job has multiple stateful operators that need different
                // retention periods.
                //
                // The hint key must match a table name, view name, or alias in the
                // FROM clause. When the source table was created via fromDataStream()
                // + createTemporaryView(), the hint resolver may not match the view
                // name directly. The workaround is to wrap the view in a subquery
                // with an explicit alias, then use that alias as the hint key.
                //
                // LOCAL DEMO: With the 3600x speedup producer, 1 hour of event time
                // passes every second. A 1-minute event-time TTL means state expires
                // after ~1/60th of a second of wall-clock time. You should see
                // checkpoint sizes stabilize quickly in the Web UI (http://localhost:8081
                // → Running Jobs → Checkpoints tab) and in the Slf4j metric reporter
                // output in the console (logged every 10 seconds).
                //
                // TIP: Use explainSql() to verify the STATE_TTL hint appears in the
                // physical plan before running the job.
                String hintAggQuery =
                    "SELECT /*+ STATE_TTL('t' = '1m') */ "
                    + "  CAST(pickupLatitude * 10 AS INT) AS lat_zone, "
                    + "  COUNT(*) AS trip_count, "
                    + "  AVG(totalAmount) AS avg_fare "
                    + "FROM (SELECT * FROM trips) t "
                    + "GROUP BY CAST(pickupLatitude * 10 AS INT)";

                // Print the optimized physical plan — verify STATE_TTL is applied
                String plan = tableEnv.explainSql(hintAggQuery);
                LOG.info("Query plan (verify STATE_TTL hint):\n{}", plan);

                Table hintAggResult = tableEnv.sqlQuery(hintAggQuery);
                tableEnv.toChangelogStream(hintAggResult).print("STATE_TTL Hint Aggregation");
                break;
            }
            // ---------------------------------------------------------------
            // --query ttl-hint-join : STATE_TTL hint — per-side TTL on Regular Join (Flink 1.18+)
            // ---------------------------------------------------------------
            case "ttl-hint-join": {
                // For regular (non-windowed) joins, each side of the join maintains
                // its own state. STATE_TTL lets you set different TTLs per side.
                //
                // In this example, pickup events are kept for 2 hours (they arrive
                // first and must wait for the matching dropoff), while dropoff events
                // only need 30 minutes of retention (they arrive shortly after pickup).
                //
                // This is much more efficient than a single global TTL because:
                //   - The left side (pickups) needs longer retention to wait for late dropoffs
                //   - The right side (dropoffs) can expire quickly once matched
                Table hintJoinResult = tableEnv.sqlQuery(
                    "SELECT /*+ STATE_TTL('p' = '2h', 'd' = '30m') */ "
                    + "  p.tripId, "
                    + "  p.pickupLatitude, "
                    + "  p.pickupLongitude, "
                    + "  p.pickupDatetime, "
                    + "  d.dropoffLatitude, "
                    + "  d.dropoffLongitude, "
                    + "  d.dropoffDatetime, "
                    + "  d.totalAmount "
                    + "FROM (SELECT tripId, pickupLatitude, pickupLongitude, pickupDatetime FROM trips) p "
                    + "INNER JOIN (SELECT tripId, dropoffLatitude, dropoffLongitude, dropoffDatetime, totalAmount FROM trips) d "
                    + "ON p.tripId = d.tripId");
                tableEnv.toChangelogStream(hintJoinResult).print("STATE_TTL Hint Join");
                break;
            }
            // ---------------------------------------------------------------
            // --query no-minibatch : Aggregation WITHOUT Mini-Batch (for comparison)
            // ---------------------------------------------------------------
            case "no-minibatch": {
                // Same aggregation as global-ttl-minibatch, but with mini-batch disabled.
                //
                // Note: Changing configuration after a query is defined does NOT
                // retroactively affect that query's execution plan. You must set
                // the configuration before calling sqlQuery().
                //
                // Without mini-batch, each incoming trip event immediately triggers:
                //   1. A state read for the zone's current aggregates
                //   2. A state update with the new values
                //   3. An output record emitted downstream
                //
                // This gives lower latency (results appear immediately) but higher
                // per-record overhead and more downstream pressure.
                tableEnv.getConfig().set("table.exec.mini-batch.enabled", "false");
                Table baselineAggResult = tableEnv.sqlQuery(
                    "SELECT "
                    + "  CAST(pickupLatitude * 10 AS INT) AS lat_zone, "
                    + "  COUNT(*) AS trip_count, "
                    + "  SUM(totalAmount) AS total_fare, "
                    + "  AVG(totalAmount) AS avg_fare, "
                    + "  MIN(totalAmount) AS min_fare, "
                    + "  MAX(totalAmount) AS max_fare "
                    + "FROM trips "
                    + "GROUP BY CAST(pickupLatitude * 10 AS INT)");
                tableEnv.toChangelogStream(baselineAggResult).print("Baseline Aggregation (no mini-batch)");
                break;
            }
            default:
                throw new IllegalArgumentException(
                    "Unknown --query '" + query + "'. Valid values: global-ttl-minibatch, ttl-hint, ttl-hint-join, no-minibatch");
        }

        // =====================================================================
        // ADDITIONAL OPTIMIZATION TIPS FOR MSF
        // =====================================================================
        //
        // 1. PARALLELISM TUNING
        //    MSF sets parallelism based on Kinesis Processing Units (KPU).
        //    Each KPU provides 1 vCPU and 4 GB memory. For SQL jobs with
        //    heavy state, consider increasing KPU count to distribute state
        //    across more TaskManagers.
        //
        // 2. ROCKSDB STATE BACKEND TUNING
        //    MSF uses RocksDB by default. For write-heavy workloads (frequent
        //    state updates from aggregations), consider tuning:
        //    - state.backend.rocksdb.block.cache-size (default 8MB per slot)
        //    - state.backend.rocksdb.writebuffer.size
        //    These can be set via MSF application properties.
        //
        // 3. CHECKPOINT INTERVAL
        //    Shorter checkpoint intervals mean faster recovery but higher
        //    overhead. For SQL aggregation jobs, 60-120 seconds is a good
        //    starting point. Adjust via MSF application configuration.
        //
        // 4. OPERATOR CHAINING
        //    Flink chains operators by default for efficiency. Avoid
        //    disabling chaining unless you need per-operator metrics for
        //    debugging. Chaining reduces serialization overhead between
        //    operators in the same task slot.
        //
        // 5. SQL QUERY PLAN INSPECTION
        //    Use tableEnv.explainSql(query) to inspect the optimized query
        //    plan before deploying. This helps identify unnecessary shuffles,
        //    missing optimizations, or unexpected operator topologies.
        //    Example:
        //      String plan = tableEnv.explainSql("SELECT ...");
        //      LOG.info("Query plan:\n{}", plan);
        //

        LOG.info("Starting SQL Optimization Example");
        env.execute("SQL Optimization Example");
    }
}
