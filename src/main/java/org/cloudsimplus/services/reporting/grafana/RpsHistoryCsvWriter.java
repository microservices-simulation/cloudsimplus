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
package org.cloudsimplus.services.reporting.grafana;

import lombok.NonNull;
import org.cloudsimplus.services.Api;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.List;
import java.util.Locale;

/**
 * CSV writers for the global and per-API RPS histories sampled by
 * {@link org.cloudsimplus.services.ServiceBrokerSimple} on every
 * {@code requestInterval}.
 *
 * <p>File contracts (matching §7-bis.1 of the port spec):</p>
 * <ul>
 *   <li>{@code global_rps_history.csv}: header
 *       {@code Timestamp,RPS}; one row per sample.</li>
 *   <li>{@code per_api_rps_history.csv}: header
 *       {@code Timestamp,<api1>,<api2>,...}; one row per sample.</li>
 * </ul>
 *
 * @since CloudSim Plus 9.0.0
 */
public final class RpsHistoryCsvWriter {
    private static final DecimalFormat FMT =
        new DecimalFormat("###.##", DecimalFormatSymbols.getInstance(Locale.ROOT));

    private RpsHistoryCsvWriter() {}

    /** Writes the global RPS history to {@code path}. */
    public static void writeGlobal(@NonNull final Path path,
                                   @NonNull final List<Double> samples,
                                   final double requestInterval) {
        try (var w = newWriter(path)) {
            w.println("Timestamp,RPS");
            for (int i = 0; i < samples.size(); i++) {
                final double ts = (i + 1) * requestInterval;
                w.printf(Locale.ROOT, "%s,%s%n", FMT.format(ts), FMT.format(samples.get(i)));
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Writes the per-API RPS history to {@code path}. Each row is a single
     * sampled timestamp followed by one column per API in {@code apis}'
     * declaration order.
     */
    public static void writePerApi(@NonNull final Path path,
                                   @NonNull final List<Api> apis,
                                   final double requestInterval) {
        try (var w = newWriter(path)) {
            // Header
            final var header = new StringBuilder("Timestamp");
            for (final Api api : apis) {
                header.append(',').append(api.getName());
            }
            w.println(header);

            // Number of rows = max history length across all APIs.
            int rows = 0;
            for (final Api api : apis) {
                rows = Math.max(rows, api.getRpsHistory().size());
            }
            for (int i = 0; i < rows; i++) {
                final double ts = (i + 1) * requestInterval;
                final var line = new StringBuilder();
                line.append(FMT.format(ts));
                for (final Api api : apis) {
                    line.append(',');
                    if (i < api.getRpsHistory().size()) {
                        line.append(FMT.format(api.getRpsHistory().get(i)));
                    } else {
                        line.append('0');
                    }
                }
                w.println(line);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static PrintWriter newWriter(final Path path) throws IOException {
        if (path.getParent() != null) {
            Files.createDirectories(path.getParent());
        }
        final Writer w = Files.newBufferedWriter(path, StandardCharsets.UTF_8);
        return new PrintWriter(w);
    }
}
