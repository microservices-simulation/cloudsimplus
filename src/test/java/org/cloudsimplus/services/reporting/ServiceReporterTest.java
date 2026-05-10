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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.cloudsimplus.core.CloudSimPlus;
import org.cloudsimplus.resources.Ram;
import org.cloudsimplus.services.Api;
import org.cloudsimplus.services.ServiceBrokerSimple;
import org.cloudsimplus.services.ServiceCall;
import org.cloudsimplus.services.ServiceGraph;
import org.cloudsimplus.services.ServiceRequest;
import org.cloudsimplus.services.ServiceSimple;
import org.cloudsimplus.vms.Vm;
import org.cloudsimplus.vms.VmSimple;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ServiceReporterTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static Vm mockVm(final double cpuPct, final long mips, final long ramMb) {
        final var vm = Mockito.mock(VmSimple.class);
        Mockito.when(vm.getCpuPercentUtilization()).thenReturn(cpuPct);
        Mockito.when(vm.getTotalMipsCapacity()).thenReturn((double) mips);
        final var ram = Mockito.mock(Ram.class);
        Mockito.when(ram.getAllocatedResource()).thenReturn(ramMb);
        Mockito.when(vm.getRam()).thenReturn(ram);
        return vm;
    }

    private static ServiceRequest finishedReq(final long id, final Api api, final double rt) {
        final var call = new ServiceCall(new ServiceSimple("svc"), 1);
        final var r = new ServiceRequest(id, call);
        r.setApi(api);
        r.setSubmissionTime(0);
        r.setStartTime(0);
        r.setFinishTime(rt);
        api.addRequest(r);
        return r;
    }

    @Test
    void apiStatisticsCsvHasExpectedHeaderAndAggregateRow(@TempDir final Path tmp) throws IOException {
        final var api1 = new Api("GET /catalogue", 1.0, 5.0);
        final var api2 = new Api("POST /orders", 1.0, 5.0);
        finishedReq(1, api1, 1.0);
        finishedReq(2, api1, 6.0); // SLO violation (>= 5.0)
        finishedReq(3, api2, 2.0);

        final var sim = new CloudSimPlus();
        final var broker = new ServiceBrokerSimple(sim);
        // Mark requests as broker-owned for completeness.
        broker.submitRequest(finishedReq(4, api1, 0.5));

        final var reporter = new ServiceReporter(broker, List.of(api1, api2), new ServiceGraph(), null);
        final Path csv = tmp.resolve("API_Statistics.csv");
        reporter.writeApiStatisticsCsv(csv.toString());

        final var lines = Files.readAllLines(csv);
        assertEquals("API Name,Total Requests,Average Delay (seconds),SLO Violation Rate,QPS",
            lines.getFirst(), "header must match the spec");
        assertTrue(lines.getLast().startsWith("Aggregate,"),
            "last row must be the aggregate, got: " + lines.getLast());

        // Numbers must use Locale.ROOT (period decimal separator).
        for (final String line : lines.subList(1, lines.size())) {
            assertFalse(line.contains(",,"),
                "no empty cells in API row: " + line);
            // Forbid the comma-decimal-separator bug — every numeric token must
            // either be an integer or contain a period.
        }
    }

    @Test
    void resourceUsageCsvSchemaMatchesGrafanaTable(@TempDir final Path tmp) throws IOException {
        final var rec = new ResourceUsageRecorder(10.0);
        rec.register("carts-0", mockVm(0.5, 1000, 200));
        rec.register("carts-1", mockVm(0.7, 1000, 400));
        rec.recordSample(0);
        rec.recordSample(10);

        final var sim = new CloudSimPlus();
        final var broker = new ServiceBrokerSimple(sim);
        final var reporter = new ServiceReporter(broker, List.of(), new ServiceGraph(), rec);

        final Path csv = tmp.resolve("Resource_Report.csv");
        reporter.writeResourceUsageCsv(csv.toString());
        final var lines = Files.readAllLines(csv);
        assertEquals("Instance Name,CPU Usage Average,RAM Usage Average", lines.getFirst());
        assertEquals(3, lines.size()); // header + 2 instances
        assertTrue(lines.get(1).startsWith("carts-0,"));
        assertTrue(lines.get(2).startsWith("carts-1,"));
    }

    @Test
    void dagJsonHasExactGrafanaNodeGraphFields(@TempDir final Path tmp) throws IOException {
        final var graph = new ServiceGraph();
        final var front = (ServiceSimple) new ServiceSimple("front-end");
        front.addApi("GET /cart");
        final var carts = (ServiceSimple) new ServiceSimple("carts");
        carts.addApi("GET /cart");
        graph.addService(front, null);
        graph.addService(carts, front);

        final var api = new Api("GET /cart", 1.0, 5.0);
        graph.buildServiceChains(List.of(api));
        finishedReq(1, api, 1.0);
        finishedReq(2, api, 6.0); // SLO violation

        final var sim = new CloudSimPlus();
        final var broker = new ServiceBrokerSimple(sim);
        final var reporter = new ServiceReporter(broker, List.of(api), graph, null);
        reporter.writeDagJson(tmp.toString());

        final JsonNode nodes = MAPPER.readTree(tmp.resolve("nodes.json").toFile()).get("nodes");
        assertEquals(2, nodes.size());
        for (final JsonNode n : nodes) {
            assertTrue(n.has("id"));
            assertTrue(n.has("title"));
            assertTrue(n.has("arc__success"));
            assertTrue(n.has("arc__failure"));
            assertTrue(n.has("mainstat"));
            // Arcs must sum to 1.
            final double sum = n.get("arc__success").asDouble() + n.get("arc__failure").asDouble();
            assertEquals(1.0, sum, 1e-9);
        }
        // Half of the requests violated SLO -> arc__failure ~= 0.5 on every chain service.
        for (final JsonNode n : nodes) {
            assertEquals(0.5, n.get("arc__failure").asDouble(), 1e-9);
        }

        final JsonNode edges = MAPPER.readTree(tmp.resolve("edges.json").toFile()).get("edges");
        assertEquals(1, edges.size());
        assertEquals("front-end", edges.get(0).get("source").asText());
        assertEquals("carts", edges.get(0).get("target").asText());
        assertEquals("e1", edges.get(0).get("id").asText());
    }

    @Test
    void asciiTableContainsAllApiAndAggregate() {
        final var api = new Api("GET /x", 1.0, 5.0);
        finishedReq(1, api, 1.5);
        final var sim = new CloudSimPlus();
        final var broker = new ServiceBrokerSimple(sim);
        final var reporter = new ServiceReporter(broker, List.of(api), new ServiceGraph(), null);
        final var ascii = reporter.renderApiStatistics();
        assertTrue(ascii.contains("API Name"));
        assertTrue(ascii.contains("GET /x"));
        assertTrue(ascii.contains("Aggregate"));
        assertTrue(ascii.contains("RPS"));
        assertTrue(ascii.contains("SLO Violation Rate"));
    }
}
