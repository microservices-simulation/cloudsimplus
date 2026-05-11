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
package org.cloudsimplus.services.reporting.mysql;

import lombok.NonNull;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

/**
 * Loads a {@code Resource_Report.csv} (produced by
 * {@link org.cloudsimplus.services.reporting.ServiceReporter#writeResourceUsageCsv(String)
 * ServiceReporter.writeResourceUsageCsv}) into the
 * {@code instance-usage.json} Grafana dashboard's MySQL table via
 * {@code LOAD DATA LOCAL INFILE}.
 *
 * <p>The dashboard panels query:</p>
 * <pre>
 * SELECT instance_name, ram_usage_average FROM mybatis.grafana_table LIMIT 50;
 * SELECT cpu_usage_average, instance_name FROM mybatis.grafana_table LIMIT 50;
 * </pre>
 *
 * <p>The target table schema (created out-of-band, see
 * {@code docs/cloud-native-port.md}) is:</p>
 * <pre>
 * CREATE TABLE grafana_table (
 *   instance_name      VARCHAR(255) PRIMARY KEY,
 *   cpu_usage_average  DOUBLE,
 *   ram_usage_average  DOUBLE
 * );
 * </pre>
 *
 * <h3>Server-side prerequisites</h3>
 * <ul>
 *   <li>MySQL global {@code local_infile=1} (set with
 *       {@code SET GLOBAL local_infile=1;} or via {@code my.cnf}).</li>
 *   <li>The JDBC URL must include {@code allowLoadLocalInfile=true}
 *       (this class adds it automatically if missing).</li>
 *   <li>The connecting user needs {@code FILE} or at least
 *       {@code INSERT}/{@code DELETE} on {@code grafana_table}.</li>
 * </ul>
 *
 * <h3>Typical usage</h3>
 * <pre>{@code
 * reporter.writeResourceUsageCsv(outDir.resolve("Resource_Report.csv").toString());
 * new MysqlResourceUsageSink(
 *         "jdbc:mysql://localhost:3306/mybatis",
 *         "root", "root")
 *     .loadCsv(outDir.resolve("Resource_Report.csv"));
 * }</pre>
 *
 * @since CloudSim Plus 9.0.0
 */
public class MysqlResourceUsageSink {
    /** Default target table (matches the dashboard JSON). */
    public static final String DEFAULT_TABLE = "grafana_table";

    private final String jdbcUrl;
    private final String user;
    private final String password;
    private String table = DEFAULT_TABLE;
    private boolean truncateFirst = true;

    /**
     * @param jdbcUrl  e.g. {@code jdbc:mysql://localhost:3306/mybatis}.
     *                 {@code allowLoadLocalInfile=true} is appended
     *                 automatically if missing.
     * @param user     MySQL user (must hold {@code INSERT}/{@code DELETE} on
     *                 the target table; {@code FILE} privilege is not
     *                 strictly required for {@code LOCAL INFILE}).
     * @param password MySQL password (may be empty, not null).
     */
    public MysqlResourceUsageSink(@NonNull final String jdbcUrl,
                                  @NonNull final String user,
                                  @NonNull final String password) {
        this.jdbcUrl = ensureLocalInfileFlag(jdbcUrl);
        this.user = user;
        this.password = password;
    }

    /** Sets the target table name (default {@value #DEFAULT_TABLE}). */
    public MysqlResourceUsageSink table(@NonNull final String t) {
        if (t.isBlank()) {
            throw new IllegalArgumentException("table must not be blank");
        }
        this.table = t;
        return this;
    }

    /**
     * If {@code true} (default), the sink runs {@code TRUNCATE TABLE} before
     * loading so the dashboard reflects only the latest run's rows. Set to
     * {@code false} to accumulate across runs (relies on the PK
     * {@code instance_name} + the {@code REPLACE} modifier to dedupe).
     */
    public MysqlResourceUsageSink truncateFirst(final boolean b) {
        this.truncateFirst = b;
        return this;
    }

    /**
     * Loads the given CSV into the configured table.
     *
     * <p>The CSV is expected to have the exact header
     * {@code Instance Name,CPU Usage Average,RAM Usage Average} (i.e. what
     * {@code ServiceReporter.writeResourceUsageCsv} writes). Line endings
     * are normalized to {@code LF} before the load to keep
     * {@code LINES TERMINATED BY '\n'} portable across Windows and Linux.</p>
     *
     * @param csvPath path to the Resource_Report.csv
     * @return number of rows ingested (sum across TRUNCATE + LOAD)
     * @throws SQLException if the load fails
     * @throws UncheckedIOException if the CSV can't be read/normalized
     */
    public int loadCsv(@NonNull final Path csvPath) throws SQLException {
        if (!Files.exists(csvPath)) {
            throw new UncheckedIOException(
                new IOException("CSV not found: " + csvPath.toAbsolutePath()));
        }
        final Path lfCsv = normalizeToLf(csvPath);
        final String forwardSlashPath = lfCsv.toAbsolutePath().toString().replace('\\', '/');

        try (Connection conn = DriverManager.getConnection(jdbcUrl, user, password);
             Statement stmt = conn.createStatement()) {
            int affected = 0;
            // MySQL 8 ships with local_infile=0 by default. As a SUPER user
            // (e.g. root) we can enable it globally; non-privileged users get
            // a clean SQLException from the subsequent LOAD pointing them at
            // the my.cnf setting. Best-effort — ignore if not permitted.
            try {
                stmt.executeUpdate("SET GLOBAL local_infile = 1");
            } catch (SQLException ignored) {
                // Lacking privilege is fine; the LOAD will surface a clearer error.
            }
            if (truncateFirst) {
                stmt.executeUpdate("TRUNCATE TABLE " + table);
            }
            // REPLACE makes the load idempotent on retries if a row already
            // exists (PK conflict on instance_name).
            final String sql =
                "LOAD DATA LOCAL INFILE '" + forwardSlashPath + "' "
                + "REPLACE INTO TABLE " + table + " "
                + "FIELDS TERMINATED BY ',' "
                + "LINES TERMINATED BY '\\n' "
                + "IGNORE 1 ROWS "
                + "(instance_name, cpu_usage_average, ram_usage_average)";
            affected += stmt.executeUpdate(sql);
            return affected;
        }
    }

    /**
     * Convenience: returns the JDBC URL this sink will connect with (after
     * the {@code allowLoadLocalInfile=true} flag has been ensured).
     */
    public String getJdbcUrl() {
        return jdbcUrl;
    }

    private static String ensureLocalInfileFlag(final String url) {
        if (url.contains("allowLoadLocalInfile=")) {
            return url;
        }
        return url + (url.contains("?") ? "&" : "?") + "allowLoadLocalInfile=true";
    }

    private static Path normalizeToLf(final Path src) {
        try {
            final List<String> lines = Files.readAllLines(src, StandardCharsets.UTF_8);
            final Path lf = src.resolveSibling(src.getFileName() + ".lf");
            // Join with LF and add a trailing LF so LOAD DATA reads the last row.
            Files.writeString(lf, String.join("\n", lines) + "\n", StandardCharsets.UTF_8);
            return lf;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
