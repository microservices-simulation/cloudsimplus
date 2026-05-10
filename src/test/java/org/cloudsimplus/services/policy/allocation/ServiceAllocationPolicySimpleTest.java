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
import org.cloudsimplus.services.ServiceSimple;
import org.cloudsimplus.services.config.Replica;
import org.cloudsimplus.services.config.ReplicaSpec;
import org.cloudsimplus.vms.VmSimple;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ServiceAllocationPolicySimpleTest {

    private static Replica replica(final String name, final String... labels) {
        final var spec = new ReplicaSpec(
            stripIndex(name), "pod", List.of(labels), 1,
            500L, 100.0, 100.0,
            ReplicaSpec.DEFAULT_REQUESTS_SHARE, ReplicaSpec.DEFAULT_REQUESTS_RAM,
            ReplicaSpec.DEFAULT_LIMITS_SHARE, ReplicaSpec.DEFAULT_LIMITS_RAM
        );
        return new Replica(name, new VmSimple(1024, 1), spec);
    }

    private static String stripIndex(final String name) {
        final int dash = name.lastIndexOf('-');
        return dash < 0 ? name : name.substring(0, dash);
    }

    @Test
    void labelIntersectionMatchesReplicasToServices() {
        final var carts = new ServiceSimple("carts");
        carts.addLabel("carts");
        final var cartsDb = new ServiceSimple("carts-db");
        cartsDb.addLabel("carts-db");
        cartsDb.addLabel("mongo");

        final List<Replica> replicas = List.of(
            replica("carts-0", "carts"),
            replica("carts-1", "carts"),
            replica("carts-db-0", "carts-db", "mongo")
        );

        final var policy = new ServiceAllocationPolicySimple();
        final var mapping = policy.match(List.of(carts, cartsDb), replicas);

        assertEquals(2, mapping.get(carts).size());
        assertEquals(1, mapping.get(cartsDb).size());
        assertEquals(2, carts.getVms().size());
        assertEquals(1, cartsDb.getVms().size());
        assertEquals(3, policy.allVms().size()); // 3 unique VMs
    }

    @Test
    void namePrefixFallbackWhenLabelsAreAbsent() {
        final var svc = new ServiceSimple("payment");
        // no labels declared
        final var policy = new ServiceAllocationPolicySimple();
        final var mapping = policy.match(
            List.of(svc),
            List.of(replica("payment-0"), replica("orders-0"))
        );
        assertEquals(1, mapping.get(svc).size());
        assertEquals("payment-0", mapping.get(svc).getFirst().name());
    }

    @Test
    void getReplicasForReturnsEmptyForUnknownService() {
        final var policy = new ServiceAllocationPolicySimple();
        policy.match(List.of(), List.of());
        final var unknown = new ServiceSimple("ghost");
        assertTrue(policy.getReplicasFor(unknown).isEmpty());
    }

    @Test
    void allVmsArePresentEvenIfUnmatched() {
        final var s1 = new ServiceSimple("a");
        s1.addLabel("a");
        final var policy = new ServiceAllocationPolicySimple();
        final var rA = replica("a-0", "a");
        final var rOrphan = replica("orphan-0", "nobody");
        policy.match(List.of(s1), List.of(rA, rOrphan));

        assertEquals(2, policy.allVms().size());
        assertSame(rA.vm(), policy.allVms().get(0));
        assertSame(rOrphan.vm(), policy.allVms().get(1));
    }

    @Test
    void nullPolicyReturnsEmpties() {
        final var policy = ServiceAllocationPolicy.NULL;
        assertTrue(policy.match(List.of(), List.of()).isEmpty());
        assertTrue(policy.allVms().isEmpty());
        assertTrue(policy.getReplicasFor(Service.NULL).isEmpty());
    }
}
