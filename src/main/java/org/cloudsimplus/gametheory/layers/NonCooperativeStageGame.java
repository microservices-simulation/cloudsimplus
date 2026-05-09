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
package org.cloudsimplus.gametheory.layers;

import lombok.NonNull;
import org.cloudsimplus.gametheory.CallGraph;
import org.cloudsimplus.gametheory.MicroserviceProfile;
import org.cloudsimplus.gametheory.ResourcePool;
import org.cloudsimplus.gametheory.solver.BestResponseGneSolver;
import org.cloudsimplus.gametheory.solver.GneResult;
import org.cloudsimplus.gametheory.solver.GneSolver;

import java.util.List;

/**
 * Layer 1 of the HHG-MS framework — the non-cooperative stage game
 * {@code G_ms} (Section 3 of <i>PROPOSED_MODEL.tex</i>).
 *
 * <p>This facade wires a {@link MicroserviceProfile} population, a
 * {@link CallGraph} and a {@link ResourcePool} together with a
 * {@link GneSolver} (defaulting to {@link BestResponseGneSolver}). Calling
 * {@link #solve()} returns the equilibrium allocation that callers can map
 * onto CloudSim VMs (see the companion {@code microservices-sim-poc} demo).</p>
 *
 * @since CloudSim Plus 9.0.0
 */
public final class NonCooperativeStageGame {
    private final List<MicroserviceProfile> services;
    private final CallGraph graph;
    private final ResourcePool pool;
    private final GneSolver solver;

    public NonCooperativeStageGame(@NonNull final List<MicroserviceProfile> services,
                                    @NonNull final CallGraph graph,
                                    @NonNull final ResourcePool pool) {
        this(services, graph, pool, BestResponseGneSolver.builder().build());
    }

    public NonCooperativeStageGame(@NonNull final List<MicroserviceProfile> services,
                                    @NonNull final CallGraph graph,
                                    @NonNull final ResourcePool pool,
                                    @NonNull final GneSolver solver) {
        if (services.isEmpty()) {
            throw new IllegalArgumentException("At least one service must be provided");
        }
        this.services = List.copyOf(services);
        this.graph = graph;
        this.pool = pool;
        this.solver = solver;
    }

    /**
     * Runs the underlying solver and returns the equilibrium result.
     */
    public GneResult solve() {
        return solver.solve(services, graph, pool);
    }

    public List<MicroserviceProfile> getServices() {
        return services;
    }

    public CallGraph getCallGraph() {
        return graph;
    }

    public ResourcePool getResourcePool() {
        return pool;
    }
}
