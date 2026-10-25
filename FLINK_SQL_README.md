# Flink SQL and Table API Workshop

Welcome to the Flink SQL and Table API workshop! This hands-on guide walks you through 9 progressive examples that teach you how to use Flink SQL for real-time stream processing with NYC taxi data on Amazon Managed Service for Apache Flink.

## Workshop Overview

You'll learn production-ready Flink SQL patterns through practical examples that solve real business problems. Each example builds on previous concepts, starting from Table Environment setup and progressing through advanced patterns like MATCH_RECOGNIZE and query optimization.

**What You'll Learn:**
- Setting up a StreamTableEnvironment and bridging DataStream ↔ Table APIs
- Defining tables, views, and computed columns with DDL
- Writing SQL queries with filters, aggregations, and deduplication
- Window Table-Valued Functions (TUMBLE, HOP, CUMULATE) for time-based analytics
- Join strategies: inner, outer, interval, and temporal joins
- Complex event processing with MATCH_RECOGNIZE pattern matching
- Top-N ranking and stream deduplication patterns
- Extending SQL with custom User-Defined Functions (scalar, table, aggregate)
- Performance tuning with state TTL and mini-batch aggregation

## Prerequisites

- Java 11+ (Amazon Corretto recommended)
- IntelliJ IDEA with Maven support
- Basic understanding of SQL and streaming concepts
- AWS credentials configured (for production deployment)
- The `add-dependencies-for-IDEA` Maven profile active in IntelliJ (required for Table API classes)

## Getting Started

### Quick Start
1. **Clone and Setup**
```bash
git clone https://github.com/aws-samples/amazon-managed-service-for-apache-flink-taxi-consumer.git
cd amazon-managed-service-for-apache-flink-taxi-consumer
mvn clean compile
```

2. **Start the Data Producer**

All examples read taxi trip events from a Kinesis Data Stream. You need to start the [Amazon Kinesis Replay](../amazon-kinesis-replay/) producer to feed data into your stream:

```bash
cd amazon-kinesis-replay
mvn clean package -DskipTests
java -jar target/amazon-kinesis-replay-*.jar -streamArn «your Kinesis stream ARN» -speedup 3600
```

This replays one hour of historic NYC taxi trip data per second. Keep the producer running in a separate terminal while you work through the examples.

> **Tip:** Adjust `-speedup` to control data rate. Use `3600` for fast iteration or `60` for a more realistic pace.

3. **Open in IntelliJ**
- File → Open → Select `pom.xml`
- Wait for Maven import and indexing
- Activate the `add-dependencies-for-IDEA` Maven profile (required for Table API classes)
- Right-click any example → Run
- Edit the run configuration and pass your stream as a program argument (required — the examples fail fast without it):
  ```
  --InputStreamArn arn:aws:kinesis:<region>:<account-id>:stream/<stream-name>
  ```
  Make sure your AWS credentials and region (for example `AWS_PROFILE` and `AWS_REGION`) are available to the run configuration.

4. **Access Web UI**
```
http://localhost:8081
```

5. **Choose the query with `--query`**

Most examples contain several queries, and one query runs per job. Pick it with the `--query` program argument in the run configuration, for example `--InputStreamArn <arn> --query hop`. Without `--query` the first query runs; an unknown value fails fast and lists the valid names.

| Example | `--query` values (first is the default) |
|---|---|
| `SqlDmlAggregationsExample` | `filter`, `group-by`, `having`, `distinct` |
| `WindowTvfExample` | `tumble`, `hop`, `cumulate` |
| `SqlJoinsExample` | `inner`, `left-outer`, `interval`, `temporal` |
| `MatchRecognizeExample` | `surge`, `v-shape`, `high-value-streak`, `spike-plateau`, `fare-drop` |
| `TopNDeduplicationExample` | `top-n`, `dedup` |
| `UdfExample` | `geohash`, `zone-split`, `weighted-avg` |
| `SqlOptimizationExample` | `ttl-hint` (default), `global-ttl-minibatch`, `ttl-hint-join`, `no-minibatch` |

### Workshop Progression
1. Start with `TableEnvSetupExample` — Foundation: bridge DataStream and Table APIs
2. Move to `SqlDdlExample` — Define tables, views, and computed columns
3. Progress through `SqlDmlAggregationsExample` — Filters, aggregations, deduplication
4. Continue with `WindowTvfExample` — Time-based analytics with TUMBLE, HOP, CUMULATE
5. Explore `SqlJoinsExample` — Inner, outer, interval, and temporal joins
6. Practice `MatchRecognizeExample` — Complex event processing
7. Build with `TopNDeduplicationExample` — Ranking and deduplication patterns
8. Extend with `UdfExample` — Custom scalar, table, and aggregate functions
9. Optimize with `SqlOptimizationExample` — State TTL and mini-batch tuning

### Production Deployment
```bash
# Build JAR for MSF deployment
mvn clean package

# Deploy to Amazon Managed Service for Apache Flink
# Set InputStreamArn in the FlinkApplicationProperties property group
```

## Workshop Structure

### Example 1: Table Environment Setup and DataStream Interop
**File:** `TableEnvSetupExample.java`
**Duration:** 20 minutes
**Difficulty:** Beginner

**Business Problem:**
You have an existing DataStream application reading taxi events from Kinesis and want to start using SQL queries without rewriting your pipeline. You need to bridge the DataStream and Table APIs.

**Key Concepts:**
- **StreamTableEnvironment**: The entry point for all Flink SQL operations, created from an existing StreamExecutionEnvironment
- **DataStream → Table conversion**: Register a typed DataStream as a SQL-accessible table with explicit schema
- **Watermark declaration**: Define event-time semantics in the table schema for time-based operations
- **Table → DataStream conversion**: Convert SQL query results back to a DataStream for further processing or output

**Step-by-Step Walkthrough:**

1. **Create the StreamTableEnvironment**
```java
StreamTableEnvironment tableEnv = StreamTableEnvironment.create(env);
```
This wraps your existing `StreamExecutionEnvironment` to add Table API and SQL capabilities. All DataStream operations remain available.

2. **Convert DataStream to Table with explicit Schema**
```java
Table tripTable = tableEnv.fromDataStream(tripStream,
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
```

**Why This Matters:** The explicit Schema maps Java fields to SQL types and declares a watermark for event-time processing. Without the watermark, time-based operations (windows, interval joins) won't work.

3. **Register as a temporary view and query**
```java
tableEnv.createTemporaryView("trips", tripTable);

Table result = tableEnv.sqlQuery(
    "SELECT tripId, totalAmount, pickupDatetime FROM trips WHERE totalAmount > 20.0");

tableEnv.toDataStream(result).print("SQL Result");
```

**Why This Matters:** Once registered as a view, the table is accessible from any SQL query in the same TableEnvironment. Converting back to DataStream lets you integrate SQL results with existing DataStream operators.

**Hands-On Exercise:**
1. Run the example and observe filtered trip output in the console
2. Modify the WHERE clause to filter by geographic bounds instead of fare
3. Add additional columns to the SELECT and observe the schema mapping
4. Open the Flink Web UI at `http://localhost:8081` and inspect the job graph


---

### Example 2: SQL DDL Statements
**File:** `SqlDdlExample.java`
**Duration:** 25 minutes
**Difficulty:** Beginner

**Business Problem:**
You need to define reusable table structures, computed columns (trip duration, fare rate), and filtered views (Manhattan-only trips) that multiple queries can share.

**Key Concepts:**
- **CREATE TABLE DDL**: Define a source table with connector configuration (Kinesis connector reference)
- **Computed columns**: Derive new columns from existing fields using SQL expressions
- **WATERMARK FOR**: Declare event-time watermark strategy in DDL
- **CREATE VIEW**: Build reusable query fragments as named views

**Step-by-Step Walkthrough:**

1. **CREATE TABLE with Kinesis connector (reference)**
```sql
CREATE TABLE trips_kinesis (
  trip_id BIGINT,
  pickup_latitude DOUBLE,
  pickup_longitude DOUBLE,
  dropoff_latitude DOUBLE,
  dropoff_longitude DOUBLE,
  total_amount DOUBLE,
  pickup_datetime TIMESTAMP(3),
  dropoff_datetime TIMESTAMP(3),
  trip_duration_seconds AS TIMESTAMPDIFF(SECOND, pickup_datetime, dropoff_datetime),
  WATERMARK FOR dropoff_datetime AS dropoff_datetime - INTERVAL '30' SECOND
) WITH (
  'connector' = 'kinesis',
  'stream.arn' = '<your-stream-arn>',
  'format' = 'json'
)
```

**Why This Matters:** This DDL shows the production pattern for MSF deployments where you define sources entirely in SQL. The computed column `trip_duration_seconds` is calculated automatically for every row. In this workshop, we use the DataStream bridge instead because the Kinesis SQL connector has different packaging requirements.

2. **Create filtered views**
```java
tableEnv.executeSql(
    "CREATE TEMPORARY VIEW manhattan_trips AS "
    + "SELECT * FROM trips "
    + "WHERE pickupLatitude BETWEEN 40.70 AND 40.82 "
    + "AND pickupLongitude BETWEEN -74.02 AND -73.93");
```

3. **Create views with computed columns**
```java
tableEnv.executeSql(
    "CREATE TEMPORARY VIEW enriched_trips AS "
    + "SELECT *, "
    + "  TIMESTAMPDIFF(SECOND, pickupDatetime, dropoffDatetime) AS tripDurationSeconds, "
    + "  totalAmount / GREATEST(TIMESTAMPDIFF(MINUTE, pickupDatetime, dropoffDatetime), 1) AS farePerMinute "
    + "FROM trips");
```

**Why This Matters:** Views let you define reusable transformations once and reference them from multiple queries. The `GREATEST(..., 1)` guard prevents division by zero for very short trips.

**Hands-On Exercise:**
1. Run the example and observe Manhattan-filtered and enriched trip output
2. Create a new view for Brooklyn trips (lat 40.57–40.70, lon -74.04 to -73.85)
3. Add a computed column for estimated trip distance using the Haversine formula approximation
4. Query the enriched_trips view to find trips with fare rates above $5/minute

---

### Example 3: SQL DML Queries and Aggregations
**File:** `SqlDmlAggregationsExample.java`
**Duration:** 30 minutes
**Difficulty:** Beginner-Intermediate

**Business Problem:**
You need to analyze taxi trip data using familiar SQL patterns: filter high-fare trips, compute statistics per geographic zone, identify busy areas, and deduplicate events from Kinesis retries.

**Key Concepts:**
- **SELECT with WHERE**: Filter streams using SQL predicates
- **GROUP BY with aggregates**: COUNT, SUM, AVG, MIN, MAX on grouped data
- **HAVING**: Filter aggregated groups by computed conditions
- **DISTINCT**: Deduplicate stream records

**Step-by-Step Walkthrough:**

1. **Filtered queries with WHERE**
```java
Table filteredResult = tableEnv.sqlQuery(
    "SELECT tripId, totalAmount, pickupLatitude, pickupLongitude "
    + "FROM trips "
    + "WHERE totalAmount > 50 "
    + "AND pickupLatitude BETWEEN 40.70 AND 40.82 "
    + "AND pickupLongitude BETWEEN -74.02 AND -73.93");
```

2. **GROUP BY with aggregate functions**
```sql
SELECT
  CAST(pickupLatitude * 10 AS INT) AS lat_zone,
  COUNT(*) AS trip_count,
  SUM(totalAmount) AS total_fare,
  AVG(totalAmount) AS avg_fare,
  MIN(totalAmount) AS min_fare,
  MAX(totalAmount) AS max_fare
FROM trips
GROUP BY CAST(pickupLatitude * 10 AS INT)
```

**Why This Matters:** The `CAST(pickupLatitude * 10 AS INT)` expression creates coarse geographic zones (e.g., 407 for ~40.7°N). This is a common pattern for spatial grouping without a full geospatial library. Note that this is an unbounded aggregation — state grows indefinitely. See Example 9 for state TTL configuration.

3. **HAVING clause for group filtering**
```sql
SELECT CAST(pickupLatitude * 10 AS INT) AS lat_zone,
  COUNT(*) AS trip_count, AVG(totalAmount) AS avg_fare
FROM trips
GROUP BY CAST(pickupLatitude * 10 AS INT)
HAVING COUNT(*) > 10
```

4. **DISTINCT for deduplication**
```sql
SELECT DISTINCT tripId FROM trips
```

**Why This Matters:** In streaming, DISTINCT maintains state for every seen key. For high-cardinality keys like tripId, this can consume significant memory. For production deduplication, consider the ROW_NUMBER pattern in Example 7 which gives more control.

**Hands-On Exercise:**
1. Run the filtered query and observe high-fare Manhattan trips
2. Re-run with `--query group-by`
3. Modify the HAVING threshold and observe how it affects output
4. Try the DISTINCT query and think about its state implications


---

### Example 4: Window Table-Valued Functions
**File:** `WindowTvfExample.java`
**Duration:** 35 minutes
**Difficulty:** Intermediate

**Business Problem:**
You need time-based analytics on taxi trips: periodic reports (every 10 minutes), trend detection (rolling averages), and cumulative revenue tracking within hourly periods.

**Key Concepts:**
- **TUMBLE**: Fixed-size, non-overlapping windows — each event belongs to exactly one window
- **HOP**: Sliding windows — each event appears in multiple overlapping windows
- **CUMULATE**: Expanding windows within a period — running totals that reset at period boundaries
- **window_start / window_end**: Special columns for grouping by window boundaries

**Step-by-Step Walkthrough:**

1. **TUMBLE window — periodic reporting**
```java
Table tumbleResult = tableEnv.sqlQuery(
    "SELECT window_start, window_end, "
    + "  CAST(pickupLatitude * 10 AS INT) AS lat_zone, "
    + "  COUNT(*) AS trip_count, "
    + "  AVG(totalAmount) AS avg_fare "
    + "FROM TABLE(TUMBLE(TABLE trips, DESCRIPTOR(dropoffDatetime), INTERVAL '10' MINUTES)) "
    + "GROUP BY window_start, window_end, CAST(pickupLatitude * 10 AS INT)");
```

**Why This Matters:** TUMBLE is the simplest window type. Each event falls into exactly one 10-minute bucket. Ideal for periodic reports like "how many trips per area every 10 minutes?" Unlike unbounded GROUP BY, windowed aggregations automatically clean up state when the window closes.

2. **HOP window — trend detection**
```sql
SELECT window_start, window_end,
  COUNT(*) AS trip_count, AVG(totalAmount) AS avg_fare
FROM TABLE(HOP(TABLE trips, DESCRIPTOR(dropoffDatetime),
  INTERVAL '5' MINUTES, INTERVAL '15' MINUTES))
GROUP BY window_start, window_end
```

**Why This Matters:** Each event appears in 3 windows (ceil(15/5) = 3). This produces a smoothed moving average that updates every 5 minutes, useful for detecting demand trends without the noise of individual events.

3. **CUMULATE window — running totals**
```sql
SELECT window_start, window_end,
  SUM(totalAmount) AS cumulative_fare, COUNT(*) AS cumulative_trips
FROM TABLE(CUMULATE(TABLE trips, DESCRIPTOR(dropoffDatetime),
  INTERVAL '10' MINUTES, INTERVAL '1' HOUR))
GROUP BY window_start, window_end
```

**Why This Matters:** CUMULATE produces expanding windows within each hour: [0,10), [0,20), [0,30), ... [0,60). This answers "how much revenue have we accumulated so far this hour?" — updated every 10 minutes. The totals reset at each hour boundary.

**Hands-On Exercise:**
1. Run the TUMBLE query (`--query tumble`) and observe periodic trip statistics
2. Switch to the HOP query (`--query hop`) and compare the smoothed output
3. Try the CUMULATE query (`--query cumulate`) and watch running totals grow within each hour
4. Experiment with different window sizes and observe the impact on output frequency and latency


---

### Example 5: SQL Joins
**File:** `SqlJoinsExample.java`
**Duration:** 40 minutes
**Difficulty:** Intermediate-Advanced

**Business Problem:**
You need to correlate different aspects of taxi trips: match pickup events with dropoff events, find incomplete trips, enforce time-bounded correlations, and enrich trips with zone-based pricing that changes over time.

**Key Concepts:**
- **INNER JOIN**: Match rows from two streams on a key
- **LEFT OUTER JOIN**: Keep all rows from the left side, NULLs for unmatched right side
- **Interval Join**: Time-bounded join using BETWEEN on timestamps
- **Temporal Join**: Lookup against a versioned reference table using FOR SYSTEM_TIME AS OF

**Step-by-Step Walkthrough:**

1. **Create derived views for join demonstrations**
```java
tableEnv.executeSql(
    "CREATE TEMPORARY VIEW pickup_events AS "
    + "SELECT tripId, pickupLatitude, pickupLongitude, pickupDatetime FROM trips");

tableEnv.executeSql(
    "CREATE TEMPORARY VIEW dropoff_events AS "
    + "SELECT tripId, dropoffLatitude, dropoffLongitude, dropoffDatetime, totalAmount FROM trips");
```

2. **INNER JOIN — match pickups with dropoffs**
```java
Table innerJoinResult = tableEnv.sqlQuery(
    "SELECT p.tripId, p.pickupLatitude, p.pickupLongitude, p.pickupDatetime, "
    + "  d.dropoffLatitude, d.dropoffLongitude, d.dropoffDatetime, d.totalAmount "
    + "FROM pickup_events p "
    + "INNER JOIN dropoff_events d ON p.tripId = d.tripId");
```

**Why This Matters:** In streaming mode, a regular join keeps all state for both sides indefinitely. For production use, always configure state TTL (see Example 9) to prevent unbounded state growth.

3. **Interval Join — time-bounded correlation**
```sql
SELECT p.tripId, p.pickupDatetime, d.dropoffDatetime, d.totalAmount
FROM pickup_events p, dropoff_events d
WHERE p.tripId = d.tripId
AND d.dropoffDatetime BETWEEN p.pickupDatetime
    AND p.pickupDatetime + INTERVAL '2' HOUR
```

**Why This Matters:** Interval joins automatically clean up state for events outside the time window. This is much more efficient than a regular join for time-correlated streams — Flink only keeps events within the 2-hour window.

4. **Temporal Join — versioned reference lookup**
```sql
SELECT t.tripId, t.totalAmount, t.dropoffDatetime,
  CAST(t.pickupLatitude * 10 AS INT) % 4 + 1 AS zone_id,
  zp.price_per_mile
FROM trips t
JOIN zone_pricing FOR SYSTEM_TIME AS OF t.dropoffDatetime AS zp
  ON CAST(t.pickupLatitude * 10 AS INT) % 4 + 1 = zp.zone_id
```

**Why This Matters:** Temporal joins look up the reference value that was effective at the time of the event, not the latest value. This is essential for accurate billing when prices change over time.

**Hands-On Exercise:**
1. Run the INNER JOIN (`--query inner`) and observe matched pickup-dropoff pairs
2. Switch to LEFT OUTER JOIN (`--query left-outer`) and look for NULL dropoff fields (incomplete trips)
3. Try the interval join (`--query interval`) and experiment with different time bounds
4. Run the temporal join (`--query temporal`) and observe how zone pricing is applied per-event


---

### Example 6: Pattern Matching with MATCH_RECOGNIZE
**File:** `MatchRecognizeExample.java`
**Duration:** 40 minutes
**Difficulty:** Advanced

**Business Problem:**
You need to detect various fare patterns in real time: surges, drops, V-shaped recoveries, high-value streaks, and spike-then-plateau sequences. These patterns help identify demand spikes, pricing anomalies, or emerging hotspots across geographic zones.

**Key Concepts:**
- **MATCH_RECOGNIZE**: SQL clause for complex event processing (CEP) on streams
- **PARTITION BY**: Detect patterns independently per geographic zone (must be a simple column)
- **PATTERN**: Regular expression-like syntax with single and multi-variable sequences
- **DEFINE**: Conditions that pattern variables must satisfy
- **MEASURES**: Extract business metrics from matched sequences (FIRST, LAST, COUNT, SUM, AVG)
- **AFTER MATCH SKIP**: Control whether matches can overlap

**Important prerequisites:**

MATCH_RECOGNIZE's `PARTITION BY` only accepts simple column references — expressions cause a validation error. All queries use a pre-computed view:
```java
tableEnv.executeSql(
    "CREATE TEMPORARY VIEW trips_with_zone AS "
    + "SELECT *, CAST(pickupLatitude * 10 AS INT) AS lat_zone FROM trips");
```

**Step-by-Step Walkthrough:**

The example file contains 5 queries, selected with `--query` (`surge`, `v-shape`, `high-value-streak`, `spike-plateau`, `fare-drop`). Each demonstrates a different pattern type.

1. **Query 1: Fare Surge — 3+ consecutive increasing fares** (active by default)

```sql
PATTERN (A{3,}?)
DEFINE A AS A.totalAmount > LAST(A.totalAmount, 1)
            OR LAST(A.totalAmount, 1) IS NULL
```

Single-variable pattern with a reluctant quantifier. Each event must have a higher fare than the previous. The `OR LAST(...) IS NULL` handles the first event in the sequence. Outputs start/end fares, surge length, and time range.

Example output: a surge of 5 trips where fares went $8 → $12 → $18 → $22 → $25 produces one row with `start_fare=8, end_fare=25, surge_length=5`.

2. **Query 2: V-Shaped Recovery — fare drops then recovers**

```sql
PATTERN (A B+? C)
DEFINE
  A AS A.totalAmount > 15,
  B AS B.totalAmount < LAST(B.totalAmount, 1)
       OR (LAST(B.totalAmount, 1) IS NULL AND B.totalAmount < A.totalAmount),
  C AS C.totalAmount > LAST(B.totalAmount)
```

Multi-variable pattern with 3 roles: A is the peak, B is one or more declining fares, C is the recovery. This demonstrates how different variables in a sequence can reference each other's values. Outputs peak fare, trough fare, and recovery fare with timestamps.

3. **Query 3: High-Value Streak — 5+ consecutive trips above $30**

```sql
PATTERN (A{5,}?)
DEFINE A AS A.totalAmount > 30
```

Simple threshold pattern — every event must exceed $30. Unlike the surge pattern, there's no comparison between consecutive events. Outputs streak length, total revenue, and average fare. Useful for detecting sustained high-demand periods.

4. **Query 4: Spike and Plateau — sharp increase then stable range**

```sql
PATTERN (A B C+?)
DEFINE
  A AS A.totalAmount < 15,
  B AS B.totalAmount > 2 * A.totalAmount,
  C AS C.totalAmount BETWEEN B.totalAmount * 0.8 AND B.totalAmount * 1.2
```

Three-variable pattern: A is a low fare, B is a spike (more than double A), C is one or more fares within ±20% of the spike. Demonstrates `BETWEEN` in DEFINE conditions and cross-variable references (`2 * A.totalAmount`). Outputs base fare, spike fare, plateau length, and plateau average.

5. **Query 5: Fare Drop Alert — 3+ consecutive decreasing fares**

```sql
PATTERN (A{3,}?)
DEFINE A AS A.totalAmount < LAST(A.totalAmount, 1)
            OR LAST(A.totalAmount, 1) IS NULL
```

Mirror of Query 1 but detects falling fares. Uses `AFTER MATCH SKIP TO NEXT ROW` instead of `SKIP PAST LAST ROW`, which means overlapping drops are detected — every possible starting point triggers a match. This demonstrates how the skip strategy affects output volume.

**Flink Streaming Limitations for MATCH_RECOGNIZE:**
- `ALL ROWS PER MATCH` is not supported — only `ONE ROW PER MATCH` works in streaming mode
- Greedy quantifiers (`A+`, `A{3,}`) cannot be the last pattern element — use reluctant (`A+?`, `A{3,}?`)
- `PARTITION BY` requires simple column references — pre-compute expressions in a view

**Hands-On Exercise:**
1. Run `--query surge` (fare surge) and observe detected patterns
2. Switch to `--query v-shape` and compare the multi-variable output
3. Try `--query spike-plateau` — adjust the spike threshold from `2 *` to `1.5 *`
4. Try `--query fare-drop` and notice how `SKIP TO NEXT ROW` produces more matches than `SKIP PAST LAST ROW`
5. Create your own pattern: detect a "round trip" where fare goes up then comes back down to within 10% of the start


#### MATCH_RECOGNIZE Reference

This section covers the full set of options available in Flink's MATCH_RECOGNIZE clause. Use it as a reference when designing your own patterns.

**Clause Structure:**
```sql
SELECT ...
FROM my_table
MATCH_RECOGNIZE (
  PARTITION BY <column>           -- optional: detect patterns per group
  ORDER BY <time_column>          -- required: event ordering
  MEASURES <expressions>          -- what to extract from matches
  ONE ROW PER MATCH | ALL ROWS PER MATCH  -- output mode
  AFTER MATCH SKIP <strategy>     -- where to resume after a match
  PATTERN (<regex>)               -- the event sequence to detect
  DEFINE <conditions>             -- conditions for pattern variables
)
```

**Pattern Quantifiers:**

| Quantifier | Meaning | Example |
|---|---|---|
| `A` | Exactly one event | Single event match |
| `A*` | Zero or more (greedy) | Optional repeating sequence |
| `A+` | One or more (greedy) | At least one event |
| `A?` | Zero or one (optional) | Optional single event |
| `A{3}` | Exactly 3 | Fixed-length sequence |
| `A{3,}` | 3 or more (greedy) | Minimum-length sequence |
| `A{3,5}` | Between 3 and 5 (greedy) | Bounded-length sequence |
| `A*?` | Zero or more (reluctant) | Shortest possible match |
| `A+?` | One or more (reluctant) | Shortest match with at least one |
| `A{3,5}?` | Between 3 and 5 (reluctant) | Shortest match within bounds |

Greedy quantifiers match as many events as possible; reluctant (non-greedy, with `?` suffix) match as few as possible.

> **Flink limitation:** Greedy quantifiers (`A+`, `A*`, `A{3,}`) are not allowed as the last element of a pattern. If your pattern ends with a repeating variable, use the reluctant form (`A+?`, `A*?`, `A{3,}?`). When `AFTER MATCH SKIP PAST LAST ROW` is used, the reluctant quantifier still consumes all matching events because the DEFINE condition controls when the sequence breaks — the match ends when the next event fails the condition, regardless of greediness.

**Multi-Variable Patterns:**

Patterns can reference multiple variables to detect sequences of different event types:

```sql
-- Detect a fare drop followed by a recovery: high fare, then low, then high again
PATTERN (A B+ C)
DEFINE
  A AS A.totalAmount > 20,
  B AS B.totalAmount < A.totalAmount,
  C AS C.totalAmount >= A.totalAmount
```

- `A B+ C` means: one A event, then one or more B events, then one C event — in sequence.
- Alternation: `A | B` matches either A or B.
- Grouping: `(A B)+ C` matches one or more (A followed by B) pairs, then C.
- Exclusion: `{- A -}` matches A but excludes it from the output (only with `ALL ROWS PER MATCH`).

**AFTER MATCH SKIP Strategies:**

Controls where pattern detection resumes after a successful match:

| Strategy | Behavior | Use Case |
|---|---|---|
| `SKIP PAST LAST ROW` | Resume after the last row of the match | Non-overlapping matches (default, most common) |
| `SKIP TO NEXT ROW` | Resume at the row after the first row of the match | Overlapping matches — detects every possible starting point |
| `SKIP TO FIRST <var>` | Resume at the first row mapped to `<var>` | Overlap starting from a specific pattern variable |
| `SKIP TO LAST <var>` | Resume at the last row mapped to `<var>` | Overlap starting from the end of a specific variable |

Example: with events [10, 15, 20, 25, 30] and `PATTERN (A{3,})` (increasing fares):
- `SKIP PAST LAST ROW` → one match: [10, 15, 20, 25, 30]
- `SKIP TO NEXT ROW` → matches starting at 10, 15, 20 (all valid 3+ sequences)

**Output Modes:**

| Mode | Output | When to Use |
|---|---|---|
| `ONE ROW PER MATCH` | Single summary row per match | Alerts, dashboards, aggregated metrics |
| `ALL ROWS PER MATCH` | Every row in the matched sequence | **Not supported in streaming mode (Flink 1.20).** Available in batch mode only |

With `ALL ROWS PER MATCH` (batch only), each output row includes the original columns plus the MEASURES columns. Use `CLASSIFIER()` in MEASURES to see which pattern variable each row was mapped to.

**MEASURES Functions:**

| Function | Description | Example |
|---|---|---|
| `FIRST(A.col)` | First value of column in variable A's matches | `FIRST(A.totalAmount)` — fare at start of surge |
| `FIRST(A.col, i)` | i-th value (0-based) in variable A | `FIRST(A.totalAmount, 2)` — third fare in sequence |
| `LAST(A.col)` | Last value of column in variable A's matches | `LAST(A.totalAmount)` — fare at end of surge |
| `LAST(A.col, i)` | i-th from last in variable A | `LAST(A.totalAmount, 1)` — second-to-last fare |
| `COUNT(A.col)` | Number of rows mapped to variable A | `COUNT(A.tripId)` — surge length |
| `SUM(A.col)` | Sum across variable A's matches | `SUM(A.totalAmount)` — total fare in surge |
| `AVG(A.col)` | Average across variable A's matches | `AVG(A.totalAmount)` — average fare in surge |
| `CLASSIFIER()` | Which variable the current row matched | Useful with `ALL ROWS PER MATCH` |
| `MATCH_NUMBER()` | Sequential match counter (1, 2, 3...) | Track how many patterns have been detected |

**Example Patterns for Taxi Data:**

1. **Fare drop then recovery (V-shape):**
```sql
PATTERN (A B+ C)
DEFINE
  A AS A.totalAmount > 15,
  B AS B.totalAmount < LAST(B.totalAmount, 1) OR (LAST(B.totalAmount, 1) IS NULL AND B.totalAmount < A.totalAmount),
  C AS C.totalAmount > LAST(B.totalAmount)
```
Detects a high fare followed by one or more decreasing fares, then a recovery.

2. **Idle period — gap in events:**
```sql
PATTERN (A B)
DEFINE
  B AS TIMESTAMPDIFF(MINUTE, A.dropoffDatetime, B.dropoffDatetime) > 30
```
Detects a gap of more than 30 minutes between consecutive events in a zone.

3. **High-value sequence — consecutive expensive trips:**
```sql
PATTERN (A{5,}?)
DEFINE
  A AS A.totalAmount > 30
```
Detects 5 or more consecutive trips above $30 in the same zone.

4. **Spike and plateau — sharp increase then stable:**
```sql
PATTERN (A B C+?)
DEFINE
  A AS A.totalAmount < 15,
  B AS B.totalAmount > 2 * A.totalAmount,
  C AS C.totalAmount BETWEEN B.totalAmount * 0.8 AND B.totalAmount * 1.2
```
Detects a low fare, then a spike to more than double, then one or more fares within 20% of the spike.

> **Gotcha — PARTITION BY requires simple column references.** You cannot use expressions like `CAST(pickupLatitude * 10 AS INT)` directly in PARTITION BY. Pre-compute the value in a view (as shown with `trips_with_zone` above) and partition by the column name.

---

### Example 7: Top-N Queries and Deduplication
**File:** `TopNDeduplicationExample.java`
**Duration:** 25 minutes
**Difficulty:** Intermediate

**Business Problem:**
You need to build a real-time leaderboard showing the top 3 highest fares per geographic zone, and you need to remove duplicate trip events caused by Kinesis retries or upstream producer issues.

**Key Concepts:**
- **ROW_NUMBER() OVER**: Assign a rank to each row within a partition
- **Top-N pattern**: Filter by row number to keep only the top N rows per group
- **Deduplication pattern**: Keep only the first occurrence of each key using ROW_NUMBER() = 1
- **Flink optimization**: Flink recognizes these patterns and uses efficient streaming operators

**Step-by-Step Walkthrough:**

1. **Top-N — Top 3 highest fares per zone**
```java
Table topNResult = tableEnv.sqlQuery(
    "SELECT * FROM ("
    + "  SELECT *, ROW_NUMBER() OVER ("
    + "    PARTITION BY CAST(pickupLatitude * 10 AS INT) "
    + "    ORDER BY totalAmount DESC"
    + "  ) AS row_num "
    + "  FROM trips"
    + ") WHERE row_num <= 3");
```

**Why This Matters:** Flink optimizes this into a streaming Top-N operator that maintains a small heap per partition (only 3 entries) rather than buffering all rows. This makes it efficient even for high-throughput streams. The leaderboard updates in real time as new trips arrive.

2. **Deduplication — keep first occurrence per tripId**
```sql
SELECT * FROM (
  SELECT *, ROW_NUMBER() OVER (
    PARTITION BY tripId
    ORDER BY dropoffDatetime ASC
  ) AS row_num
  FROM trips
) WHERE row_num = 1
```

**Why This Matters:** Flink recognizes `ROW_NUMBER() ... WHERE row_num = 1` as a deduplication pattern and optimizes it into a stateful operator that stores only the first-seen row per key. This is more efficient and controllable than using `SELECT DISTINCT`, especially for high-cardinality keys.

**Hands-On Exercise:**
1. Run the Top-3 query and observe the leaderboard updating in real time
2. Change N from 3 to 5 and observe the impact
3. Switch to the deduplication query (`--query dedup`) and verify each tripId appears only once
4. Modify the deduplication to keep the last occurrence instead (change ASC to DESC)

---

### Example 8: User-Defined Functions
**File:** `UdfExample.java` + `udf/` subpackage
**Duration:** 35 minutes
**Difficulty:** Intermediate-Advanced

**Business Problem:**
Standard SQL functions don't cover all your business logic. You need custom functions to compute geohashes from coordinates, split trips into zone-crossing events, and calculate distance-weighted average fares.

**Key Concepts:**
- **ScalarFunction**: One input row → one output value (e.g., geohash from lat/lon)
- **TableFunction**: One input row → zero or more output rows (e.g., split trip into zones)
- **AggregateFunction**: Many input rows → one output value (e.g., weighted average)
- **Programmatic registration** vs **CREATE FUNCTION DDL**

**Step-by-Step Walkthrough:**

1. **ScalarFunction — GeoHash**

```java
public class GeoHashFunction extends ScalarFunction {
    public String eval(double latitude, double longitude) {
        if (latitude < -90 || latitude > 90 || longitude < -180 || longitude > 180) {
            return "INVALID";
        }
        return GeoHash.geoHashStringWithCharacterPrecision(latitude, longitude, 5);
    }
}
```

Register and use in SQL:
```java
tableEnv.createTemporaryFunction("geohash", GeoHashFunction.class);

Table result = tableEnv.sqlQuery(
    "SELECT tripId, pickupLatitude, pickupLongitude, "
    + "  geohash(pickupLatitude, pickupLongitude) AS pickup_hash "
    + "FROM trips");
```

**Why This Matters:** Geohashes convert coordinates into a string that represents a grid cell (~4.9km × 4.9km at precision 5). Nearby locations share a common prefix, making geohashes useful for spatial grouping and indexing.

2. **TableFunction — Zone Splitter with LATERAL TABLE**

```java
@FunctionHint(output = @DataTypeHint("ROW<zone_type STRING, zone_name STRING>"))
public class ZoneSplitterFunction extends TableFunction<Row> {
    public void eval(double pickupLat, double pickupLon, double dropoffLat, double dropoffLon) {
        collect(Row.of("PICKUP_ZONE", getZone(pickupLat, pickupLon)));
        collect(Row.of("DROPOFF_ZONE", getZone(dropoffLat, dropoffLon)));
    }
}
```

Use with LATERAL TABLE:
```sql
SELECT t.tripId, z.zone_type, z.zone_name
FROM trips t,
LATERAL TABLE(zone_splitter(t.pickupLatitude, t.pickupLongitude,
  t.dropoffLatitude, t.dropoffLongitude)) AS z(zone_type, zone_name)
```

**Why This Matters:** Table functions expand one row into multiple rows. This is the SQL equivalent of a flatMap — each trip produces a PICKUP_ZONE row and a DROPOFF_ZONE row, enabling per-zone analysis.

3. **AggregateFunction — Weighted Average**

```java
public class WeightedAvgFunction extends AggregateFunction<Double, WeightedAvgAccumulator> {
    public void accumulate(WeightedAvgAccumulator acc, Double value, Double weight) {
        if (value != null && weight != null) {
            acc.weightedSum += value * weight;
            acc.weightCount += weight;
        }
    }

    @Override
    public Double getValue(WeightedAvgAccumulator acc) {
        return acc.weightCount == 0.0 ? 0.0 : acc.weightedSum / acc.weightCount;
    }
}
```

Use in SQL:
```sql
SELECT CAST(pickupLatitude * 10 AS INT) AS lat_zone,
  weighted_avg(totalAmount,
    CAST(GREATEST(TIMESTAMPDIFF(MINUTE, pickupDatetime, dropoffDatetime), 1) AS DOUBLE))
    AS weighted_fare
FROM trips
GROUP BY CAST(pickupLatitude * 10 AS INT)
```

**Why This Matters:** The weighted average gives longer trips more influence on the zone's average fare, producing a more representative metric than a simple average. The AggregateFunction also implements `retract()` and `merge()` for use in retract-based and session window aggregations.

#### Flink UDF Types Reference

Flink supports 5 kinds of user-defined functions. This workshop demonstrates the first 3; the remaining 2 are described below for reference.

| # | Type | Base Class | Signature | Demonstrated? |
|---|---|---|---|---|
| 1 | **Scalar Function** | `ScalarFunction` | 1 row → 1 value | ✅ `GeoHashFunction` |
| 2 | **Table Function** | `TableFunction<T>` | 1 row → 0..N rows | ✅ `ZoneSplitterFunction` |
| 3 | **Aggregate Function** | `AggregateFunction<T, ACC>` | N rows → 1 value | ✅ `WeightedAvgFunction` |
| 4 | **Table Aggregate Function** | `TableAggregateFunction<T, ACC>` | N rows → 0..N rows | ❌ Table API only (no SQL syntax) |
| 5 | **Async Table Function** | `AsyncTableFunction<T>` | 1 key → async lookup result | ❌ Reference pattern below |

**Table Aggregate Functions** work like regular aggregate functions but can emit multiple rows instead of one. They are only usable through the Table API (`flatAggregate()`), not in SQL. A typical use case is computing Top-N values per group without the ROW_NUMBER window trick.

**Async Table Functions** power asynchronous lookup joins — they let Flink look up reference data from an external system (DynamoDB, Redis, a REST API) without blocking the processing thread. They are wired into the SQL engine through the connector SPI, not registered directly like the other UDF types.

#### Async Table Functions (Lookup Joins) — Reference Pattern

An `AsyncTableFunction` enables non-blocking lookups against external stores during query execution. Flink invokes it automatically when you write a temporal join with `FOR SYSTEM_TIME AS OF` against a table backed by a lookup source.

Unlike ScalarFunction/TableFunction/AggregateFunction, you don't register an AsyncTableFunction directly with `createTemporaryFunction()`. Instead, it's wired through Flink's connector SPI:

1. Implement `AsyncTableFunction<RowData>` — the lookup logic
2. Implement `LookupTableSource` — tells Flink how to create the lookup function
3. Implement `DynamicTableSourceFactory` — the factory that Flink discovers via SPI
4. Register the factory in `META-INF/services/org.apache.flink.table.factories.Factory`

Here's what the lookup function itself looks like (simulated with a HashMap standing in for DynamoDB):

```java
/**
 * Reference pattern — not runnable without the TableFactory + SPI wiring.
 *
 * In production, replace the HashMap with an async DynamoDB/Redis/HTTP client
 * and call resultFuture.complete() in the callback.
 */
public class ZonePricingLookupFunction extends AsyncTableFunction<RowData> {

    // Simulates an external store (DynamoDB, Redis, etc.)
    private transient Map<Integer, Double> pricingCache;

    @Override
    public void open(FunctionContext context) {
        pricingCache = new HashMap<>();
        pricingCache.put(1, 2.50);  // zone 1: $2.50/mile
        pricingCache.put(2, 3.00);  // zone 2: $3.00/mile
        pricingCache.put(3, 2.75);  // zone 3: $2.75/mile
        pricingCache.put(4, 3.25);  // zone 4: $3.25/mile
    }

    public void eval(CompletableFuture<Collection<RowData>> resultFuture, int zoneId) {
        // In production: submit async request to DynamoDB/Redis here
        CompletableFuture.supplyAsync(() -> {
            Double price = pricingCache.getOrDefault(zoneId, 0.0);
            GenericRowData row = new GenericRowData(2);
            row.setField(0, zoneId);
            row.setField(1, price);
            return Collections.singletonList((RowData) row);
        }).thenAccept(resultFuture::complete);
    }
}
```

The SQL query that triggers the lookup is a standard temporal join:

```sql
SELECT t.tripId, t.totalAmount, zp.price_per_mile
FROM trips t
JOIN zone_pricing FOR SYSTEM_TIME AS OF t.dropoffDatetime AS zp
  ON CAST(t.pickupLatitude * 10 AS INT) % 4 + 1 = zp.zone_id
```

Flink sees that `zone_pricing` is backed by a `LookupTableSource` and automatically invokes the `AsyncTableFunction` for each join key, batching and parallelizing the lookups.

> **Why this isn't a runnable example:** The `DynamicTableSourceFactory` + SPI registration is ~100 lines of boilerplate that would obscure the actual lookup logic. In this workshop, the temporal join in `SqlJoinsExample` uses an in-memory versioned table instead. For a production async lookup connector, see the [Flink Connector Developer Guide](https://nightlies.apache.org/flink/flink-docs-release-1.20/docs/dev/table/sourcessinks/).

4. **CREATE FUNCTION DDL syntax (reference)**
```sql
CREATE TEMPORARY FUNCTION geohash
  AS 'com.amazonaws.samples.msf.taxi.consumer.flink_sql.udf.GeoHashFunction';
```

#### Looking Ahead: Process Table Functions (PTFs) — Flink 2.0+

Flink 2.0 introduces [Process Table Functions (PTFs)](https://nightlies.apache.org/flink/flink-docs-master/docs/dev/table/functions/ptfs/) — the most powerful function type available. PTFs are not available in Flink 1.20.x (the version used in this workshop and currently supported by Amazon MSF), but they're worth knowing about for future upgrades.

While ScalarFunction, TableFunction, and AggregateFunction cover most use cases, PTFs go further by giving you access to:

- **Flink managed state** — maintain state across rows with automatic checkpointing
- **Event-time and timers** — register callbacks that fire at specific event-time watermarks
- **Table changelogs** — process insert, update, and delete operations
- **Partitioned table input** — process rows grouped by key, similar to a keyed DataStream operator

A PTF is essentially a user-defined operator that can be as feature-rich as built-in operations like windowed aggregations or MATCH_RECOGNIZE. It maps zero, one, or multiple tables to output rows.

```java
// Flink 2.0+ only — a stateful PTF that greets customers and tracks visit count
public class GreetingWithMemory extends ProcessTableFunction<String> {
    public static class VisitState {
        public long counter = 0L;
    }

    public void eval(
        Context ctx,
        @StateHint VisitState state,
        @ArgumentHint(ArgumentTrait.SET_SEMANTIC_TABLE) Row input
    ) {
        state.counter++;
        String name = input.getFieldAs("name");
        collect("Hello " + name + ", visit #" + state.counter);
    }
}
```

```sql
-- Flink 2.0+ only
SELECT * FROM greeting_with_memory(TABLE customers PARTITION BY customer_id)
```

PTFs are the SQL-native way to implement what would otherwise require dropping to the DataStream API (KeyedProcessFunction, timers, custom state). On Flink 1.20.x, use the DataStream API for equivalent stateful processing (KeyedProcessFunction with ValueState and timers).

**Hands-On Exercise:**
1. Run the GeoHash query and observe geohash strings for pickup locations
2. Switch to the Zone Splitter query (`--query zone-split`) and see each trip expanded into two zone rows
3. Try the Weighted Average query (`--query weighted-avg`) and compare results with a simple AVG
4. Create your own ScalarFunction that categorizes fares as "low" / "medium" / "high"

---

### Example 9: SQL Hints and Query Optimization
**File:** `SqlOptimizationExample.java`
**Duration:** 30 minutes
**Difficulty:** Advanced

**Business Problem:**
Your streaming SQL aggregation works in development but in production the state grows unbounded, checkpoints slow down, and downstream systems are overwhelmed by per-record updates. You need to tune performance for production workloads.

**Key Concepts:**
- **State TTL**: Automatically expire state entries that haven't been accessed within a time limit
- **STATE_TTL SQL hint**: Set per-operator TTL directly in the query (Flink 1.18+) — different TTLs for joins vs aggregations in the same job
- **Mini-batch aggregation**: Buffer records and process them in batches to reduce state access overhead
- **Throughput vs latency tradeoffs**: Understand the impact of each optimization
- **MSF-specific considerations**: RocksDB state backend, KPU sizing, checkpoint tuning

**Step-by-Step Walkthrough:**

1. **Configure global State TTL**
```java
tableEnv.getConfig().set("table.exec.state.ttl", "3600000"); // 1 hour in ms
```

**Why This Matters:** Without TTL, unbounded aggregations (GROUP BY without windows) keep state for every key forever. Over time this causes out-of-memory errors or degraded checkpoint performance. TTL tells Flink to expire state entries not accessed within the configured duration. The tradeoff: setting TTL too low causes premature eviction and incorrect results (counters reset); setting it too high defeats the purpose.

2. **Configure Mini-Batch Aggregation**
```java
tableEnv.getConfig().set("table.exec.mini-batch.enabled", "true");
tableEnv.getConfig().set("table.exec.mini-batch.allow-latency", "5s");
tableEnv.getConfig().set("table.exec.mini-batch.size", "1000");
```

**Why This Matters:** By default, every incoming event triggers a state read, update, and downstream emit. Mini-batch buffers records and processes them together, reducing the number of state accesses and output records. The batch flushes when either the buffer reaches 1000 records or 5 seconds elapse. The tradeoff: results are delayed by up to the allow-latency value.

3. **Optimized aggregation query (global TTL)**
```java
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
```

4. **STATE_TTL hint — per-operator TTL on GROUP BY**

Instead of the global `table.exec.state.ttl`, you can set TTL per-operator directly in the SQL query using the `STATE_TTL` hint. This is powerful when a single job has multiple stateful operators that need different retention periods.

**Important:** When the source table was created via `fromDataStream()` + `createTemporaryView()` (as in this workshop), the hint resolver may not match the view name directly. The workaround is to wrap the view in a subquery with an explicit alias, then use that alias as the hint key. Tables created via SQL DDL (`CREATE TABLE`) can use the table name directly.

```sql
SELECT /*+ STATE_TTL('t' = '1m') */
  CAST(pickupLatitude * 10 AS INT) AS lat_zone,
  COUNT(*) AS trip_count,
  AVG(totalAmount) AS avg_fare
FROM (SELECT * FROM trips) t
GROUP BY CAST(pickupLatitude * 10 AS INT)
```

The hint overrides the global `table.exec.state.ttl` for this specific operator only. The `explainSql()` output will show `state=[ttl= '1m']` on the `GroupAggregate` node when the hint is applied correctly.

5. **STATE_TTL hint — per-side TTL on Regular Join**

For regular (non-windowed) joins, each side maintains its own state. `STATE_TTL` lets you set different TTLs per side — this is where hints really shine, because the two sides often have very different retention needs.

```sql
SELECT /*+ STATE_TTL('p' = '2h', 'd' = '30m') */
  p.tripId, p.pickupLatitude, p.pickupLongitude, p.pickupDatetime,
  d.dropoffLatitude, d.dropoffLongitude, d.dropoffDatetime, d.totalAmount
FROM (SELECT tripId, pickupLatitude, pickupLongitude, pickupDatetime FROM trips) p
INNER JOIN (SELECT tripId, dropoffLatitude, dropoffLongitude, dropoffDatetime, totalAmount FROM trips) d
ON p.tripId = d.tripId
```

**Why This Matters:** Pickup events arrive first and must wait for the matching dropoff, so they need longer retention (2 hours). Dropoff events arrive shortly after and can expire quickly once matched (30 minutes). With the global TTL, both sides would share the same value — either too long for dropoffs (wasting state) or too short for pickups (losing matches). The hint solves this cleanly.

**STATE_TTL hint rules:**
- Works on regular joins and GROUP BY aggregations (not windowed aggregations — those clean up automatically)
- The hint key must match the table name, view name, or alias in the query
- For tables created via `fromDataStream()` + `createTemporaryView()`, wrap in a subquery with an alias: `FROM (SELECT * FROM my_view) t` and use `STATE_TTL('t' = '...')`
- Tables created via SQL DDL (`CREATE TABLE`) can use the table name directly as the hint key
- Supports time units: `'30m'`, `'2h'`, `'1d'`, `'360000s'`, `'15h'`
- Overrides the global `table.exec.state.ttl` for the targeted operator only
- For cascade joins (`A JOIN B JOIN C`), specify all three tables in one hint

**Hands-On Exercise:**
1. Run `--query ttl-hint` (STATE_TTL hint with 1-minute TTL) — this is the default
2. Check the console for the `explainSql()` output — verify `STATE_TTL` appears in the physical plan
3. Open the Flink Web UI at `http://localhost:8081` → Running Jobs → Checkpoints tab
4. Watch checkpoint sizes stabilize as expired state is cleaned up (with 3600x speedup, 1 minute of event time passes in ~1/60th of a second)
5. Switch to `--query global-ttl-minibatch` and compare batched output (results arrive in bursts, not per-record)
6. Try `--query ttl-hint-join` and observe matched pickup-dropoff pairs with asymmetric retention
7. Run `--query no-minibatch` (same aggregation, mini-batch disabled) and compare output frequency

**Observing STATE_TTL Eviction Locally:**

When running in IntelliJ with the 3600x speedup producer, state TTL eviction is observable almost immediately. Here's how to verify it's working:

1. **`explainSql()` — verify the hint is in the plan (pre-runtime check)**

   The example calls `explainSql()` before executing the query and logs the physical plan. Look for `state=[ttl= '1m']` (or your configured TTL) in the `GroupAggregate` or `Join` operator. If the hint is missing, the TTL is not being applied — check that the hint key matches the subquery alias exactly.

   ```
   INFO  - Query plan (verify STATE_TTL hint):
   == Optimized Physical Plan ==
   GroupAggregate(groupBy=[lat_zone], select=[...], state=[ttl= '1m'])
   ```

2. **Checkpoint sizes in the Web UI (runtime check)**

   Open `http://localhost:8081` → Running Jobs → your job → Checkpoints tab. The "State Size" column shows how much state each checkpoint captures. With TTL enabled and the 3600x speedup:
   - State size should stabilize after a few checkpoints (state is being evicted as fast as it's created)
   - Without TTL, state size grows monotonically with each checkpoint

3. **Comparison experiment**

   To really see the difference, run the same aggregation query twice:
   - First with `STATE_TTL('t' = '1m')` — watch checkpoint sizes stabilize
   - Then switch to `--query global-ttl-minibatch` (global TTL of 1 hour) — with 3600x speedup, 1 hour of event time passes in 1 second, so even the global TTL evicts quickly. To see unbounded growth, temporarily set `table.exec.state.ttl` to `0` (disabled) and observe checkpoint sizes growing linearly.

---

## Known Limitations

Flink SQL on Amazon Managed Service for Apache Flink (MSF) has several limitations to be aware of:

### Streaming SQL Constraints

| Limitation | Description | Workaround |
|---|---|---|
| Single sink per job | A streaming SQL job can only write to one sink | Use STATEMENT SET for multiple sinks, or split into separate jobs |
| No CTAS in streaming | CREATE TABLE AS SELECT is batch-only | CREATE TABLE first, then INSERT INTO separately |
| Unbounded state growth | GROUP BY without windows keeps state forever | Configure state TTL (global or per-operator via `STATE_TTL` hint) or use windowed aggregations |
| `toDataStream()` fails for update queries | Queries with GROUP BY, DISTINCT, joins, or Top-N produce update/retract streams that `toDataStream()` cannot handle | Use `toChangelogStream()` for any query that produces updates (see Best Practices) |
| Append-only sinks reject changelog streams | Kinesis, standard Kafka, and other append-only sinks cannot consume update/retract results from GROUP BY, joins, or Top-N queries | Use windowed aggregations for append-only output, use `upsert-kafka` or database sinks for changelogs, or drop to DataStream API to filter row kinds (see Best Practices) |
| Greedy quantifiers cannot end a MATCH_RECOGNIZE pattern | `PATTERN (A{3,})` or `PATTERN (A+)` as the last element throws a validation error | Use the reluctant form: `A{3,}?`, `A+?`. Behavior is equivalent when DEFINE controls the match boundary |
| ALL ROWS PER MATCH not supported in streaming | MATCH_RECOGNIZE only supports `ONE ROW PER MATCH` in streaming mode | Use `ONE ROW PER MATCH` and join back to the source stream if per-event detail is needed |
| STATE_TTL hint doesn't resolve `fromDataStream` view names | The hint resolver cannot match view names created via `fromDataStream()` + `createTemporaryView()` | Wrap the view in a subquery with an alias: `FROM (SELECT * FROM my_view) t` and use `STATE_TTL('t' = '...')`. Tables created via SQL DDL work directly |

### Connector and Catalog Limitations

| Limitation | Description | Workaround |
|---|---|---|
| Kinesis SQL connector packaging | The Kinesis SQL connector (`flink-sql-connector-kinesis`) differs from the DataStream connector | Use the DataStream-to-Table bridge as shown in these examples |
| Catalog persistence | The default in-memory catalog is lost on restart | Consider Hive Metastore (support varies by Flink version) |
| UDF deployment | No dynamic UDF loading — all functions must be in the application JAR | Package UDFs with your application at build time |

### Production Considerations

| Consideration | Impact | Recommendation |
|---|---|---|
| State backend | MSF uses RocksDB by default | Tune `block.cache-size` and `writebuffer.size` for write-heavy workloads |
| Checkpoint interval | Default may not be optimal | Start with 60–120 seconds, adjust via application properties |
| KPU sizing | Each KPU provides 1 vCPU + 4 GB memory | Increase KPU count for SQL jobs with heavy state to distribute across TaskManagers |
| Configuration timing | Changing config after query definition has no effect | Set all `table.exec.*` configuration before calling `sqlQuery()` |

## Best Practices

### Converting Table Results to DataStream: `toDataStream` vs `toChangelogStream`

When converting a Flink SQL `Table` result back to a `DataStream`, you must choose the right method based on the type of query:

- **`toDataStream()`** — Use for append-only queries where each row is a new, independent event. Examples: `SELECT ... FROM ... WHERE ...` (simple filters), `LATERAL TABLE` (table functions), window TVF aggregations (TUMBLE, HOP, CUMULATE), and `MATCH_RECOGNIZE` pattern matching.

- **`toChangelogStream()`** — Use for queries that produce updates or retractions, where previously emitted rows may need to be corrected. Examples: `GROUP BY` without windows (unbounded aggregations), `DISTINCT`, regular joins (`INNER JOIN`, `LEFT OUTER JOIN` on streaming tables), `Top-N` ranking queries (`ROW_NUMBER() ... WHERE row_num <= N`), and deduplication patterns.

**Why this matters:** Flink SQL queries that aggregate, join, or rank over unbounded streams produce a *changelog* — a stream of insert, update, and delete operations. `toDataStream()` only supports insert-only (append) streams and will throw a `TableException` at runtime if the query produces updates. `toChangelogStream()` handles the full changelog protocol, emitting `+I` (insert), `-U` (update before), `+U` (update after), and `-D` (delete) row kinds.

**Rule of thumb:** If your query has `GROUP BY`, `DISTINCT`, `JOIN` (on regular streaming tables), or `ROW_NUMBER()` with a filter, use `toChangelogStream()`. If it's a simple filter, projection, window TVF, or `MATCH_RECOGNIZE`, `toDataStream()` is fine.

```java
// Append-only query — toDataStream() is correct
Table filtered = tableEnv.sqlQuery("SELECT * FROM trips WHERE totalAmount > 50");
tableEnv.toDataStream(filtered).print("Filtered");

// Update query — must use toChangelogStream()
Table aggregated = tableEnv.sqlQuery(
    "SELECT CAST(pickupLatitude * 10 AS INT) AS zone, COUNT(*) AS cnt "
    + "FROM trips GROUP BY CAST(pickupLatitude * 10 AS INT)");
tableEnv.toChangelogStream(aggregated).print("Aggregated");
```

**Sink compatibility — why this matters for Kinesis, Kafka, and other append-only sinks:**

The same constraint applies when writing query results to external sinks via SQL `INSERT INTO`. Most streaming sinks — including the Kinesis connector and the standard Kafka connector — are append-only: they can only accept insert (`+I`) rows. If your query produces an update/retract changelog (GROUP BY, joins, Top-N, etc.), Flink will reject the sink at planning time with an error like *"Table sink doesn't support consuming update changes"*.

There is no built-in SQL-only way to convert a changelog stream into an append-only stream. Your options are:

1. **Restructure the query to be append-only.** Use windowed aggregations (TUMBLE, HOP, CUMULATE) instead of unbounded GROUP BY. Window results are emitted once when the window closes, producing only inserts.

2. **Use an upsert-capable sink.** The `upsert-kafka` connector, JDBC/database sinks, and OpenSearch sinks natively handle changelogs by performing upserts on a primary key. This is the cleanest approach when the downstream system supports it.

3. **Drop to the DataStream API.** Convert the Table to a changelog DataStream with `toChangelogStream()`, then attach any DataStream sink (KinesisStreamsSink, KafkaSink, FileSink, custom sinks, etc.). Once you call `toChangelogStream()`, you're back in DataStream land — the sink doesn't know the data came from SQL. The changelog rows are tagged with a `RowKind` (`+I` insert, `-U` update-before, `+U` update-after, `-D` delete), so you can filter, transform, or serialize them however you need before writing.

```java
// Example: convert SQL changelog to DataStream, filter to append-only, write to any sink
DataStream<Row> changelog = tableEnv.toChangelogStream(aggregated);

// Option A: keep only inserts and latest updates (drop retractions)
DataStream<Row> appendOnly = changelog
    .filter(row -> row.getKind() == RowKind.INSERT || row.getKind() == RowKind.UPDATE_AFTER);

// Option B: serialize the full changelog including RowKind so the consumer
// can reconstruct state (useful for CDC-style downstream processing)
DataStream<String> fullChangelog = changelog
    .map(row -> row.getKind() + " | " + row.toString());

// Attach any DataStream sink — Kinesis, Kafka, files, custom, etc.
appendOnly.sinkTo(myKinesisSink);
```

> **Caution:** Dropping retractions (Option A) means the downstream consumer sees stale values that were later corrected. This is acceptable for logging or monitoring dashboards but not for systems that need exact counts or totals. Serializing the full changelog (Option B) preserves correctness but requires the consumer to understand the changelog protocol.

### State Management

1. **Always configure state TTL for unbounded aggregations.** Any GROUP BY without a window accumulates state indefinitely. Set `table.exec.state.ttl` to match your business semantics (e.g., 1 hour if you only care about recent activity). For jobs with multiple stateful operators, use `STATE_TTL` SQL hints to set per-operator TTLs — e.g., longer retention for the slow side of a join, shorter for the fast side.

2. **Prefer windowed aggregations over unbounded GROUP BY.** TUMBLE, HOP, and CUMULATE windows automatically clean up state when the window closes. Use unbounded aggregations only when you truly need lifetime totals.

3. **Monitor state size.** Check the `lastCheckpointSize` CloudWatch metric on MSF (or the Checkpoints tab → "State Size" in the local Web UI at `http://localhost:8081`). If checkpoint size grows linearly over time, you need TTL or should switch to windowed aggregations. Use `tableEnv.explainSql(query)` to verify that `STATE_TTL` hints are reflected in the physical plan.

4. **Choose appropriate partition keys.** High-cardinality keys (e.g., tripId) create millions of state entries. Low-cardinality keys (e.g., geographic zone) keep state manageable.

### Performance Tuning

1. **Enable mini-batch for high-throughput aggregations.** Start with `allow-latency=5s` and `size=1000`, then tune based on your throughput and latency requirements.

2. **Use interval joins instead of regular joins** when events have a natural time correlation. Interval joins automatically bound state, while regular joins keep state indefinitely.

3. **Inspect query plans.** Use `tableEnv.explainSql(query)` to identify unnecessary shuffles or missing optimizations before deploying to production.

4. **Leverage operator chaining.** Flink chains operators by default for efficiency. Avoid disabling chaining unless you need per-operator metrics for debugging.

### Production Deployment

1. **Test locally first.** All examples use IntelliJRunHelper for local development with the Flink Web UI at `http://localhost:8081`. Verify correctness locally before deploying to MSF.

2. **Parameterize configuration.** Stream ARNs, regions, and tuning parameters should come from MSF application properties (`KinesisAnalyticsRuntime.getApplicationProperties()`), not hardcoded values.

3. **Handle missing dependencies gracefully.** Wrap `StreamTableEnvironment.create()` in a try-catch for `NoClassDefFoundError` and log which dependencies are needed.

4. **Plan for schema evolution.** When changing SQL queries or UDFs, consider state compatibility. Changing a GROUP BY key or aggregation function may require discarding existing state (no savepoint restore).

## Key Takeaways

By completing this workshop, you'll have mastered:

1. **DataStream-Table Interop**: Bridge existing DataStream pipelines with SQL queries
2. **SQL DDL/DML**: Define tables, views, and write queries using standard SQL
3. **Window TVFs**: Analyze data across time horizons with TUMBLE, HOP, and CUMULATE
4. **Join Strategies**: Correlate streams with inner, outer, interval, and temporal joins
5. **Pattern Matching**: Detect complex event sequences with MATCH_RECOGNIZE
6. **Ranking and Deduplication**: Build leaderboards and remove duplicates efficiently
7. **Custom Functions**: Extend SQL with scalar, table, and aggregate UDFs — plus reference patterns for async lookups and PTFs
8. **Production Optimization**: Tune state TTL, mini-batch, and MSF configuration

## Resources

- [Apache Flink SQL Documentation](https://nightlies.apache.org/flink/flink-docs-release-1.20/docs/dev/table/sql/overview/)
- [Flink Table API Documentation](https://nightlies.apache.org/flink/flink-docs-release-1.20/docs/dev/table/tableapi/)
- [Amazon Managed Service for Apache Flink](https://docs.aws.amazon.com/managed-flink/)
- [Window TVF Reference](https://nightlies.apache.org/flink/flink-docs-release-1.20/docs/dev/table/sql/queries/window-tvf/)
- [MATCH_RECOGNIZE Reference](https://nightlies.apache.org/flink/flink-docs-release-1.20/docs/dev/table/sql/queries/match_recognize/)
- [SQL Hints Reference (STATE_TTL, LOOKUP, Join Hints)](https://nightlies.apache.org/flink/flink-docs-release-1.20/docs/dev/table/sql/queries/hints/)
- [Process Table Functions (PTFs)](https://nightlies.apache.org/flink/flink-docs-master/docs/dev/table/functions/ptfs/)
- [User-Defined Functions Reference](https://nightlies.apache.org/flink/flink-docs-release-1.20/docs/dev/table/functions/udfs/)
- [Connector Developer Guide (Lookup Sources)](https://nightlies.apache.org/flink/flink-docs-release-1.20/docs/dev/table/sourcessinks/)

---

**Happy Querying! 🚀**
