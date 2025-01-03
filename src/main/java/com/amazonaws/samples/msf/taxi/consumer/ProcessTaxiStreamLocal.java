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
import com.amazonaws.samples.msf.taxi.consumer.events.kinesis.TripEvent;
import com.amazonaws.samples.msf.taxi.consumer.utils.GeoUtils;
import org.apache.flink.api.common.eventtime.WatermarkStrategy;
import org.apache.flink.api.java.utils.ParameterTool;
import org.apache.flink.connector.kinesis.source.KinesisStreamsSource;
import org.apache.flink.streaming.api.datastream.DataStream;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;
import org.apache.flink.util.Preconditions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;


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
        KinesisStreamsSource<TripEvent> kinesisSource = KinesisStreamsSource.<TripEvent>builder()
                // Read events from the Kinesis stream passed in as a parameter
                .setStreamArn(streamArn)
                // Deserialize events
                .setDeserializationSchema(new EventDeserializationSchema())
                .build();

        // Attach Kinesis source to the dataflow
        DataStream<TripEvent> kinesisStream = env.fromSource(
                        kinesisSource,
                        // Create watermarks delayed by 2 minutes, to allow for out-of-order events
                        WatermarkStrategy.<TripEvent>forBoundedOutOfOrderness(Duration.ofMinutes(2))
                                // Event-time is dropoff time
                                .withTimestampAssigner((event, ts) -> event.dropoffDatetime.toEpochMilli()),
                        "Kinesis source")
                .returns(TripEvent.class);


        // Retain only trip events within NYC
        DataStream<TripEvent> trips = kinesisStream
                // Remove all events with geo coordinates outside of NYC
                .filter(GeoUtils::hasValidCoordinates);


        // Print trip events to stdout
        trips.print();

        LOG.info("Reading events from Kinesis stream {}", streamArn);

        // Execute the dataflow
        env.execute();
    }
}
