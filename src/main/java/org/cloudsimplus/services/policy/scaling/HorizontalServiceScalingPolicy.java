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
package org.cloudsimplus.services.policy.scaling;

import lombok.Getter;
import lombok.NonNull;
import org.cloudsimplus.services.Service;
import org.cloudsimplus.services.reporting.ResourceUsageRecorder;
import org.cloudsimplus.services.reporting.UsageSample;

import java.util.List;
import java.util.function.BiConsumer;

/**
 * Horizontal scaling: monitors a {@link Service}'s averaged recent CPU
 * utilization (mean over the last {@link #getRecentRange() recentRange}
 * samples) and triggers a scaling action when it crosses one of the
 * {@link #getCpuThreshold() CPU thresholds}.
 *
 * <p>Mirrors CloudNativeSim's {@code policy.scaling.HorizontalScalingPolicy},
 * but reads from a {@link ResourceUsageRecorder} (no global static map) and
 * delegates the actual replica-add/remove to a user-supplied
 * {@link #setOnScale(BiConsumer) callback} so the broker can hook in the
 * concrete VM-creation logic.</p>
 *
 * <h3>Threshold semantics</h3>
 * <ul>
 *   <li>{@code average > cpuThreshold[1]} (default {@code 0.85}) → scale UP.</li>
 *   <li>{@code 0 < average < cpuThreshold[0]} (default {@code 0.40}) → scale DOWN.</li>
 *   <li>Anything else: no action.</li>
 * </ul>
 *
 * @since CloudSim Plus 9.0.0
 */
@Getter
public class HorizontalServiceScalingPolicy implements ServiceScalingPolicy {
    /** Default lower / upper CPU utilization thresholds (fraction of capacity). */
    public static final double[] DEFAULT_CPU_THRESHOLD = {0.40, 0.85};

    private final ResourceUsageRecorder recorder;
    private double[] cpuThreshold;
    private int recentRange;

    private int scaleUpCount;
    private int scaleDownCount;
    private Direction lastDirection;

    private BiConsumer<Service, Direction> onScale;

    /**
     * Creates a horizontal scaling policy reading from {@code recorder} with
     * the {@link #DEFAULT_CPU_THRESHOLD default thresholds} and a recent-window
     * of 10 samples.
     */
    public HorizontalServiceScalingPolicy(@NonNull final ResourceUsageRecorder recorder) {
        this.recorder = recorder;
        this.cpuThreshold = DEFAULT_CPU_THRESHOLD.clone();
        this.recentRange = 10;
    }

    /** Override the (lower, upper) CPU utilization thresholds. */
    public HorizontalServiceScalingPolicy cpuThreshold(final double lower, final double upper) {
        if (lower < 0 || upper <= lower || upper > 1) {
            throw new IllegalArgumentException("require 0 <= lower < upper <= 1");
        }
        this.cpuThreshold = new double[]{lower, upper};
        return this;
    }

    /** Override the number of recent samples averaged when computing utilization. */
    public HorizontalServiceScalingPolicy recentRange(final int n) {
        if (n <= 0) {
            throw new IllegalArgumentException("recentRange must be > 0");
        }
        this.recentRange = n;
        return this;
    }

    /**
     * Registers a callback invoked by {@link #scale(Service)} when the policy
     * decides to scale a service. The callback receives the service and the
     * direction; it is responsible for the concrete VM creation/removal logic.
     */
    public HorizontalServiceScalingPolicy setOnScale(final BiConsumer<Service, Direction> cb) {
        this.onScale = cb;
        return this;
    }

    @Override
    public boolean needScaling(@NonNull final Service service) {
        final double avg = averagedRecentUtilization(service);
        if (avg > cpuThreshold[1]) {
            this.lastDirection = Direction.UP;
            return true;
        }
        if (avg > 0 && avg < cpuThreshold[0]) {
            this.lastDirection = Direction.DOWN;
            return true;
        }
        return false;
    }

    @Override
    public void scale(@NonNull final Service service) {
        if (lastDirection == null) {
            return;
        }
        final Direction d = lastDirection;
        if (d == Direction.UP) {
            scaleUpCount++;
        } else {
            scaleDownCount++;
        }
        if (onScale != null) {
            onScale.accept(service, d);
        }
        lastDirection = null;
    }

    private double averagedRecentUtilization(final Service service) {
        double sum = 0;
        int counted = 0;
        for (final var vm : service.getVms()) {
            // Lookup the per-instance series by scanning the recorder's UID map.
            // The recorder stores absolute MIPS; divide by total capacity to
            // get a fraction in [0, 1].
            final String uid = uidFor(vm, service);
            if (uid == null) {
                continue;
            }
            final List<UsageSample> samples = recorder.getCpuHistory().get(uid);
            if (samples == null || samples.isEmpty()) {
                continue;
            }
            final var window = samples.subList(Math.max(0, samples.size() - recentRange), samples.size());
            final double meanMips = window.stream().mapToDouble(UsageSample::usage).average().orElse(0);
            final double cap = vm.getTotalMipsCapacity();
            if (cap > 0) {
                sum += meanMips / cap;
                counted++;
            }
        }
        return counted == 0 ? 0 : sum / counted;
    }

    /**
     * Resolves the {@link ResourceUsageRecorder} UID for {@code vm} backing
     * {@code service}. Falls back to scanning the recorder's UID map.
     */
    private String uidFor(final org.cloudsimplus.vms.Vm vm, final Service service) {
        for (final String uid : recorder.getInstanceUids()) {
            if (uid.startsWith(service.getName() + "-") || uid.equals(service.getName())) {
                // Fast path: name-prefix convention from ServiceRegistry.
                if (recorder.getCpuHistory().get(uid) != null
                    && !recorder.getCpuHistory().get(uid).isEmpty()) {
                    return uid;
                }
            }
        }
        return null;
    }
}
