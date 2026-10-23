package com.amazonaws.samples.msf.taxi.consumer.flink_sql.udf;

import org.apache.flink.table.functions.AggregateFunction;

/**
 * An aggregate UDF that computes a weighted average.
 *
 * <p>Given a series of (value, weight) pairs, the result is
 * {@code sum(value * weight) / sum(weight)}. Returns 0.0 when the total weight is zero.
 *
 * <p>Usage in Flink SQL:
 * <pre>
 *   tableEnv.createTemporaryFunction("weighted_avg", WeightedAvgFunction.class);
 *   tableEnv.sqlQuery(
 *       "SELECT weighted_avg(totalAmount, tripDistance) AS distance_weighted_fare FROM trips");
 * </pre>
 */
public class WeightedAvgFunction extends AggregateFunction<Double, WeightedAvgFunction.WeightedAvgAccumulator> {

    /**
     * Accumulator holding the running weighted sum and total weight.
     */
    public static class WeightedAvgAccumulator {
        public double weightedSum = 0.0;
        public double weightCount = 0.0;
    }

    @Override
    public WeightedAvgAccumulator createAccumulator() {
        return new WeightedAvgAccumulator();
    }

    @Override
    public Double getValue(WeightedAvgAccumulator acc) {
        if (acc.weightCount == 0.0) {
            return 0.0;
        }
        return acc.weightedSum / acc.weightCount;
    }

    /**
     * Accumulates a (value, weight) pair into the running totals.
     *
     * @param acc    the accumulator
     * @param value  the value to aggregate
     * @param weight the weight for this value
     */
    public void accumulate(WeightedAvgAccumulator acc, Double value, Double weight) {
        if (value != null && weight != null) {
            acc.weightedSum += value * weight;
            acc.weightCount += weight;
        }
    }

    /**
     * Retracts a previously accumulated (value, weight) pair.
     * Used in retract-based aggregation (e.g., over windows or retracting streams).
     *
     * @param acc    the accumulator
     * @param value  the value to retract
     * @param weight the weight to retract
     */
    public void retract(WeightedAvgAccumulator acc, Double value, Double weight) {
        if (value != null && weight != null) {
            acc.weightedSum -= value * weight;
            acc.weightCount -= weight;
        }
    }

    /**
     * Merges a group of accumulators into a single accumulator.
     * Used in session window or other merge-based aggregations.
     *
     * @param acc    the target accumulator
     * @param others the accumulators to merge in
     */
    public void merge(WeightedAvgAccumulator acc, Iterable<WeightedAvgAccumulator> others) {
        for (WeightedAvgAccumulator other : others) {
            acc.weightedSum += other.weightedSum;
            acc.weightCount += other.weightCount;
        }
    }
}
