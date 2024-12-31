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
import com.amazonaws.samples.msf.taxi.consumer.events.kinesis.Event;
import com.amazonaws.samples.msf.taxi.consumer.events.kinesis.TripEvent;
import com.amazonaws.samples.msf.taxi.consumer.utils.GeoUtils;
import org.apache.flink.api.common.eventtime.WatermarkStrategy;
import org.apache.flink.api.java.utils.ParameterTool;
import org.apache.flink.connector.kinesis.source.KinesisStreamsSource;
import org.apache.flink.streaming.api.datastream.DataStream;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;
import org.apache.flink.streaming.runtime.operators.util.AssignerWithPunctuatedWatermarksAdapter;
import org.apache.flink.util.Preconditions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


public class ProcessTaxiStreamLocal {
    private static final Logger LOG = LoggerFactory.getLogger(ProcessTaxiStreamLocal.class);

    public static void main(String[] args) throws Exception {
        StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();


        // Read the parameters specified from the command line
        ParameterTool parameter = ParameterTool.fromArgs(args);

        /// Add the Kinesis source

        // Input stream ARN
        String streamArn = parameter.get("InputStreamArn");
        Preconditions.checkNotNull(streamArn, "InputStreamArn configuration parameter not defined");

        // Create the Kinesis source
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


        // Retain only trip events within NYC
        DataStream<TripEvent> trips = kinesisStream
                // Remove all events that aren't TripEvents
                .filter(event -> TripEvent.class.isAssignableFrom(event.getClass()))
                // Cast Event to TripEvent
                .map(event -> (TripEvent) event)
                // Remove all events with geo coordinates outside of NYC
                .filter(GeoUtils::hasValidCoordinates);


        // Print trip events to stdout
        trips.print();

        LOG.info("Reading events from Kinesis stream {}", streamArn);

        // Execute the dataflow
        env.execute();
    }
}
