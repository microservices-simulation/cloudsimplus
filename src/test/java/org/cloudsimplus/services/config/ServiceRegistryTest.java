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
package org.cloudsimplus.services.config;

import org.cloudsimplus.services.Api;
import org.cloudsimplus.services.Service;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ServiceRegistryTest {

    private static InputStream resource(final String name) {
        final InputStream in = ServiceRegistryTest.class.getClassLoader().getResourceAsStream(name);
        assertNotNull(in, "Test resource not found: " + name);
        return in;
    }

    @Test
    void loadsSockShopFixtureWithCorrectCounts() {
        final var reg = new ServiceRegistry()
            .servicesInputStream(resource("sockshop/services.json"))
            .instancesInputStream(resource("sockshop/instances.yaml"))
            .load();

        // 9 APIs in services.json
        assertEquals(9, reg.getApis().size());

        // 14 services
        assertEquals(14, reg.getServiceGraph().getAllServices().size());

        // Total replicas: carts(2) + carts-db(1) + front-end(2) + catalogue(1) +
        // catalogue-db(1) + orders(1) + orders-db(1) + payment(1) +
        // queue-master(1) + rabbitmq(1) + session-db(2) + shipping(1) + user(1) +
        // user-db(1) = 17
        assertEquals(17, reg.getReplicas().size());

        // GET /catalogue weight is 2.0 in the JSON
        final Api getCatalogue = reg.getApis().stream()
            .filter(a -> a.getName().equals("GET /catalogue"))
            .findFirst().orElseThrow();
        assertEquals(2.0, getCatalogue.getWeight());
        // Default SLO threshold applied since the field is absent
        assertEquals(Api.DEFAULT_SLO_THRESHOLD, getCatalogue.getSloThreshold());
    }

    @Test
    void servicesGraphMirrorsCallsHierarchy() {
        final var reg = new ServiceRegistry()
            .servicesInputStream(resource("sockshop/services.json"))
            .instancesInputStream(resource("sockshop/instances.yaml"))
            .load();

        final Service frontEnd = reg.getServicesByName().get("front-end");
        assertEquals(6, reg.getServiceGraph().getCalls(frontEnd).size());

        // catalogue has only one parent (front-end)
        final Service catalogue = reg.getServicesByName().get("catalogue");
        assertEquals(1, reg.getServiceGraph().getParentServices(catalogue).size());
        assertEquals("front-end",
            reg.getServiceGraph().getParentServices(catalogue).getFirst().getName());

        // catalogue calls catalogue-db
        assertEquals(1, reg.getServiceGraph().getCalls(catalogue).size());
        assertEquals("catalogue-db",
            reg.getServiceGraph().getCalls(catalogue).getFirst().getName());
    }

    @Test
    void buildServiceChainsAfterRegistryHasExpectedSizes() {
        final var reg = new ServiceRegistry()
            .servicesInputStream(resource("sockshop/services.json"))
            .instancesInputStream(resource("sockshop/instances.yaml"))
            .load();
        final var chains = reg.getServiceGraph().buildServiceChains(reg.getApis());

        final Map<String, Api> apis = new java.util.HashMap<>();
        for (final var a : reg.getApis()) {
            apis.put(a.getName(), a);
        }

        assertEquals(3, chains.get(apis.get("GET /catalogue")).size());
        assertEquals(4, chains.get(apis.get("GET /login")).size());
        // POST /orders touches 9 services: front-end, orders, carts, carts-db,
        // payment, orders-db, shipping, rabbitmq, queue-master
        assertEquals(9, chains.get(apis.get("POST /orders")).size());
    }

    @Test
    void replicaSpecsCarryDefaults() {
        final var reg = new ServiceRegistry()
            .servicesInputStream(resource("sockshop/services.json"))
            .instancesInputStream(resource("sockshop/instances.yaml"))
            .load();

        // carts-db has no requests/limits block in the YAML — defaults must apply
        final ReplicaSpec cartsDb = reg.getReplicaSpecs().stream()
            .filter(s -> "carts-db".equals(s.prefix()))
            .findFirst().orElseThrow();
        assertEquals(ReplicaSpec.DEFAULT_REQUESTS_SHARE, cartsDb.requestsShare());
        assertEquals(ReplicaSpec.DEFAULT_REQUESTS_RAM, cartsDb.requestsRam());
        assertEquals(ReplicaSpec.DEFAULT_LIMITS_SHARE, cartsDb.limitsShare());
        assertEquals(ReplicaSpec.DEFAULT_LIMITS_RAM, cartsDb.limitsRam());

        // carts has explicit requests/limits
        final ReplicaSpec carts = reg.getReplicaSpecs().stream()
            .filter(s -> "carts".equals(s.prefix()))
            .findFirst().orElseThrow();
        assertEquals(100L, carts.requestsShare());
        assertEquals(200L, carts.requestsRam());
        assertEquals(300L, carts.limitsShare());
        assertEquals(500L, carts.limitsRam());
        assertEquals(2, carts.replicas());
    }

    @Test
    void replicaNamesAreStableInstanceUids() {
        final var reg = new ServiceRegistry()
            .servicesInputStream(resource("sockshop/services.json"))
            .instancesInputStream(resource("sockshop/instances.yaml"))
            .load();

        // carts has 2 replicas, named carts-0 / carts-1
        final long carts = reg.getReplicas().stream()
            .filter(r -> r.name().startsWith("carts-"))
            // exclude carts-db-...
            .filter(r -> r.spec().prefix().equals("carts"))
            .count();
        assertEquals(2, carts);

        assertTrue(reg.getReplicas().stream().anyMatch(r -> r.name().equals("carts-0")));
        assertTrue(reg.getReplicas().stream().anyMatch(r -> r.name().equals("carts-1")));
    }
}
