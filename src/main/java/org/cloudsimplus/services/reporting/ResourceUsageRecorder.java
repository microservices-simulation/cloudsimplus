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
package org.cloudsimplus.services.reporting;

import lombok.Getter;
import lombok.NonNull;
import org.cloudsimplus.core.Simulation;
import org.cloudsimplus.services.Service;
import org.cloudsimplus.vms.Vm;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Periodically samples per-{@link Vm} CPU and RAM utilization and stores the
 * resulting time series, keyed by a stable {@code instanceUid} (typically
 * {@code "<service-name>-<index>"}).
 *
 * <p>This is the CloudSim Plus equivalent of the four
 * {@code Exporter.usageOf*History} static maps from CloudNativeSim, but
 * scoped to a single recorder instance (no global mutable state). The recorder
 * is consumed by:</p>
 * <ul>
 *     <li>the upcoming
 *         {@link org.cloudsimplus.services.policy.scaling.ServiceScalingPolicy
 *         service-scaling policies}, which read the most recent samples to
 *         decide whether a service should scale up/down;</li>
 *     <li>the upcoming {@code ServiceReporter}, which dumps the full series to
 *         per-instance CSV files for offline analysis (Grafana, etc.).</li>
 * </ul>
 *
 * <p>Samples can be produced two ways:</p>
 * <ol>
 *     <li>by calling {@link #recordSample(double)} explicitly at every
 *         scheduling tick (handy in unit tests and in the broker), or</li>
 *     <li>by calling {@link #attach(Simulation)}, which registers an
 *         {@link Simulation#addOnClockTickListener clock-tick listener} that
 *         fires {@link #recordSample(double)} at most once per
 *         {@link #getSamplingInterval() sampling interval}.</li>
 * </ol>
 *
 * @since CloudSim Plus 9.0.0
 */
public class ResourceUsageRecorder {
    /** Default sampling interval, matching CloudNativeSim's default {@code schedulingInterval}. */
    public static final double DEFAULT_SAMPLING_INTERVAL = 10.0;

    @Getter
    private final double samplingInterval;

    private final Map<String, Vm> vmsByUid = new LinkedHashMap<>();
    private final Map<Vm, String> uidByVm = new LinkedHashMap<>();
    private final Map<Vm, Service> serviceByVm = new LinkedHashMap<>();

    private final Map<String, List<UsageSample>> cpuHistory = new LinkedHashMap<>();
    private final Map<String, List<UsageSample>> ramHistory = new LinkedHashMap<>();

    private double lastSampleTime = -1;

    /**
     * Creates a recorder with the {@link #DEFAULT_SAMPLING_INTERVAL default
     * sampling interval} (10 s).
     */
    public ResourceUsageRecorder() {
        this(DEFAULT_SAMPLING_INTERVAL);
    }

    /**
     * @param samplingInterval the minimum elapsed simulation time (in seconds)
     *                         between two consecutive samples for the same VM.
     *                         Must be &gt; 0.
     */
    public ResourceUsageRecorder(final double samplingInterval) {
        if (samplingInterval <= 0) {
            throw new IllegalArgumentException("samplingInterval must be > 0");
        }
        this.samplingInterval = samplingInterval;
    }

    /**
     * Registers a VM under a given unique id. The id is used as the row key in
     * every per-instance CSV the reporter writes; choose something stable
     * (e.g. {@code "carts-0"}, {@code "carts-1"}).
     *
     * @param instanceUid stable unique id for the VM
     * @param vm          the VM to sample
     * @return this recorder, to enable chaining
     */
    public ResourceUsageRecorder register(@NonNull final String instanceUid, @NonNull final Vm vm) {
        return register(instanceUid, vm, null);
    }

    /**
     * Registers a VM under a given unique id, optionally tagging it with the
     * {@link Service} it backs.
     *
     * @param instanceUid stable unique id for the VM
     * @param vm          the VM to sample
     * @param service     the service this VM backs (or {@code null} if not
     *                    associated with any service yet)
     * @return this recorder, to enable chaining
     */
    public ResourceUsageRecorder register(@NonNull final String instanceUid,
                                          @NonNull final Vm vm,
                                          final Service service) {
        if (instanceUid.isBlank()) {
            throw new IllegalArgumentException("instanceUid must not be blank");
        }
        vmsByUid.put(instanceUid, vm);
        uidByVm.put(vm, instanceUid);
        if (service != null) {
            serviceByVm.put(vm, service);
        }
        cpuHistory.computeIfAbsent(instanceUid, k -> new ArrayList<>());
        ramHistory.computeIfAbsent(instanceUid, k -> new ArrayList<>());
        return this;
    }

    /**
     * Hooks the recorder into a running {@link Simulation}: a clock-tick
     * listener fires {@link #recordSample(double)} at most once per
     * {@link #getSamplingInterval() sampling interval}.
     *
     * @param simulation the simulation to listen on
     * @return this recorder, to enable chaining
     */
    public ResourceUsageRecorder attach(@NonNull final Simulation simulation) {
        simulation.addOnClockTickListener(info -> recordSample(info.getTime()));
        return this;
    }

    /**
     * Forces a sample for every registered VM at the given clock value, but
     * only if at least {@link #getSamplingInterval()} seconds have elapsed
     * since the last sample. Returns {@code true} if a sample was actually
     * recorded, {@code false} if it was skipped.
     */
    public boolean recordSample(final double clock) {
        if (lastSampleTime >= 0 && clock - lastSampleTime + 1e-9 < samplingInterval) {
            return false;
        }
        final double session = lastSampleTime < 0 ? samplingInterval : clock - lastSampleTime;
        for (final var entry : vmsByUid.entrySet()) {
            final var uid = entry.getKey();
            final var vm = entry.getValue();
            cpuHistory.get(uid).add(new UsageSample(clock, session, cpuUsage(vm)));
            ramHistory.get(uid).add(new UsageSample(clock, session, ramUsage(vm)));
        }
        lastSampleTime = clock;
        return true;
    }

    /**
     * @return an unmodifiable view of the per-instance CPU samples (in MIPS).
     */
    public Map<String, List<UsageSample>> getCpuHistory() {
        return unmodifiable(cpuHistory);
    }

    /**
     * @return an unmodifiable view of the per-instance RAM samples (in MB).
     */
    public Map<String, List<UsageSample>> getRamHistory() {
        return unmodifiable(ramHistory);
    }

    /**
     * @return the average CPU usage (in MIPS, mean of {@link UsageSample#usage()}
     *         across the recorded series) for the given instance, or {@code 0.0}
     *         if no samples were recorded.
     */
    public double getAverageCpu(@NonNull final String instanceUid) {
        return averageOf(cpuHistory.get(instanceUid));
    }

    /**
     * @return the average RAM usage (in MB) for the given instance, or
     *         {@code 0.0} if no samples were recorded.
     */
    public double getAverageRam(@NonNull final String instanceUid) {
        return averageOf(ramHistory.get(instanceUid));
    }

    /**
     * @return the average CPU usage (in MIPS) over every replica that backs
     *         {@code service}, or {@code 0.0} if no replica has any samples.
     */
    public double getAverageCpuOf(@NonNull final Service service) {
        return averageOfService(service, cpuHistory);
    }

    /**
     * @return the average RAM usage (in MB) over every replica that backs
     *         {@code service}, or {@code 0.0} if no replica has any samples.
     */
    public double getAverageRamOf(@NonNull final Service service) {
        return averageOfService(service, ramHistory);
    }

    /**
     * @return read-only list of registered instance UIDs (in registration order).
     */
    public List<String> getInstanceUids() {
        return List.copyOf(vmsByUid.keySet());
    }

    private double averageOfService(final Service service, final Map<String, List<UsageSample>> map) {
        double sum = 0.0;
        int count = 0;
        for (final var e : serviceByVm.entrySet()) {
            if (!service.equals(e.getValue())) {
                continue;
            }
            final String uid = uidByVm.get(e.getKey());
            if (uid == null) {
                continue;
            }
            for (final var s : map.get(uid)) {
                sum += s.usage();
                count++;
            }
        }
        return count == 0 ? 0.0 : sum / count;
    }

    private static double cpuUsage(final Vm vm) {
        // Absolute CPU consumption in MIPS = % utilization × total MIPS capacity.
        return vm.getCpuPercentUtilization() * vm.getTotalMipsCapacity();
    }

    private static double ramUsage(final Vm vm) {
        // RAM allocated to the VM in MB.
        return vm.getRam().getAllocatedResource();
    }

    private static double averageOf(final List<UsageSample> samples) {
        if (samples == null || samples.isEmpty()) {
            return 0.0;
        }
        double sum = 0.0;
        for (final var s : samples) {
            sum += s.usage();
        }
        return sum / samples.size();
    }

    private static Map<String, List<UsageSample>> unmodifiable(final Map<String, List<UsageSample>> in) {
        final Map<String, List<UsageSample>> out = new LinkedHashMap<>();
        for (final var e : in.entrySet()) {
            out.put(e.getKey(), Collections.unmodifiableList(e.getValue()));
        }
        return Collections.unmodifiableMap(out);
    }
}
