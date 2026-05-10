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
import lombok.NonNull;
import lombok.Setter;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * A request type / endpoint exposed by the simulated cloud-native application
 * (e.g. {@code "GET /catalogue"}).
 *
 * <p>Each {@link Api} carries:</p>
 * <ul>
 *     <li>a {@link #getWeight() weight} used by the
 *         {@link org.cloudsimplus.services.generator.RequestGenerator request generator}
 *         to perform weighted random selection between APIs;</li>
 *     <li>an {@link #getSloThreshold() SLO threshold} (in seconds) above which a
 *         {@link ServiceRequest} is considered SLO-violating;</li>
 *     <li>a topologically-ordered {@link #getServiceChain() chain of services}
 *         (resolved by {@link ServiceGraph#buildServiceChains(java.util.List)})
 *         that defines how a request bound to this API fans out across
 *         microservices;</li>
 *     <li>an in-memory log of every {@link #getRequests() request} bound to this API
 *         and a sampled {@link #getRpsHistory() per-interval RPS history}.</li>
 * </ul>
 *
 * <p>Counterpart of {@code entity.API} in the original CloudNativeSim toolkit.</p>
 *
 * @since CloudSim Plus 9.0.0
 */
@Getter
public class Api {
    /** Default Service-Level Objective threshold, in seconds. */
    public static final double DEFAULT_SLO_THRESHOLD = 5.0;

    private final String name;

    @Setter
    private double weight;

    @Setter
    private double sloThreshold;

    @Setter
    private List<Service> serviceChain;

    private final List<ServiceRequest> requests = new ArrayList<>();

    private final List<Double> rpsHistory = new ArrayList<>();

    /**
     * Creates an API with weight {@code 1.0} and the
     * {@link #DEFAULT_SLO_THRESHOLD default SLO threshold}.
     *
     * @param name the API identifier (for example {@code "GET /catalogue"});
     *             must be non-blank
     */
    public Api(@NonNull final String name) {
        this(name, 1.0, DEFAULT_SLO_THRESHOLD);
    }

    /**
     * Creates an API with the given weight and the
     * {@link #DEFAULT_SLO_THRESHOLD default SLO threshold}.
     *
     * @param name   the API identifier; must be non-blank
     * @param weight the weight used for weighted random selection (must be &ge; 0)
     */
    public Api(@NonNull final String name, final double weight) {
        this(name, weight, DEFAULT_SLO_THRESHOLD);
    }

    /**
     * Full constructor.
     *
     * @param name         the API identifier; must be non-blank
     * @param weight       the weight used for weighted random selection (must be &ge; 0)
     * @param sloThreshold the SLO threshold in seconds (must be &gt; 0)
     */
    public Api(@NonNull final String name, final double weight, final double sloThreshold) {
        if (name.isBlank()) {
            throw new IllegalArgumentException("API name must not be blank");
        }
        if (weight < 0) {
            throw new IllegalArgumentException("API weight must be >= 0");
        }
        if (sloThreshold <= 0) {
            throw new IllegalArgumentException("SLO threshold must be > 0");
        }
        this.name = name;
        this.weight = weight;
        this.sloThreshold = sloThreshold;
        this.serviceChain = new ArrayList<>();
    }

    /**
     * @return an unmodifiable view of every {@link ServiceRequest} bound to this API
     *         (in submission order).
     */
    public List<ServiceRequest> getRequests() {
        return Collections.unmodifiableList(requests);
    }

    /**
     * @return an unmodifiable view of the per-{@code requestInterval} RPS samples
     *         appended via {@link #recordRpsSample(int, int)}.
     */
    public List<Double> getRpsHistory() {
        return Collections.unmodifiableList(rpsHistory);
    }

    /**
     * Registers a request bound to this API. The request's
     * {@link ServiceRequest#getResponseTime() response time} is read by
     * {@link #getAverageDelay()} and {@link #getSloViolations()} once the request
     * has finished.
     *
     * @param request the request to attach
     * @return this API, to enable chaining
     */
    public Api addRequest(@NonNull final ServiceRequest request) {
        requests.add(request);
        return this;
    }

    /**
     * Appends a new sampled requests-per-second value to {@link #getRpsHistory()}.
     *
     * @param requestCount   the number of requests bound to this API observed during
     *                       the last interval
     * @param intervalSeconds the interval size in seconds (must be &gt; 0)
     */
    public void recordRpsSample(final int requestCount, final int intervalSeconds) {
        if (intervalSeconds <= 0) {
            throw new IllegalArgumentException("intervalSeconds must be > 0");
        }
        rpsHistory.add((double) requestCount / intervalSeconds);
    }

    /**
     * @return the mean {@link ServiceRequest#getResponseTime() response time} over
     *         all <i>finished</i> requests bound to this API, or {@code 0.0} when no
     *         finished request has been seen yet.
     */
    public double getAverageDelay() {
        return requests.stream()
            .filter(ServiceRequest::isFinished)
            .mapToDouble(ServiceRequest::getResponseTime)
            .average()
            .orElse(0.0);
    }

    /**
     * @return the number of finished requests whose response time meets or exceeds
     *         {@link #getSloThreshold()}.
     */
    public int getSloViolations() {
        return (int) requests.stream()
            .filter(ServiceRequest::isFinished)
            .filter(r -> r.getResponseTime() >= sloThreshold)
            .count();
    }

    /**
     * @return the SLO violation rate as a fraction in {@code [0, 1]}, or {@code 0.0}
     *         when no finished request has been seen yet.
     */
    public double getSloViolationRate() {
        final long finished = requests.stream().filter(ServiceRequest::isFinished).count();
        return finished == 0 ? 0.0 : (double) getSloViolations() / finished;
    }

    /**
     * @return the average value of {@link #getRpsHistory()}, or {@code 0.0} when no
     *         sample has been recorded yet.
     */
    public double getAvgRps() {
        return rpsHistory.stream()
            .mapToDouble(Double::doubleValue)
            .average()
            .orElse(0.0);
    }

    @Override
    public String toString() {
        return "Api[%s, weight=%s, slo=%ss]".formatted(name, weight, sloThreshold);
    }
}
