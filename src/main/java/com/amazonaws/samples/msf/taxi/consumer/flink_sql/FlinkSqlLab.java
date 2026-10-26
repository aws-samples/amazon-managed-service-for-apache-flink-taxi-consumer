package com.amazonaws.samples.msf.taxi.consumer.flink_sql;

import org.apache.flink.api.java.utils.ParameterTool;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Single entry point for the Flink SQL lab when running in IntelliJ.
 *
 * Create one Run Configuration for this class and switch exercises by changing
 * the program arguments only:
 *
 *   --InputStreamArn arn:aws:kinesis:...:stream/... --example windows --query hop
 *
 * --example picks the exercise class, --query picks the query inside it.
 * All arguments are passed through unchanged to the exercise's main().
 */
public class FlinkSqlLab {

    private static final Map<String, String> EXAMPLES = new LinkedHashMap<>();

    static {
        EXAMPLES.put("table-env", "TableEnvSetupExample");
        EXAMPLES.put("ddl", "SqlDdlExample");
        EXAMPLES.put("aggregations", "SqlDmlAggregationsExample  (--query filter | group-by | having | distinct)");
        EXAMPLES.put("windows", "WindowTvfExample  (--query tumble | hop | cumulate)");
        EXAMPLES.put("joins", "SqlJoinsExample  (--query inner | left-outer | interval | temporal)");
        EXAMPLES.put("match-recognize", "MatchRecognizeExample  (--query surge | v-shape | high-value-streak | spike-plateau | fare-drop)");
        EXAMPLES.put("topn-dedup", "TopNDeduplicationExample  (--query top-n | dedup)");
        EXAMPLES.put("udf", "UdfExample  (--query geohash | zone-split | weighted-avg)");
        EXAMPLES.put("optimization", "SqlOptimizationExample  (--query ttl-hint | global-ttl-minibatch | no-minibatch | ttl-hint-join)");
    }

    public static void main(String[] args) throws Exception {
        String example = ParameterTool.fromArgs(args).get("example");
        if (example == null) {
            throw new IllegalArgumentException("Missing --example. " + usage());
        }

        switch (example) {
            case "table-env":       TableEnvSetupExample.main(args); break;
            case "ddl":             SqlDdlExample.main(args); break;
            case "aggregations":    SqlDmlAggregationsExample.main(args); break;
            case "windows":         WindowTvfExample.main(args); break;
            case "joins":           SqlJoinsExample.main(args); break;
            case "match-recognize": MatchRecognizeExample.main(args); break;
            case "topn-dedup":      TopNDeduplicationExample.main(args); break;
            case "udf":             UdfExample.main(args); break;
            case "optimization":    SqlOptimizationExample.main(args); break;
            default:
                throw new IllegalArgumentException("Unknown --example '" + example + "'. " + usage());
        }
    }

    private static String usage() {
        StringBuilder sb = new StringBuilder("Valid values:");
        EXAMPLES.forEach((name, cls) -> sb.append("\n  --example ").append(name).append("  ->  ").append(cls));
        return sb.toString();
    }
}
