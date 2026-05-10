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

import org.cloudsimplus.services.Service;
import org.cloudsimplus.services.config.Replica;
import org.cloudsimplus.vms.Vm;

import java.util.List;
import java.util.Map;

/**
 * Service-level allocation policy: maps {@link Replica}s onto {@link Service}s
 * (label-affinity matching) and exposes the resulting {@link Vm}s for the
 * broker to submit to the datacenter.
 *
 * <p>This sits on top of CloudSim Plus' regular
 * {@link org.cloudsimplus.allocationpolicies.VmAllocationPolicy VmAllocationPolicy} —
 * VM-to-Host placement is still delegated to the datacenter; what this policy
 * adds is the higher-level "which replica belongs to which service" decision
 * that the original CloudNativeSim
 * {@code policy.allocation.ServiceAllocationPolicySimple} implements.</p>
 *
 * <p>The default implementation is {@link ServiceAllocationPolicySimple}; the
 * {@link #NULL no-op placeholder} can be used in scenarios that do not need
 * any matching.</p>
 *
 * @since CloudSim Plus 9.0.0
 */
public interface ServiceAllocationPolicy {
    /** Null Object placeholder for {@link ServiceAllocationPolicy}. */
    ServiceAllocationPolicy NULL = new ServiceAllocationPolicy() {
        @Override
        public Map<Service, List<Replica>> match(final List<Service> services, final List<Replica> replicas) {
            return Map.of();
        }
        @Override
        public Map<Service, List<Replica>> getServiceReplicas() {
            return Map.of();
        }
        @Override
        public List<Replica> getReplicasFor(final Service service) {
            return List.of();
        }
        @Override
        public List<Vm> allVms() {
            return List.of();
        }
    };

    /**
     * Computes a stable mapping from each {@link Service} to the subset of
     * {@code replicas} that belong to it. The result is also stored internally
     * and accessible via {@link #getServiceReplicas()}.
     *
     * <p>Each matched replica's VM is registered with the corresponding
     * service via {@link Service#addVm(Vm)}.</p>
     *
     * @param services the services to allocate replicas to
     * @param replicas the candidate replica pool
     * @return the per-service replica mapping
     */
    Map<Service, List<Replica>> match(List<Service> services, List<Replica> replicas);

    /**
     * @return read-only view of the most recent {@link #match(List, List) match}
     *         result.
     */
    Map<Service, List<Replica>> getServiceReplicas();

    /** @return the replicas matched to {@code service}, or an empty list. */
    List<Replica> getReplicasFor(Service service);

    /**
     * @return all VMs that were selected by the most recent
     *         {@link #match(List, List)} call (in deterministic order),
     *         ready to be submitted to a broker.
     */
    List<Vm> allVms();
}
