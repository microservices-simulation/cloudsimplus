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
package org.cloudsimplus.examples.services;

import ch.qos.logback.classic.Level;
import org.cloudsimplus.brokers.DatacenterBrokerSimple;
import org.cloudsimplus.core.CloudSimPlus;
import org.cloudsimplus.datacenters.DatacenterSimple;
import org.cloudsimplus.hosts.Host;
import org.cloudsimplus.hosts.HostSimple;
import org.cloudsimplus.resources.Pe;
import org.cloudsimplus.resources.PeSimple;
import org.cloudsimplus.services.Api;
import org.cloudsimplus.services.Service;
import org.cloudsimplus.services.ServiceBrokerSimple;
import org.cloudsimplus.services.ServiceGraph;
import org.cloudsimplus.services.config.Replica;
import org.cloudsimplus.services.config.ServiceRegistry;
import org.cloudsimplus.services.generator.RequestGenerator;
import org.cloudsimplus.services.policy.allocation.ServiceAllocationPolicySimple;
import org.cloudsimplus.services.policy.scaling.HorizontalServiceScalingPolicy;
import org.cloudsimplus.services.reporting.ResourceUsageRecorder;
import org.cloudsimplus.services.reporting.ServiceReporter;
import org.cloudsimplus.util.Log;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end SockShop scenario, ported from CloudNativeSim's
 * {@code examples/src/sockshop/SockShopExample.java} to the CloudSim Plus
 * cloud-native services package.
 *
 * <p>Loads {@code services.json} + {@code instances.yaml} via
 * {@link ServiceRegistry}, builds the service DAG, spawns 17 SockShop
 * replicas onto a single host, and runs a short request-generator workload.
 * The end-of-run hook produces every metric file in §7-bis.1 of the port
 * spec ({@code API_Statistics.csv}, {@code Resource_Report.csv},
 * {@code nodes.json}, {@code edges.json}, {@code global_rps_history.csv},
 * per-instance time-series CSVs).</p>
 *
 * <p>Wired as a JUnit test so it runs under {@code mvn test} and writes its
 * outputs into a {@link TempDir} unique to each invocation.</p>
 *
 * @since CloudSim Plus 9.0.0
 */
class SockShopExample {

    /** Run as a JUnit test (writes to a temp dir, asserts on artifacts). */
    @Test
    void runEndToEnd(@TempDir final Path outDir) throws IOException {
        Log.setLevel(Level.OFF);
        final var run = run(outDir);

        // ---- §12 acceptance assertions ----
        // (1) Every required output file exists.
        assertTrue(Files.exists(outDir.resolve("API_Statistics.csv")));
        assertTrue(Files.exists(outDir.resolve("Resource_Report.csv")));
        assertTrue(Files.exists(outDir.resolve("nodes.json")));
        assertTrue(Files.exists(outDir.resolve("edges.json")));
        assertTrue(Files.exists(outDir.resolve("global_rps_history.csv")));
        assertTrue(Files.exists(outDir.resolve("per_api_rps_history.csv")));
        // (2) Per-instance usage history CSVs were emitted (at least one).
        try (var s = Files.list(outDir)) {
            assertTrue(s.anyMatch(p -> p.getFileName().toString().endsWith("_cpu_Usage_History.csv")));
        }
        // (3) API_Statistics.csv has the spec-mandated header + Aggregate row.
        final var statLines = Files.readAllLines(outDir.resolve("API_Statistics.csv"));
        assertEquals(
            "API Name,Total Requests,Average Delay (seconds),SLO Violation Rate,QPS",
            statLines.getFirst());
        assertTrue(statLines.getLast().startsWith("Aggregate,"));
        // (4) Resource_Report.csv has the spec-mandated header.
        final var resLines = Files.readAllLines(outDir.resolve("Resource_Report.csv"));
        assertEquals("Instance Name,CPU Usage Average,RAM Usage Average", resLines.getFirst());
        // (5) Some requests were actually generated and finished.
        assertTrue(run.totalGenerated > 0,
            "expected requests to be generated; got " + run.totalGenerated);
        assertTrue(run.totalFinished > 0,
            "expected requests to finish; got " + run.totalFinished);
    }

    /** Programmatic entry point — useful for {@code mvn exec:java} runs. */
    public static void main(final String[] args) throws IOException {
        final Path out = args.length > 0 ? Path.of(args[0]) : Files.createTempDirectory("sockshop-");
        Log.setLevel(Level.WARN);
        final var run = run(out);
        System.out.println("SockShopExample — wrote artifacts to " + out.toAbsolutePath());
        System.out.println(run.summary());
    }

    /** Result summary for assertions / logging. */
    private record Result(int totalGenerated, int totalFinished, int scaleUpCount) {
        String summary() {
            return "totalGenerated=%d, totalFinished=%d, scaleUpCount=%d"
                .formatted(totalGenerated, totalFinished, scaleUpCount);
        }
    }

    private static Result run(final Path outDir) throws IOException {
        Files.createDirectories(outDir);
        final var sim = new CloudSimPlus();

        // 1. Datacenter sized to fit ~17 light VMs.
        final List<Pe> peList = new ArrayList<>();
        for (int i = 0; i < 32; i++) {
            peList.add(new PeSimple(2000));
        }
        final Host host = new HostSimple(64_000, 1_000_000, 10_000_000, peList);
        new DatacenterSimple(sim, List.of(host));

        // 2. Load SockShop registration from the test-classpath fixtures.
        final var reg = new ServiceRegistry()
            .servicesInputStream(resource("sockshop/services.json"))
            .instancesInputStream(resource("sockshop/instances.yaml"))
            .load();

        final List<Api> apis = reg.getApis();
        final ServiceGraph graph = reg.getServiceGraph();
        final List<Replica> replicas = reg.getReplicas();
        graph.buildServiceChains(apis);

        // 3. Service-level allocation: label-affinity match Replica → Service.
        final var allocPolicy = new ServiceAllocationPolicySimple();
        allocPolicy.match(graph.getAllServices(), replicas);

        // 4. Wire the broker.
        final var broker = (ServiceBrokerSimple) new ServiceBrokerSimple(sim);
        broker.submitVmList(allocPolicy.allVms());
        graph.getAllServices().forEach(broker::addService);

        // 5. Resource sampling.
        final var recorder = new ResourceUsageRecorder(10.0);
        for (final Replica r : replicas) {
            // Find the service that backs this replica.
            Service backing = null;
            for (final Service s : graph.getAllServices()) {
                if (allocPolicy.getReplicasFor(s).contains(r)) {
                    backing = s;
                    break;
                }
            }
            recorder.register(r.name(), r.vm(), backing);
        }

        // 6. Service-level horizontal scaling for every service.
        final var scaleHits = new int[1];
        for (final Service s : graph.getAllServices()) {
            final var policy = new HorizontalServiceScalingPolicy(recorder)
                .setOnScale((svc, dir) -> scaleHits[0]++);
            broker.setServiceScalingPolicy(s, policy);
        }

        // 7. Request generator (short horizon for a fast test run).
        final var gen = new RequestGenerator()
            .apis(apis)
            .clientsMode(20, 5)
            .waitTimeSpan(2, 5)
            .timeLimit(15)
            .length(8, 2)
            .random(new Random(42));

        broker.setServiceGraph(graph)
            .setRequestGenerator(gen)
            .setResourceUsageRecorder(recorder)
            .setRequestInterval(1.0)
            .setServiceSchedulingInterval(2.0);

        // 8. Run.
        sim.start();

        // 9. Reporter — emit every artifact §7-bis.1 calls for.
        final var reporter = new ServiceReporter(broker, apis, graph, recorder);
        reporter.printApiStatistics();
        reporter.printResourceUsage();
        reporter.writeApiStatisticsCsv(outDir.resolve("API_Statistics.csv").toString());
        reporter.writeResourceUsageCsv(outDir.resolve("Resource_Report.csv").toString());
        reporter.writeUsageDetailCsv(outDir.toString());
        reporter.writeGlobalRpsHistoryCsv(outDir.resolve("global_rps_history.csv").toString());
        reporter.writePerApiRpsHistoryCsv(outDir.resolve("per_api_rps_history.csv").toString());
        reporter.writeDagJson(outDir.toString());

        return new Result(gen.getTotalGenerated(), broker.getFinishedRequests().size(), scaleHits[0]);
    }

    private static InputStream resource(final String name) {
        final InputStream in = SockShopExample.class.getClassLoader().getResourceAsStream(name);
        if (in == null) {
            throw new IllegalStateException("Test resource not found: " + name);
        }
        return in;
    }

    /**
     * Helper used by the integration test in
     * {@link DatacenterBrokerSimple} smoke checks; intentionally
     * package-private to avoid widening API surface.
     */
    @SuppressWarnings("unused")
    private static Result rerun(final Path outDir) throws IOException {
        return run(outDir);
    }
}
