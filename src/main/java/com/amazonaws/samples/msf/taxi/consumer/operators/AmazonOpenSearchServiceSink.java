/*
 * Copyright 2024 Amazon.com, Inc. or its affiliates. All Rights Reserved.
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

package com.amazonaws.samples.msf.taxi.consumer.operators;

import io.github.acm19.aws.interceptor.http.AwsRequestSigningApacheInterceptor;
import org.apache.flink.connector.opensearch.sink.OpensearchSink;
import org.apache.flink.connector.opensearch.sink.OpensearchSinkBuilder;
import org.apache.flink.connector.opensearch.sink.RestClientFactory;
import org.apache.http.HttpHost;
import org.apache.http.HttpRequestInterceptor;
import org.opensearch.action.index.IndexRequest;
import org.opensearch.client.Requests;
import org.opensearch.client.RestClientBuilder;
import org.opensearch.common.xcontent.XContentType;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.auth.signer.Aws4Signer;
import software.amazon.awssdk.regions.Region;

import java.net.MalformedURLException;
import java.net.URL;

/**
 * This class wraps the creation of an OpenSearch sink writing JSON payload to Amazon OpenSearch service
 */
public class AmazonOpenSearchServiceSink {

    private static IndexRequest createIndexRequest(String jsonElement, String index) {
        return Requests.indexRequest()
                .index(index)
                .source(jsonElement, XContentType.JSON);
    }

    private static String extractHost(String endpointUrl) {
        try {
            URL openSearchEndpointUrl = new URL(endpointUrl);
            return openSearchEndpointUrl.getHost();
        } catch (MalformedURLException e) {
            throw new RuntimeException("Invalid endpoint URL: " + endpointUrl, e);
        }
    }

    public static <IN> OpensearchSink<IN> buildAmazonOpenSearchSink(String index, String endpointUrl, RestClientFactory clientFactory) {
        String host = extractHost(endpointUrl);

        return new OpensearchSinkBuilder<IN>()
                .setHosts(new HttpHost(host, 443, "https"))
                .setAllowInsecure(true)
                .setBulkFlushMaxActions(1) // disable bulk writes for simplicity
                .setRestClientFactory(clientFactory)
                .setEmitter((element, ctx, indexer) ->
                        indexer.add(createIndexRequest(element.toString(), index)))
                .build();
    }

    public static RestClientFactory createAmazonOpenSearchSigningRestClientFactory(String region) {
        return new RestClientFactory() {
            @Override
            public void configureRestClientBuilder(RestClientBuilder restClientBuilder, RestClientConfig restClientConfig) {
                // http client interceptor implementing request signing for Amazon OpenSearch Service
                HttpRequestInterceptor interceptor = new AwsRequestSigningApacheInterceptor(
                        "es",
                        Aws4Signer.create(),
                        DefaultCredentialsProvider.create(),
                        Region.of(region));
                restClientBuilder.setHttpClientConfigCallback(httpAsyncClientBuilder -> httpAsyncClientBuilder.addInterceptorLast(interceptor));
            }
        };
    }
}
