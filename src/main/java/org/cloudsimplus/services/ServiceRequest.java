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
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A top-level request that enters the system and triggers a {@link ServiceCall}
 * tree (e.g. an end-user HTTP request that fans out into A → B, A → D → E).
 *
 * <p>The {@link ServiceBroker} drives the request to completion by running the
 * cloudlets associated with each {@link ServiceCall} on its target service's VMs,
 * threading them together through cloudlet-finish listeners. The
 * {@link #getFinishTime() finish time} reflects when the root call (and thus
 * the whole subtree) completed.</p>
 *
 * @since CloudSim Plus 9.0.0
 */
@Getter @Setter
public class ServiceRequest {
    private long id;

    @NonNull
    private final ServiceCall rootCall;

    /**
     * Optional submission delay (in seconds): the broker will wait this long
     * after the simulation starts before firing the root call.
     */
    private double submissionDelay;

    /** When the broker received the request (set by the broker). */
    private double submissionTime = -1;

    /** When the root call started running on its VM. */
    private double startTime = -1;

    /** When the root call (and the whole tree) finished. */
    private double finishTime = -1;

    /**
     * The {@link Api} this request was generated from, or {@code null} if the
     * request was built outside of the {@link org.cloudsimplus.services.generator.RequestGenerator}
     * (e.g. constructed by hand in a unit test).
     */
    private Api api;

    /**
     * Cumulative path delay (in seconds) accrued at every {@link Service} that
     * the request has touched. Populated by the broker as each {@link ServiceCall}
     * completes and consumed by the critical-path latency calculation
     * (max over the {@link ServiceGraph#getSinks(java.util.List) sink} services
     * of the chain).
     */
    private final Map<Service, Double> nodeDelay = new HashMap<>();

    private final List<Object> tags = new ArrayList<>();

    /**
     * Creates a request whose entrypoint is the given root call.
     *
     * @param id       the request id (used for logging/tracing)
     * @param rootCall the entrypoint call
     */
    public ServiceRequest(final long id, @NonNull final ServiceCall rootCall) {
        this.id = id;
        this.rootCall = rootCall;
    }

    /**
     * @return the total response time (in seconds) of the request, or a negative
     * value if not finished yet.
     */
    public double getResponseTime() {
        if (submissionTime < 0 || finishTime < 0) {
            return -1;
        }
        return finishTime - submissionTime;
    }

    /**
     * @return {@code true} when the root call has completed.
     */
    public boolean isFinished() {
        return finishTime >= 0;
    }

    /**
     * @return read-only list of arbitrary tags attached to this request
     * (handy for tracing or grouping in scenarios).
     */
    public List<Object> getTags() {
        return Collections.unmodifiableList(tags);
    }

    /**
     * Records (or updates) the cumulative path delay accrued at the given service
     * for this request. The delay is the time elapsed between the request's
     * {@link #getSubmissionTime() submission} and the moment the service finished
     * processing this request along the call path that reached it.
     *
     * <p>If a delay was previously recorded for the same service, the larger
     * value wins (keeping the longest path through that node, which is what the
     * critical-path latency calculation needs).</p>
     *
     * @param service the service whose path delay is being recorded
     * @param delay   cumulative path delay in seconds (must be &ge; 0)
     * @return this request, to enable chaining
     */
    public ServiceRequest recordNodeDelay(@NonNull final Service service, final double delay) {
        if (delay < 0) {
            throw new IllegalArgumentException("delay must be >= 0");
        }
        nodeDelay.merge(service, delay, Math::max);
        return this;
    }

    /**
     * @return an unmodifiable view of the per-service cumulative path delay map
     *         populated by the broker as the request progresses.
     */
    public Map<Service, Double> getNodeDelay() {
        return Collections.unmodifiableMap(nodeDelay);
    }

    /**
     * Attaches an arbitrary tag (e.g. a user id, a request kind) to this request.
     */
    public ServiceRequest addTag(final Object tag) {
        tags.add(tag);
        return this;
    }

    @Override
    public String toString() {
        return "ServiceRequest[id=%d, root=%s, finished=%s]"
            .formatted(id, rootCall.getService().getName(), isFinished());
    }
}
