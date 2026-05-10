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
import org.cloudsimplus.services.reporting.ResourceUsageRecorder;
import org.cloudsimplus.services.reporting.UsageSample;

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
 * Writes one CSV per (instance, resource) pair into a target directory.
 * File names follow CloudNativeSim's {@code <instance>_<resource>_Usage_History.csv}
 * convention, with header {@code Timestamp,Average}.
 *
 * <p>Files are produced for {@code cpu} and {@code ram} resources for every
 * instance registered with the {@link ResourceUsageRecorder}.</p>
 *
 * @since CloudSim Plus 9.0.0
 */
public final class UsageHistoryCsvWriter {
    private static final DecimalFormat FMT =
        new DecimalFormat("###.##", DecimalFormatSymbols.getInstance(Locale.ROOT));

    private UsageHistoryCsvWriter() {}

    /**
     * Writes per-instance CPU + RAM history files into {@code dir}.
     *
     * @param dir      target directory (created if missing)
     * @param recorder the recorder whose history is dumped
     */
    public static void write(@NonNull final Path dir,
                             @NonNull final ResourceUsageRecorder recorder) {
        try {
            Files.createDirectories(dir);
            for (final String uid : recorder.getInstanceUids()) {
                writeOne(dir.resolve(safeFileName(uid) + "_cpu_Usage_History.csv"),
                    recorder.getCpuHistory().get(uid));
                writeOne(dir.resolve(safeFileName(uid) + "_ram_Usage_History.csv"),
                    recorder.getRamHistory().get(uid));
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void writeOne(final Path path, final List<UsageSample> samples) throws IOException {
        try (var w = new PrintWriter((Writer) Files.newBufferedWriter(path, StandardCharsets.UTF_8))) {
            w.println("Timestamp,Average");
            if (samples == null) {
                return;
            }
            for (final UsageSample s : samples) {
                w.printf(Locale.ROOT, "%s,%s%n", FMT.format(s.timestamp()), FMT.format(s.usage()));
            }
        }
    }

    private static String safeFileName(final String uid) {
        return uid.replaceAll("[^A-Za-z0-9_.-]", "_");
    }
}
