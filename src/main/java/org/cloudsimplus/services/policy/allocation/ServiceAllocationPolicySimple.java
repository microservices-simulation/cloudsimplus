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
package org.cloudsimplus.services.policy.allocation;

import lombok.NonNull;
import org.cloudsimplus.services.Service;
import org.cloudsimplus.services.config.Replica;
import org.cloudsimplus.vms.Vm;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Default {@link ServiceAllocationPolicy}: matches a {@link Replica} to a
 * {@link Service} when their {@link Service#getLabels() label sets} intersect.
 *
 * <p>If a service declares no labels, it falls back to matching by name —
 * a replica whose {@link Replica#name() name} starts with the service's
 * name (followed by {@code "-"}) is considered a backing replica. This
 * mirrors the {@code prefix} convention used by the CloudNativeSim
 * {@code instances.yaml}.</p>
 *
 * @since CloudSim Plus 9.0.0
 */
public class ServiceAllocationPolicySimple implements ServiceAllocationPolicy {
    private final Map<Service, List<Replica>> serviceReplicas = new LinkedHashMap<>();
    private final List<Vm> allVms = new ArrayList<>();

    @Override
    public Map<Service, List<Replica>> match(@NonNull final List<Service> services,
                                             @NonNull final List<Replica> replicas) {
        serviceReplicas.clear();
        allVms.clear();

        for (final Service svc : services) {
            final List<Replica> matched = new ArrayList<>();
            for (final Replica r : replicas) {
                if (matches(svc, r)) {
                    matched.add(r);
                    svc.addVm(r.vm());
                }
            }
            serviceReplicas.put(svc, matched);
        }

        // Preserve the input replica order, deduplicated by identity
        // (Vm.equals is id-based and two unallocated VMs may share id == -1).
        for (final Replica r : replicas) {
            boolean present = false;
            for (final Vm v : allVms) {
                if (v == r.vm()) {
                    present = true;
                    break;
                }
            }
            if (!present) {
                allVms.add(r.vm());
            }
        }
        return Collections.unmodifiableMap(serviceReplicas);
    }

    /**
     * Returns {@code true} when {@code replica} should back {@code service},
     * either by label intersection or by the {@code "<service>-N"} naming
     * fallback.
     */
    protected boolean matches(@NonNull final Service service, @NonNull final Replica replica) {
        final List<String> svcLabels = service.getLabels();
        final List<String> rLabels = replica.spec().labels();
        if (!svcLabels.isEmpty() && !rLabels.isEmpty()) {
            for (final String l : svcLabels) {
                if (rLabels.contains(l)) {
                    return true;
                }
            }
            return false;
        }
        // Name-prefix fallback when either side declares no labels.
        return replica.name().equals(service.getName())
            || replica.name().startsWith(service.getName() + "-");
    }

    @Override
    public Map<Service, List<Replica>> getServiceReplicas() {
        return Collections.unmodifiableMap(serviceReplicas);
    }

    @Override
    public List<Replica> getReplicasFor(@NonNull final Service service) {
        return Collections.unmodifiableList(
            serviceReplicas.getOrDefault(service, Collections.emptyList()));
    }

    @Override
    public List<Vm> allVms() {
        return Collections.unmodifiableList(allVms);
    }
}
