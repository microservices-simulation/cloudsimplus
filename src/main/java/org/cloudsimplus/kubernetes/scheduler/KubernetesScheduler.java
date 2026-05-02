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
package org.cloudsimplus.kubernetes.scheduler;

import lombok.Getter;
import lombok.Setter;
import lombok.experimental.Accessors;
import org.cloudsimplus.allocationpolicies.VmAllocationPolicyTopologyAware;
import org.cloudsimplus.hosts.Host;
import org.cloudsimplus.kubernetes.KubernetesNode;
import org.cloudsimplus.kubernetes.KubernetesPod;
import org.cloudsimplus.kubernetes.PodAffinity;
import org.cloudsimplus.kubernetes.Taint;
import org.cloudsimplus.kubernetes.Toleration;
import org.cloudsimplus.vms.Vm;

import java.util.Collections;
import java.util.List;
import java.util.function.Supplier;

/**
 * A Kubernetes-flavored {@link VmAllocationPolicyTopologyAware}: layers the
 * core kube-scheduler filters and scoring on top of the existing topology
 * policies (cost / latency / spread / rack-anti-affinity / geographic spread).
 *
 * <p><b>Strict filters added on top of the parent's checks:</b></p>
 * <ul>
 *   <li>{@link KubernetesNode#isSchedulable() schedulable} flag (analog of
 *       {@code kubectl cordon})</li>
 *   <li>The pod's {@link KubernetesPod#getNodeSelector() nodeSelector} must
 *       match the node's labels</li>
 *   <li>The pod's {@link KubernetesPod#getNodeAffinity() required nodeAffinity}
 *       rules must be satisfied</li>
 *   <li>The pod must {@link Toleration#tolerates(Taint) tolerate} every
 *       {@code NoSchedule}/{@code NoExecute} taint on the node</li>
 * </ul>
 *
 * <p><b>Score adjustments (lower is better, mirroring the parent):</b></p>
 * <ul>
 *   <li>Subtract NodeAffinity {@code preferred} weight when matched</li>
 *   <li>Add a small penalty for each {@code PreferNoSchedule} taint not
 *       tolerated by the pod</li>
 * </ul>
 *
 * <p>Non-K8s VMs / non-K8s hosts fall straight through to the parent's
 * behavior, so this policy is safe to use in mixed scenarios.</p>
 *
 * @since CloudSim Plus 9.0.0
 */
@Getter @Setter @Accessors(chain = true)
public class KubernetesScheduler extends VmAllocationPolicyTopologyAware {

    /** Score penalty added for each PreferNoSchedule taint not tolerated by the pod. */
    public static final double PREFER_NO_SCHEDULE_PENALTY = 10.0;

    /**
     * Source of the placed pods used to evaluate {@link PodAffinity}. The
     * broker installs this on construction so the scheduler can read peer-pod
     * placement state. Defaults to an empty list (PodAffinity rules then no-op).
     */
    private Supplier<List<KubernetesPod>> placedPodsSource = Collections::emptyList;

    public KubernetesScheduler() {
        super();
    }

    public KubernetesScheduler(final Policy policy) {
        super(policy);
    }

    @Override
    protected boolean passesStrictConstraints(final Vm vm, final Host host) {
        if (!super.passesStrictConstraints(vm, host)) {
            return false;
        }
        if (!(vm instanceof KubernetesPod pod) || !(host instanceof KubernetesNode node)) {
            return true;
        }
        if (!node.isSchedulable()) {
            return false;
        }
        if (!pod.getNodeSelector().matches(node.getLabels())) {
            return false;
        }
        if (!pod.getNodeAffinity().requiredMatches(node.getLabels())) {
            return false;
        }
        if (!Toleration.coversAll(pod.getTolerations(), node.getTaints())) {
            return false;
        }
        final boolean affinityOk = passesRequiredPodAffinity(pod, node);
        return affinityOk;
    }

    private boolean passesRequiredPodAffinity(final KubernetesPod pod, final KubernetesNode candidate) {
        if (pod.getPodAffinity().isEmpty()) {
            return true;
        }
        final var placed = collectPlacedPods();
        for (final var rule : pod.getPodAffinity().getRules()) {
            if (!rule.isRequired()) {
                continue;
            }
            final boolean hasMatchingPeerInBucket = anyMatchInBucket(pod, placed, rule, candidate);
            if (rule.antiAffinity() && hasMatchingPeerInBucket) {
                return false;
            }
            if (!rule.antiAffinity() && !hasMatchingPeerInBucket) {
                return false;
            }
        }
        return true;
    }

    /**
     * Reads placed pods directly from the datacenter's hosts, so PodAffinity
     * works even when the broker hasn't yet discovered its datacenters
     * (which happens asynchronously). Falls back to the broker-supplied source
     * if one was wired up.
     */
    private List<KubernetesPod> collectPlacedPods() {
        final List<KubernetesPod> out = new java.util.ArrayList<>(placedPodsSource.get());
        for (final var h : getHostList()) {
            for (final var v : h.getVmList()) {
                if (v instanceof KubernetesPod kp && !out.contains(kp)) {
                    out.add(kp);
                }
            }
        }
        return out;
    }

    private static boolean anyMatchInBucket(
        final KubernetesPod self,
        final List<KubernetesPod> placed,
        final PodAffinity.Rule rule,
        final KubernetesNode candidate)
    {
        for (final var peer : placed) {
            if (peer == self) {
                continue;
            }
            if (!peer.isCreated()) {
                continue;
            }
            if (!(peer.getHost() instanceof KubernetesNode peerNode)) {
                continue;
            }
            if (rule.selector().matches(peer.getLabels()) && rule.sameBucket(candidate, peerNode)) {
                return true;
            }
        }
        return false;
    }

    @Override
    protected double score(final Vm vm, final Host host) {
        final double base = super.score(vm, host);
        if (!(vm instanceof KubernetesPod pod) || !(host instanceof KubernetesNode node)) {
            return base;
        }
        // NodeAffinity preferred: higher weight ↔ better fit, so subtract from the score.
        final double affinityBonus = pod.getNodeAffinity().preferredScore(node.getLabels());
        // PreferNoSchedule taints: each non-tolerated one nudges the score up.
        final double taintPenalty = preferNoSchedulePenalty(pod, node);
        // Preferred PodAffinity / PodAntiAffinity contributions.
        final double podAffinityScore = preferredPodAffinityScore(pod, node);
        return base - affinityBonus + taintPenalty + podAffinityScore;
    }

    private double preferredPodAffinityScore(final KubernetesPod pod, final KubernetesNode node) {
        if (pod.getPodAffinity().isEmpty()) {
            return 0.0;
        }
        final var placed = collectPlacedPods();
        double score = 0.0;
        for (final var rule : pod.getPodAffinity().getRules()) {
            if (rule.isRequired()) {
                continue;
            }
            final boolean hasMatch = anyMatchInBucket(pod, placed, rule, node);
            // For affinity: matching peers in the bucket reduce the score (better fit).
            // For anti-affinity: matching peers increase the score (worse fit).
            if (hasMatch) {
                score += rule.antiAffinity() ? rule.weight() : -rule.weight();
            }
        }
        return score;
    }

    private static double preferNoSchedulePenalty(final KubernetesPod pod, final KubernetesNode node) {
        double penalty = 0.0;
        for (final var taint : node.getTaints()) {
            if (taint.effect() != Taint.Effect.PREFER_NO_SCHEDULE) {
                continue;
            }
            if (pod.getTolerations().stream().noneMatch(t -> t.tolerates(taint))) {
                penalty += PREFER_NO_SCHEDULE_PENALTY;
            }
        }
        return penalty;
    }
}
