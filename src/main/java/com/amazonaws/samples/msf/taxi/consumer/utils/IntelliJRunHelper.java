package com.amazonaws.samples.msf.taxi.consumer.utils;

import org.apache.flink.configuration.Configuration;
import org.apache.flink.configuration.RestOptions;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;
import org.apache.flink.client.program.rest.RestClusterClient;
import org.apache.flink.configuration.JobManagerOptions;
import org.apache.flink.runtime.jobgraph.SavepointConfigOptions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Helper class for running Flink jobs in IntelliJ with Web UI and savepoint support
 */
public class IntelliJRunHelper {
    private static final Logger LOG = LoggerFactory.getLogger(IntelliJRunHelper.class);
    
    /**
     * Creates a StreamExecutionEnvironment configured for IntelliJ development
     * with Web UI enabled and job-specific savepoint directory configured
     */
    public static StreamExecutionEnvironment createEnvironment() {
        return createEnvironment("default-job");
    }
    
    /**
     * Creates environment with job-specific savepoint directory
     */
    public static StreamExecutionEnvironment createEnvironment(String jobName) {
        Configuration config = new Configuration();
        
        // Enable Web UI on port 8081
        config.setInteger(RestOptions.PORT, 8081);
        // Keep the local Web UI on loopback only
        config.setString(RestOptions.BIND_ADDRESS, "localhost");
        
        // Create job-specific directories
        String jobSavepointDir = "file:///tmp/flink-savepoints/" + jobName;
        String jobCheckpointDir = "file:///tmp/flink-checkpoints/" + jobName;
        
        // Configure checkpointing directory for savepoints
        config.setString("state.checkpoints.dir", jobCheckpointDir);
        config.setString("state.savepoints.dir", jobSavepointDir);
        
        // Create environment with configuration
        StreamExecutionEnvironment env = StreamExecutionEnvironment.createLocalEnvironmentWithWebUI(config);
        
        // Enable checkpointing every 15 seconds (more frequent for testing)
        env.enableCheckpointing(15000);
        
        // Configure for EventTime processing
        env.getConfig().setAutoWatermarkInterval(1000);
        
        LOG.info("Flink Web UI available at: http://localhost:8081");
        LOG.info("Job-specific savepoint directory: /tmp/flink-savepoints/{}", jobName);
        LOG.info("Job-specific checkpoint directory: /tmp/flink-checkpoints/{}", jobName);
        LOG.info("");
        LOG.info("💾 CREATE SAVEPOINT OPTIONS:");
        LOG.info("1. Web UI: http://localhost:8081 → Running Jobs → Your Job → Savepoints → Create");
        LOG.info("2. REST API: Run SavepointManager.main() after starting your job");
        LOG.info("3. Command: curl -X POST http://localhost:8081/jobs/YOUR_JOB_ID/savepoints \\");
        LOG.info("           -H 'Content-Type: application/json' \\");
        LOG.info("           -d '{{\"cancel-job\": false, \"target-directory\": \"{}\"}}'", jobSavepointDir);
        LOG.info("");
        
        return env;
    }
    
    /**
     * Creates environment that can restore from savepoint
     */
    public static StreamExecutionEnvironment createEnvironmentWithSavepoint(String savepointPath) {
        Configuration config = new Configuration();
        config.setInteger(RestOptions.PORT, 8081);
        // Keep the local Web UI on loopback only
        config.setString(RestOptions.BIND_ADDRESS, "localhost");
        config.setString("state.checkpoints.dir", "file:///tmp/flink-checkpoints");
        config.setString("execution.checkpointing.savepoint-dir", "file:///tmp/flink-savepoints");
        
        // Set savepoint restore path
        config.setString("execution.savepoint.path", savepointPath);
        
        StreamExecutionEnvironment env = StreamExecutionEnvironment.createLocalEnvironmentWithWebUI(config);
        env.enableCheckpointing(30000);
        env.getConfig().setAutoWatermarkInterval(1000);
        
        LOG.info("Restoring from savepoint: {}", savepointPath);
        LOG.info("Flink Web UI available at: http://localhost:8081");
        
        return env;
    }
    
    /**
     * Creates environment with automatic savepoint creation
     * Useful for testing schema evolution scenarios
     */
    public static StreamExecutionEnvironment createEnvironmentWithAutoSavepoint(int savepointIntervalSeconds) {
        Configuration config = new Configuration();
        config.setInteger(RestOptions.PORT, 8081);
        // Keep the local Web UI on loopback only
        config.setString(RestOptions.BIND_ADDRESS, "localhost");
        config.setString("state.checkpoints.dir", "file:///tmp/flink-checkpoints");
        config.setString("execution.checkpointing.savepoint-dir", "file:///tmp/flink-savepoints");
        
        StreamExecutionEnvironment env = StreamExecutionEnvironment.createLocalEnvironmentWithWebUI(config);
        
        // Enable frequent checkpointing for savepoint creation
        env.enableCheckpointing(savepointIntervalSeconds * 1000);
        env.getConfig().setAutoWatermarkInterval(1000);
        
        LOG.info("Auto-savepoint mode: checkpoint every {} seconds", savepointIntervalSeconds);
        LOG.info("Flink Web UI available at: http://localhost:8081");
        LOG.info("Create savepoints via Web UI: Running Jobs → Your Job → Savepoints → Create Savepoint");
        
        return env;
    }
}
