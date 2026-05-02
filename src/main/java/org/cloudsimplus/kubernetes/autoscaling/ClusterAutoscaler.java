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
package org.cloudsimplus.kubernetes.autoscaling;

import lombok.Getter;
import lombok.NonNull;
import lombok.Setter;
import lombok.experimental.Accessors;
import org.cloudsimplus.datacenters.Datacenter;
import org.cloudsimplus.kubernetes.KubernetesClusterBroker;
import org.cloudsimplus.kubernetes.KubernetesNode;
import org.cloudsimplus.kubernetes.lifecycle.Tick;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Cluster Autoscaler — adds nodes from a {@link NodePool} when pods cannot be
 * scheduled, and decommissions empty nodes after a configurable idle window.
 *
 * <p>Unschedulable-pod detection: any submitted pod whose
 * {@link org.cloudsimplus.vms.Vm#isCreated()} is still false after the broker
 * has had a chance to attempt placement counts as unschedulable.</p>
 *
 * <p>This scaler supports a single {@link NodePool} per instance; register
 * multiple instances on a broker for heterogeneous fleets.</p>
 *
 * @since CloudSim Plus 9.0.0
 */
@Getter @Setter @Accessors(chain = true)
public class ClusterAutoscaler implements Tick {

    private static final Logger LOG = LoggerFactory.getLogger(ClusterAutoscaler.class.getSimpleName());

    @NonNull
    private final KubernetesClusterBroker broker;

    @NonNull
    private final NodePool pool;

    /** Idle seconds before a node becomes a candidate for decommission. */
    private double scaleDownAfterSeconds = 600.0;

    /** Minimum simulated seconds between two cluster-scale actions. */
    private double cooldownSeconds = 30.0;

    private double lastActionAt = -1.0;
    private int provisioned;
    private final Map<KubernetesNode, Double> idleSince = new LinkedHashMap<>();

    public ClusterAutoscaler(final KubernetesClusterBroker broker, final NodePool pool) {
        this.broker = broker;
        this.pool = pool;
    }

    @Override
    public void tick(final double clockTime) {
        if (clockTime - lastActionAt < cooldownSeconds && lastActionAt >= 0) {
            return;
        }
        if (scaleUpIfPodsPending(clockTime)) {
            return;
        }
        scaleDownIfNodesIdle(clockTime);
    }

    private boolean scaleUpIfPodsPending(final double clockTime) {
        final boolean hasPending = broker.getPods().stream().anyMatch(p -> !p.isCreated() && !p.isFailed());
        if (!hasPending) {
            return false;
        }
        if (provisioned >= pool.getMax()) {
            return false;
        }
        final var dc = primaryDatacenter();
        if (dc == null) {
            return false;
        }
        final var node = pool.getTemplate().get();
        dc.addHost(node);
        provisioned++;
        lastActionAt = clockTime;
        LOG.info("ClusterAutoscaler({}): provisioned new node '{}' to relieve pending pods (pool size now {})",
            pool.getName(), node.effectiveName(), provisioned);
        // Trigger another VM-creation attempt — pending VMs may now fit.
        broker.getPods().stream()
            .filter(p -> !p.isCreated() && !p.isFailed())
            .findFirst()
            .ifPresent(broker::submitVm);
        return true;
    }

    private void scaleDownIfNodesIdle(final double clockTime) {
        final var nodes = nodesFromPool();
        final List<KubernetesNode> toDecommission = new ArrayList<>();
        for (final var node : nodes) {
            final boolean empty = broker.placedPodsOnNode(node).isEmpty();
            if (!empty) {
                idleSince.remove(node);
                continue;
            }
            final double since = idleSince.computeIfAbsent(node, n -> clockTime);
            if (clockTime - since >= scaleDownAfterSeconds && nodes.size() - toDecommission.size() > pool.getMin()) {
                toDecommission.add(node);
            }
        }
        for (final var n : toDecommission) {
            n.setActive(false);
            idleSince.remove(n);
            provisioned--;
            lastActionAt = clockTime;
            LOG.info("ClusterAutoscaler({}): decommissioned idle node '{}' (pool size now {})",
                pool.getName(), n.effectiveName(), provisioned);
        }
    }

    private Datacenter primaryDatacenter() {
        return broker.getDatacenters().isEmpty() ? null : broker.getDatacenters().get(0);
    }

    private List<KubernetesNode> nodesFromPool() {
        // Heuristic: nodes whose name starts with the pool name are considered part of it.
        return broker.getNodes().stream()
            .filter(n -> n.effectiveName().startsWith(pool.getName()))
            .toList();
    }
}
