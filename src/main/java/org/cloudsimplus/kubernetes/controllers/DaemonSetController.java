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
package org.cloudsimplus.kubernetes.controllers;

import lombok.Getter;
import lombok.Setter;
import lombok.experimental.Accessors;
import org.cloudsimplus.kubernetes.KubernetesNode;
import org.cloudsimplus.kubernetes.KubernetesPod;
import org.cloudsimplus.kubernetes.LabelSelector;
import org.cloudsimplus.kubernetes.LabelSet;
import org.cloudsimplus.kubernetes.Namespace;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * DaemonSet controller — ensures exactly one pod per matching node. The
 * {@link #getNodeSelector() nodeSelector} filters which nodes are eligible;
 * {@link #reconcile()} adds a pod to any matching node that doesn't already
 * have one, and tolerates node additions over time.
 *
 * @since CloudSim Plus 9.0.0
 */
@Getter @Setter @Accessors(chain = true)
public class DaemonSetController implements Controller {

    private static final Logger LOG = LoggerFactory.getLogger(DaemonSetController.class.getSimpleName());

    private final long uid;
    private final String name;
    private final Namespace namespace;
    private final PodTemplate template;
    private LabelSelector nodeSelector = LabelSelector.MATCH_ALL;
    private final Map<Long, KubernetesPod> podByNodeId = new LinkedHashMap<>();
    private int nextOrdinal;
    private ControllerManager manager;

    public DaemonSetController(final long uid, final String name, final Namespace namespace,
                               final PodTemplate template) {
        this.uid = uid;
        this.name = name;
        this.namespace = namespace;
        this.template = template;
    }

    @Override
    public String getKind() {
        return "DaemonSet";
    }

    @Override
    public void onPodLost(final KubernetesPod pod) {
        podByNodeId.values().removeIf(p -> p == pod);
    }

    @Override
    public void reconcile() {
        for (final var node : manager.broker().getNodes()) {
            if (!nodeSelector.matches(node.getLabels())) {
                continue;
            }
            if (podByNodeId.containsKey(node.getId())) {
                continue;
            }
            spawnPodOnNode(node);
        }
    }

    private void spawnPodOnNode(final KubernetesNode node) {
        final int ordinal = nextOrdinal++;
        final var pod = template.create(ordinal);
        pod.setPodName(name + "-" + node.effectiveName());
        pod.setNamespace(namespace);
        pod.setLabels(pod.getLabels().merge(LabelSet.of()
            .with(LABEL_CONTROLLER_UID, Long.toString(uid))
            .with(LABEL_CONTROLLER_KIND, getKind())
            .build()));
        // Pin to this specific node by adding a single-node selector.
        final var nodeName = node.effectiveName();
        if (!nodeName.isEmpty() && node.getLabels().has("kubernetes.io/hostname")) {
            pod.setNodeSelector(LabelSelector.matchLabel(
                "kubernetes.io/hostname", node.getLabels().get("kubernetes.io/hostname")));
        }
        podByNodeId.put(node.getId(), pod);
        LOG.info("{}: DaemonSet '{}': scheduling {} on node {}",
            manager.broker().getSimulation().clockStr(), name, pod.getPodName(), node.effectiveName());
        manager.broker().submitPod(pod);
    }
}
