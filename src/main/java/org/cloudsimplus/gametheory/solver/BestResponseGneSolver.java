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

import lombok.Builder;
import org.cloudsimplus.gametheory.CallGraph;
import org.cloudsimplus.gametheory.MicroserviceProfile;
import org.cloudsimplus.gametheory.ResourcePool;
import org.cloudsimplus.gametheory.ResourceVector;
import org.cloudsimplus.gametheory.UtilityModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * Distributed best-response solver for the HHG-MS Layer 1 GNEP
 * (Algorithm 1 of <i>PROPOSED_MODEL.tex</i>).
 *
 * <p>Each outer sweep visits the players in order and replaces
 * {@code R_i^(t)} by the best response to {@code R_{-i}^(t)} (Gauss-Seidel
 * scheme). Per-player best responses are obtained through a multi-start
 * projected gradient ascent on the strictly concave utility, with a
 * stability barrier {@code μ_i > λ_i + ε}.</p>
 *
 * <p>Convergence is declared when the {@code ‖R^(t) - R^(t-1)‖_∞} drops
 * below {@code tolerance} or when the iteration cap is reached.</p>
 *
 * <p>The solver is fully deterministic and pure-Java; it has no external
 * numerical-optimisation dependency.</p>
 *
 * @since CloudSim Plus 9.0.0
 */
@Builder
public final class BestResponseGneSolver implements GneSolver {
    private static final Logger LOG = LoggerFactory.getLogger(BestResponseGneSolver.class.getSimpleName());

    /** Maximum number of outer best-response sweeps. */
    @Builder.Default
    private final int maxSweeps = 60;

    /** Convergence tolerance on {@code ‖R^(t) - R^(t-1)‖_∞}. */
    @Builder.Default
    private final double tolerance = 1.0e-4;

    /** Maximum projected-gradient iterations per player and per sweep. */
    @Builder.Default
    private final int innerMaxIter = 200;

    /** Backtracking iterations inside the per-player line search. */
    @Builder.Default
    private final int lineSearchTrials = 30;

    /** Numerical floor used as the lower bound of every component. */
    @Builder.Default
    private final double componentFloor = 1.0e-4;

    /** Whether to log per-sweep diagnostics at INFO level. */
    @Builder.Default
    private final boolean verbose = false;

    @Override
    public GneResult solve(final List<MicroserviceProfile> services,
                           final CallGraph graph,
                           final ResourcePool pool) {
        final int n = services.size();
        if (n == 0) {
            return new GneResult(new ResourceVector[0], new double[0], 0, true, List.of());
        }

        final double[] lambda = UtilityModel.effectiveArrivalRates(services, graph);

        // Warm start: half of a fair share of the capacity.
        final var allocation = new ResourceVector[n];
        for (int i = 0; i < n; i++) {
            allocation[i] = pool.getCapacity().scale(0.5 / n);
        }

        final List<Double> history = new ArrayList<>();
        boolean converged = false;
        int sweep;
        for (sweep = 1; sweep <= maxSweeps; sweep++) {
            final var previous = new ResourceVector[n];
            System.arraycopy(allocation, 0, previous, 0, n);

            for (int i = 0; i < n; i++) {
                allocation[i] = bestResponse(i, allocation, services.get(i), lambda[i], pool);
            }

            final double sw = UtilityModel.socialWelfare(services, allocation, lambda, pool);
            history.add(sw);

            double maxDelta = 0.0;
            for (int i = 0; i < n; i++) {
                maxDelta = Math.max(maxDelta, ResourceVector.maxAbsDelta(allocation[i], previous[i]));
            }

            if (verbose) {
                LOG.info("[Layer 1] sweep {} social_welfare={} maxDelta={}",
                    sweep, formatDouble(sw), formatDouble(maxDelta));
            }

            if (maxDelta < tolerance) {
                converged = true;
                break;
            }
        }

        return new GneResult(allocation, lambda, Math.min(sweep, maxSweeps), converged, history);
    }

    // ------------------------------------------------------------------
    // per-player best response
    // ------------------------------------------------------------------

    private ResourceVector bestResponse(final int i, final ResourceVector[] allocation,
                                         final MicroserviceProfile profile, final double lambdaI,
                                         final ResourcePool pool) {
        final ResourceVector residual = residualUpperBound(i, allocation, pool);

        // Multi-start: keep the best ascent across several plausible seeds.
        final var starts = new ResourceVector[] {
            allocation[i],
            residual.scale(0.50),
            residual.scale(0.25),
            residual.scale(0.75),
            residual.scale(0.10).plus(ResourceVector.filled(componentFloor))
        };

        ResourceVector best = allocation[i];
        double bestUtility = UtilityModel.utility(profile, best, lambdaI, pool);

        for (final var seed : starts) {
            final ResourceVector candidate = projectedGradientAscent(profile, lambdaI, pool, residual, seed);
            final double u = UtilityModel.utility(profile, candidate, lambdaI, pool);
            if (u > bestUtility) {
                bestUtility = u;
                best = candidate;
            }
        }

        // Last resort: if no start beat the previous allocation, also evaluate
        // the all-zero point (player drops out — utility = 0 by convention).
        final double dropoutUtility = 0.0;
        if (bestUtility < dropoutUtility) {
            return ResourceVector.zero();
        }
        return best;
    }

    private ResourceVector residualUpperBound(final int i, final ResourceVector[] allocation,
                                               final ResourcePool pool) {
        final var sum = new double[ResourceVector.DIMENSIONS];
        for (int j = 0; j < allocation.length; j++) {
            if (j == i) {
                continue;
            }
            for (int k = 0; k < ResourceVector.DIMENSIONS; k++) {
                sum[k] += allocation[j].get(k);
            }
        }
        final var upper = new double[ResourceVector.DIMENSIONS];
        for (int k = 0; k < ResourceVector.DIMENSIONS; k++) {
            upper[k] = Math.max(0.0, pool.getCapacity().get(k) - sum[k]);
        }
        return ResourceVector.of(upper);
    }

    private ResourceVector projectedGradientAscent(final MicroserviceProfile profile,
                                                    final double lambdaI,
                                                    final ResourcePool pool,
                                                    final ResourceVector residual,
                                                    final ResourceVector seed) {
        final ResourceVector lo = ResourceVector.filled(componentFloor);
        final ResourceVector hi = residual;

        ResourceVector x = seed.clamp(lo, hi);

        // If even the residual upper bound cannot satisfy the stability
        // constraint, the player has to drop out.
        if (UtilityModel.capacity(profile, hi) <= lambdaI + UtilityModel.STABILITY_EPSILON) {
            return ResourceVector.zero();
        }

        // Push x up until μ(x) > λ + ε. We climb proportionally toward
        // the upper bound; the Cobb-Douglas capacity is monotone.
        int safety = 0;
        while (UtilityModel.capacity(profile, x) <= lambdaI + UtilityModel.STABILITY_EPSILON && safety++ < 30) {
            final double[] mid = new double[ResourceVector.DIMENSIONS];
            for (int k = 0; k < ResourceVector.DIMENSIONS; k++) {
                mid[k] = 0.5 * (x.get(k) + hi.get(k));
            }
            x = ResourceVector.of(mid).clamp(lo, hi);
        }

        // Initial step proportional to the largest residual component.
        double step = 0.10 * Math.max(componentFloor, maxComponent(hi));
        double currentU = UtilityModel.utility(profile, x, lambdaI, pool);

        for (int iter = 0; iter < innerMaxIter; iter++) {
            final double[] grad = numericalGradient(profile, x, lambdaI, pool, hi);

            boolean improved = false;
            double s = step;
            for (int trial = 0; trial < lineSearchTrials; trial++) {
                final var candidate = stepAndProject(x, grad, s, lo, hi);
                if (UtilityModel.capacity(profile, candidate) <= lambdaI + UtilityModel.STABILITY_EPSILON) {
                    s *= 0.5;
                    continue;
                }
                final double newU = UtilityModel.utility(profile, candidate, lambdaI, pool);
                if (newU > currentU + 1.0e-10) {
                    x = candidate;
                    currentU = newU;
                    step = s * 1.20;
                    improved = true;
                    break;
                }
                s *= 0.5;
            }
            if (!improved) {
                break;
            }
        }
        return x;
    }

    private double[] numericalGradient(final MicroserviceProfile profile, final ResourceVector x,
                                        final double lambdaI, final ResourcePool pool,
                                        final ResourceVector hi) {
        final double[] grad = new double[ResourceVector.DIMENSIONS];
        final double[] base = x.toArray();
        for (int k = 0; k < ResourceVector.DIMENSIONS; k++) {
            final double scale = Math.max(componentFloor, hi.get(k));
            final double h = Math.max(1.0e-7, 1.0e-4 * scale);
            final double[] forward = base.clone();
            final double[] backward = base.clone();
            forward[k] = base[k] + h;
            backward[k] = Math.max(componentFloor, base[k] - h);
            final double uPlus = UtilityModel.utility(profile, ResourceVector.of(forward), lambdaI, pool);
            final double uMinus = UtilityModel.utility(profile, ResourceVector.of(backward), lambdaI, pool);
            grad[k] = (uPlus - uMinus) / (forward[k] - backward[k]);
        }
        return grad;
    }

    private ResourceVector stepAndProject(final ResourceVector x, final double[] grad,
                                           final double step, final ResourceVector lo,
                                           final ResourceVector hi) {
        final double[] cand = new double[ResourceVector.DIMENSIONS];
        for (int k = 0; k < ResourceVector.DIMENSIONS; k++) {
            cand[k] = x.get(k) + step * grad[k];
        }
        return ResourceVector.of(cand).clamp(lo, hi);
    }

    private static double maxComponent(final ResourceVector v) {
        double m = 0.0;
        for (int k = 0; k < ResourceVector.DIMENSIONS; k++) {
            m = Math.max(m, v.get(k));
        }
        return m;
    }

    private static String formatDouble(final double d) {
        return String.format("%.6f", d);
    }
}
