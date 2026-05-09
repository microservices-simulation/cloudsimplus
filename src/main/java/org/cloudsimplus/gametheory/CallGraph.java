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
package org.cloudsimplus.gametheory;

import lombok.NonNull;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A directed call graph between microservices, weighted by the call
 * probabilities {@code π_ji} of equation (1) in the HHG-MS paper.
 *
 * <p>An edge {@code (M_j → M_i)} with weight {@code π_ji} means that for
 * every request served by {@code M_j}, in expectation {@code π_ji} calls
 * are forwarded to {@code M_i}. The graph drives the effective arrival
 * rate computation in {@link UtilityModel#effectiveArrivalRates}.</p>
 *
 * <p>This type is purely topological: it does not depend on
 * {@link org.cloudsimplus.services.Service} or any CloudSim entity, so it
 * can be used to study the model independently of a running simulation.</p>
 *
 * @since CloudSim Plus 9.0.0
 */
public final class CallGraph {
    /** {@code edges.get(j).get(i) = π_ji}. */
    private final Map<String, Map<String, Double>> edgesFrom = new LinkedHashMap<>();
    private final List<String> nodes = new ArrayList<>();

    /**
     * Adds a node with the given name, if not already present.
     */
    public CallGraph addNode(@NonNull final String name) {
        if (!nodes.contains(name)) {
            nodes.add(name);
            edgesFrom.put(name, new LinkedHashMap<>());
        }
        return this;
    }

    /**
     * Adds (or overwrites) the call probability of the edge {@code from → to}.
     *
     * @param from the caller service name
     * @param to   the callee service name
     * @param prob expected number of {@code to} invocations per {@code from} request
     *             (typically in {@code [0, 1]} but values &gt; 1 are allowed for
     *             explicit fan-out modelling)
     */
    public CallGraph addEdge(@NonNull final String from, @NonNull final String to, final double prob) {
        if (prob < 0) {
            throw new IllegalArgumentException("Edge probability must be >= 0");
        }
        addNode(from);
        addNode(to);
        edgesFrom.get(from).put(to, prob);
        return this;
    }

    /**
     * @return read-only list of node names in insertion order.
     */
    public List<String> nodes() {
        return Collections.unmodifiableList(nodes);
    }

    /**
     * @return the call probability {@code π_ji} from {@code from} to {@code to},
     * or 0 if there is no edge.
     */
    public double prob(final String from, final String to) {
        final var out = edgesFrom.get(from);
        return out == null ? 0.0 : out.getOrDefault(to, 0.0);
    }

    /**
     * @return read-only list of out-edges (target name → probability) for
     * the given service.
     */
    public Map<String, Double> outEdges(final String from) {
        final var out = edgesFrom.get(from);
        return out == null ? Map.of() : Collections.unmodifiableMap(out);
    }

    @Override
    public String toString() {
        final var sb = new StringBuilder("CallGraph{");
        for (final var from : nodes) {
            for (final var entry : edgesFrom.get(from).entrySet()) {
                sb.append("\n  ").append(from).append(" -[")
                  .append(String.format("%.3f", entry.getValue()))
                  .append("]-> ").append(entry.getKey());
            }
        }
        return sb.append("\n}").toString();
    }
}
