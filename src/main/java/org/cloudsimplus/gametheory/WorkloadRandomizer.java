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

import lombok.NonNull;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Stateless utility for generating stochastic workload parameters used by
 * the HHG-MS Layer 1 stage game.
 *
 * <p>The class exposes the small subset of probability distributions needed
 * by the demo:</p>
 * <ul>
 *   <li>{@link #nextExponential} — inter-arrival times of a Poisson process
 *       (the only memoryless continuous distribution);</li>
 *   <li>{@link #nextPoisson} — count of arrivals over a fixed window;</li>
 *   <li>{@link #nextGamma} — Marsaglia–Tsang sampler used as a building
 *       block for the rest;</li>
 *   <li>{@link #nextBeta} — bounded sample in {@code [0, 1]} from two
 *       Gamma deviates (X / (X + Y) trick);</li>
 *   <li>{@link #randomArrivalRate} — Gamma sample with target mean and
 *       coefficient of variation (the classic queueing-theory parameterisation);</li>
 *   <li>{@link #randomizeCallGraph} — perturbation of every edge weight
 *       {@code π_ji} of an existing {@link CallGraph} (Beta in {@code [0, 1]}
 *       and LogNormal for fan-out values {@code > 1}).</li>
 * </ul>
 *
 * <p>All routines accept an explicit {@link Random} so the caller controls
 * reproducibility through seeding.</p>
 *
 * @since CloudSim Plus 9.0.0
 */
public final class WorkloadRandomizer {

    private WorkloadRandomizer() {
        // utility class
    }

    /**
     * Inverse-CDF sample of {@code Exp(λ)}: {@code -ln(1-U) / λ}.
     * Mean = {@code 1/λ}.
     */
    public static double nextExponential(final double lambda, @NonNull final Random rng) {
        if (lambda <= 0.0) {
            return Double.POSITIVE_INFINITY;
        }
        final double u = rng.nextDouble();
        return -Math.log(1.0 - u) / lambda;
    }

    /**
     * Poisson({@code λ}) sample. Uses Knuth's method for {@code λ &lt; 30}
     * and the Gaussian approximation {@code N(λ, λ)} otherwise (the
     * approximation error stays below 1% in absolute count for {@code λ ≥ 30}).
     */
    public static int nextPoisson(final double lambda, @NonNull final Random rng) {
        if (lambda <= 0.0) {
            return 0;
        }
        if (lambda < 30.0) {
            final double L = Math.exp(-lambda);
            int k = 0;
            double p = 1.0;
            do {
                k++;
                p *= rng.nextDouble();
            } while (p > L);
            return k - 1;
        }
        final double g = lambda + Math.sqrt(lambda) * rng.nextGaussian();
        return Math.max(0, (int) Math.round(g));
    }

    /**
     * Gamma({@code shape, scale}) sample using Marsaglia &amp; Tsang (2000).
     * Mean = {@code shape · scale}, Variance = {@code shape · scale²}.
     */
    public static double nextGamma(final double shape, final double scale, @NonNull final Random rng) {
        if (shape <= 0.0 || scale <= 0.0) {
            throw new IllegalArgumentException("Gamma shape and scale must be > 0");
        }
        if (shape < 1.0) {
            // Boost: if X ~ Gamma(shape+1), then X · U^(1/shape) ~ Gamma(shape).
            return nextGamma(shape + 1.0, scale, rng) * Math.pow(rng.nextDouble(), 1.0 / shape);
        }
        final double d = shape - 1.0 / 3.0;
        final double c = 1.0 / Math.sqrt(9.0 * d);
        while (true) {
            double x;
            double v;
            do {
                x = rng.nextGaussian();
                v = 1.0 + c * x;
            } while (v <= 0.0);
            v = v * v * v;
            final double u = rng.nextDouble();
            if (u < 1.0 - 0.0331 * x * x * x * x) {
                return d * v * scale;
            }
            if (Math.log(u) < 0.5 * x * x + d * (1.0 - v + Math.log(v))) {
                return d * v * scale;
            }
        }
    }

    /**
     * Beta({@code a, b}) sample in {@code [0, 1]}.
     * Mean = {@code a / (a + b)}, concentration = {@code a + b}.
     */
    public static double nextBeta(final double a, final double b, @NonNull final Random rng) {
        if (a <= 0.0 || b <= 0.0) {
            throw new IllegalArgumentException("Beta a, b must be > 0");
        }
        final double x = nextGamma(a, 1.0, rng);
        final double y = nextGamma(b, 1.0, rng);
        return x / (x + y);
    }

    /**
     * Returns a Gamma-distributed arrival rate with the given mean and
     * coefficient of variation (CV = stdev / mean).
     *
     * <p>Mapping: {@code shape = 1/CV²}, {@code scale = mean · CV²}.
     * This parameterisation is convenient because the resulting samples
     * are non-negative, right-skewed, and reproduce mean and CV exactly.</p>
     */
    public static double randomArrivalRate(final double mean, final double cv, @NonNull final Random rng) {
        if (mean <= 0.0) {
            return 0.0;
        }
        if (cv <= 0.0) {
            return mean;
        }
        final double shape = 1.0 / (cv * cv);
        final double scale = mean * cv * cv;
        return nextGamma(shape, scale, rng);
    }

    /**
     * Returns a perturbed copy of {@code base} where every edge weight
     * {@code π_ji} is resampled.
     *
     * <p>Two regimes:</p>
     * <ul>
     *   <li>{@code π &lt;= 1.0} — sampled from {@code Beta(a, b)} with
     *       mean {@code clip(π, 0.05, 0.95)} and concentration
     *       {@code a + b = max(2, concentration)}. Higher concentration
     *       values produce samples closer to the original mean.</li>
     *   <li>{@code π &gt; 1.0} (explicit fan-out) — multiplicative
     *       LogNormal jitter with target mean {@code π} and coefficient
     *       of variation {@code cv}. Solving for the underlying normal
     *       parameters gives {@code σ² = ln(1 + cv²)} and
     *       {@code μ = ln(π) - σ²/2}.</li>
     * </ul>
     *
     * <p>Node ordering is preserved.</p>
     */
    public static CallGraph randomizeCallGraph(@NonNull final CallGraph base,
                                                final double concentration,
                                                final double cv,
                                                @NonNull final Random rng) {
        if (concentration <= 0.0) {
            throw new IllegalArgumentException("concentration must be > 0");
        }
        if (cv < 0.0) {
            throw new IllegalArgumentException("cv must be >= 0");
        }
        final var out = new CallGraph();
        for (final var node : base.nodes()) {
            out.addNode(node);
        }
        for (final var from : base.nodes()) {
            for (final var entry : base.outEdges(from).entrySet()) {
                final String to = entry.getKey();
                final double pi = entry.getValue();
                final double sampled;
                if (pi <= 1.0) {
                    final double mean = Math.min(0.95, Math.max(0.05, pi));
                    final double k = Math.max(2.0, concentration);
                    final double a = Math.max(1.0e-3, mean * k);
                    final double b = Math.max(1.0e-3, (1.0 - mean) * k);
                    sampled = nextBeta(a, b, rng);
                } else {
                    final double sigma2 = Math.log(1.0 + cv * cv);
                    final double mu = Math.log(pi) - 0.5 * sigma2;
                    sampled = Math.exp(mu + Math.sqrt(sigma2) * rng.nextGaussian());
                }
                out.addEdge(from, to, sampled);
            }
        }
        return out;
    }

    /**
     * Returns a copy of {@code services} with the named service's external
     * arrival rate replaced by {@code newLambdaExt}. All other parameters
     * (Cobb-Douglas exponents, kappa, SLA, ...) are kept intact.
     *
     * <p>If no service in the list matches {@code targetName}, an
     * {@link IllegalArgumentException} is raised.</p>
     */
    public static List<MicroserviceProfile> withLambdaExt(
            @NonNull final List<MicroserviceProfile> services,
            @NonNull final String targetName,
            final double newLambdaExt) {
        boolean found = false;
        final var out = new ArrayList<MicroserviceProfile>(services.size());
        for (final var p : services) {
            if (p.getName().equals(targetName)) {
                found = true;
                out.add(MicroserviceProfile.builder()
                    .name(p.getName())
                    .alpha(p.getAlpha())
                    .kappa(p.getKappa())
                    .lambdaExt(newLambdaExt)
                    .networkDelay(p.getNetworkDelay())
                    .maxRevenue(p.getMaxRevenue())
                    .slaThreshold(p.getSlaThreshold())
                    .slaElasticity(p.getSlaElasticity())
                    .regularizer(p.getRegularizer())
                    .build());
            } else {
                out.add(p);
            }
        }
        if (!found) {
            throw new IllegalArgumentException("No service named '" + targetName + "' in profile list");
        }
        return out;
    }
}
