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

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import lombok.Getter;
import lombok.NonNull;
import org.cloudsimplus.services.Api;
import org.cloudsimplus.services.Service;
import org.cloudsimplus.services.ServiceGraph;
import org.cloudsimplus.services.ServiceSimple;
import org.cloudsimplus.vms.Vm;
import org.cloudsimplus.vms.VmSimple;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Loads CloudNativeSim's two registration files —
 * {@code services.json} and {@code instances.yaml} — into a strongly typed
 * model of {@link Api}s, a {@link ServiceGraph}, and {@link Replica}s.
 *
 * <p>This is the CloudSim Plus equivalent of {@code core.Register}, but uses
 * Jackson + the YAMLFactory and DTOs ({@link ServicesFileDto},
 * {@link InstancesFileDto}) instead of the original
 * {@code Tools.readJson}/{@code Tools.readYaml} key-path soup.</p>
 *
 * <h3>Usage</h3>
 *
 * <pre>{@code
 * var reg = new ServiceRegistry()
 *               .servicesFile("examples/sockshop/services.json")
 *               .instancesFile("examples/sockshop/instances.yaml")
 *               .load();
 * var apis     = reg.getApis();
 * var graph    = reg.getServiceGraph();
 * var replicas = reg.getReplicas();
 * graph.buildServiceChains(apis);
 * }</pre>
 *
 * <p>Defaults for missing instance fields match CloudNativeSim:
 * {@link ReplicaSpec#DEFAULT_REQUESTS_SHARE 100} request share /
 * {@link ReplicaSpec#DEFAULT_REQUESTS_RAM 200 MB} request RAM /
 * {@link ReplicaSpec#DEFAULT_LIMITS_SHARE 1024} limit share /
 * {@link ReplicaSpec#DEFAULT_LIMITS_RAM 1000 MB} limit RAM.</p>
 *
 * @since CloudSim Plus 9.0.0
 */
public class ServiceRegistry {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());

    private String servicesFile;
    private String instancesFile;
    private Path servicesPath;
    private Path instancesPath;
    private InputStream servicesStream;
    private InputStream instancesStream;

    @Getter private final List<Api> apis = new ArrayList<>();
    @Getter private final ServiceGraph serviceGraph = new ServiceGraph();
    @Getter private final List<Replica> replicas = new ArrayList<>();
    @Getter private final List<ReplicaSpec> replicaSpecs = new ArrayList<>();

    private final Map<String, Service> servicesByName = new LinkedHashMap<>();

    /** Configures the path to the services JSON file. */
    public ServiceRegistry servicesFile(@NonNull final String path) {
        this.servicesFile = path;
        return this;
    }

    /** Configures the path to the instances YAML file. */
    public ServiceRegistry instancesFile(@NonNull final String path) {
        this.instancesFile = path;
        return this;
    }

    /** Configures a {@link Path} to the services JSON file (absolute or relative). */
    public ServiceRegistry servicesPath(@NonNull final Path path) {
        this.servicesPath = path;
        return this;
    }

    /** Configures a {@link Path} to the instances YAML file (absolute or relative). */
    public ServiceRegistry instancesPath(@NonNull final Path path) {
        this.instancesPath = path;
        return this;
    }

    /**
     * Configures an in-memory services JSON source. Caller retains ownership
     * of the stream (it is read but not closed by this class).
     */
    public ServiceRegistry servicesInputStream(@NonNull final InputStream stream) {
        this.servicesStream = stream;
        return this;
    }

    /**
     * Configures an in-memory instances YAML source. Caller retains ownership
     * of the stream.
     */
    public ServiceRegistry instancesInputStream(@NonNull final InputStream stream) {
        this.instancesStream = stream;
        return this;
    }

    /**
     * Reads both configured files and populates {@link #getApis()},
     * {@link #getServiceGraph()}, {@link #getReplicas()},
     * {@link #getReplicaSpecs()}.
     *
     * @return this registry, to enable chaining
     */
    public ServiceRegistry load() {
        loadServices();
        loadInstances();
        return this;
    }

    /**
     * @return read-only map from service name to {@link Service} (in
     *         registration order).
     */
    public Map<String, Service> getServicesByName() {
        return Collections.unmodifiableMap(servicesByName);
    }

    private void loadServices() {
        final ServicesFileDto dto = parseServices();

        for (final var api : dto.apis) {
            if (api.name == null || api.name.isBlank()) {
                continue;
            }
            final double weight = api.weight == null ? 1.0 : api.weight;
            final double slo = api.sloThreshold == null ? Api.DEFAULT_SLO_THRESHOLD : api.sloThreshold;
            apis.add(new Api(api.name, weight, slo));
        }

        // First pass: register all services as nodes.
        for (final var sd : dto.services) {
            final var s = new ServiceSimple(sd.name);
            sd.labels.forEach(s::addLabel);
            sd.apis.forEach(s::addApi);
            servicesByName.put(sd.name, s);
            serviceGraph.addService(s, null);
        }

        // Second pass: wire parent -> child edges.
        for (final var sd : dto.services) {
            final Service parent = servicesByName.get(sd.name);
            for (final String childName : sd.calls) {
                final Service child = servicesByName.get(childName);
                if (child == null) {
                    throw new IllegalStateException(
                        "services.json: unknown call target '%s' from '%s'"
                            .formatted(childName, sd.name));
                }
                serviceGraph.addService(child, parent);
            }
        }
    }

    private void loadInstances() {
        final InstancesFileDto dto = parseInstances();
        for (final var inst : dto.instances) {
            final var spec = toSpec(inst);
            replicaSpecs.add(spec);
            for (int i = 0; i < spec.replicas(); i++) {
                final var name = spec.prefix() + "-" + i;
                final var vm = buildVm(spec);
                replicas.add(new Replica(name, vm, spec));
            }
        }
    }

    private static ReplicaSpec toSpec(final InstancesFileDto.InstanceDto i) {
        final var requests = i.requests == null ? new InstancesFileDto.ResourceSpec() : i.requests;
        final var limits = i.limits == null ? new InstancesFileDto.ResourceSpec() : i.limits;
        return new ReplicaSpec(
            i.prefix,
            i.type == null ? "pod" : i.type.toLowerCase(),
            i.labels == null ? Collections.emptyList() : i.labels,
            i.replicas == null ? 1 : i.replicas,
            i.size == null ? ReplicaSpec.DEFAULT_SIZE : i.size,
            i.recBw == null ? ReplicaSpec.DEFAULT_REC_BW : i.recBw,
            i.transBw == null ? ReplicaSpec.DEFAULT_TRANS_BW : i.transBw,
            requests.share == null ? ReplicaSpec.DEFAULT_REQUESTS_SHARE : requests.share,
            requests.ram == null ? ReplicaSpec.DEFAULT_REQUESTS_RAM : requests.ram,
            limits.share == null ? ReplicaSpec.DEFAULT_LIMITS_SHARE : limits.share,
            limits.ram == null ? ReplicaSpec.DEFAULT_LIMITS_RAM : limits.ram
        );
    }

    private static Vm buildVm(final ReplicaSpec spec) {
        // Size the VM by its CPU + RAM limits, mirroring CloudNativeSim's
        // limit-based capacity sizing.
        final var vm = new VmSimple(spec.limitsShare(), 1);
        vm.setRam(spec.limitsRam());
        vm.setBw((long) Math.max(1, spec.recBw() + spec.transBw()));
        vm.setSize(spec.size());
        vm.setDescription(spec.prefix());
        return vm;
    }

    private ServicesFileDto parseServices() {
        try {
            if (servicesStream != null) {
                return JSON.readValue(servicesStream, ServicesFileDto.class);
            }
            if (servicesPath != null) {
                return JSON.readValue(servicesPath.toFile(), ServicesFileDto.class);
            }
            if (servicesFile != null) {
                return JSON.readValue(Path.of(servicesFile).toFile(), ServicesFileDto.class);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to parse services file", e);
        }
        throw new IllegalStateException("No services source configured (call servicesFile/Path/InputStream).");
    }

    private InstancesFileDto parseInstances() {
        try {
            if (instancesStream != null) {
                return YAML.readValue(instancesStream, InstancesFileDto.class);
            }
            if (instancesPath != null) {
                return YAML.readValue(Files.newInputStream(instancesPath), InstancesFileDto.class);
            }
            if (instancesFile != null) {
                return YAML.readValue(Files.newInputStream(Path.of(instancesFile)), InstancesFileDto.class);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to parse instances file", e);
        }
        throw new IllegalStateException("No instances source configured (call instancesFile/Path/InputStream).");
    }
}
