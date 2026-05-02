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
package org.cloudsimplus.kubernetes.autoscaling;

import org.cloudsimplus.kubernetes.KubernetesPod;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Unit tests for the HPA scaling math, cooldown, and clamping logic. Uses
 * Mockito to stub {@link KubernetesPod#isCreated()} / {@link KubernetesPod#getCpuPercentUtilization()}
 * so the test runs without a simulation.
 */
class HorizontalPodAutoscalerTest {

    private static KubernetesPod podAt(final double utilization) {
        final var p = Mockito.mock(KubernetesPod.class);
        Mockito.when(p.isCreated()).thenReturn(true);
        Mockito.when(p.getCpuPercentUtilization()).thenReturn(utilization);
        return p;
    }

    /**
     * Build an HPA whose "current replicas" are read from / written to
     * {@code current}, and whose pod source returns the supplied pods.
     */
    private static HorizontalPodAutoscaler newHpa(
        final List<KubernetesPod> pods, final AtomicInteger current, final double target)
    {
        return new HorizontalPodAutoscaler(
            "test-hpa", target, () -> pods, current::get, current::set);
    }

    @Test
    void targetOutOfRangeRejected() {
        assertThrows(IllegalArgumentException.class,
            () -> new HorizontalPodAutoscaler("h", 0.0, List::of, () -> 1, n -> { }));
        assertThrows(IllegalArgumentException.class,
            () -> new HorizontalPodAutoscaler("h", 1.5, List::of, () -> 1, n -> { }));
    }

    @Test
    void utilizationAboveTargetTriggersScaleUp() {
        final var current = new AtomicInteger(2);
        final var hpa = newHpa(List.of(podAt(0.9), podAt(0.9)), current, 0.5)
            .setMinReplicas(1).setMaxReplicas(10).setCooldownSeconds(0);
        // ceil(2 * 0.9 / 0.5) = ceil(3.6) = 4
        hpa.tick(1.0);
        assertEquals(4, current.get());
    }

    @Test
    void utilizationBelowTargetTriggersScaleDown() {
        final var current = new AtomicInteger(4);
        final var hpa = newHpa(List.of(podAt(0.2), podAt(0.2), podAt(0.2), podAt(0.2)), current, 0.8)
            .setMinReplicas(1).setMaxReplicas(10).setCooldownSeconds(0);
        // ceil(4 * 0.2 / 0.8) = 1
        hpa.tick(1.0);
        assertEquals(1, current.get());
    }

    @Test
    void resultIsClampedToMinAndMax() {
        final var current = new AtomicInteger(5);
        final var hpaUp = newHpa(List.of(podAt(0.99)), current, 0.1)
            .setMinReplicas(1).setMaxReplicas(7).setCooldownSeconds(0);
        hpaUp.tick(1.0);
        assertEquals(7, current.get(), "Must clamp to maxReplicas");

        current.set(5);
        final var hpaDown = newHpa(List.of(podAt(0.01)), current, 0.99)
            .setMinReplicas(3).setMaxReplicas(10).setCooldownSeconds(0);
        hpaDown.tick(1.0);
        assertEquals(3, current.get(), "Must clamp to minReplicas");
    }

    @Test
    void cooldownPreventsBackToBackScaling() {
        final var current = new AtomicInteger(2);
        final var hpa = newHpa(List.of(podAt(0.9), podAt(0.9)), current, 0.5)
            .setMinReplicas(1).setMaxReplicas(10).setCooldownSeconds(60.0);

        hpa.tick(0.0);
        final int afterFirst = current.get();
        // A second tick well within the cooldown window must NOT scale.
        hpa.tick(10.0);
        assertEquals(afterFirst, current.get(), "Cooldown must suppress consecutive scaling actions");
    }

    @Test
    void emptyPodSetIsNoOp() {
        final var current = new AtomicInteger(2);
        final var hpa = newHpa(List.of(), current, 0.5).setCooldownSeconds(0);
        hpa.tick(1.0);
        assertEquals(2, current.get(), "No pods → no scaling decision");
    }

    @Test
    void zeroUtilizationIsNoOp() {
        // avg 0 → no signal for scaling (would otherwise scale to 0 / target → clamps).
        final var current = new AtomicInteger(3);
        final var hpa = newHpa(List.of(podAt(0.0), podAt(0.0)), current, 0.5)
            .setMinReplicas(1).setMaxReplicas(10).setCooldownSeconds(0);
        hpa.tick(1.0);
        assertEquals(3, current.get(), "Zero utilization → no scale event");
    }

    @Test
    void onlyReadyPodsAreSampled() {
        final var ready = podAt(0.9);
        final var pending = Mockito.mock(KubernetesPod.class);
        Mockito.when(pending.isCreated()).thenReturn(false);

        final var current = new AtomicInteger(1);
        final var hpa = newHpa(List.of(ready, pending), current, 0.5)
            .setMinReplicas(1).setMaxReplicas(10).setCooldownSeconds(0);
        hpa.tick(1.0);
        // Only the ready pod's util counts: ceil(1 * 0.9 / 0.5) = 2
        assertEquals(2, current.get());
    }
}
