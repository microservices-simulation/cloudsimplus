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
package org.cloudsimplus.gametheory;

import java.util.List;

/**
 * Pure-function math layer for the HHG-MS non-cooperative stage game.
 *
 * <p>Implements the closed-form pieces of Section 2 of the paper:</p>
 * <ul>
 *   <li>{@link #capacity} — Cobb-Douglas service capacity μ_i (eq. 2);</li>
 *   <li>{@link #effectiveArrivalRates} — λ propagation through the call
 *       graph, solving λ = λ^ext + P λ (eq. 1);</li>
 *   <li>{@link #latency} — M/G/1 expected response time (eq. 3);</li>
 *   <li>{@link #revenue} — SLA-elastic per-request revenue (eq. 4);</li>
 *   <li>{@link #utility} — per-player utility (eq. 5);</li>
 *   <li>{@link #socialWelfare} — sum of utilities (used as social cost
 *       in §3.4 and as the objective of the centralised baseline).</li>
 * </ul>
 *
 * <p>All routines are stateless static methods; no instance is ever
 * created.</p>
 *
 * @since CloudSim Plus 9.0.0
 */
public final class UtilityModel {
    /** Stability margin used by the solvers when the constraint μ &gt; λ is tight. */
    public static final double STABILITY_EPSILON = 1.0e-6;

    /** Numerical floor used to avoid pow(0, α) singularities. */
    private static final double POSITIVE_FLOOR = 1.0e-12;

    private UtilityModel() {
        // utility class
    }

    /**
     * Cobb-Douglas service capacity {@code μ_i(R_i) = κ_i ∏_k R_i^{(k)}^{α_k}}.
     */
    public static double capacity(final MicroserviceProfile profile, final ResourceVector r) {
        final var alpha = profile.getAlpha();
        double product = 1.0;
        for (int k = 0; k < ResourceVector.DIMENSIONS; k++) {
            final double base = Math.max(r.get(k), POSITIVE_FLOOR);
            product *= Math.pow(base, alpha.get(k));
        }
        return profile.getKappa() * product;
    }

    /**
     * Solves {@code λ = λ^ext + P λ} where {@code P[i][j] = π_ji} for an
     * acyclic call graph using forward substitution in topological order
     * over the service list. For a cyclic graph the solver falls back to
     * a Jacobi iteration with a fixed iteration cap.
     */
    public static double[] effectiveArrivalRates(final List<MicroserviceProfile> services,
                                                 final CallGraph graph) {
        final int n = services.size();
        final double[] lambda = new double[n];
        final double[] lambdaExt = new double[n];
        for (int i = 0; i < n; i++) {
            lambdaExt[i] = services.get(i).getLambdaExt();
            lambda[i] = lambdaExt[i];
        }

        // Jacobi iterations: λ^{t+1}_i = λ^ext_i + Σ_j π_ji λ^t_j.
        // Converges in O(depth) iterations on a DAG.
        final int maxIter = 64;
        final double tol = 1.0e-10;
        for (int t = 0; t < maxIter; t++) {
            final double[] next = new double[n];
            for (int i = 0; i < n; i++) {
                double s = lambdaExt[i];
                final String to = services.get(i).getName();
                for (int j = 0; j < n; j++) {
                    final String from = services.get(j).getName();
                    s += graph.prob(from, to) * lambda[j];
                }
                next[i] = s;
            }
            double diff = 0.0;
            for (int i = 0; i < n; i++) {
                diff = Math.max(diff, Math.abs(next[i] - lambda[i]));
            }
            System.arraycopy(next, 0, lambda, 0, n);
            if (diff < tol) {
                break;
            }
        }
        return lambda;
    }

    /**
     * Expected response time of the M/G/1 queue backing {@code profile}
     * under allocation {@code r} and arrival rate {@code lambda}.
     * Returns {@link Double#POSITIVE_INFINITY} when the queue is unstable.
     */
    public static double latency(final MicroserviceProfile profile, final ResourceVector r, final double lambda) {
        final double mu = capacity(profile, r);
        if (mu <= lambda) {
            return Double.POSITIVE_INFINITY;
        }
        return profile.getNetworkDelay() + 1.0 / (mu - lambda);
    }

    /**
     * SLA-elastic per-request revenue {@code V_i(T_i) = ν_i [1 - (T/T_SLA)^φ]^+}.
     */
    public static double revenue(final MicroserviceProfile profile, final double t) {
        if (Double.isInfinite(t) || Double.isNaN(t)) {
            return 0.0;
        }
        final double frac = t / profile.getSlaThreshold();
        final double penalty = Math.pow(frac, profile.getSlaElasticity());
        return profile.getMaxRevenue() * Math.max(0.0, 1.0 - penalty);
    }

    /**
     * Per-player utility
     * {@code U_i = V_i(T_i) λ_i  -  π · R_i  -  ½ ρ_i ‖R_i‖²}
     * (eq. 5 of the paper). Returns a strictly negative value if the
     * stability constraint is violated.
     */
    public static double utility(final MicroserviceProfile profile, final ResourceVector r,
                                 final double lambda, final ResourcePool pool) {
        final double t = latency(profile, r, lambda);
        final double v = revenue(profile, t);
        final double throughputRevenue = v * lambda;
        final double resourceCost = pool.getPrice().dot(r);
        final double regularizer = 0.5 * profile.getRegularizer() * r.squaredNorm();
        return throughputRevenue - resourceCost - regularizer;
    }

    /**
     * @return the social welfare {@code Σ_i U_i} of the joint allocation.
     */
    public static double socialWelfare(final List<MicroserviceProfile> services,
                                        final ResourceVector[] allocation,
                                        final double[] lambdas,
                                        final ResourcePool pool) {
        double s = 0.0;
        for (int i = 0; i < services.size(); i++) {
            s += utility(services.get(i), allocation[i], lambdas[i], pool);
        }
        return s;
    }

    /**
     * @return {@code true} if the joint allocation respects the shared
     * capacity constraint (eq. 6) up to a small tolerance.
     */
    public static boolean isFeasible(final ResourceVector[] allocation, final ResourcePool pool) {
        final double[] sums = new double[ResourceVector.DIMENSIONS];
        for (final var r : allocation) {
            for (int k = 0; k < ResourceVector.DIMENSIONS; k++) {
                sums[k] += r.get(k);
            }
        }
        for (int k = 0; k < ResourceVector.DIMENSIONS; k++) {
            if (sums[k] > pool.getCapacity().get(k) + 1e-6) {
                return false;
            }
        }
        return true;
    }
}
