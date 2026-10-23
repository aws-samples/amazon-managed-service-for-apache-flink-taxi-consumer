package com.amazonaws.samples.msf.taxi.consumer.flink_sql.udf;

import ch.hsr.geohash.GeoHash;
import org.apache.flink.table.functions.ScalarFunction;

/**
 * A scalar UDF that computes a geohash string from latitude and longitude coordinates.
 *
 * <p>Usage in Flink SQL:
 * <pre>
 *   tableEnv.createTemporaryFunction("geohash", GeoHashFunction.class);
 *   tableEnv.sqlQuery("SELECT geohash(pickupLatitude, pickupLongitude) FROM trips");
 * </pre>
 *
 * <p>Returns a geohash string with precision 5 (~4.9km x 4.9km grid cell).
 * Returns "INVALID" for coordinates outside valid ranges.
 */
public class GeoHashFunction extends ScalarFunction {

    private static final int PRECISION = 5;

    /**
     * Computes a geohash string from the given latitude and longitude.
     *
     * @param latitude  WGS84 latitude, must be in [-90, 90]; null returns "INVALID"
     * @param longitude WGS84 longitude, must be in [-180, 180]; null returns "INVALID"
     * @return geohash string of length 5, or "INVALID" for out-of-range coordinates
     */
    public String eval(Double latitude, Double longitude) {
        if (latitude == null || longitude == null) {
            return "INVALID";
        }
        if (latitude < -90 || latitude > 90 || longitude < -180 || longitude > 180) {
            return "INVALID";
        }
        return GeoHash.geoHashStringWithCharacterPrecision(latitude, longitude, PRECISION);
    }
}
