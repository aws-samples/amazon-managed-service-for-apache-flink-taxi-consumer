package com.amazonaws.samples.msf.taxi.consumer.events.kinesis;

import com.google.gson.*;
import com.google.gson.internal.Streams;
import com.google.gson.stream.JsonReader;

import java.io.ByteArrayInputStream;
import java.io.InputStreamReader;
import java.time.Instant;

public class TripEvent {
    public final long tripId;
    public final double pickupLatitude;
    public final double pickupLongitude;
    public final double dropoffLatitude;
    public final double dropoffLongitude;
    public final double totalAmount;
    public final Instant pickupDatetime;
    public final Instant dropoffDatetime;

    public TripEvent() {
        tripId = 0;
        pickupLatitude = 0;
        pickupLongitude = 0;
        dropoffLatitude = 0;
        dropoffLongitude = 0;
        totalAmount = 0;
        pickupDatetime = Instant.EPOCH;
        dropoffDatetime = Instant.EPOCH;
    }

    private static final Gson gson = new GsonBuilder()
            .setFieldNamingPolicy(FieldNamingPolicy.LOWER_CASE_WITH_UNDERSCORES)
            .registerTypeAdapter(Instant.class, (JsonDeserializer<Instant>) (json, typeOfT, context) -> Instant.parse(json.getAsString()))
            .create();

    public static TripEvent parseEvent(byte[] event) {
        //parse the event payload and remove the type attribute
        JsonReader jsonReader =  new JsonReader(new InputStreamReader(new ByteArrayInputStream(event)));
        JsonElement jsonElement = Streams.parse(jsonReader);

        return gson.fromJson(jsonElement, TripEvent.class);
    }

    @Override
    public String toString() {
        return "TripEvent{" +
                "tripId=" + tripId +
                ", pickupLatitude=" + pickupLatitude +
                ", pickupLongitude=" + pickupLongitude +
                ", dropoffLatitude=" + dropoffLatitude +
                ", dropoffLongitude=" + dropoffLongitude +
                ", totalAmount=" + totalAmount +
                ", pickupDatetime=" + pickupDatetime +
                ", dropoffDatetime=" + dropoffDatetime +
                '}';
    }
}
