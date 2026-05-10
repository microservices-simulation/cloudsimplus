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
import org.cloudsimplus.services.reporting.ResourceUsageRecorder;
import org.cloudsimplus.vms.Vm;
import org.cloudsimplus.vms.VmSimple;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VerticalServiceScalingPolicyTest {

    private static Vm mockVm(final double cpuPct, final long mips, final long ramCap) {
        final var vm = Mockito.mock(VmSimple.class);
        Mockito.when(vm.getCpuPercentUtilization()).thenReturn(cpuPct);
        Mockito.when(vm.getTotalMipsCapacity()).thenReturn((double) mips);
        final var ram = Mockito.mock(Ram.class);
        Mockito.when(ram.getCapacity()).thenReturn(ramCap);
        Mockito.when(ram.getAllocatedResource()).thenReturn(ramCap);
        Mockito.when(vm.getRam()).thenReturn(ram);
        Mockito.when(vm.setRam(Mockito.anyLong())).thenReturn(vm);
        return vm;
    }

    @Test
    void scaleUpResizesRamByFactor() {
        final var rec = new ResourceUsageRecorder(10.0);
        final var svc = new ServiceSimple("carts");
        final var hot = mockVm(0.95, 1000, 200);
        rec.register("carts-0", hot, svc);
        svc.addVm(hot);
        for (int t = 0; t <= 100; t += 10) {
            rec.recordSample(t);
        }
        final var policy = new VerticalServiceScalingPolicy(rec).scalingFactor(2.0);
        assertTrue(policy.needScaling(svc));
        policy.scale(svc);
        Mockito.verify(hot).setRam(400L); // 200 * 2.0
        assertEquals(1, policy.getScaleUpCount());
    }

    @Test
    void scaleDownDividesRamByFactor() {
        final var rec = new ResourceUsageRecorder(10.0);
        final var svc = new ServiceSimple("carts");
        final var cold = mockVm(0.05, 1000, 400);
        rec.register("carts-0", cold, svc);
        svc.addVm(cold);
        for (int t = 0; t <= 100; t += 10) {
            rec.recordSample(t);
        }
        final var policy = new VerticalServiceScalingPolicy(rec).scalingFactor(2.0);
        assertTrue(policy.needScaling(svc));
        policy.scale(svc);
        Mockito.verify(cold).setRam(200L); // 400 / 2.0
        assertEquals(1, policy.getScaleDownCount());
    }

    @Test
    void rejectsBadConfiguration() {
        final var policy = new VerticalServiceScalingPolicy(new ResourceUsageRecorder());
        assertThrows(IllegalArgumentException.class, () -> policy.scalingFactor(1.0));
        assertThrows(IllegalArgumentException.class, () -> policy.scalingFactor(0.5));
        assertThrows(IllegalArgumentException.class, () -> policy.recentRange(-1));
    }
}
