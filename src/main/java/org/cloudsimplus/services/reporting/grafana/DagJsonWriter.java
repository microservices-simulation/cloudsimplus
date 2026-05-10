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
package org.cloudsimplus.services.reporting.grafana;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import lombok.NonNull;
import org.cloudsimplus.services.Api;
import org.cloudsimplus.services.Service;
import org.cloudsimplus.services.ServiceBrokerSimple;
import org.cloudsimplus.services.ServiceGraph;
import org.cloudsimplus.services.ServiceRequest;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Writes {@code nodes.json} and {@code edges.json} consumable by Grafana's
 * NodeGraph panel via the Infinity datasource.
 *
 * <p>Field names are fixed by Grafana's NodeGraph schema and must not change:</p>
 * <ul>
 *   <li>nodes: {@code id}, {@code title}, {@code arc__success},
 *       {@code arc__failure}, {@code mainstat}.</li>
 *   <li>edges: {@code id}, {@code source}, {@code target}.</li>
 * </ul>
 *
 * <p>{@code mainstat} carries the total request count touching that service.
 * The arc fields together always sum to {@code 1.0}; failure here means
 * "request finished but breached the API's SLO threshold" — there is no
 * separate failure status in the simulator.</p>
 *
 * @since CloudSim Plus 9.0.0
 */
public final class DagJsonWriter {
    private static final ObjectMapper MAPPER = new ObjectMapper()
        .enable(SerializationFeature.INDENT_OUTPUT);

    private DagJsonWriter() {}

    /** Writes both files into the given directory. */
    public static void write(@NonNull final Path dir,
                             @NonNull final ServiceGraph graph,
                             @NonNull final List<Api> apis,
                             final ServiceBrokerSimple broker) {
        write(dir.resolve("nodes.json"), dir.resolve("edges.json"), graph, apis, broker);
    }

    /** Writes to explicit file paths. */
    public static void write(@NonNull final Path nodesPath,
                             @NonNull final Path edgesPath,
                             @NonNull final ServiceGraph graph,
                             @NonNull final List<Api> apis,
                             final ServiceBrokerSimple broker) {
        try {
            ensureParent(nodesPath);
            ensureParent(edgesPath);
            MAPPER.writeValue(nodesPath.toFile(), buildNodes(graph, apis, broker));
            MAPPER.writeValue(edgesPath.toFile(), buildEdges(graph));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static Map<String, Object> buildNodes(final ServiceGraph graph,
                                                  final List<Api> apis,
                                                  final ServiceBrokerSimple broker) {
        // Per-service counters: totals (touched by any request), violations.
        final Map<Service, Integer> totals = new HashMap<>();
        final Map<Service, Integer> violations = new HashMap<>();

        for (final Api api : apis) {
            for (final ServiceRequest r : api.getRequests()) {
                if (!r.isFinished()) {
                    continue;
                }
                final boolean violatesSlo = r.getResponseTime() >= api.getSloThreshold();
                for (final Service s : api.getServiceChain()) {
                    totals.merge(s, 1, Integer::sum);
                    if (violatesSlo) {
                        violations.merge(s, 1, Integer::sum);
                    }
                }
            }
        }

        final List<Map<String, Object>> nodes = new ArrayList<>();
        for (final Service s : graph.getAllServices()) {
            final int total = totals.getOrDefault(s, 0);
            final int viol = violations.getOrDefault(s, 0);
            final double success = total == 0 ? 1.0 : 1.0 - (double) viol / total;
            final double failure = total == 0 ? 0.0 : (double) viol / total;
            final var node = new LinkedHashMap<String, Object>();
            node.put("id", s.getName());
            node.put("title", titleCase(s.getName()));
            node.put("arc__success", success);
            node.put("arc__failure", failure);
            node.put("mainstat", total);
            nodes.add(node);
        }
        // Optional: avoid an unused-broker warning while keeping it in the
        // signature for future per-edge metric expansion.
        if (broker != null) {
            // currently unused
        }
        return Map.of("nodes", nodes);
    }

    private static Map<String, Object> buildEdges(final ServiceGraph graph) {
        final List<Map<String, Object>> edges = new ArrayList<>();
        int idx = 1;
        for (final Service src : graph.getAllServices()) {
            for (final Service tgt : graph.getCalls(src)) {
                final var edge = new LinkedHashMap<String, Object>();
                edge.put("id", "e" + idx++);
                edge.put("source", src.getName());
                edge.put("target", tgt.getName());
                edges.add(edge);
            }
        }
        return Map.of("edges", edges);
    }

    /** "front-end" → "Front-End"; "carts-db" → "Carts-Db". */
    private static String titleCase(final String name) {
        final var parts = name.split("-");
        final var sb = new StringBuilder();
        for (int i = 0; i < parts.length; i++) {
            if (parts[i].isEmpty()) {
                continue;
            }
            if (i > 0) {
                sb.append('-');
            }
            sb.append(Character.toUpperCase(parts[i].charAt(0)));
            if (parts[i].length() > 1) {
                sb.append(parts[i].substring(1));
            }
        }
        return sb.toString();
    }

    private static void ensureParent(final Path path) throws IOException {
        if (path.getParent() != null) {
            Files.createDirectories(path.getParent());
        }
    }
}
