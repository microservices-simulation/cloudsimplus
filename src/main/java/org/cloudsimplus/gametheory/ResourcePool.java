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
 * Platform-wide resource capacities and unit prices used by the HHG-MS
 * non-cooperative stage game.
 *
 * <p>The shared resource constraint of equation (6) in the paper is
 * {@code Σ_i R_i &lt;= C}, where {@code C} is {@link #getCapacity()}.
 * Per-unit resource prices are {@code π = (π_CPU, π_RAM, π_BW, π_Storage)}.</p>
 *
 * @since CloudSim Plus 9.0.0
 */
@Getter
public final class ResourcePool {
    /** Global capacities {@code C = (C_CPU, C_RAM, C_BW, C_Storage)}. */
    private final ResourceVector capacity;

    /** Per-unit prices {@code π = (π_CPU, π_RAM, π_BW, π_Storage)}. */
    private final ResourceVector price;

    public ResourcePool(@NonNull final ResourceVector capacity, @NonNull final ResourceVector price) {
        for (int k = 0; k < ResourceVector.DIMENSIONS; k++) {
            if (capacity.get(k) <= 0) {
                throw new IllegalArgumentException("Capacity must be > 0 on dimension " + ResourceVector.NAMES[k]);
            }
            if (price.get(k) < 0) {
                throw new IllegalArgumentException("Price must be >= 0 on dimension " + ResourceVector.NAMES[k]);
            }
        }
        this.capacity = capacity;
        this.price = price;
    }

    /**
     * Convenience overload accepting raw arrays.
     */
    public static ResourcePool of(final double[] capacity, final double[] price) {
        return new ResourcePool(ResourceVector.of(capacity), ResourceVector.of(price));
    }

    @Override
    public String toString() {
        return "ResourcePool[capacity=%s, price=%s]".formatted(capacity, price);
    }
}
