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

import java.util.Arrays;

/**
 * An immutable 4-resource allocation vector
 * {@code R = (CPU, RAM, BW, Storage)}.
 *
 * <p>The four-component layout matches the resource set
 * {@code K = {CPU, RAM, BW, Storage}} of equation (6) in the HHG-MS paper.
 * Vector indices are stable across the entire game-theory subsystem and are
 * exposed through {@link #INDEX_CPU}, {@link #INDEX_RAM}, {@link #INDEX_BW},
 * {@link #INDEX_STORAGE} for readability.</p>
 *
 * @since CloudSim Plus 9.0.0
 */
public final class ResourceVector {
    /** Number of resource dimensions tracked by every vector. */
    public static final int DIMENSIONS = 4;

    public static final int INDEX_CPU     = 0;
    public static final int INDEX_RAM     = 1;
    public static final int INDEX_BW      = 2;
    public static final int INDEX_STORAGE = 3;

    /** Canonical resource names, indexed by {@code INDEX_*}. */
    public static final String[] NAMES = {"CPU", "RAM", "BW", "Storage"};

    private final double[] values;

    private ResourceVector(final double[] values) {
        this.values = values;
    }

    /**
     * Creates a resource vector from individual components.
     */
    public static ResourceVector of(final double cpu, final double ram, final double bw, final double storage) {
        return new ResourceVector(new double[] {cpu, ram, bw, storage});
    }

    /**
     * Creates a resource vector from an existing array (defensive copy).
     */
    public static ResourceVector of(final double[] components) {
        if (components.length != DIMENSIONS) {
            throw new IllegalArgumentException("Resource vector requires " + DIMENSIONS + " components");
        }
        return new ResourceVector(Arrays.copyOf(components, DIMENSIONS));
    }

    /**
     * Returns the all-zero vector.
     */
    public static ResourceVector zero() {
        return new ResourceVector(new double[DIMENSIONS]);
    }

    /**
     * Returns a vector filled with {@code v} on every dimension.
     */
    public static ResourceVector filled(final double v) {
        final var a = new double[DIMENSIONS];
        Arrays.fill(a, v);
        return new ResourceVector(a);
    }

    public double get(final int index) {
        return values[index];
    }

    public double cpu()     { return values[INDEX_CPU]; }
    public double ram()     { return values[INDEX_RAM]; }
    public double bw()      { return values[INDEX_BW]; }
    public double storage() { return values[INDEX_STORAGE]; }

    /**
     * Returns the underlying array (defensive copy).
     */
    public double[] toArray() {
        return Arrays.copyOf(values, DIMENSIONS);
    }

    /**
     * Component-wise sum.
     */
    public ResourceVector plus(final ResourceVector other) {
        final var out = new double[DIMENSIONS];
        for (int k = 0; k < DIMENSIONS; k++) {
            out[k] = values[k] + other.values[k];
        }
        return new ResourceVector(out);
    }

    /**
     * Component-wise difference.
     */
    public ResourceVector minus(final ResourceVector other) {
        final var out = new double[DIMENSIONS];
        for (int k = 0; k < DIMENSIONS; k++) {
            out[k] = values[k] - other.values[k];
        }
        return new ResourceVector(out);
    }

    /**
     * Scalar multiplication.
     */
    public ResourceVector scale(final double factor) {
        final var out = new double[DIMENSIONS];
        for (int k = 0; k < DIMENSIONS; k++) {
            out[k] = values[k] * factor;
        }
        return new ResourceVector(out);
    }

    /**
     * Component-wise clamp into {@code [lo, hi]}.
     */
    public ResourceVector clamp(final ResourceVector lo, final ResourceVector hi) {
        final var out = new double[DIMENSIONS];
        for (int k = 0; k < DIMENSIONS; k++) {
            out[k] = Math.max(lo.values[k], Math.min(hi.values[k], values[k]));
        }
        return new ResourceVector(out);
    }

    /**
     * Squared L2 norm.
     */
    public double squaredNorm() {
        double s = 0.0;
        for (int k = 0; k < DIMENSIONS; k++) {
            s += values[k] * values[k];
        }
        return s;
    }

    /**
     * Inner product with another vector.
     */
    public double dot(final ResourceVector other) {
        double s = 0.0;
        for (int k = 0; k < DIMENSIONS; k++) {
            s += values[k] * other.values[k];
        }
        return s;
    }

    /**
     * Maximum absolute deviation between two vectors (used by the
     * best-response convergence check).
     */
    public static double maxAbsDelta(final ResourceVector a, final ResourceVector b) {
        double m = 0.0;
        for (int k = 0; k < DIMENSIONS; k++) {
            m = Math.max(m, Math.abs(a.values[k] - b.values[k]));
        }
        return m;
    }

    @Override
    public String toString() {
        return "R[CPU=%.4f, RAM=%.4f, BW=%.4f, Storage=%.4f]"
            .formatted(values[0], values[1], values[2], values[3]);
    }
}
