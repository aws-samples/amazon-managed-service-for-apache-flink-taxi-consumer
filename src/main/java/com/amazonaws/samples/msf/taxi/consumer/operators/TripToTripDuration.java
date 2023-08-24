package com.amazonaws.samples.msf.taxi.consumer.operators;

import ch.hsr.geohash.GeoHash;
import com.amazonaws.samples.msf.taxi.consumer.events.flink.TripDuration;
import com.amazonaws.samples.msf.taxi.consumer.events.kinesis.TripEvent;
import com.amazonaws.samples.msf.taxi.consumer.utils.GeoUtils;
import org.apache.flink.api.common.functions.FlatMapFunction;
import org.apache.flink.util.Collector;

import java.time.Duration;

public class TripToTripDuration implements FlatMapFunction<TripEvent, TripDuration> {
  @Override
  public void flatMap(TripEvent tripEvent, Collector<TripDuration> collector) {
    String pickupLocation = GeoHash.geoHashStringWithCharacterPrecision(tripEvent.pickupLatitude, tripEvent.pickupLongitude, 6);
    long tripDuration = Duration.between(tripEvent.pickupDatetime, tripEvent.dropoffDatetime).toMinutes();

    if (GeoUtils.nearJFK(tripEvent.dropoffLatitude, tripEvent.dropoffLongitude)) {
      collector.collect(new TripDuration(tripDuration, pickupLocation, "JFK"));
    } else if (GeoUtils.nearLGA(tripEvent.dropoffLatitude, tripEvent.dropoffLongitude)) {
      collector.collect(new TripDuration(tripDuration, pickupLocation, "LGA"));
    }
  }
}