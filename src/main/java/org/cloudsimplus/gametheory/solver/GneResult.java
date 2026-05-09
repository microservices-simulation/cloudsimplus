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

import org.cloudsimplus.gametheory.ResourceVector;

import java.util.Arrays;
import java.util.List;

/**
 * Output of a {@link GneSolver}.
 *
 * @param allocation       per-player resource vector at the (approximate)
 *                         Generalised Nash Equilibrium, indexed by player order.
 * @param effectiveLambda  effective per-service arrival rates {@code λ_i}
 *                         used during the solve (after equation (1) propagation).
 * @param iterations       number of best-response sweeps actually executed.
 * @param converged        {@code true} iff the solver stopped because of the
 *                         tolerance criterion rather than the iteration cap.
 * @param welfareHistory   social welfare at the end of every sweep — useful
 *                         for plotting convergence in the demo.
 *
 * @since CloudSim Plus 9.0.0
 */
public record GneResult(
        ResourceVector[] allocation,
        double[] effectiveLambda,
        int iterations,
        boolean converged,
        List<Double> welfareHistory) {

    /**
     * Returns the social welfare {@code Σ U_i(R^*_i)} at the equilibrium.
     */
    public double finalWelfare() {
        return welfareHistory.isEmpty() ? Double.NaN : welfareHistory.get(welfareHistory.size() - 1);
    }

    /**
     * @return a defensive copy of the allocation array.
     */
    public ResourceVector[] allocationCopy() {
        return Arrays.copyOf(allocation, allocation.length);
    }
}
