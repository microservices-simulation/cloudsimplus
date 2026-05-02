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
import com.google.gson.Gson;
import org.cloudsimplus.core.CloudSimPlus;
import org.cloudsimplus.datacenters.DatacenterSimple;
import org.cloudsimplus.hosts.Host;
import org.cloudsimplus.hosts.HostSimple;
import org.cloudsimplus.resources.Pe;
import org.cloudsimplus.resources.PeSimple;
import org.cloudsimplus.services.ServiceBrokerSimple.CallDetail;
import org.cloudsimplus.util.Log;
import org.cloudsimplus.vms.Vm;
import org.cloudsimplus.vms.VmSimple;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Smoke test running a full simulation through the
 * {@link ServiceBrokerSimple} to verify the chain
 * {@code Request → A → B → A → D → E} executes end-to-end.
 *
 * <p>Lives in the unit-test source set (not under {@code integrationtests/})
 * so it runs as part of the default {@code mvn test} suite.</p>
 */
class ServiceBrokerSimpleTest {

    private static final Gson gson = new Gson();

    @BeforeAll
    static void quietLogs() {
        Log.setLevel(Level.OFF);
    }

    @Test
    void chainA_B_A_D_E_finishesWithCorrectOrderingAndTimings() {
        final var simulation = new CloudSimPlus();

        // One large host that can fit four 1-PE VMs.
        final List<Pe> peList = List.of(
            new PeSimple(1000), new PeSimple(1000),
            new PeSimple(1000), new PeSimple(1000),
            new PeSimple(1000), new PeSimple(1000));
        final Host host = new HostSimple(8_000, 100_000, 1_000_000, peList);
        new DatacenterSimple(simulation, List.of(host));

        final var broker = new ServiceBrokerSimple(simulation);

        // 4 VMs, one per service.
        final Vm vmA = new VmSimple(1000, 1).setRam(512).setBw(1000).setSize(10_000);
        final Vm vmB = new VmSimple(1000, 1).setRam(512).setBw(1000).setSize(10_000);
        final Vm vmD = new VmSimple(1000, 1).setRam(512).setBw(1000).setSize(10_000);
        final Vm vmE = new VmSimple(1000, 1).setRam(512).setBw(1000).setSize(10_000);
        broker.submitVmList(List.of(vmA, vmB, vmD, vmE));

        final var svcA = new ServiceSimple("A").addVm(vmA);
        final var svcB = new ServiceSimple("B").addVm(vmB);
        final var svcD = new ServiceSimple("D").addVm(vmD);
        final var svcE = new ServiceSimple("E").addVm(vmE);
        broker.addService(svcA).addService(svcB).addService(svcD).addService(svcE);

        // A: 1000 MI before children, 500 MI after, calls B then D.
        // B: 1500 MI (leaf).
        // D: 800 MI before children, 200 MI after, calls E.
        // E: 600 MI (leaf).
        final var aCall = new ServiceCall(svcA, 1_000, 500);
        final var bCall = new ServiceCall(svcB, 1_500);
        final var dCall = new ServiceCall(svcD, 800, 200);
        final var eCall = new ServiceCall(svcE, 600);
        dCall.addChild(eCall);
        aCall.addChild(bCall).addChild(dCall);

        final var req = new ServiceRequest(0, aCall);
        broker.submitRequest(req);

        simulation.start();

        assertTrue(req.isFinished(), "Request must finish");
        assertEquals(ServiceCall.State.COMPLETED, aCall.getState());
        assertEquals(ServiceCall.State.COMPLETED, bCall.getState());
        assertEquals(ServiceCall.State.COMPLETED, dCall.getState());
        assertEquals(ServiceCall.State.COMPLETED, eCall.getState());

        // Strict ordering: a child finishes before its parent does;
        // a sibling fires after the previous sibling completes.
        assertTrue(eCall.getFinishTime() <= dCall.getFinishTime() + 1e-9);
        assertTrue(dCall.getFinishTime() <= aCall.getFinishTime() + 1e-9);
        assertTrue(bCall.getFinishTime() <= dCall.getStartTime() + 0.5,
            "B must finish before D starts (sequential children)");

        // Sum of MI/MIPS along the call tree = 4.6s (allow event-scheduling slack).
        assertEquals(4.6, req.getResponseTime(), 1.0);
    }

    @Test
    void getStatistics_returnsCorrectMetrics() {
        final var simulation = new CloudSimPlus();

        // One large host that can fit four 1-PE VMs.
        final List<Pe> peList = List.of(
            new PeSimple(1000), new PeSimple(1000),
            new PeSimple(1000), new PeSimple(1000),
            new PeSimple(1000), new PeSimple(1000));
        final Host host = new HostSimple(8_000, 100_000, 1_000_000, peList);
        new DatacenterSimple(simulation, List.of(host));

        final var broker = new ServiceBrokerSimple(simulation);

        // 4 VMs, one per service.
        final Vm vmA = new VmSimple(1000, 1).setRam(512).setBw(1000).setSize(10_000);
        final Vm vmB = new VmSimple(1000, 1).setRam(512).setBw(1000).setSize(10_000);
        final Vm vmD = new VmSimple(1000, 1).setRam(512).setBw(1000).setSize(10_000);
        final Vm vmE = new VmSimple(1000, 1).setRam(512).setBw(1000).setSize(10_000);
        broker.submitVmList(List.of(vmA, vmB, vmD, vmE));

        final var svcA = new ServiceSimple("A").addVm(vmA);
        final var svcB = new ServiceSimple("B").addVm(vmB);
        final var svcD = new ServiceSimple("D").addVm(vmD);
        final var svcE = new ServiceSimple("E").addVm(vmE);
        broker.addService(svcA).addService(svcB).addService(svcD).addService(svcE);

        // Create two requests with the same call tree
        final var aCall1 = new ServiceCall(svcA, 1_000, 500);
        final var bCall1 = new ServiceCall(svcB, 1_500);
        final var dCall1 = new ServiceCall(svcD, 800, 200);
        final var eCall1 = new ServiceCall(svcE, 600);
        dCall1.addChild(eCall1);
        aCall1.addChild(bCall1).addChild(dCall1);
        final var req1 = new ServiceRequest(0, aCall1);

        final var aCall2 = new ServiceCall(svcA, 1_000, 500);
        final var bCall2 = new ServiceCall(svcB, 1_500);
        final var dCall2 = new ServiceCall(svcD, 800, 200);
        final var eCall2 = new ServiceCall(svcE, 600);
        dCall2.addChild(eCall2);
        aCall2.addChild(bCall2).addChild(dCall2);
        final var req2 = new ServiceRequest(1, aCall2);

        broker.submitRequests(List.of(req1, req2));

        simulation.start();

        final var stats = broker.getStatistics();
        assertEquals(2, stats.getCount());
        assertTrue(stats.getMeanResponseTime() > 4.6, "Mean should be greater than single request time");
        assertTrue(stats.getMinResponseTime() >= 4.6, "Min should be at least single request time");
        assertTrue(stats.getMaxResponseTime() > 4.6, "Max should be greater than single request time");
        assertTrue(stats.getStdDevResponseTime() >= 0, "Std dev should be non-negative");
        assertEquals(stats.getP50ResponseTime(), stats.getMedianResponseTime(), 1e-9);
        assertTrue(stats.getP95ResponseTime() >= stats.getP50ResponseTime(), "P95 should be >= median");
        assertEquals(4, stats.getNumServices()); // 4 services in the test
        assertEquals(2, stats.getNumRequests()); // 2 requests submitted
        assertEquals(2, stats.getNumFinishedRequests()); // both finished
        assertEquals(4, stats.getNumVMs()); // 4 VMs
    }

    @Test
    void getCallsDetails_returnsJsonWithCallInformation() {
        final var simulation = new CloudSimPlus();

        // One large host that can fit four 1-PE VMs.
        final List<Pe> peList = List.of(
            new PeSimple(1000), new PeSimple(1000),
            new PeSimple(1000), new PeSimple(1000),
            new PeSimple(1000), new PeSimple(1000));
        final Host host = new HostSimple(8_000, 100_000, 1_000_000, peList);
        new DatacenterSimple(simulation, List.of(host));

        final var broker = new ServiceBrokerSimple(simulation);

        // 4 VMs, one per service.
        final Vm vmA = new VmSimple(1000, 1).setRam(512).setBw(1000).setSize(10_000);
        final Vm vmB = new VmSimple(1000, 1).setRam(512).setBw(1000).setSize(10_000);
        final Vm vmD = new VmSimple(1000, 1).setRam(512).setBw(1000).setSize(10_000);
        final Vm vmE = new VmSimple(1000, 1).setRam(512).setBw(1000).setSize(10_000);
        broker.submitVmList(List.of(vmA, vmB, vmD, vmE));

        final var svcA = new ServiceSimple("A").addVm(vmA);
        final var svcB = new ServiceSimple("B").addVm(vmB);
        final var svcD = new ServiceSimple("D").addVm(vmD);
        final var svcE = new ServiceSimple("E").addVm(vmE);
        broker.addService(svcA).addService(svcB).addService(svcD).addService(svcE);

        // A: 1000 MI before children, 500 MI after, calls B then D.
        // B: 1500 MI (leaf).
        // D: 800 MI before children, 200 MI after, calls E.
        // E: 600 MI (leaf).
        final var aCall = new ServiceCall(svcA, 1_000, 500);
        final var bCall = new ServiceCall(svcB, 1_500);
        final var dCall = new ServiceCall(svcD, 800, 200);
        final var eCall = new ServiceCall(svcE, 600);
        dCall.addChild(eCall);
        aCall.addChild(bCall).addChild(dCall);

        final var req = new ServiceRequest(0, aCall);
        broker.submitRequest(req);

        simulation.start();

        final String callsDetails = broker.getCallsDetails();
        System.out.println("CallsDetails JSON: " + callsDetails);
        assertNotNull(callsDetails);
        assertTrue(callsDetails.contains("callerService"));
        assertTrue(callsDetails.contains("calleeService"));
        assertTrue(callsDetails.contains("handshakeDuration"));
        assertTrue(callsDetails.contains("simulationTime"));
        // Should have 4 calls: root A, B, D, E
        // Parse the JSON to count
        com.google.gson.reflect.TypeToken<List<CallDetail>> typeToken = new com.google.gson.reflect.TypeToken<List<CallDetail>>() {};
        List<CallDetail> details = gson.fromJson(callsDetails, typeToken.getType());
        assertTrue(details.size() >= 4, "Should have at least 4 call details, but found " + details.size());
    }

    @Test
    void getDAG_returnsJsonWithServiceGraph() {
        final var simulation = new CloudSimPlus();

        // One large host that can fit four 1-PE VMs.
        final List<Pe> peList = List.of(
            new PeSimple(1000), new PeSimple(1000),
            new PeSimple(1000), new PeSimple(1000),
            new PeSimple(1000), new PeSimple(1000));
        final Host host = new HostSimple(8_000, 100_000, 1_000_000, peList);
        new DatacenterSimple(simulation, List.of(host));

        final var broker = new ServiceBrokerSimple(simulation);

        // 4 VMs, one per service.
        final Vm vmA = new VmSimple(1000, 1).setRam(512).setBw(1000).setSize(10_000);
        final Vm vmB = new VmSimple(1000, 1).setRam(512).setBw(1000).setSize(10_000);
        final Vm vmD = new VmSimple(1000, 1).setRam(512).setBw(1000).setSize(10_000);
        final Vm vmE = new VmSimple(1000, 1).setRam(512).setBw(1000).setSize(10_000);
        broker.submitVmList(List.of(vmA, vmB, vmD, vmE));

        final var svcA = new ServiceSimple("A").addVm(vmA);
        final var svcB = new ServiceSimple("B").addVm(vmB);
        final var svcD = new ServiceSimple("D").addVm(vmD);
        final var svcE = new ServiceSimple("E").addVm(vmE);
        broker.addService(svcA).addService(svcB).addService(svcD).addService(svcE);

        // A: 1000 MI before children, 500 MI after, calls B then D.
        // B: 1500 MI (leaf).
        // D: 800 MI before children, 200 MI after, calls E.
        // E: 600 MI (leaf).
        final var aCall = new ServiceCall(svcA, 1_000, 500);
        final var bCall = new ServiceCall(svcB, 1_500);
        final var dCall = new ServiceCall(svcD, 800, 200);
        final var eCall = new ServiceCall(svcE, 600);
        dCall.addChild(eCall);
        aCall.addChild(bCall).addChild(dCall);

        final var req = new ServiceRequest(0, aCall);
        broker.submitRequest(req);

        simulation.start();

        final String dagJson = broker.getDAG();
        assertNotNull(dagJson);
        assertTrue(dagJson.contains("A"));
        assertTrue(dagJson.contains("B"));
        assertTrue(dagJson.contains("D"));
        assertTrue(dagJson.contains("E"));
        assertTrue(dagJson.contains("callCount"));
        assertTrue(dagJson.contains("averageLatency"));
    }
}
