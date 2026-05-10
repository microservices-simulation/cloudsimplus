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
package org.cloudsimplus.services.reporting;

import org.cloudsimplus.resources.Ram;
import org.cloudsimplus.services.ServiceSimple;
import org.cloudsimplus.vms.Vm;
import org.cloudsimplus.vms.VmSimple;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ResourceUsageRecorderTest {

    private static Vm mockVm(final double cpuPct, final long mips, final long ramMb) {
        // Vm is a sealed interface — mock the (non-final) VmSimple instead.
        final var vm = Mockito.mock(VmSimple.class);
        Mockito.when(vm.getCpuPercentUtilization()).thenReturn(cpuPct);
        Mockito.when(vm.getTotalMipsCapacity()).thenReturn((double) mips);
        final var ram = Mockito.mock(Ram.class);
        Mockito.when(ram.getAllocatedResource()).thenReturn(ramMb);
        Mockito.when(vm.getRam()).thenReturn(ram);
        return vm;
    }

    @Test
    void rejectsNonPositiveSamplingInterval() {
        assertThrows(IllegalArgumentException.class, () -> new ResourceUsageRecorder(0));
        assertThrows(IllegalArgumentException.class, () -> new ResourceUsageRecorder(-1));
    }

    @Test
    void recordsCpuAndRamSamplesAtIntervalBoundaries() {
        final var vm = mockVm(0.5, 1000, 200);
        final var rec = new ResourceUsageRecorder(10.0);
        rec.register("svc-0", vm);

        // First sample is always taken, even though no time has elapsed yet.
        assertTrue(rec.recordSample(0.0));
        // Second sample within the same interval is dropped.
        assertFalse(rec.recordSample(5.0));
        // Sample at exactly samplingInterval is taken.
        assertTrue(rec.recordSample(10.0));

        assertEquals(2, rec.getCpuHistory().get("svc-0").size());
        assertEquals(2, rec.getRamHistory().get("svc-0").size());

        final var cpu = rec.getCpuHistory().get("svc-0").getFirst();
        assertEquals(0.0, cpu.timestamp());
        assertEquals(10.0, cpu.session()); // first sample uses samplingInterval
        assertEquals(500.0, cpu.usage()); // 0.5 * 1000 MIPS

        final var cpu2 = rec.getCpuHistory().get("svc-0").get(1);
        assertEquals(10.0, cpu2.session()); // 10 - 0
        assertEquals(500.0, cpu2.usage());

        final var ram = rec.getRamHistory().get("svc-0").getFirst();
        assertEquals(200.0, ram.usage());
    }

    @Test
    void averageHelpersAggregateAcrossReplicasOfAService() {
        final var svc = new ServiceSimple("carts");
        final var vm0 = mockVm(0.4, 1000, 200);
        final var vm1 = mockVm(0.8, 1000, 400);

        final var rec = new ResourceUsageRecorder(10.0);
        rec.register("carts-0", vm0, svc);
        rec.register("carts-1", vm1, svc);

        rec.recordSample(0.0);
        rec.recordSample(10.0);

        // Per-instance averages: 400 MIPS / 800 MIPS, 200 MB / 400 MB
        assertEquals(400.0, rec.getAverageCpu("carts-0"), 1e-9);
        assertEquals(800.0, rec.getAverageCpu("carts-1"), 1e-9);
        assertEquals(200.0, rec.getAverageRam("carts-0"), 1e-9);
        assertEquals(400.0, rec.getAverageRam("carts-1"), 1e-9);

        // Service-level: average across all 4 samples (2 replicas × 2 samples)
        assertEquals((400 + 400 + 800 + 800) / 4.0, rec.getAverageCpuOf(svc), 1e-9);
        assertEquals((200 + 200 + 400 + 400) / 4.0, rec.getAverageRamOf(svc), 1e-9);
    }

    @Test
    void instanceUidsArePreservedInRegistrationOrder() {
        final var rec = new ResourceUsageRecorder(10.0);
        rec.register("b", mockVm(0.0, 100, 100));
        rec.register("a", mockVm(0.0, 100, 100));
        rec.register("c", mockVm(0.0, 100, 100));
        assertEquals(java.util.List.of("b", "a", "c"), rec.getInstanceUids());
    }

    @Test
    void blankInstanceUidIsRejected() {
        final var rec = new ResourceUsageRecorder(10.0);
        assertThrows(IllegalArgumentException.class,
            () -> rec.register(" ", mockVm(0.0, 100, 100)));
    }
}
