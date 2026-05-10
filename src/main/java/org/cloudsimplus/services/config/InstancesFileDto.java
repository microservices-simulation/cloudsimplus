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

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Collections;
import java.util.List;

/**
 * Jackson DTO mirroring the CloudNativeSim {@code instances.yaml} file shape:
 *
 * <pre>
 * instances:
 *   - prefix: carts
 *     type: pod
 *     labels: [carts]
 *     replicas: 2
 *     size: 500
 *     rec_bw: 100
 *     trans_bw: 100
 *     requests: { share: 100, ram: 200 }
 *     limits:   { share: 300, ram: 500 }
 * </pre>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public final class InstancesFileDto {
    public List<InstanceDto> instances = Collections.emptyList();

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static final class InstanceDto {
        /** Naming prefix; replicas are named {@code "<prefix>-<index>"}. */
        public String prefix;
        /** Instance kind: {@code pod}, {@code container}, ... (free-text). */
        public String type;
        public List<String> labels = Collections.emptyList();
        /** Number of identical replicas to spawn. Defaults to {@code 1}. */
        public Integer replicas;
        /** VM-side storage size, in MB. */
        public Long size;
        /** Receive bandwidth, in Mb/s. */
        @JsonProperty("rec_bw")
        public Double recBw;
        /** Transmit bandwidth, in Mb/s. */
        @JsonProperty("trans_bw")
        public Double transBw;
        public ResourceSpec requests;
        public ResourceSpec limits;
    }

    /** Either a {@code requests} or a {@code limits} block. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static final class ResourceSpec {
        /** CPU share (CloudNativeSim "share"); in MIPS-equivalent units. */
        public Long share;
        /** RAM, in MB. */
        public Long ram;
        /** Optional explicit MIPS, if not derivable from share. */
        public Long mips;
    }
}
