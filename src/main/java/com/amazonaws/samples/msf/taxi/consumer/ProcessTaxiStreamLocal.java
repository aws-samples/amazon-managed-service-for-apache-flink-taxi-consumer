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
import com.amazonaws.samples.msf.taxi.consumer.events.kinesis.Event;
import com.amazonaws.samples.msf.taxi.consumer.events.kinesis.TripEvent;
import com.amazonaws.samples.msf.taxi.consumer.utils.GeoUtils;
import org.apache.flink.api.java.utils.ParameterTool;
import org.apache.flink.kinesis.shaded.com.amazonaws.regions.Regions;
import org.apache.flink.streaming.api.datastream.DataStream;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;
import org.apache.flink.streaming.connectors.kinesis.FlinkKinesisConsumer;
import org.apache.flink.streaming.connectors.kinesis.config.AWSConfigConstants;
import org.apache.flink.streaming.connectors.kinesis.config.ConsumerConfigConstants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Properties;


public class ProcessTaxiStreamLocal {
    private static final Logger LOG = LoggerFactory.getLogger(ProcessTaxiStreamLocal.class);

    private static final String DEFAULT_STREAM_NAME = "managed-flink-workshop";
    private static final String DEFAULT_REGION_NAME = Regions.getCurrentRegion() == null ? "us-west-1" : Regions.getCurrentRegion().getName();


    public static void main(String[] args) throws Exception {
        StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();


        // Read the parameters specified from the command line
        ParameterTool parameter = ParameterTool.fromArgs(args);


        Properties kinesisConsumerConfig = new Properties();
        // Set the region the Kinesis stream is located in
        kinesisConsumerConfig.setProperty(AWSConfigConstants.AWS_REGION, parameter.get("Region", DEFAULT_REGION_NAME));
        // Obtain credentials through the DefaultCredentialsProviderChain, which includes credentials from the instance metadata
        kinesisConsumerConfig.setProperty(AWSConfigConstants.AWS_CREDENTIALS_PROVIDER, "AUTO");
        // Poll new events from the Kinesis stream once every second
        kinesisConsumerConfig.setProperty(ConsumerConfigConstants.SHARD_GETRECORDS_INTERVAL_MILLIS, "1000");


        // Create Kinesis source
        DataStream<Event> kinesisStream = env.addSource(new FlinkKinesisConsumer<>(
                // Read events from the Kinesis stream passed in as a parameter
                parameter.get("InputStreamName", DEFAULT_STREAM_NAME),
                // Deserialize events with EventSchema
                new EventDeserializationSchema(),
                // Using the previously defined Kinesis consumer properties
                kinesisConsumerConfig
        ));


        DataStream<TripEvent> trips = kinesisStream
                // Remove all events that aren't TripEvents
                .filter(event -> TripEvent.class.isAssignableFrom(event.getClass()))
                // Cast Event to TripEvent
                .map(event -> (TripEvent) event)
                // Remove all events with geo coordinates outside of NYC
                .filter(GeoUtils::hasValidCoordinates);


        // Print trip events to stdout
        trips.print();


        LOG.info("Reading events from stream {}", parameter.get("InputStreamName", DEFAULT_STREAM_NAME));

        env.execute();
    }
}
