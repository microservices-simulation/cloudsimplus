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
package org.cloudsimplus.kubernetes;

import lombok.Getter;
import lombok.NonNull;
import org.cloudsimplus.core.CloudSimPlus;
import org.cloudsimplus.core.events.SimEvent;
import org.cloudsimplus.hosts.Host;
import org.cloudsimplus.kubernetes.controllers.Controller;
import org.cloudsimplus.kubernetes.controllers.ControllerManager;
import org.cloudsimplus.kubernetes.lifecycle.Kubelet;
import org.cloudsimplus.kubernetes.lifecycle.Tick;
import org.cloudsimplus.kubernetes.scheduler.KubernetesScheduler;
import org.cloudsimplus.services.Service;
import org.cloudsimplus.services.ServiceBroker;
import org.cloudsimplus.services.ServiceBrokerSimple;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The Kubernetes control plane stand-in: a {@link ServiceBrokerSimple}
 * extension that registers {@link KubernetesPod}s, {@link KubernetesService}s,
 * {@link Namespace}s and {@link Controller}s, and acts as the kubelet — when
 * a pod's underlying VM is placed on a node, the broker submits each
 * {@link KubernetesContainer} as a cloudlet bound to that pod (init containers
 * first, then main, per {@link Kubelet}).
 *
 * <p>Because it extends {@link ServiceBrokerSimple}, the call-graph engine
 * ({@link org.cloudsimplus.services.ServiceCall},
 * {@link org.cloudsimplus.services.ServiceRequest}) is inherited unchanged: a
 * {@link KubernetesService} routes service calls round-robin across its
 * selector-matched pods, and the broker drives the resulting cloudlets to
 * completion using the existing pre/post-phase + child-call state machine.</p>
 *
 * <p>A periodic <i>controller tick</i> drives all reconciliation loops
 * (controllers, autoscalers, kubelet probes). It fires every
 * {@link #getControllerTickIntervalSeconds()} seconds — adjust this when
 * autoscaler / probe responsiveness matters more than simulation speed.</p>
 *
 * @since CloudSim Plus 9.0.0
 */
public class KubernetesClusterBroker extends ServiceBrokerSimple {

    private static final Logger LOG = LoggerFactory.getLogger(KubernetesClusterBroker.class.getSimpleName());

    /** Custom event tag for the periodic controller tick. */
    private static final int K8S_TICK_TAG = 9800;

    private final List<KubernetesPod> pods = new ArrayList<>();
    private final Map<String, KubernetesService> servicesByQualifiedName = new LinkedHashMap<>();
    private final Map<String, Namespace> namespacesByName = new LinkedHashMap<>();

    @Getter
    private final ControllerManager controllerManager = new ControllerManager(this);

    @Getter
    private final Kubelet kubelet = new Kubelet(this);

    private final List<Tick> tickers = new ArrayList<>();

    /**
     * How often the controller / kubelet / autoscaler tick fires. Default 1 s
     * is responsive without dominating event traffic; reduce for tighter HPA
     * loops, increase for huge clusters.
     */
    @Getter
    private double controllerTickIntervalSeconds = 1.0;

    public KubernetesClusterBroker(final CloudSimPlus simulation) {
        super(simulation);
        addNamespace(Namespace.DEFAULT);
        registerTick(now -> controllerManager.reconcileAll());
        registerTick(kubelet);
    }

    public KubernetesClusterBroker(final CloudSimPlus simulation, final String name) {
        super(simulation, name);
        addNamespace(Namespace.DEFAULT);
        registerTick(now -> controllerManager.reconcileAll());
        registerTick(kubelet);
    }

    /** Sets the controller-tick period. Must be {@code > 0}. */
    public KubernetesClusterBroker setControllerTickIntervalSeconds(final double seconds) {
        if (seconds <= 0) {
            throw new IllegalArgumentException("tick interval must be > 0");
        }
        this.controllerTickIntervalSeconds = seconds;
        return this;
    }

    /** Registers a {@link Tick} handler fired on every controller tick. */
    public KubernetesClusterBroker registerTick(@NonNull final Tick tick) {
        tickers.add(tick);
        return this;
    }

    // -------------------- Namespaces --------------------

    /**
     * Registers a namespace. Idempotent.
     */
    public KubernetesClusterBroker addNamespace(@NonNull final Namespace ns) {
        namespacesByName.putIfAbsent(ns.getName(), ns);
        return this;
    }

    public Optional<Namespace> getNamespace(final String name) {
        return Optional.ofNullable(namespacesByName.get(name));
    }

    /** @return read-only view of all registered namespaces. */
    public List<Namespace> getNamespaces() {
        return List.copyOf(namespacesByName.values());
    }

    // -------------------- Pods --------------------

    /**
     * Submits a pod: registers it for label-based discovery, wires the
     * container-submission hook, and forwards the underlying VM to the
     * datacenter for placement.
     */
    public KubernetesClusterBroker submitPod(@NonNull final KubernetesPod pod) {
        addNamespace(pod.getNamespace());
        pods.add(pod);
        pod.addOnHostAllocationListener(info -> onPodPlaced(pod, info.getHost()));
        pod.addOnHostDeallocationListener(info -> onPodLost(pod));
        pod.addOnCreationFailureListener(info -> onPodLost(pod));
        submitVm(pod);
        return this;
    }

    public KubernetesClusterBroker submitPods(@NonNull final List<KubernetesPod> podList) {
        podList.forEach(this::submitPod);
        return this;
    }

    /** @return read-only view of every pod ever submitted to this broker. */
    public List<KubernetesPod> getPods() {
        return Collections.unmodifiableList(pods);
    }

    /**
     * @return placed pods (created on a host) currently running on the given node.
     */
    public List<KubernetesPod> placedPodsOnNode(@NonNull final KubernetesNode node) {
        return pods.stream()
            .filter(KubernetesPod::isCreated)
            .filter(p -> p.getHost() == node)
            .toList();
    }

    /**
     * @return pods in {@code namespace} whose labels match {@code selector}.
     * Includes pods that have not yet been placed on a node.
     */
    public List<KubernetesPod> getPodsBySelector(
        @NonNull final LabelSelector selector,
        @NonNull final Namespace namespace)
    {
        return pods.stream()
            .filter(p -> namespace.equals(p.getNamespace()))
            .filter(p -> selector.matches(p.getLabels()))
            .toList();
    }

    /**
     * @return the (first) pod whose qualified name matches {@code namespace/podName},
     * or empty when no such pod has been submitted.
     */
    public Optional<KubernetesPod> getPod(final Namespace namespace, final String podName) {
        if (namespace == null || podName == null) {
            return Optional.empty();
        }
        for (final var p : pods) {
            if (namespace.equals(p.getNamespace()) && podName.equals(p.getPodName())) {
                return Optional.of(p);
            }
        }
        return Optional.empty();
    }

    // -------------------- Nodes --------------------

    /** Looks a node up by its {@link KubernetesNode#getNodeName() name}. */
    public Optional<KubernetesNode> getNode(final String nodeName) {
        if (nodeName == null) {
            return Optional.empty();
        }
        for (final var dc : getDatacenterList()) {
            for (final Host h : dc.getHostList()) {
                if (h instanceof KubernetesNode kn && nodeName.equals(kn.effectiveName())) {
                    return Optional.of(kn);
                }
            }
        }
        return Optional.empty();
    }

    /** @return every {@link KubernetesNode} known to any datacenter this broker uses. */
    public List<KubernetesNode> getNodes() {
        final List<KubernetesNode> out = new ArrayList<>();
        for (final var dc : getDatacenterList()) {
            for (final Host h : dc.getHostList()) {
                if (h instanceof KubernetesNode kn) {
                    out.add(kn);
                }
            }
        }
        return out;
    }

    /** Public alias for {@link #getDatacenterList()} so autoscalers can grow the cluster. */
    public List<org.cloudsimplus.datacenters.Datacenter> getDatacenters() {
        return List.copyOf(getDatacenterList());
    }

    // -------------------- Services --------------------

    /**
     * Registers a service. K8s services are additionally indexed by qualified
     * name and wired up to read pods from this broker for selector-based
     * endpoint resolution.
     */
    @Override
    public ServiceBroker addService(@NonNull final Service service) {
        super.addService(service);
        if (service instanceof KubernetesService ks) {
            servicesByQualifiedName.put(qualifiedName(ks.getNamespace(), ks.getName()), ks);
            addNamespace(ks.getNamespace());
            ks.setPodSource(this::getPods);
        }
        return this;
    }

    /**
     * Looks up a {@link KubernetesService} by namespace and name.
     */
    public Optional<KubernetesService> getServiceByName(final Namespace namespace, final String name) {
        if (namespace == null || name == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(servicesByQualifiedName.get(qualifiedName(namespace, name)));
    }

    private static String qualifiedName(final Namespace ns, final String name) {
        return ns.getName() + "/" + name;
    }

    // -------------------- Controllers --------------------

    /** Registers a {@link Controller} (Deployment, ReplicaSet, ...) on this broker. */
    public KubernetesClusterBroker addController(@NonNull final Controller controller) {
        controllerManager.register(controller);
        return this;
    }

    // -------------------- Lifecycle hooks (broker self-event) --------------------

    @Override
    public void startInternal() {
        super.startInternal();
        // Kick off the periodic controller tick.
        schedule(controllerTickIntervalSeconds, K8S_TICK_TAG);
    }

    @Override
    public void processEvent(final SimEvent evt) {
        if (evt.getTag() == K8S_TICK_TAG) {
            fireTick();
            schedule(controllerTickIntervalSeconds, K8S_TICK_TAG);
            return;
        }
        super.processEvent(evt);
    }

    private void fireTick() {
        final double now = getSimulation().clock();
        for (final var t : tickers) {
            try {
                t.tick(now);
            } catch (RuntimeException ex) {
                LOG.error("{}: {}: tick handler {} threw: {}",
                    getSimulation().clockStr(), getName(), t.getClass().getSimpleName(), ex.toString());
            }
        }
    }

    // -------------------- Kubelet hooks --------------------

    private void onPodPlaced(final KubernetesPod pod, final Host host) {
        kubelet.startPod(pod, host);
        controllerManager.onPodCreated(pod);
    }

    private void onPodLost(final KubernetesPod pod) {
        kubelet.stopPod(pod);
        controllerManager.onPodLost(pod);
    }
}
