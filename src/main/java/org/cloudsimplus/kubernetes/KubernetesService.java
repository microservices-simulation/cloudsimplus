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
import lombok.Setter;
import lombok.experimental.Accessors;
import org.cloudsimplus.services.ServiceSimple;
import org.cloudsimplus.vms.Vm;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.List;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * A Kubernetes Service: a stable virtual endpoint that load-balances traffic
 * across the {@link KubernetesPod}s in {@link #getNamespace() its namespace}
 * whose labels match {@link #getSelector() the selector}.
 *
 * <p>Unlike its parent {@link ServiceSimple}, the backing pod list is
 * <i>resolved dynamically</i> on every {@link #getVms()}/{@link #selectVm()}
 * call from a pod source supplied by the
 * {@link KubernetesClusterBroker} via {@link #setPodSource(Supplier)}. This
 * means newly-created pods (and ones that finish / are removed) flow into and
 * out of the endpoint set without any manual registration.</p>
 *
 * <p>{@link #addVm(Vm)} is intentionally <i>not</i> the way to populate this
 * service — endpoints come from the selector. Calls are accepted and logged
 * at {@code WARN} so misuse is loud rather than silent.</p>
 *
 * @since CloudSim Plus 9.0.0
 */
@Getter @Setter @Accessors(chain = true)
public class KubernetesService extends ServiceSimple {
    private static final Logger LOG = LoggerFactory.getLogger(KubernetesService.class.getSimpleName());

    @NonNull
    private LabelSelector selector;

    @NonNull
    private Namespace namespace;

    @NonNull
    private Supplier<List<KubernetesPod>> podSource = Collections::emptyList;

    private int roundRobinIndex;

    public KubernetesService(final String name, final Namespace namespace, final LabelSelector selector) {
        super(name);
        if (namespace == null) {
            throw new IllegalArgumentException("Namespace is required for a KubernetesService");
        }
        if (selector == null) {
            throw new IllegalArgumentException("Selector is required for a KubernetesService");
        }
        this.namespace = namespace;
        this.selector = selector;
    }

    /**
     * @return the {@link KubernetesPod}s currently backing this service: created
     * pods in the same namespace whose labels match the selector. The list is
     * recomputed on every call to reflect cluster state.
     */
    public List<KubernetesPod> backingPods() {
        return podSource.get().stream()
            .filter(KubernetesPod::isCreated)
            .filter(KubernetesService::isPodReadyOrPreReadiness)
            .filter(p -> namespace.equals(p.getNamespace()))
            .filter(p -> selector.matches(p.getLabels()))
            .toList();
    }

    /**
     * A pod is eligible as a service endpoint when it is either explicitly
     * Ready (a readiness probe has marked it so) or has no readiness gate at
     * all (no probe configured ⇒ ready by default — this matches the kubelet's
     * behaviour for pods without a readiness probe).
     */
    private static boolean isPodReadyOrPreReadiness(final KubernetesPod pod) {
        // If any container declares a readiness probe, gate on Ready.
        final boolean hasReadinessGate = pod.getContainers().stream()
            .anyMatch(c -> c.getReadinessProbe() != null);
        return !hasReadinessGate || pod.isReady();
    }

    @Override
    public List<Vm> getVms() {
        return backingPods().stream().map(p -> (Vm) p).collect(Collectors.toUnmodifiableList());
    }

    /**
     * {@inheritDoc}
     * <p>Round-robins across the dynamically-resolved
     * {@link #backingPods() backing pods}. Returns {@link Vm#NULL} when the
     * selector currently matches no created pod.</p>
     */
    @Override
    public Vm selectVm() {
        final var vms = getVms();
        if (vms.isEmpty()) {
            return Vm.NULL;
        }
        final var vm = vms.get(roundRobinIndex % vms.size());
        roundRobinIndex = (roundRobinIndex + 1) % vms.size();
        return vm;
    }

    /**
     * Discouraged: a {@link KubernetesService}'s endpoints are derived from its
     * {@link #getSelector() selector}, not from manual registration. Calls are
     * accepted (the parent stores them) but ignored by {@link #getVms()}.
     */
    @Override
    public org.cloudsimplus.services.Service addVm(@NonNull final Vm vm) {
        LOG.warn("addVm() on KubernetesService '{}' is a no-op; pods are discovered via the label selector",
            getName());
        return this;
    }

    @Override
    public String toString() {
        return "KubernetesService[%s/%s, selector=%s]"
            .formatted(namespace.getName(), getName(), selector);
    }
}
