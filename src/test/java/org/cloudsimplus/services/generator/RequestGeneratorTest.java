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
package org.cloudsimplus.services.generator;

import org.cloudsimplus.services.Api;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RequestGeneratorTest {

    @Test
    void rpsModeProducesExpectedAverageArrivalsUnderDeterministicSeed() {
        final var gen = new RequestGenerator()
            .apis(List.of(new Api("op", 1.0)))
            .rpsMode(10)
            .length(5, 0)
            .random(new Random(42));

        int total = 0;
        for (int t = 1; t <= 10; t++) {
            total += gen.generate(t).size();
        }
        // 10 RPS over 10 ticks of 1s each => 100 requests.
        assertEquals(100, total);
        assertEquals(100, gen.getTotalGenerated());
    }

    @Test
    void numLimitCapsTotalRequests() {
        final var gen = new RequestGenerator()
            .apis(List.of(new Api("op", 1.0)))
            .rpsMode(10)
            .numLimit(7)
            .length(1, 0)
            .random(new Random(0));

        int total = 0;
        for (int t = 1; t <= 5; t++) {
            total += gen.generate(t).size();
        }
        assertEquals(7, total);
    }

    @Test
    void timeLimitStopsAfterHorizon() {
        final var gen = new RequestGenerator()
            .apis(List.of(new Api("op", 1.0)))
            .rpsMode(5)
            .timeLimit(3)
            .length(1, 0)
            .random(new Random(0));

        int total = 0;
        for (int t = 1; t <= 6; t++) {
            total += gen.generate(t).size();
        }
        // 5 rps * 3s = 15
        assertEquals(15, total);
    }

    @Test
    void weightedSelectionRoughlyMatchesWeights() {
        final var a = new Api("a", 1.0);
        final var b = new Api("b", 3.0);
        final var gen = new RequestGenerator()
            .apis(List.of(a, b))
            .rpsMode(1000)
            .length(1, 0)
            .random(new Random(123));

        final var samples = gen.generate(1);
        final Map<String, Integer> counts = new HashMap<>();
        for (final var r : samples) {
            counts.merge(r.getApi().getName(), 1, Integer::sum);
        }
        // 1 : 3 weights => b should be roughly 3x a; check loose bounds.
        final int countA = counts.getOrDefault("a", 0);
        final int countB = counts.getOrDefault("b", 0);
        assertTrue(countB > countA, "b (weight 3) should outnumber a (weight 1)");
        // tolerate large noise but require b to be at least 2x a
        assertTrue(countB > 2 * countA, "b expected ~3x a, got %d/%d".formatted(countB, countA));
    }

    @Test
    void clientsModeRespectsSpawnRateAndWaitTime() {
        final var gen = new RequestGenerator()
            .apis(List.of(new Api("op", 1.0)))
            .clientsMode(5, 5)             // up to 5 clients, all spawn in tick 1
            .waitTimeSpan(2, 3)            // each client waits exactly 2s
            .length(1, 0)
            .random(new Random(7));

        // tick 1: spawn 5 clients (all wait==0), each fires one request immediately
        final int t1 = gen.generate(1).size();
        assertEquals(5, t1);
        assertEquals(5, gen.getCurrentClients());

        // tick 2: only 1s elapsed; each client still waiting (waitTime=2)
        final int t2 = gen.generate(2).size();
        assertEquals(0, t2);

        // tick 3: 1s more elapsed; each client's countdown reaches 0, fires again
        final int t3 = gen.generate(3).size();
        assertEquals(5, t3);
    }

    @Test
    void rejectsBadConfiguration() {
        final var gen = new RequestGenerator();
        assertThrows(IllegalArgumentException.class, () -> gen.clientsMode(0, 1));
        assertThrows(IllegalArgumentException.class, () -> gen.clientsMode(1, 0));
        assertThrows(IllegalArgumentException.class, () -> gen.rpsMode(0));
        assertThrows(IllegalArgumentException.class, () -> gen.waitTimeSpan(5, 5));
        assertThrows(IllegalArgumentException.class, () -> gen.timeLimit(0));
        assertThrows(IllegalArgumentException.class, () -> gen.numLimit(0));
        assertThrows(IllegalArgumentException.class, () -> gen.length(0, 1));
        assertThrows(IllegalArgumentException.class, () -> gen.length(1, -1));
    }

    @Test
    void pickRandomApiThrowsWithoutApis() {
        final var gen = new RequestGenerator();
        assertThrows(IllegalStateException.class, gen::pickRandomApi);
    }

    @Test
    void requestsAreBoundToTheSelectedApi() {
        final var api = new Api("op", 1.0);
        final var gen = new RequestGenerator()
            .apis(List.of(api))
            .rpsMode(3)
            .length(5, 0)
            .random(new Random(1));

        final var reqs = gen.generate(1);
        assertEquals(3, reqs.size());
        for (final var r : reqs) {
            assertSame(api, r.getApi());
            assertFalse(r.isFinished());
            // Submission time matches generator clock
            assertEquals(1.0, r.getSubmissionTime());
        }
        // Each request is also recorded on the API
        assertEquals(3, api.getRequests().size());
    }
}
