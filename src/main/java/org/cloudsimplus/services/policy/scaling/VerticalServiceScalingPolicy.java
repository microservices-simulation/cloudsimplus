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
import org.cloudsimplus.vms.Vm;

import java.util.List;
import java.util.function.BiConsumer;

/**
 * Vertical scaling: when a service's averaged recent CPU utilization crosses
 * a threshold, resize the existing replicas up or down by a configured
 * {@link #getScalingFactor() scaling factor} (multiplicative).
 *
 * <p>Only RAM is mutated by default — CloudSim Plus' MIPS capacity is fixed
 * at VM construction time and cannot be safely changed at runtime through
 * the standard API. RAM is used as a proxy for "vertical capacity" so the
 * trigger semantics match CloudNativeSim's {@code VerticalScalingPolicy};
 * users that need full per-PE MIPS rescaling should fall back to
 * CloudSim Plus'
 * {@link org.cloudsimplus.autoscaling.VerticalVmScalingSimple} on the
 * underlying VMs.</p>
 *
 * @since CloudSim Plus 9.0.0
 */
@Getter
public class VerticalServiceScalingPolicy implements ServiceScalingPolicy {
    public static final double[] DEFAULT_CPU_THRESHOLD = {0.40, 0.85};
    public static final double DEFAULT_SCALING_FACTOR = 1.5;

    private final ResourceUsageRecorder recorder;
    private double[] cpuThreshold;
    private int recentRange;
    private double scalingFactor;

    private int scaleUpCount;
    private int scaleDownCount;
    private Direction lastDirection;

    private BiConsumer<Service, Direction> onScale;

    public VerticalServiceScalingPolicy(@NonNull final ResourceUsageRecorder recorder) {
        this.recorder = recorder;
        this.cpuThreshold = DEFAULT_CPU_THRESHOLD.clone();
        this.recentRange = 10;
        this.scalingFactor = DEFAULT_SCALING_FACTOR;
    }

    public VerticalServiceScalingPolicy cpuThreshold(final double lower, final double upper) {
        if (lower < 0 || upper <= lower || upper > 1) {
            throw new IllegalArgumentException("require 0 <= lower < upper <= 1");
        }
        this.cpuThreshold = new double[]{lower, upper};
        return this;
    }

    public VerticalServiceScalingPolicy scalingFactor(final double factor) {
        if (factor <= 1.0) {
            throw new IllegalArgumentException("scalingFactor must be > 1.0");
        }
        this.scalingFactor = factor;
        return this;
    }

    public VerticalServiceScalingPolicy recentRange(final int n) {
        if (n <= 0) {
            throw new IllegalArgumentException("recentRange must be > 0");
        }
        this.recentRange = n;
        return this;
    }

    public VerticalServiceScalingPolicy setOnScale(final BiConsumer<Service, Direction> cb) {
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
            applyResize(service, scalingFactor);
        } else {
            scaleDownCount++;
            applyResize(service, 1.0 / scalingFactor);
        }
        if (onScale != null) {
            onScale.accept(service, d);
        }
        lastDirection = null;
    }

    private void applyResize(final Service service, final double factor) {
        for (final Vm vm : service.getVms()) {
            final long currentRam = vm.getRam().getCapacity();
            final long newRam = (long) Math.max(1, currentRam * factor);
            vm.setRam(newRam);
        }
    }

    private double averagedRecentUtilization(final Service service) {
        double sum = 0;
        int counted = 0;
        for (final var vm : service.getVms()) {
            for (final String uid : recorder.getInstanceUids()) {
                if (!uid.startsWith(service.getName() + "-") && !uid.equals(service.getName())) {
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
                break;
            }
        }
        return counted == 0 ? 0 : sum / counted;
    }
}
