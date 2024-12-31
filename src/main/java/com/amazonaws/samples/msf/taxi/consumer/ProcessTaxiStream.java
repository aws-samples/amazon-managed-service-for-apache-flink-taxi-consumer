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
import org.apache.flink.api.common.eventtime.WatermarkStrategy;
import org.apache.flink.api.java.functions.KeySelector;
import org.apache.flink.api.java.tuple.Tuple2;
import org.apache.flink.api.java.utils.ParameterTool;
import org.apache.flink.connector.kinesis.source.KinesisStreamsSource;
import org.apache.flink.connector.opensearch.sink.RestClientFactory;
import org.apache.flink.streaming.api.datastream.DataStream;
import org.apache.flink.streaming.api.environment.LocalStreamEnvironment;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;
import org.apache.flink.streaming.api.windowing.assigners.TumblingEventTimeWindows;
import org.apache.flink.streaming.runtime.operators.util.AssignerWithPunctuatedWatermarksAdapter;
import org.apache.flink.util.Preconditions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.regions.providers.DefaultAwsRegionProviderChain;

import java.time.Duration;
import java.util.Map;
import java.util.Properties;


public class ProcessTaxiStream {
    private static final Logger LOG = LoggerFactory.getLogger(ProcessTaxiStream.class);

    public static void main(String[] args) throws Exception {
        StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();

        // Read configuration parameters from command line, when running locally,
        // or from the runtime environment, when running in Amazon Managed Service for Apache Flink
        ParameterTool parameter;
        if (env instanceof LocalStreamEnvironment) {
            // Read the parameters specified from the command line
            parameter = ParameterTool.fromArgs(args);
        } else {
            // Read the parameters from the runtime environment
            Map<String, Properties> applicationProperties = KinesisAnalyticsRuntime.getApplicationProperties();

            Properties flinkProperties = applicationProperties.get("FlinkApplicationProperties");

            if (flinkProperties == null) {
                throw new RuntimeException("Unable to load FlinkApplicationProperties properties from the MSF (Kinesis Analytics) runtime.");
            }

            parameter = ParameterToolUtils.fromApplicationProperties(flinkProperties);
        }


        /// Add Kinesis source

        // Input stream ARN
        String streamArn = parameter.get("InputStreamArn");
        Preconditions.checkNotNull(streamArn, "InputStreamArn configuration parameter not defined");

        // Create the source
        KinesisStreamsSource<Event> kinesisSource = KinesisStreamsSource.<Event>builder()
                // Read events from the Kinesis stream passed in as a parameter
                .setStreamArn(streamArn)
                // Deserialize events
                .setDeserializationSchema(new EventDeserializationSchema())
                .build();

        // Attach Kinesis source to the dataflow
        DataStream<Event> kinesisStream = env.fromSource(
                        kinesisSource,
                        // Extract watermarks from watermark events
                        WatermarkStrategy.<Event>forGenerator(new AssignerWithPunctuatedWatermarksAdapter.Strategy<Event>(new TimestampAssigner()))
                                .withTimestampAssigner((event, ts) -> event.getTimestamp()),
                        "Kinesis source")
                .returns(Event.class);

        /// Define transformations

        // Retain only trip events within NYC
        DataStream<TripEvent> trips = kinesisStream
                // Remove all events that aren't TripEvents
                .filter(event -> TripEvent.class.isAssignableFrom(event.getClass()))
                // Cast Event to TripEvent
                .map(event -> (TripEvent) event)
                // Remove all events with geo coordinates outside of NYC
                .filter(GeoUtils::hasValidCoordinates);

        // Count pickups by geo-hash, every hour
        DataStream<PickupCount> pickupCounts = trips
                // (1) compute geo hash for every event
                .map(new TripToGeoHash())
                // (2) partition by geo hash
                .keyBy(item -> item.geoHash)
                // (3) collect all events in a 1-hour window
                .window(TumblingEventTimeWindows.of(Duration.ofHours(1)))
                // (4) count events per geo hash in the 1-hour window
                .apply(new CountByGeoHash());

        // Calculate average trip duration to airports, every hour
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
                // (3) collect all trip durations in the 1-hour window
                .window(TumblingEventTimeWindows.of(Duration.ofHours(1)))
                // (4) calculate average trip duration, per pickup geo hash and destination airport, in the 1-hour window
                .apply(new TripDurationToAverageTripDuration());

        /// Add OpenSource sinks

        // OpenSearch endpoint
        String opensearchEndpoint = parameter.get("OpenSearchEndpoint");
        Preconditions.checkNotNull(opensearchEndpoint, "OpenSearchEndpoint configuration parameter not defined");

        // AWS Region (uses the current region when running in Managed Flink or EC2)
        DefaultAwsRegionProviderChain regionProvider = new DefaultAwsRegionProviderChain();
        String defaultRegion = regionProvider.getRegion() == null ? "us-west-1" : regionProvider.getRegion().id();
        String region = parameter.get("Region", defaultRegion);

        RestClientFactory osRestClientFactory = AmazonOpenSearchServiceSink.createAmazonOpenSearchSigningRestClientFactory(region);

        // Attach 2 sinks to the dataflow, one per OpenSearch index
        pickupCounts.sinkTo(AmazonOpenSearchServiceSink.buildAmazonOpenSearchSink("pickup_count", opensearchEndpoint, osRestClientFactory));
        tripDurations.sinkTo(AmazonOpenSearchServiceSink.buildAmazonOpenSearchSink("trip_duration", opensearchEndpoint, osRestClientFactory));


        LOG.info("Reading events from Kinesis stream {}", streamArn);
        LOG.info("Writing output to OpenSearch endpoint {}", opensearchEndpoint);

        // Execute the dataflow
        env.execute();
    }

}