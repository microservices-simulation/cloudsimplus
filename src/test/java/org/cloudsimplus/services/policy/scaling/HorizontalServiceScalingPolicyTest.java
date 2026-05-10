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
package org.cloudsimplus.services.policy.scaling;

import org.cloudsimplus.resources.Ram;
import org.cloudsimplus.services.ServiceSimple;
import org.cloudsimplus.services.policy.scaling.ServiceScalingPolicy.Direction;
import org.cloudsimplus.services.reporting.ResourceUsageRecorder;
import org.cloudsimplus.vms.Vm;
import org.cloudsimplus.vms.VmSimple;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HorizontalServiceScalingPolicyTest {

    private static Vm mockVm(final double cpuPct, final long mips, final long ramMb) {
        final var vm = Mockito.mock(VmSimple.class);
        Mockito.when(vm.getCpuPercentUtilization()).thenReturn(cpuPct);
        Mockito.when(vm.getTotalMipsCapacity()).thenReturn((double) mips);
        final var ram = Mockito.mock(Ram.class);
        Mockito.when(ram.getAllocatedResource()).thenReturn(ramMb);
        Mockito.when(vm.getRam()).thenReturn(ram);
        return vm;
    }

    @Test
    void scaleUpFiresWhenAverageUtilizationCrossesUpperThreshold() {
        final var rec = new ResourceUsageRecorder(10.0);
        final var svc = new ServiceSimple("carts");
        final var hot = mockVm(0.90, 1000, 200);
        rec.register("carts-0", hot, svc);
        svc.addVm(hot);
        // Take 11 samples to fill the recent-range window.
        for (int t = 0; t <= 100; t += 10) {
            rec.recordSample(t);
        }

        final var hits = new AtomicInteger();
        final var policy = new HorizontalServiceScalingPolicy(rec)
            .setOnScale((s, d) -> hits.incrementAndGet());

        assertTrue(policy.needScaling(svc));
        policy.scale(svc);
        assertEquals(1, policy.getScaleUpCount());
        assertEquals(0, policy.getScaleDownCount());
        assertEquals(1, hits.get());
    }

    @Test
    void scaleDownFiresWhenAverageDropsBelowLowerThreshold() {
        final var rec = new ResourceUsageRecorder(10.0);
        final var svc = new ServiceSimple("carts");
        final var cold = mockVm(0.10, 1000, 200);
        rec.register("carts-0", cold, svc);
        svc.addVm(cold);
        for (int t = 0; t <= 100; t += 10) {
            rec.recordSample(t);
        }
        final var policy = new HorizontalServiceScalingPolicy(rec);
        assertTrue(policy.needScaling(svc));
        policy.scale(svc);
        assertEquals(1, policy.getScaleDownCount());
        assertEquals(0, policy.getScaleUpCount());
    }

    @Test
    void noScalingInsideTheStableBand() {
        final var rec = new ResourceUsageRecorder(10.0);
        final var svc = new ServiceSimple("carts");
        final var ok = mockVm(0.50, 1000, 200);
        rec.register("carts-0", ok, svc);
        svc.addVm(ok);
        for (int t = 0; t <= 100; t += 10) {
            rec.recordSample(t);
        }
        final var policy = new HorizontalServiceScalingPolicy(rec);
        assertFalse(policy.needScaling(svc));
        policy.scale(svc); // no-op
        assertEquals(0, policy.getScaleUpCount());
        assertEquals(0, policy.getScaleDownCount());
    }

    @Test
    void rejectsBadConfiguration() {
        final var policy = new HorizontalServiceScalingPolicy(new ResourceUsageRecorder());
        assertThrows(IllegalArgumentException.class, () -> policy.cpuThreshold(-0.1, 0.5));
        assertThrows(IllegalArgumentException.class, () -> policy.cpuThreshold(0.5, 0.5));
        assertThrows(IllegalArgumentException.class, () -> policy.cpuThreshold(0.5, 1.5));
        assertThrows(IllegalArgumentException.class, () -> policy.recentRange(0));
    }

    @Test
    void emptySamplesYieldNoScaling() {
        final var rec = new ResourceUsageRecorder(10.0);
        final var svc = new ServiceSimple("ghost");
        final var policy = new HorizontalServiceScalingPolicy(rec);
        // Service has no VMs registered with the recorder => no samples
        assertFalse(policy.needScaling(svc));
    }

    @Test
    void onScaleCallbackReceivesCorrectDirection() {
        final var rec = new ResourceUsageRecorder(10.0);
        final var svc = new ServiceSimple("carts");
        final var hot = mockVm(0.95, 1000, 200);
        rec.register("carts-0", hot, svc);
        svc.addVm(hot);
        for (int t = 0; t <= 100; t += 10) {
            rec.recordSample(t);
        }
        final var observed = new java.util.ArrayList<Direction>();
        final var policy = new HorizontalServiceScalingPolicy(rec)
            .setOnScale((s, d) -> observed.add(d));
        assertTrue(policy.needScaling(svc));
        policy.scale(svc);
        assertEquals(java.util.List.of(Direction.UP), observed);
    }
}
