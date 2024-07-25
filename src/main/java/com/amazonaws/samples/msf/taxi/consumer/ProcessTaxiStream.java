/*
 * Copyright 2023 Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"). You may
 * not use this file except in compliance with the License. A copy of the
 * License is located at
 *
 *    http://aws.amazon.com/apache2.0/
 *
 * or in the "license" file accompanying this file. This file is distributed
 * on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 * express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */

package com.amazonaws.samples.msf.taxi.consumer;

import com.amazonaws.samples.msf.taxi.consumer.events.EventDeserializationSchema;
import com.amazonaws.samples.msf.taxi.consumer.events.TimestampAssigner;
import com.amazonaws.samples.msf.taxi.consumer.events.es.AverageTripDuration;
import com.amazonaws.samples.msf.taxi.consumer.events.es.PickupCount;
import com.amazonaws.samples.msf.taxi.consumer.events.flink.TripDuration;
import com.amazonaws.samples.msf.taxi.consumer.events.kinesis.Event;
import com.amazonaws.samples.msf.taxi.consumer.events.kinesis.TripEvent;
import com.amazonaws.samples.msf.taxi.consumer.operators.*;
import com.amazonaws.samples.msf.taxi.consumer.utils.GeoUtils;
import com.amazonaws.samples.msf.taxi.consumer.utils.ParameterToolUtils;
import com.amazonaws.services.kinesisanalytics.runtime.KinesisAnalyticsRuntime;
import org.apache.flink.api.java.functions.KeySelector;
import org.apache.flink.api.java.tuple.Tuple2;
import org.apache.flink.api.java.utils.ParameterTool;
import org.apache.flink.connector.opensearch.sink.RestClientFactory;
import org.apache.flink.kinesis.shaded.com.amazonaws.regions.Regions;
import org.apache.flink.streaming.api.datastream.DataStream;
import org.apache.flink.streaming.api.environment.LocalStreamEnvironment;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;
import org.apache.flink.streaming.api.windowing.assigners.TumblingEventTimeWindows;
import org.apache.flink.streaming.api.windowing.time.Time;
import org.apache.flink.streaming.connectors.kinesis.FlinkKinesisConsumer;
import org.apache.flink.streaming.connectors.kinesis.config.AWSConfigConstants;
import org.apache.flink.streaming.connectors.kinesis.config.ConsumerConfigConstants;
import org.apache.flink.streaming.runtime.operators.util.AssignerWithPunctuatedWatermarksAdapter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.Properties;


public class ProcessTaxiStream {
    private static final Logger LOG = LoggerFactory.getLogger(ProcessTaxiStream.class);

    private static final String DEFAULT_STREAM_NAME = "managed-flink-workshop";
    private static final String DEFAULT_REGION_NAME = Regions.getCurrentRegion() == null ? "us-west-1" : Regions.getCurrentRegion().getName();


    public static void main(String[] args) throws Exception {
        StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();


        ParameterTool parameter;

        if (env instanceof LocalStreamEnvironment) {
            // read the parameters specified from the command line
            parameter = ParameterTool.fromArgs(args);
        } else {
            // read the parameters from the Kinesis Analytics environment
            Map<String, Properties> applicationProperties = KinesisAnalyticsRuntime.getApplicationProperties();

            Properties flinkProperties = applicationProperties.get("FlinkApplicationProperties");

            if (flinkProperties == null) {
                throw new RuntimeException("Unable to load FlinkApplicationProperties properties from the MSF (Kinesis Analytics) runtime.");
            }

            parameter = ParameterToolUtils.fromApplicationProperties(flinkProperties);
        }


        // Set Kinesis consumer properties
        Properties kinesisConsumerConfig = new Properties();
        // Set the region the Kinesis stream is located in
        kinesisConsumerConfig.setProperty(AWSConfigConstants.AWS_REGION, parameter.get("Region", DEFAULT_REGION_NAME));
        // Obtain credentials through the DefaultCredentialsProviderChain, which includes the instance metadata
        kinesisConsumerConfig.setProperty(AWSConfigConstants.AWS_CREDENTIALS_PROVIDER, "AUTO");
        // Poll new events from the Kinesis stream once every second
        kinesisConsumerConfig.setProperty(ConsumerConfigConstants.SHARD_GETRECORDS_INTERVAL_MILLIS, "1000");


        // Create Kinesis source
        DataStream<Event> kinesisStream = env.addSource(new FlinkKinesisConsumer<>(
                // Read events from the Kinesis stream passed in as a parameter
                parameter.get("InputStreamName", DEFAULT_STREAM_NAME),
                // Deserialize events with EventSchema
                new EventDeserializationSchema(),
                // Using the previously defined properties
                kinesisConsumerConfig
        ));


        DataStream<TripEvent> trips = kinesisStream
                // Extract watermarks from watermark events
                .assignTimestampsAndWatermarks(new AssignerWithPunctuatedWatermarksAdapter.Strategy<>(new TimestampAssigner()))
                // Remove all events that aren't TripEvents
                .filter(event -> TripEvent.class.isAssignableFrom(event.getClass()))
                // Cast Event to TripEvent
                .map(event -> (TripEvent) event)
                // Remove all events with geo coordinates outside of NYC
                .filter(GeoUtils::hasValidCoordinates);


        DataStream<PickupCount> pickupCounts = trips
                // (1) compute geo hash for every event
                .map(new TripToGeoHash())
                // (2) partition by geo hash
                .keyBy(item -> item.geoHash)
                // (3) collect all events in a one hour window
                .window(TumblingEventTimeWindows.of(Time.hours(1)))
                // (4) count events per geo hash in the one hour window
                .apply(new CountByGeoHash());


        DataStream<AverageTripDuration> tripDurations = trips
                // (1) trips to trip durations, only retaining trips to the airports
                .flatMap(new TripToTripDuration())
                // (2) partition by pickup location geo hash and destination airport
                .keyBy(new KeySelector<TripDuration, Tuple2<String, String>>() {
                    @Override
                    public Tuple2<String, String> getKey(TripDuration item) throws Exception {
                        return Tuple2.of(item.pickupGeoHash, item.airportCode);
                    }
                })
                //(3) collect all trip durations in the one hour window
                .window(TumblingEventTimeWindows.of(Time.hours(1)))
                //(4) calculate average trip duration, per pickup geo hash and destination airport, in the one hour window
                .apply(new TripDurationToAverageTripDuration());


        // Create OpenSearch sink
        if (parameter.has("OpenSearchEndpoint")) {
            String opensearchEndpoint = parameter.get("OpenSearchEndpoint");
            final String region = parameter.get("Region", DEFAULT_REGION_NAME);

            RestClientFactory osRestClientFactory = AmazonOpenSearchServiceSink.createAmazonOpenSearchSigningRestClientFactory(region);

            // 2x sinks, one per OpenSearch index
            pickupCounts.sinkTo(AmazonOpenSearchServiceSink.buildAmazonOpenSearchSink("pickup_count", opensearchEndpoint, osRestClientFactory));
            tripDurations.sinkTo(AmazonOpenSearchServiceSink.buildAmazonOpenSearchSink("trip_duration", opensearchEndpoint, osRestClientFactory));
        }


        LOG.info("Reading events from stream {}", parameter.get("InputStreamName", DEFAULT_STREAM_NAME));

        env.execute();
    }

}