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
 * Jackson DTO mirroring the top-level structure of the CloudNativeSim
 * {@code services.json} file:
 *
 * <pre>
 * {
 *   "APIs":     [ { "name": "GET /catalogue", "weight": 2.0 }, ... ],
 *   "services": [ { "name": "front-end", "labels": [...], "calls": [...], "APIs": [...] }, ... ]
 * }
 * </pre>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public final class ServicesFileDto {
    /** API definitions (name + weight). */
    @JsonProperty("APIs")
    public List<ApiDto> apis = Collections.emptyList();

    /** Service definitions (name, labels, downstream calls, declared APIs). */
    public List<ServiceDto> services = Collections.emptyList();

    /** A single API entry. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static final class ApiDto {
        public String name;
        public Double weight;
        public Double sloThreshold;
    }

    /** A single service entry. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static final class ServiceDto {
        public String name;
        public List<String> labels = Collections.emptyList();
        /** Children — services this service synchronously calls. */
        public List<String> calls = Collections.emptyList();
        /** APIs this service participates in serving. */
        @JsonProperty("APIs")
        public List<String> apis = Collections.emptyList();
    }
}
