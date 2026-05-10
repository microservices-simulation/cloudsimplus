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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for the {@link ServiceRequest#recordNodeDelay(Service, double) nodeDelay}
 * extension and the request/{@link Api} association added in step 1.
 */
class ServiceRequestTest {

    @Test
    void nodeDelayKeepsLongestPath() {
        final var s = new ServiceSimple("svc");
        final var req = new ServiceRequest(1, new ServiceCall(s, 1));

        req.recordNodeDelay(s, 0.5);
        req.recordNodeDelay(s, 1.2);
        req.recordNodeDelay(s, 0.8);

        assertEquals(1.2, req.getNodeDelay().get(s), 1e-9);
    }

    @Test
    void nodeDelayMapIsUnmodifiable() {
        final var req = new ServiceRequest(1, new ServiceCall(new ServiceSimple("a"), 1));
        req.recordNodeDelay(new ServiceSimple("a"), 0.1);
        assertThrows(UnsupportedOperationException.class,
            () -> req.getNodeDelay().clear());
    }

    @Test
    void recordNodeDelayRejectsNegative() {
        final var req = new ServiceRequest(1, new ServiceCall(new ServiceSimple("a"), 1));
        assertThrows(IllegalArgumentException.class,
            () -> req.recordNodeDelay(new ServiceSimple("a"), -0.1));
    }

    @Test
    void apiIsOptional() {
        final var req = new ServiceRequest(7, new ServiceCall(new ServiceSimple("svc"), 1));
        assertTrue(req.getApi() == null);

        final var api = new Api("GET /x");
        req.setApi(api);
        assertEquals(api, req.getApi());
    }
}
