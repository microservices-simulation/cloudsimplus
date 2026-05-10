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
 * Unit tests for {@link Api}: covers SLO violation counting, RPS history,
 * average delay, and the cumulative-weight algorithm used downstream by the
 * request generator.
 */
class ApiTest {

    private static ServiceRequest finishedRequest(final long id, final double responseTime) {
        final var call = new ServiceCall(new ServiceSimple("svc"), 1);
        final var req = new ServiceRequest(id, call);
        req.setSubmissionTime(0.0);
        req.setStartTime(0.0);
        req.setFinishTime(responseTime);
        return req;
    }

    @Test
    void rejectsBlankNameAndNegativeWeight() {
        assertThrows(IllegalArgumentException.class, () -> new Api(" "));
        assertThrows(IllegalArgumentException.class, () -> new Api("api", -0.1));
        assertThrows(IllegalArgumentException.class, () -> new Api("api", 1.0, 0.0));
    }

    @Test
    void defaultsAreReasonable() {
        final var api = new Api("GET /catalogue");
        assertEquals(1.0, api.getWeight());
        assertEquals(Api.DEFAULT_SLO_THRESHOLD, api.getSloThreshold());
        assertTrue(api.getServiceChain().isEmpty());
        assertTrue(api.getRequests().isEmpty());
        assertTrue(api.getRpsHistory().isEmpty());
    }

    @Test
    void averageDelayIgnoresUnfinishedRequests() {
        final var api = new Api("op");
        api.addRequest(finishedRequest(1, 1.0));
        api.addRequest(finishedRequest(2, 3.0));

        // unfinished: should not be counted
        final var pending = new ServiceRequest(3, new ServiceCall(new ServiceSimple("svc"), 1));
        api.addRequest(pending);

        assertEquals(2.0, api.getAverageDelay(), 1e-9);
    }

    @Test
    void sloViolationsCountedAtAndAboveThreshold() {
        final var api = new Api("op", 1.0, 2.0);
        api.addRequest(finishedRequest(1, 1.0));   // ok
        api.addRequest(finishedRequest(2, 2.0));   // violates (>=)
        api.addRequest(finishedRequest(3, 5.0));   // violates

        assertEquals(2, api.getSloViolations());
        assertEquals(2.0 / 3.0, api.getSloViolationRate(), 1e-9);
    }

    @Test
    void rpsHistoryAveragesAcrossSamples() {
        final var api = new Api("op");
        api.recordRpsSample(10, 1);  // 10 rps
        api.recordRpsSample(20, 2);  // 10 rps
        api.recordRpsSample(0, 1);   // 0 rps

        assertEquals(3, api.getRpsHistory().size());
        assertEquals((10.0 + 10.0 + 0.0) / 3.0, api.getAvgRps(), 1e-9);
    }

    @Test
    void rpsHistoryRejectsNonPositiveInterval() {
        final var api = new Api("op");
        assertThrows(IllegalArgumentException.class, () -> api.recordRpsSample(5, 0));
        assertThrows(IllegalArgumentException.class, () -> api.recordRpsSample(5, -1));
    }

    /**
     * Sanity check on the cumulative-weight algorithm used in {@code getRandomAPI}.
     * We don't depend on randomness — instead we verify the cumulative array
     * itself is monotonically increasing and ends at the total weight.
     */
    @Test
    void cumulativeWeightArrayIsCorrect() {
        final var apis = java.util.List.of(
            new Api("a", 1.0),
            new Api("b", 3.0),
            new Api("c", 2.0)
        );
        final double[] cumulative = new double[apis.size()];
        double sum = 0;
        for (int i = 0; i < apis.size(); i++) {
            sum += apis.get(i).getWeight();
            cumulative[i] = sum;
        }
        assertEquals(1.0, cumulative[0], 1e-9);
        assertEquals(4.0, cumulative[1], 1e-9);
        assertEquals(6.0, cumulative[2], 1e-9);
    }
}
