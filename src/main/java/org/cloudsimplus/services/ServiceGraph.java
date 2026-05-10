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

import lombok.NonNull;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A directed acyclic graph (DAG) of {@link Service}s describing how
 * microservices call one another inside a cloud-native application.
 *
 * <p>Nodes are services; edges are
 * "{@code parent → child} (parent invokes child synchronously)" relationships.
 * The graph also exposes per-{@link Api} <i>service chains</i> — a topologically
 * sorted list of the services that participate in serving that API. The
 * {@link org.cloudsimplus.services.ServiceBrokerSimple broker} uses these
 * chains to materialize the {@link ServiceCall} tree of every incoming request.</p>
 *
 * <p>Counterpart of {@code entity.ServiceGraph} in the original CloudNativeSim
 * toolkit.</p>
 *
 * @since CloudSim Plus 9.0.0
 */
public class ServiceGraph {
    /** {@code parent → children}. Insertion order is preserved (LinkedHashMap). */
    private final Map<Service, List<Service>> serviceHierarchy = new LinkedHashMap<>();

    /** {@code child → parents}. */
    private final Map<Service, List<Service>> reverseServiceHierarchy = new LinkedHashMap<>();

    /** Number of incoming edges per service (number of services that call it). */
    private final Map<Service, Integer> inDegree = new LinkedHashMap<>();

    /** Number of outgoing edges per service (number of services it calls). */
    private final Map<Service, Integer> outDegree = new LinkedHashMap<>();

    /**
     * Per-{@link Api} chain: every service whose {@link Service#getApiList() apiList}
     * contains the API's name, sorted by ascending in-degree so sources come
     * first. Populated by {@link #buildServiceChains(List)}.
     */
    private final Map<Api, List<Service>> serviceChains = new LinkedHashMap<>();

    /**
     * Adds {@code service} to the graph, optionally wiring it as a child of
     * {@code parentService}.
     *
     * @param service       the service to add (or update). Its
     *                      {@link Service#setServiceGraph(ServiceGraph) serviceGraph}
     *                      back-reference is set to this graph.
     * @param parentService the parent service to wire {@code service} under, or
     *                      {@code null} if {@code service} is a root
     * @return this graph, to enable chaining
     */
    public ServiceGraph addService(@NonNull final Service service, final Service parentService) {
        serviceHierarchy.computeIfAbsent(service, k -> new ArrayList<>());
        reverseServiceHierarchy.computeIfAbsent(service, k -> new ArrayList<>());
        inDegree.putIfAbsent(service, 0);
        outDegree.putIfAbsent(service, 0);

        if (parentService != null && parentService != Service.NULL) {
            serviceHierarchy.computeIfAbsent(parentService, k -> new ArrayList<>());
            reverseServiceHierarchy.computeIfAbsent(parentService, k -> new ArrayList<>());
            inDegree.putIfAbsent(parentService, 0);
            outDegree.putIfAbsent(parentService, 0);

            // Avoid duplicate edges
            if (!serviceHierarchy.get(parentService).contains(service)) {
                serviceHierarchy.get(parentService).add(service);
                reverseServiceHierarchy.get(service).add(parentService);
                inDegree.merge(service, 1, Integer::sum);
                outDegree.merge(parentService, 1, Integer::sum);
            }
        }
        service.setServiceGraph(this);
        return this;
    }

    /**
     * Removes {@code service} from the graph and tears down all edges that
     * reference it.
     */
    public ServiceGraph deleteService(@NonNull final Service service) {
        if (!serviceHierarchy.containsKey(service)) {
            return this;
        }

        for (final Service parent : new ArrayList<>(reverseServiceHierarchy.get(service))) {
            serviceHierarchy.get(parent).remove(service);
            outDegree.merge(parent, -1, Integer::sum);
        }
        for (final Service child : new ArrayList<>(serviceHierarchy.get(service))) {
            reverseServiceHierarchy.get(child).remove(service);
            inDegree.merge(child, -1, Integer::sum);
        }

        serviceHierarchy.remove(service);
        reverseServiceHierarchy.remove(service);
        inDegree.remove(service);
        outDegree.remove(service);

        for (final List<Service> chain : serviceChains.values()) {
            chain.remove(service);
        }
        return this;
    }

    /**
     * @return read-only list of services {@code service} calls (its children).
     */
    public List<Service> getCalls(@NonNull final Service service) {
        return Collections.unmodifiableList(
            serviceHierarchy.getOrDefault(service, Collections.emptyList()));
    }

    /**
     * @return read-only list of services that call {@code service} (its parents).
     */
    public List<Service> getParentServices(@NonNull final Service service) {
        return Collections.unmodifiableList(
            reverseServiceHierarchy.getOrDefault(service, Collections.emptyList()));
    }

    /**
     * @return services in {@code chain} that have no parent inside {@code chain}
     *         (the entry points / "sources" of the API's call graph).
     */
    public List<Service> getSources(final List<Service> chain) {
        final List<Service> sources = new ArrayList<>();
        for (final Service s : chain) {
            final List<Service> parents = reverseServiceHierarchy.getOrDefault(s, Collections.emptyList());
            if (parents.stream().noneMatch(chain::contains)) {
                sources.add(s);
            }
        }
        return sources;
    }

    /**
     * @return services in {@code chain} that have no child inside {@code chain}
     *         (the leaves / "sinks" of the API's call graph).
     */
    public List<Service> getSinks(final List<Service> chain) {
        final List<Service> sinks = new ArrayList<>();
        for (final Service s : chain) {
            final List<Service> children = serviceHierarchy.getOrDefault(s, Collections.emptyList());
            if (children.stream().noneMatch(chain::contains)) {
                sinks.add(s);
            }
        }
        return sinks;
    }

    /**
     * Builds a topologically sorted {@link Service} chain for every API in
     * {@code apis}. A service participates in an API's chain iff its
     * {@link Service#getApiList() apiList} contains the API's
     * {@link Api#getName() name}; chain entries are sorted by ascending in-degree
     * (sources first).
     *
     * <p>Each {@link Api}'s {@link Api#setServiceChain(List) serviceChain} is
     * also updated as a side effect so that the request generator can fan out
     * directly from {@code Api}.</p>
     *
     * @param apis the APIs to build chains for
     * @return read-only view of the per-API chains map (also accessible via
     *         {@link #getServiceChains()})
     */
    public Map<Api, List<Service>> buildServiceChains(@NonNull final List<Api> apis) {
        serviceChains.clear();
        for (final Api api : apis) {
            final List<Service> chain = new ArrayList<>();
            for (final Service s : serviceHierarchy.keySet()) {
                if (s.getApiList().contains(api.getName())) {
                    chain.add(s);
                }
            }
            chain.sort(Comparator.comparingInt(s -> inDegree.getOrDefault(s, 0)));
            serviceChains.put(api, chain);
            api.setServiceChain(chain);
        }
        return Collections.unmodifiableMap(serviceChains);
    }

    /**
     * @return read-only view of the per-API service chains computed by
     *         {@link #buildServiceChains(List)}.
     */
    public Map<Api, List<Service>> getServiceChains() {
        return Collections.unmodifiableMap(serviceChains);
    }

    /**
     * @return read-only list of all services registered in the graph (in
     *         insertion order).
     */
    public List<Service> getAllServices() {
        return List.copyOf(serviceHierarchy.keySet());
    }

    /**
     * @return the in-degree of {@code service}, or {@code 0} if it is not in the
     *         graph.
     */
    public int getInDegree(@NonNull final Service service) {
        return inDegree.getOrDefault(service, 0);
    }

    /**
     * @return the out-degree of {@code service}, or {@code 0} if it is not in the
     *         graph.
     */
    public int getOutDegree(@NonNull final Service service) {
        return outDegree.getOrDefault(service, 0);
    }

    /**
     * @return {@code true} if {@code service} is registered in this graph.
     */
    public boolean contains(@NonNull final Service service) {
        return serviceHierarchy.containsKey(service);
    }
}
