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

import lombok.NonNull;
import org.cloudsimplus.services.Api;
import org.cloudsimplus.services.Service;
import org.cloudsimplus.services.ServiceBrokerSimple;
import org.cloudsimplus.services.ServiceCall;
import org.cloudsimplus.services.ServiceGraph;
import org.cloudsimplus.services.ServiceRequest;
import org.cloudsimplus.services.reporting.grafana.DagJsonWriter;
import org.cloudsimplus.services.reporting.grafana.RpsHistoryCsvWriter;
import org.cloudsimplus.services.reporting.grafana.UsageHistoryCsvWriter;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Aggregates all metric printers and writers for a finished simulation run.
 *
 * <h3>ASCII printers</h3>
 * <ul>
 *   <li>{@link #printApiStatistics()} — per-API + aggregate metrics
 *       (Total Requests, Failure Rate, RPS, Average Delay, SLO Violation
 *       Rate).</li>
 *   <li>{@link #printResourceUsage()} — per-instance CPU/RAM averages.</li>
 * </ul>
 *
 * <h3>CSV writers</h3>
 * <ul>
 *   <li>{@link #writeApiStatisticsCsv(String)} → {@code API_Statistics.csv}.</li>
 *   <li>{@link #writeResourceUsageCsv(String)} → {@code Resource_Report.csv}
 *       (loads 1:1 into the Grafana {@code grafana_table} schema).</li>
 *   <li>{@link #writeUsageDetailCsv(String)} → per-instance time-series CSVs.</li>
 *   <li>{@link #writeGlobalRpsHistoryCsv(String)} → {@code global_rps_history.csv}.</li>
 *   <li>{@link #writePerApiRpsHistoryCsv(String)} → {@code per_api_rps_history.csv}.</li>
 * </ul>
 *
 * <h3>Grafana DAG JSON</h3>
 * <ul>
 *   <li>{@link #writeDagJson(String)} → {@code nodes.json} +
 *       {@code edges.json} (consumable by Grafana's NodeGraph panel via the
 *       Infinity datasource).</li>
 * </ul>
 *
 * <p>All numeric formatting uses {@link Locale#ROOT} to avoid the
 * comma-decimal-separator bug in CloudNativeSim's writer.</p>
 *
 * @since CloudSim Plus 9.0.0
 */
public class ServiceReporter {
    private static final DecimalFormatSymbols ROOT_SYMBOLS = DecimalFormatSymbols.getInstance(Locale.ROOT);
    private static final DecimalFormat NUMBER_FMT = new DecimalFormat("###.##", ROOT_SYMBOLS);
    private static final DecimalFormat PERCENT_FMT = new DecimalFormat("0.00", ROOT_SYMBOLS);

    private final ServiceBrokerSimple broker;
    private final List<Api> apis;
    private final ServiceGraph graph;
    private final ResourceUsageRecorder recorder;

    /**
     * @param broker   the broker the simulation ran on (must be finished)
     * @param apis     all APIs the request generator drew from
     * @param graph    the service graph used by the simulation
     * @param recorder the resource usage recorder (may be {@code null} if no
     *                 sampling was configured)
     */
    public ServiceReporter(@NonNull final ServiceBrokerSimple broker,
                           @NonNull final List<Api> apis,
                           @NonNull final ServiceGraph graph,
                           final ResourceUsageRecorder recorder) {
        this.broker = broker;
        this.apis = apis;
        this.graph = graph;
        this.recorder = recorder;
    }

    /**
     * Convenience constructor that sources the recorder from the broker (or
     * {@code null} if none was configured).
     */
    public ServiceReporter(final ServiceBrokerSimple broker,
                           final List<Api> apis,
                           final ServiceGraph graph) {
        this(broker, apis, graph, broker.getResourceUsageRecorder());
    }

    // --------------------------------------------------------------------
    // ASCII printers
    // --------------------------------------------------------------------

    /** Prints the per-API + aggregate statistics to {@code System.out}. */
    public void printApiStatistics() {
        System.out.println(renderApiStatistics());
    }

    /** Prints per-instance CPU/RAM averages to {@code System.out}. */
    public void printResourceUsage() {
        System.out.println(renderResourceUsage());
    }

    /**
     * Renders an ASCII summary table containing one row per API plus a final
     * aggregate row.
     */
    public String renderApiStatistics() {
        final var t = new AsciiTable(
            "API Name", "Total Requests", "Failure Rate", "RPS", "Average Delay", "SLO Violation Rate");

        int aggTotal = 0;
        int aggFailed = 0;
        int aggSloViol = 0;
        double aggDelaySum = 0;
        double aggRps = 0;

        for (final Api api : apis) {
            final int total = api.getRequests().size();
            final long finished = api.getRequests().stream().filter(ServiceRequest::isFinished).count();
            final long failed = total - finished;
            final int sloViol = api.getSloViolations();
            final double avgDelay = api.getAverageDelay();
            final double rps = api.getAvgRps();

            t.addRow(
                api.getName(),
                Integer.toString(total),
                fmtPct(total == 0 ? 0 : (double) failed / total),
                fmtNumber(rps),
                fmtNumber(avgDelay) + " s",
                fmtPct(api.getSloViolationRate())
            );

            aggTotal += total;
            aggFailed += failed;
            aggSloViol += sloViol;
            aggDelaySum += avgDelay * total;
            aggRps += rps;
        }

        t.addRow(
            "Aggregate",
            Integer.toString(aggTotal),
            fmtPct(aggTotal == 0 ? 0 : (double) aggFailed / aggTotal),
            fmtNumber(aggRps),
            fmtNumber(aggTotal == 0 ? 0 : aggDelaySum / aggTotal) + " s",
            fmtPct(aggTotal == 0 ? 0 : (double) aggSloViol / aggTotal)
        );

        return t.render();
    }

    /** Renders an ASCII per-instance CPU/RAM averages table. */
    public String renderResourceUsage() {
        final var t = new AsciiTable("Instance Name", "CPU Usage Average", "RAM Usage Average");
        if (recorder == null) {
            return t.render();
        }
        for (final String uid : recorder.getInstanceUids()) {
            t.addRow(
                uid,
                fmtNumber(recorder.getAverageCpu(uid)),
                fmtNumber(recorder.getAverageRam(uid))
            );
        }
        return t.render();
    }

    // --------------------------------------------------------------------
    // CSV writers
    // --------------------------------------------------------------------

    /**
     * Writes {@code API_Statistics.csv} with the header
     * {@code API Name,Total Requests,Average Delay (seconds),SLO Violation Rate,QPS}
     * and a final {@code Aggregate,...} row, mirroring CloudNativeSim's output.
     */
    public void writeApiStatisticsCsv(@NonNull final String path) {
        try (var w = newWriter(path)) {
            w.println("API Name,Total Requests,Average Delay (seconds),SLO Violation Rate,QPS");
            int aggTotal = 0;
            int aggSloViol = 0;
            double aggDelaySum = 0;
            double aggRps = 0;
            for (final Api api : apis) {
                final int total = api.getRequests().size();
                final int sloViol = api.getSloViolations();
                final double avgDelay = api.getAverageDelay();
                final double rps = api.getAvgRps();
                w.printf(Locale.ROOT, "%s,%d,%s,%s,%s%n",
                    api.getName(), total,
                    fmtNumber(avgDelay), fmtPct(api.getSloViolationRate()),
                    fmtNumber(rps));
                aggTotal += total;
                aggSloViol += sloViol;
                aggDelaySum += avgDelay * total;
                aggRps += rps;
            }
            final double aggDelay = aggTotal == 0 ? 0 : aggDelaySum / aggTotal;
            final double aggSloRate = aggTotal == 0 ? 0 : (double) aggSloViol / aggTotal;
            w.printf(Locale.ROOT, "%s,%d,%s,%s,%s%n",
                "Aggregate", aggTotal,
                fmtNumber(aggDelay), fmtPct(aggSloRate),
                fmtNumber(aggRps));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Writes {@code Resource_Report.csv} with the header
     * {@code Instance Name,CPU Usage Average,RAM Usage Average} — loads 1:1
     * into the Grafana {@code grafana_table} MySQL schema documented in the
     * port spec.
     */
    public void writeResourceUsageCsv(@NonNull final String path) {
        try (var w = newWriter(path)) {
            w.println("Instance Name,CPU Usage Average,RAM Usage Average");
            if (recorder == null) {
                return;
            }
            for (final String uid : recorder.getInstanceUids()) {
                w.printf(Locale.ROOT, "%s,%s,%s%n",
                    uid,
                    fmtNumber(recorder.getAverageCpu(uid)),
                    fmtNumber(recorder.getAverageRam(uid)));
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Writes one CSV per (instance, resource) into {@code dir}, named
     * {@code <instance>_cpu_Usage_History.csv} and
     * {@code <instance>_ram_Usage_History.csv}, with header
     * {@code Timestamp,Average}.
     */
    public void writeUsageDetailCsv(@NonNull final String dir) {
        if (recorder == null) {
            return;
        }
        UsageHistoryCsvWriter.write(Path.of(dir), recorder);
    }

    /**
     * Writes {@code global_rps_history.csv} with the header {@code Timestamp,RPS}
     * and one row per sampled {@code requestInterval}.
     */
    public void writeGlobalRpsHistoryCsv(@NonNull final String path) {
        RpsHistoryCsvWriter.writeGlobal(Path.of(path),
            broker.getGlobalRpsHistory(),
            broker.getRequestInterval());
    }

    /**
     * Writes {@code per_api_rps_history.csv} with header
     * {@code Timestamp,<api1>,<api2>,...} and one row per sampled interval.
     */
    public void writePerApiRpsHistoryCsv(@NonNull final String path) {
        RpsHistoryCsvWriter.writePerApi(Path.of(path), apis, broker.getRequestInterval());
    }

    // --------------------------------------------------------------------
    // Grafana DAG JSON
    // --------------------------------------------------------------------

    /**
     * Writes {@code nodes.json} + {@code edges.json} into {@code dir},
     * consumable by Grafana's NodeGraph panel via the Infinity datasource.
     */
    public void writeDagJson(@NonNull final String dir) {
        DagJsonWriter.write(Path.of(dir), graph, apis, broker);
    }

    /** Explicit per-file paths overload. */
    public void writeDagJson(@NonNull final String nodesPath, @NonNull final String edgesPath) {
        DagJsonWriter.write(Path.of(nodesPath), Path.of(edgesPath), graph, apis, broker);
    }

    // --------------------------------------------------------------------
    // Per-API service chains (printChains)
    // --------------------------------------------------------------------

    /**
     * Renders an ASCII outline of every per-API service chain and the global
     * dependency tree, useful for debugging.
     */
    public String renderChains() {
        final var sb = new StringBuilder();
        for (final Api api : apis) {
            sb.append("API: ").append(api.getName()).append('\n');
            for (final Service s : api.getServiceChain()) {
                sb.append("  - ").append(s.getName()).append('\n');
            }
        }
        return sb.toString();
    }

    /** Convenience: prints the chain outline to {@code System.out}. */
    public void printChains() {
        System.out.println(renderChains());
    }

    /**
     * @return raw per-edge call-counts and average latencies built up by the
     *         broker during the run. Keyed by {@code source} → {@code target}
     *         service names.
     */
    public Map<String, Map<String, EdgeStats>> getDagEdgeStats() {
        // Re-derive from the broker's internal DAG (exposed as JSON only).
        // For now, ServiceReporter only consumes the structural graph for
        // nodes.json/edges.json; per-edge stats are exposed via the
        // ServiceBroker.getDAG() JSON.
        return new LinkedHashMap<>();
    }

    /** Edge statistics record (currently a placeholder for future use). */
    public record EdgeStats(int callCount, double averageLatency) {}

    // --------------------------------------------------------------------
    // Internals
    // --------------------------------------------------------------------

    private static String fmtNumber(final double v) {
        return NUMBER_FMT.format(v);
    }

    private static String fmtPct(final double fraction) {
        return PERCENT_FMT.format(fraction * 100.0) + "%";
    }

    private static PrintWriter newWriter(final String path) throws IOException {
        final Path p = Path.of(path);
        if (p.getParent() != null) {
            Files.createDirectories(p.getParent());
        }
        final Writer w = Files.newBufferedWriter(p, StandardCharsets.UTF_8);
        return new PrintWriter(w);
    }
}
