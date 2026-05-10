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

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ServiceGraphTest {

    @Test
    void addServiceWiresParentChildAndDegrees() {
        final var g = new ServiceGraph();
        final var a = new ServiceSimple("a");
        final var b = new ServiceSimple("b");
        g.addService(a, null);
        g.addService(b, a);

        assertEquals(List.of(b), g.getCalls(a));
        assertEquals(List.of(a), g.getParentServices(b));
        assertEquals(0, g.getInDegree(a));
        assertEquals(1, g.getOutDegree(a));
        assertEquals(1, g.getInDegree(b));
        assertEquals(0, g.getOutDegree(b));
        assertSame(g, a.getServiceGraph());
    }

    @Test
    void duplicateEdgesAreIgnored() {
        final var g = new ServiceGraph();
        final var a = new ServiceSimple("a");
        final var b = new ServiceSimple("b");
        g.addService(b, a);
        g.addService(b, a);
        assertEquals(1, g.getOutDegree(a));
        assertEquals(1, g.getInDegree(b));
    }

    @Test
    void deleteServiceCleansUpEdges() {
        final var g = new ServiceGraph();
        final var a = new ServiceSimple("a");
        final var b = new ServiceSimple("b");
        final var c = new ServiceSimple("c");
        g.addService(a, null);
        g.addService(b, a);
        g.addService(c, b);

        g.deleteService(b);

        assertFalse(g.contains(b));
        assertEquals(0, g.getOutDegree(a));
        assertEquals(0, g.getInDegree(c));
        assertTrue(g.getCalls(a).isEmpty());
        assertTrue(g.getParentServices(c).isEmpty());
    }

    /**
     * Mini SockShop slice: front-end → catalogue → catalogue-db.
     * For API "GET /catalogue", chain should be [front-end, catalogue, catalogue-db]
     * (sorted by ascending in-degree).
     */
    @Test
    void buildServiceChainsRespectsApiListAndTopologicalOrder() {
        final var g = new ServiceGraph();
        final var frontEnd = new ServiceSimple("front-end");
        frontEnd.addApi("GET /catalogue");
        final var catalogue = new ServiceSimple("catalogue");
        catalogue.addApi("GET /catalogue");
        final var catalogueDb = new ServiceSimple("catalogue-db");
        catalogueDb.addApi("GET /catalogue");
        final var unrelated = new ServiceSimple("payment");
        unrelated.addApi("POST /cards");

        g.addService(frontEnd, null);
        g.addService(catalogue, frontEnd);
        g.addService(catalogueDb, catalogue);
        g.addService(unrelated, null);

        final var apiCatalogue = new Api("GET /catalogue");
        final var apiCards = new Api("POST /cards");

        final Map<Api, List<Service>> chains = g.buildServiceChains(List.of(apiCatalogue, apiCards));

        assertEquals(List.of(frontEnd, catalogue, catalogueDb), chains.get(apiCatalogue));
        assertEquals(List.of(unrelated), chains.get(apiCards));
        assertEquals(chains.get(apiCatalogue), apiCatalogue.getServiceChain());
    }

    @Test
    void sourcesAndSinksAreScopedToTheChain() {
        final var g = new ServiceGraph();
        final var a = new ServiceSimple("a");
        final var b = new ServiceSimple("b");
        final var c = new ServiceSimple("c");
        // a -> b -> c, but the "API chain" only contains b and c
        g.addService(a, null);
        g.addService(b, a);
        g.addService(c, b);

        final List<Service> chain = List.of(b, c);
        assertEquals(List.of(b), g.getSources(chain));
        assertEquals(List.of(c), g.getSinks(chain));
    }

    /**
     * Round-trip on the full SockShop topology — taken straight from
     * examples/src/sockshop/services.json. Asserts that the per-API chain
     * sizes match the services that declare each API in their apiList.
     */
    @Test
    void sockShopTopologyChainSizesMatchExpectations() {
        final var g = sockShopGraph();

        // Build a minimal API set
        final Map<String, Api> apis = new HashMap<>();
        for (final String name : new String[] {
            "GET /catalogue", "GET /login", "POST /register", "GET /cart", "POST /cart",
            "POST /cards", "POST /addresses", "POST /orders", "GET /tags"
        }) {
            apis.put(name, new Api(name));
        }
        final var chains = g.buildServiceChains(List.copyOf(apis.values()));

        // GET /catalogue: front-end, catalogue, catalogue-db => 3
        assertEquals(3, chains.get(apis.get("GET /catalogue")).size());
        // GET /login: front-end, user, user-db, session-db => 4
        assertEquals(4, chains.get(apis.get("GET /login")).size());
        // POST /orders: front-end, orders, carts, carts-db, payment, orders-db,
        //               shipping, rabbitmq, queue-master => 9
        assertEquals(9, chains.get(apis.get("POST /orders")).size());
        // GET /tags is only declared by front-end => 1
        assertEquals(1, chains.get(apis.get("GET /tags")).size());

        // Sources for POST /orders is just front-end (only service with no
        // parent inside the chain).
        final var postOrders = chains.get(apis.get("POST /orders"));
        final var sources = g.getSources(postOrders);
        assertEquals(1, sources.size());
        assertEquals("front-end", sources.getFirst().getName());
    }

    /** Helper: SockShop service topology hand-translated from the JSON. */
    private static ServiceGraph sockShopGraph() {
        final var g = new ServiceGraph();
        final var frontEnd = withApis(new ServiceSimple("front-end"),
            "GET /catalogue", "GET /login", "POST /register", "GET /cart", "POST /cart",
            "POST /cards", "POST /addresses", "POST /orders", "GET /tags");
        final var orders = withApis(new ServiceSimple("orders"), "POST /orders");
        final var carts = withApis(new ServiceSimple("carts"),
            "GET /cart", "POST /cart", "POST /orders");
        final var cartsDb = withApis(new ServiceSimple("carts-db"),
            "GET /cart", "POST /cart", "POST /orders");
        final var catalogue = withApis(new ServiceSimple("catalogue"), "GET /catalogue");
        final var catalogueDb = withApis(new ServiceSimple("catalogue-db"), "GET /catalogue");
        final var payment = withApis(new ServiceSimple("payment"),
            "POST /cards", "POST /orders");
        final var user = withApis(new ServiceSimple("user"), "GET /login", "POST /register");
        final var userDb = withApis(new ServiceSimple("user-db"), "GET /login", "POST /register");
        final var ordersDb = withApis(new ServiceSimple("orders-db"), "POST /orders");
        final var shipping = withApis(new ServiceSimple("shipping"),
            "POST /addresses", "POST /orders");
        final var rabbitmq = withApis(new ServiceSimple("rabbitmq"), "POST /orders");
        final var queueMaster = withApis(new ServiceSimple("queue-master"), "POST /orders");
        final var sessionDb = withApis(new ServiceSimple("session-db"),
            "GET /login", "POST /register");

        g.addService(frontEnd, null);
        // front-end calls
        for (final var child : List.of(carts, orders, catalogue, user, payment, sessionDb)) {
            g.addService(child, frontEnd);
        }
        // carts -> carts-db -> shipping
        g.addService(cartsDb, carts);
        g.addService(shipping, cartsDb);
        // catalogue -> catalogue-db
        g.addService(catalogueDb, catalogue);
        // user -> user-db, session-db
        g.addService(userDb, user);
        g.addService(sessionDb, user);
        // orders -> orders-db, queue-master, carts, payment, shipping
        g.addService(ordersDb, orders);
        g.addService(queueMaster, orders);
        g.addService(carts, orders);
        g.addService(payment, orders);
        g.addService(shipping, orders);
        // shipping -> rabbitmq ; queue-master -> rabbitmq
        g.addService(rabbitmq, shipping);
        g.addService(rabbitmq, queueMaster);
        return g;
    }

    private static ServiceSimple withApis(final ServiceSimple s, final String... apis) {
        for (final String a : apis) {
            s.addApi(a);
        }
        return s;
    }
}
