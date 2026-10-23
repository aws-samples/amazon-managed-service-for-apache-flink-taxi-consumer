package com.amazonaws.samples.msf.taxi.consumer.flink_sql.udf;

import org.apache.flink.table.annotation.DataTypeHint;
import org.apache.flink.table.annotation.FunctionHint;
import org.apache.flink.table.functions.TableFunction;
import org.apache.flink.types.Row;

/**
 * A table UDF that splits a trip into pickup and dropoff zone events.
 *
 * <p>For each invocation, emits exactly 2 rows:
 * <ul>
 *   <li>Row("PICKUP_ZONE", zoneName) for the pickup coordinates</li>
 *   <li>Row("DROPOFF_ZONE", zoneName) for the dropoff coordinates</li>
 * </ul>
 *
 * <p>Zone mapping uses approximate NYC bounding boxes:
 * <ul>
 *   <li>Manhattan North: lat [40.76, 40.82], lon [-73.98, -73.93]</li>
 *   <li>Manhattan South: lat [40.70, 40.76], lon [-74.02, -73.97]</li>
 *   <li>Brooklyn: lat [40.57, 40.70], lon [-74.04, -73.85]</li>
 *   <li>Queens: lat [40.68, 40.80], lon [-73.93, -73.70]</li>
 *   <li>Other: valid coordinates not in any named zone</li>
 *   <li>UNKNOWN: coordinates outside valid ranges</li>
 * </ul>
 *
 * <p>Usage in Flink SQL:
 * <pre>
 *   tableEnv.createTemporaryFunction("zone_splitter", ZoneSplitterFunction.class);
 *   tableEnv.sqlQuery(
 *       "SELECT t.tripId, z.zone_type, z.zone_name " +
 *       "FROM trips t, LATERAL TABLE(zone_splitter(t.pickupLatitude, t.pickupLongitude, " +
 *       "  t.dropoffLatitude, t.dropoffLongitude)) AS z(zone_type, zone_name)");
 * </pre>
 */
@FunctionHint(output = @DataTypeHint("ROW<zone_type STRING, zone_name STRING>"))
public class ZoneSplitterFunction extends TableFunction<Row> {

    private static final double VALID_LAT_MIN = -90.0;
    private static final double VALID_LAT_MAX = 90.0;
    private static final double VALID_LON_MIN = -180.0;
    private static final double VALID_LON_MAX = 180.0;

    /**
     * Evaluates the function, emitting one row for the pickup zone and one for the dropoff zone.
     *
     * @param pickupLat  pickup latitude
     * @param pickupLon  pickup longitude
     * @param dropoffLat dropoff latitude
     * @param dropoffLon dropoff longitude
     */
    public void eval(Double pickupLat, Double pickupLon, Double dropoffLat, Double dropoffLon) {
        collect(Row.of("PICKUP_ZONE", getZone(pickupLat, pickupLon)));
        collect(Row.of("DROPOFF_ZONE", getZone(dropoffLat, dropoffLon)));
    }

    /**
     * Maps a coordinate pair to a NYC zone name.
     *
     * @param lat latitude
     * @param lon longitude
     * @return zone name, or "UNKNOWN" if outside valid coordinate ranges
     */
    public static String getZone(Double lat, Double lon) {
        if (lat == null || lon == null
                || lat < VALID_LAT_MIN || lat > VALID_LAT_MAX
                || lon < VALID_LON_MIN || lon > VALID_LON_MAX) {
            return "UNKNOWN";
        }

        // Manhattan North: lat [40.76, 40.82], lon [-73.98, -73.93]
        if (lat >= 40.76 && lat <= 40.82 && lon >= -73.98 && lon <= -73.93) {
            return "Manhattan North";
        }

        // Manhattan South: lat [40.70, 40.76], lon [-74.02, -73.97]
        if (lat >= 40.70 && lat <= 40.76 && lon >= -74.02 && lon <= -73.97) {
            return "Manhattan South";
        }

        // Brooklyn: lat [40.57, 40.70], lon [-74.04, -73.85]
        if (lat >= 40.57 && lat <= 40.70 && lon >= -74.04 && lon <= -73.85) {
            return "Brooklyn";
        }

        // Queens: lat [40.68, 40.80], lon [-73.93, -73.70]
        if (lat >= 40.68 && lat <= 40.80 && lon >= -73.93 && lon <= -73.70) {
            return "Queens";
        }

        return "Other";
    }
}
