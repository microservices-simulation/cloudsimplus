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
import org.cloudsimplus.kubernetes.KubernetesPod;
import org.cloudsimplus.kubernetes.controllers.DeploymentController;
import org.cloudsimplus.kubernetes.controllers.ReplicaSetController;
import org.cloudsimplus.kubernetes.lifecycle.Tick;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.function.IntConsumer;
import java.util.function.IntSupplier;
import java.util.function.Supplier;

/**
 * Horizontal Pod Autoscaler. Periodically samples a controller's pods, computes
 * average CPU utilisation, and adjusts the controller's
 * {@code desiredReplicas} to drive utilisation toward a target.
 *
 * <p>Wraps either a {@link ReplicaSetController} or a
 * {@link DeploymentController} via static {@link #of(ReplicaSetController, double)}
 * / {@link #of(DeploymentController, double)} factory methods. Mirrors the
 * algorithm used by Kubernetes' built-in HPA:
 * {@code desired = ceil(current * avgUtil / target)}, clamped to
 * {@code [minReplicas, maxReplicas]} and gated by a cooldown.</p>
 *
 * @since CloudSim Plus 9.0.0
 */
@Getter @Setter @Accessors(chain = true)
public class HorizontalPodAutoscaler implements Tick {

    private static final Logger LOG = LoggerFactory.getLogger(HorizontalPodAutoscaler.class.getSimpleName());

    @NonNull
    private final String name;

    /** Target CPU utilisation as a fraction in {@code (0, 1]}. */
    private final double targetCpuUtilization;

    private int minReplicas = 1;
    private int maxReplicas = 10;

    /** Minimum simulated seconds between two scaling events. */
    private double cooldownSeconds = 60.0;

    private double lastScaleAt = -1.0;

    /** Pulls the current backing pods. */
    private final Supplier<List<KubernetesPod>> podsSupplier;
    /** Reads the controller's current desired-replicas value. */
    private final IntSupplier desiredReplicasSupplier;
    /** Updates the controller's desired-replicas value. */
    private final IntConsumer desiredReplicasSetter;

    public HorizontalPodAutoscaler(
        final String name,
        final double targetCpuUtilization,
        final Supplier<List<KubernetesPod>> podsSupplier,
        final IntSupplier desiredReplicasSupplier,
        final IntConsumer desiredReplicasSetter)
    {
        if (targetCpuUtilization <= 0 || targetCpuUtilization > 1) {
            throw new IllegalArgumentException("target CPU utilisation must be in (0, 1]");
        }
        this.name = name;
        this.targetCpuUtilization = targetCpuUtilization;
        this.podsSupplier = podsSupplier;
        this.desiredReplicasSupplier = desiredReplicasSupplier;
        this.desiredReplicasSetter = desiredReplicasSetter;
    }

    /** Convenience: wrap a {@link ReplicaSetController}. */
    public static HorizontalPodAutoscaler of(final ReplicaSetController rs, final double targetCpu) {
        return new HorizontalPodAutoscaler(
            rs.getName() + "-hpa",
            targetCpu,
            rs::getManagedPods,
            rs::getDesiredReplicas,
            rs::setDesiredReplicas);
    }

    /** Convenience: wrap a {@link DeploymentController}. */
    public static HorizontalPodAutoscaler of(final DeploymentController dep, final double targetCpu) {
        return new HorizontalPodAutoscaler(
            dep.getName() + "-hpa",
            targetCpu,
            () -> dep.getActiveReplicaSet().getManagedPods(),
            dep::getDesiredReplicas,
            dep::setDesiredReplicas);
    }

    @Override
    public void tick(final double clockTime) {
        if (clockTime - lastScaleAt < cooldownSeconds && lastScaleAt >= 0) {
            return;
        }
        final var readyPods = podsSupplier.get().stream()
            .filter(KubernetesPod::isCreated)
            .toList();
        if (readyPods.isEmpty()) {
            return;
        }
        final double avg = readyPods.stream()
            .mapToDouble(KubernetesPod::getCpuPercentUtilization)
            .average()
            .orElse(0.0);
        if (avg <= 0) {
            return;
        }
        final int current = desiredReplicasSupplier.getAsInt();
        final int desired = Math.max(minReplicas, Math.min(maxReplicas,
            (int) Math.ceil(current * avg / targetCpuUtilization)));
        if (desired != current) {
            LOG.info("{}: HPA '{}': avg CPU={}%, replicas {} -> {}",
                String.format("%.2f", clockTime), name,
                String.format("%.1f", avg * 100), current, desired);
            desiredReplicasSetter.accept(desired);
            lastScaleAt = clockTime;
        }
    }
}
