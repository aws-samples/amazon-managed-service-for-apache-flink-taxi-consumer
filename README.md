## Amazon Managed Service for Apache Flink - Taxi Consumer

Sample Apache Flink application that can be deployed to [Amazon Managed Service for Apache Flink](https://aws.amazon.com/managed-service-apache-flink/) 
(formerly known as Amazon Kinesis Data Analytics). 

The application reads taxi events from a Kinesis data stream, processes and aggregates them, and ingests the result to an 
Amazon OpenSearch Service cluster for visualization with Kibana.

### Data generator

This example application expects a dataset that can be published into a Kinesis Data Stream using 
[Kinesis Data Replay](https://github.com/aws-samples/amazon-kinesis-replay).

By default, [Kinesis Data Replay](https://github.com/aws-samples/amazon-kinesis-replay) publishes a historic data set of 
taxi trips that made in New York City into a Kinesis Data Stream.
Data are based on a public dataset, also [available from the Registry of Open Data on AWS](https://registry.opendata.aws/nyc-tlc-trip-records-pds/).

## Amazon Managed Service for Apache Flink workshop

An end-to-end workshop, to learn how to modify and build this Flink application, deploy it on Amazon Managed Service for Apache Flink, 
and visualize the result in a dashboard, is available on [AWS Workshop Studio](https://catalog.us-east-1.prod.workshops.aws/workshops/2437ee74-38ed-4817-94b9-45b920cb168a).

## License

This sample is licensed under the Apache 2.0 License.
