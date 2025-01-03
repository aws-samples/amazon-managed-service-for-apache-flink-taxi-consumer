package com.amazonaws.samples.msf.taxi.consumer.operators;

import ch.hsr.geohash.GeoHash;
import com.amazonaws.samples.msf.taxi.consumer.events.flink.TripGeoHash;
import com.amazonaws.samples.msf.taxi.consumer.events.kinesis.TripEvent;
import org.apache.flink.api.common.functions.MapFunction;

public class TripToGeoHash implements MapFunction<TripEvent, TripGeoHash> {
    @Override
    public TripGeoHash map(TripEvent tripEvent) {
        return new TripGeoHash(GeoHash.geoHashStringWithCharacterPrecision(tripEvent.pickupLatitude, tripEvent.pickupLongitude, 7));
    }
}

