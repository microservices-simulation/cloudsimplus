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

import lombok.Getter;
import lombok.NonNull;

/**
 * Game-theoretic profile of a single microservice player {@code M_i}.
 *
 * <p>Each field maps directly onto a symbol of the system model
 * (Section 2 of <i>PROPOSED_MODEL.tex</i>):</p>
 *
 * <ul>
 *   <li>{@code alpha = (a_i, b_i, d_i, e_i)} — Cobb-Douglas exponents
 *       (must be non-negative and sum to 1, see eq. (2));</li>
 *   <li>{@code kappa κ_i} — capacity scaling factor;</li>
 *   <li>{@code lambdaExt λ_i^ext} — external Poisson arrival rate;</li>
 *   <li>{@code networkDelay D_i} — fixed delay added to the M/G/1 response time;</li>
 *   <li>{@code maxRevenue ν_i} — maximum unit revenue;</li>
 *   <li>{@code slaThreshold T_i^SLA} — SLA contract threshold (seconds);</li>
 *   <li>{@code slaElasticity φ} — exponent controlling how sharply
 *       revenue collapses near the SLA;</li>
 *   <li>{@code regularizer ρ_i} — local quadratic regulariser that gives
 *       strict concavity to the per-player utility (cf. Prop. 1).</li>
 * </ul>
 *
 * @since CloudSim Plus 9.0.0
 */
@Getter
public final class MicroserviceProfile {
    private final String name;
    private final ResourceVector alpha;
    private final double kappa;
    private final double lambdaExt;
    private final double networkDelay;
    private final double maxRevenue;
    private final double slaThreshold;
    private final double slaElasticity;
    private final double regularizer;

    private MicroserviceProfile(final Builder b) {
        this.name = b.name;
        this.alpha = b.alpha;
        this.kappa = b.kappa;
        this.lambdaExt = b.lambdaExt;
        this.networkDelay = b.networkDelay;
        this.maxRevenue = b.maxRevenue;
        this.slaThreshold = b.slaThreshold;
        this.slaElasticity = b.slaElasticity;
        this.regularizer = b.regularizer;
        validate();
    }

    public static Builder builder() {
        return new Builder();
    }

    private void validate() {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("name must be set");
        }
        if (alpha == null) {
            throw new IllegalArgumentException("alpha must be set");
        }
        double sum = 0.0;
        for (int k = 0; k < ResourceVector.DIMENSIONS; k++) {
            final double a = alpha.get(k);
            if (a < 0) {
                throw new IllegalArgumentException("alpha components must be >= 0");
            }
            sum += a;
        }
        if (Math.abs(sum - 1.0) > 1e-9) {
            throw new IllegalArgumentException("alpha exponents must sum to 1, got " + sum);
        }
        if (kappa <= 0) {
            throw new IllegalArgumentException("kappa must be > 0");
        }
        if (lambdaExt < 0) {
            throw new IllegalArgumentException("lambdaExt must be >= 0");
        }
        if (slaThreshold <= 0) {
            throw new IllegalArgumentException("slaThreshold must be > 0");
        }
        if (slaElasticity < 1.0) {
            throw new IllegalArgumentException("slaElasticity (phi) must be >= 1 for utility concavity");
        }
        if (regularizer < 0) {
            throw new IllegalArgumentException("regularizer must be >= 0");
        }
    }

    @Override
    public String toString() {
        return "MicroserviceProfile[%s, alpha=%s, kappa=%.3f, lambdaExt=%.3f, T_sla=%.3fs]"
            .formatted(name, alpha, kappa, lambdaExt, slaThreshold);
    }

    /**
     * Mutable builder for {@link MicroserviceProfile}. Defaults are sensible
     * for a single-service scenario and only {@code name} and {@code alpha}
     * have no default.
     */
    public static final class Builder {
        private String name;
        private ResourceVector alpha;
        private double kappa = 1.0;
        private double lambdaExt = 0.0;
        private double networkDelay = 0.005;
        private double maxRevenue = 1.0;
        private double slaThreshold = 0.100;
        private double slaElasticity = 2.0;
        private double regularizer = 0.01;

        public Builder name(@NonNull final String v)         { this.name = v; return this; }
        public Builder alpha(@NonNull final ResourceVector v){ this.alpha = v; return this; }
        public Builder kappa(final double v)                 { this.kappa = v; return this; }
        public Builder lambdaExt(final double v)             { this.lambdaExt = v; return this; }
        public Builder networkDelay(final double v)          { this.networkDelay = v; return this; }
        public Builder maxRevenue(final double v)            { this.maxRevenue = v; return this; }
        public Builder slaThreshold(final double v)          { this.slaThreshold = v; return this; }
        public Builder slaElasticity(final double v)         { this.slaElasticity = v; return this; }
        public Builder regularizer(final double v)           { this.regularizer = v; return this; }

        public MicroserviceProfile build() {
            return new MicroserviceProfile(this);
        }
    }
}
