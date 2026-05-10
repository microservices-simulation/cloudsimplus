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
package org.cloudsimplus.services.config;

import java.util.List;

/**
 * Resource specification for a group of replicas declared in a CloudNativeSim
 * {@code instances.yaml} file.
 *
 * <p>Carries the raw declared values; the {@link ServiceRegistry} uses them to
 * size the corresponding {@link org.cloudsimplus.vms.VmSimple} replicas.</p>
 *
 * @param prefix         the naming prefix; replicas are named
 *                       {@code "<prefix>-<index>"}
 * @param type           the instance kind ({@code "pod"}, {@code "container"},
 *                       free-text)
 * @param labels         labels carried by every replica in this group
 * @param replicas       number of identical replicas to spawn
 * @param size           VM storage size in MB
 * @param recBw          receive bandwidth in Mb/s
 * @param transBw        transmit bandwidth in Mb/s
 * @param requestsShare  reserved CPU share (MIPS-equivalent), default
 *                       {@link #DEFAULT_REQUESTS_SHARE}
 * @param requestsRam    reserved RAM in MB, default
 *                       {@link #DEFAULT_REQUESTS_RAM}
 * @param limitsShare    CPU share cap (MIPS-equivalent), default
 *                       {@link #DEFAULT_LIMITS_SHARE}
 * @param limitsRam      RAM cap in MB, default {@link #DEFAULT_LIMITS_RAM}
 * @since CloudSim Plus 9.0.0
 */
public record ReplicaSpec(
    String prefix,
    String type,
    List<String> labels,
    int replicas,
    long size,
    double recBw,
    double transBw,
    long requestsShare,
    long requestsRam,
    long limitsShare,
    long limitsRam
) {
    /** Default reserved CPU share (CloudNativeSim default). */
    public static final long DEFAULT_REQUESTS_SHARE = 100L;
    /** Default reserved RAM in MB (CloudNativeSim default). */
    public static final long DEFAULT_REQUESTS_RAM = 200L;
    /** Default CPU share cap (CloudNativeSim default). */
    public static final long DEFAULT_LIMITS_SHARE = 1024L;
    /** Default RAM cap in MB (CloudNativeSim default). */
    public static final long DEFAULT_LIMITS_RAM = 1000L;
    /** Default storage size in MB. */
    public static final long DEFAULT_SIZE = 500L;
    /** Default receive bandwidth in Mb/s. */
    public static final double DEFAULT_REC_BW = 100.0;
    /** Default transmit bandwidth in Mb/s. */
    public static final double DEFAULT_TRANS_BW = 100.0;
}
