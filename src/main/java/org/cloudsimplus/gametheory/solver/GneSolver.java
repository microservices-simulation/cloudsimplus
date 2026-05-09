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
package org.cloudsimplus.gametheory.solver;

import org.cloudsimplus.gametheory.CallGraph;
import org.cloudsimplus.gametheory.MicroserviceProfile;
import org.cloudsimplus.gametheory.ResourcePool;

import java.util.List;

/**
 * Strategy interface for solvers of the Generalised Nash Equilibrium
 * Problem (GNEP) defined in Section 3 of the HHG-MS paper.
 *
 * <p>A GNE solver takes the population of microservice profiles, the
 * call graph (used to derive effective arrival rates), and the shared
 * resource pool, and returns an allocation profile that satisfies the
 * GNE optimality conditions up to a tolerance.</p>
 *
 * @since CloudSim Plus 9.0.0
 */
public interface GneSolver {
    /**
     * Solves the GNEP for the given population and resource pool.
     *
     * @param services list of player profiles (order is preserved
     *                 throughout the result allocation array)
     * @param graph    call graph used to propagate external arrival
     *                 rates into effective rates {@code λ_i}
     * @param pool     shared platform-wide capacities and unit prices
     * @return a {@link GneResult} with the equilibrium allocation,
     *         effective arrival rates, and convergence diagnostics
     */
    GneResult solve(List<MicroserviceProfile> services, CallGraph graph, ResourcePool pool);
}
