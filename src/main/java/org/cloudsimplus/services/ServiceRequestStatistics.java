/*
 * CloudSim Plus: A modern, highly-extensible and easier-to-use Framework for
 * Modeling and Simulation of Cloud Computing Infrastructures and Services.
 * http://cloudsimplus.org
 *
 *     Copyright (C) 2015-2021 Universidade da Beira Interior (UBI, Portugal) and
 *     the Instituto Federal de Educação Ciência e Tecnologia do Tocantins (IFTO, Brazil).
 *
 *     This file is part of CloudSim Plus.
 *
 *     CloudSim Plus is free software: you can redistribute it and/or modify
 *     it under the terms of the GNU General Public License as published by
 *     the Free Software Foundation, either version 3 of the License, or
 *     (at your option) any later version.
 *
 *     CloudSim Plus is distributed in the hope that it will be useful,
 *     but WITHOUT ANY WARRANTY; without even the implied warranty of
 *     MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *     GNU General Public License for more details.
 *
 *     You should have received a copy of the GNU General Public License
 *     along with CloudSim Plus. If not, see <http://www.gnu.org/licenses/>.
 */
package org.cloudsimplus.services;

import lombok.Getter;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Provides comprehensive statistics for a collection of {@link ServiceRequest}s.
 * Computes metrics such as mean, median, percentiles, min, max, standard deviation, etc.
 * for response times and other attributes.
 *
 * @since CloudSim Plus 9.0.0
 */
@Getter
public class ServiceRequestStatistics {
    private final List<Double> responseTimes;
    private final List<Double> startTimes;
    private final List<Double> finishTimes;

    private final int count;
    private final double meanResponseTime;
    private final double medianResponseTime;
    private final double minResponseTime;
    private final double maxResponseTime;
    private final double stdDevResponseTime;
    private final double p25ResponseTime;
    private final double p50ResponseTime;
    private final double p75ResponseTime;
    private final double p90ResponseTime;
    private final double p95ResponseTime;
    private final double p99ResponseTime;

    // Simulation configuration
    private final int numServices;
    private final int numRequests;
    private final int numFinishedRequests;
    private final int numVMs;

    // Similar for startTimes and finishTimes if needed, but focusing on responseTime for now

    /**
     * Creates statistics for the given service broker.
     * Only finished requests are considered (where {@link ServiceRequest#isFinished()} is true).
     *
     * @param broker the service broker containing requests and configuration
     */
    public ServiceRequestStatistics(final ServiceBroker broker) {
        final List<ServiceRequest> finished = broker.getFinishedRequests();

        this.count = finished.size();
        this.responseTimes = finished.stream()
            .map(ServiceRequest::getResponseTime)
            .toList();
        this.startTimes = finished.stream()
            .map(ServiceRequest::getStartTime)
            .toList();
        this.finishTimes = finished.stream()
            .map(ServiceRequest::getFinishTime)
            .toList();

        // Compute response time stats
        final List<Double> sortedResponseTimes = new ArrayList<>(responseTimes);
        Collections.sort(sortedResponseTimes);

        this.meanResponseTime = computeMean(responseTimes);
        this.medianResponseTime = computePercentile(sortedResponseTimes, 0.5);
        this.minResponseTime = sortedResponseTimes.isEmpty() ? 0 : sortedResponseTimes.getFirst();
        this.maxResponseTime = sortedResponseTimes.isEmpty() ? 0 : sortedResponseTimes.getLast();
        this.stdDevResponseTime = computeStdDev(responseTimes, meanResponseTime);
        this.p25ResponseTime = computePercentile(sortedResponseTimes, 0.25);
        this.p50ResponseTime = computePercentile(sortedResponseTimes, 0.5);
        this.p75ResponseTime = computePercentile(sortedResponseTimes, 0.75);
        this.p90ResponseTime = computePercentile(sortedResponseTimes, 0.9);
        this.p95ResponseTime = computePercentile(sortedResponseTimes, 0.95);
        this.p99ResponseTime = computePercentile(sortedResponseTimes, 0.99);

        // Simulation configuration
        this.numServices = broker.getServices().size();
        this.numRequests = broker.getRequests().size();
        this.numFinishedRequests = broker.getFinishedRequests().size();
        this.numVMs = ((org.cloudsimplus.brokers.DatacenterBroker) broker).getVmsNumber();
    }

    private double computeMean(final List<Double> values) {
        if (values.isEmpty()) return 0;
        return values.stream().mapToDouble(Double::doubleValue).sum() / values.size();
    }

    private double computeStdDev(final List<Double> values, final double mean) {
        if (values.size() < 2) return 0;
        final double variance = values.stream()
            .mapToDouble(v -> Math.pow(v - mean, 2))
            .sum() / (values.size() - 1);
        return Math.sqrt(variance);
    }

    private double computePercentile(final List<Double> sortedValues, final double percentile) {
        if (sortedValues.isEmpty()) return 0;
        final int n = sortedValues.size();
        final double pos = percentile * (n - 1);
        final int lower = (int) pos;
        final int upper = Math.min(lower + 1, n - 1);
        final double weight = pos - lower;
        return sortedValues.get(lower) * (1 - weight) + sortedValues.get(upper) * weight;
    }

    @Override
    public String toString() {
        return "{" +
            "\"simulation_config\": {" +
                "\"num_services\": " + numServices + "," +
                "\"num_requests\": " + numRequests + "," +
                "\"num_finished_requests\": " + numFinishedRequests + "," +
                "\"num_vms\": " + numVMs +
            "}," +
            "\"response_time_stats\": {" +
                "\"count\": " + count + "," +
                "\"mean\": " + String.format("%.4f", meanResponseTime) + "," +
                "\"median\": " + String.format("%.4f", medianResponseTime) + "," +
                "\"min\": " + String.format("%.4f", minResponseTime) + "," +
                "\"max\": " + String.format("%.4f", maxResponseTime) + "," +
                "\"std_dev\": " + String.format("%.4f", stdDevResponseTime) + "," +
                "\"p25\": " + String.format("%.4f", p25ResponseTime) + "," +
                "\"p50\": " + String.format("%.4f", p50ResponseTime) + "," +
                "\"p75\": " + String.format("%.4f", p75ResponseTime) + "," +
                "\"p90\": " + String.format("%.4f", p90ResponseTime) + "," +
                "\"p95\": " + String.format("%.4f", p95ResponseTime) + "," +
                "\"p99\": " + String.format("%.4f", p99ResponseTime) +
            "}" +
        "}";
    }
}
