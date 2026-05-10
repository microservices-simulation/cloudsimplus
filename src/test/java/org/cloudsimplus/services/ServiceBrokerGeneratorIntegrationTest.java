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
package org.cloudsimplus.services;

import ch.qos.logback.classic.Level;
import org.cloudsimplus.core.CloudSimPlus;
import org.cloudsimplus.datacenters.DatacenterSimple;
import org.cloudsimplus.hosts.HostSimple;
import org.cloudsimplus.resources.Pe;
import org.cloudsimplus.resources.PeSimple;
import org.cloudsimplus.services.generator.RequestGenerator;
import org.cloudsimplus.services.reporting.ResourceUsageRecorder;
import org.cloudsimplus.util.Log;
import org.cloudsimplus.vms.Vm;
import org.cloudsimplus.vms.VmSimple;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Integration test for the step-7 wiring: when a {@link RequestGenerator} and
 * {@link ServiceGraph} are configured on the broker, requests are spawned
 * automatically, the call tree is expanded from the API's chain, and
 * critical-path nodeDelay is recorded.
 */
class ServiceBrokerGeneratorIntegrationTest {
    @BeforeAll
    static void quietLogs() {
        Log.setLevel(Level.OFF);
    }

    @Test
    void generatorTickProducesAndExecutesRequestsViaApiChain() {
        final var sim = new CloudSimPlus();

        final List<Pe> pes = List.of(
            new PeSimple(1000), new PeSimple(1000),
            new PeSimple(1000), new PeSimple(1000));
        new DatacenterSimple(sim, List.of(new HostSimple(8_000, 100_000, 1_000_000, pes)));

        final var broker = new ServiceBrokerSimple(sim);

        final Vm vmFront = new VmSimple(1000, 1).setRam(512).setBw(1000).setSize(10_000);
        final Vm vmCart = new VmSimple(1000, 1).setRam(512).setBw(1000).setSize(10_000);
        final Vm vmDb = new VmSimple(1000, 1).setRam(512).setBw(1000).setSize(10_000);
        broker.submitVmList(List.of(vmFront, vmCart, vmDb));

        final var graph = new ServiceGraph();
        final var front = (ServiceSimple) new ServiceSimple("front").addVm(vmFront);
        front.addApi("GET /cart");
        final var cart = (ServiceSimple) new ServiceSimple("cart").addVm(vmCart);
        cart.addApi("GET /cart");
        final var db = (ServiceSimple) new ServiceSimple("db").addVm(vmDb);
        db.addApi("GET /cart");
        graph.addService(front, null);
        graph.addService(cart, front);
        graph.addService(db, cart);
        broker.addService(front).addService(cart).addService(db);

        final var api = new Api("GET /cart", 1.0, 30.0);
        graph.buildServiceChains(List.of(api));

        final var gen = new RequestGenerator()
            .apis(List.of(api))
            .rpsMode(2)         // 2 requests per tick
            .timeLimit(2)       // 2 seconds → ~4 requests total
            .length(500, 0)
            .random(new Random(7));

        broker.setServiceGraph(graph)
            .setRequestGenerator(gen)
            .setResourceUsageRecorder(new ResourceUsageRecorder(10.0))
            .setRequestInterval(1.0);

        sim.start();

        // 2 generated × 2 ticks = ~4 requests; allow for ramp/drain edge cases.
        assertTrue(broker.getRequests().size() >= 2,
            "expected at least 2 generated requests, got %d".formatted(broker.getRequests().size()));
        assertEquals(broker.getRequests().size(), broker.getFinishedRequests().size(),
            "every generated request must finish");

        // The placeholder root call's service got pinned to the chain's source.
        for (final var r : broker.getFinishedRequests()) {
            assertSame(front, r.getRootCall().getService(),
                "root call must target the chain's source service");
            // Root has 1 child (cart), which has 1 child (db).
            assertEquals(1, r.getRootCall().getChildren().size());
            assertEquals(1, r.getRootCall().getChildren().getFirst().getChildren().size());
            // nodeDelay populated for every service in the chain.
            assertTrue(r.getNodeDelay().containsKey(front));
            assertTrue(r.getNodeDelay().containsKey(cart));
            assertTrue(r.getNodeDelay().containsKey(db));
        }
        // Global RPS history was recorded (one entry per tick).
        assertTrue(broker.getGlobalRpsHistory().size() >= 1);
    }

    @Test
    void rejectsNonPositiveIntervals() {
        final var broker = new ServiceBrokerSimple(new CloudSimPlus());
        assertThrows(IllegalArgumentException.class, () -> broker.setRequestInterval(0));
        assertThrows(IllegalArgumentException.class, () -> broker.setServiceSchedulingInterval(-1));
    }
}
