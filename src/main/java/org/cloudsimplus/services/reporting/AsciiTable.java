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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * A tiny self-contained ASCII table renderer used by {@link ServiceReporter}
 * for the {@code printApiStatistics} / {@code printResourceUsage} outputs.
 *
 * <p>Rendered with a fixed border style:</p>
 *
 * <pre>
 * +--------+-----+
 * | header | ... |
 * +--------+-----+
 * | row    | ... |
 * +--------+-----+
 * </pre>
 *
 * @since CloudSim Plus 9.0.0
 */
final class AsciiTable {
    private final String[] headers;
    private final List<String[]> rows = new ArrayList<>();
    private final int[] widths;

    AsciiTable(final String... headers) {
        this.headers = headers.clone();
        this.widths = new int[headers.length];
        for (int i = 0; i < headers.length; i++) {
            widths[i] = headers[i].length();
        }
    }

    AsciiTable addRow(final String... cells) {
        if (cells.length != headers.length) {
            throw new IllegalArgumentException(
                "expected %d cells, got %d".formatted(headers.length, cells.length));
        }
        final String[] copy = cells.clone();
        rows.add(copy);
        for (int i = 0; i < cells.length; i++) {
            widths[i] = Math.max(widths[i], cells[i] == null ? 0 : cells[i].length());
        }
        return this;
    }

    String render() {
        final var sb = new StringBuilder();
        appendBorder(sb);
        appendRow(sb, headers);
        appendBorder(sb);
        for (final String[] row : rows) {
            appendRow(sb, row);
            appendBorder(sb);
        }
        return sb.toString();
    }

    private void appendBorder(final StringBuilder sb) {
        sb.append('+');
        for (final int w : widths) {
            sb.append("-".repeat(w + 2)).append('+');
        }
        sb.append('\n');
    }

    private void appendRow(final StringBuilder sb, final String[] cells) {
        sb.append('|');
        for (int i = 0; i < cells.length; i++) {
            final String c = cells[i] == null ? "" : cells[i];
            sb.append(' ').append(pad(c, widths[i])).append(" |");
        }
        sb.append('\n');
    }

    private static String pad(final String s, final int w) {
        if (s.length() >= w) {
            return s;
        }
        final char[] padding = new char[w - s.length()];
        Arrays.fill(padding, ' ');
        return s + new String(padding);
    }
}
