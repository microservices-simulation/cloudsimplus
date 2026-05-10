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
package org.cloudsimplus.services.policy.scaling;

import org.cloudsimplus.services.Service;

/**
 * Service-level scaling policy. The broker calls
 * {@link #needScaling(Service)} on every registered policy at each
 * {@link org.cloudsimplus.services.ServiceBrokerSimple#getServiceSchedulingInterval()
 * scheduling tick}; if it returns {@code true}, {@link #scale(Service)} is
 * invoked to perform the actual scaling action.
 *
 * <p>The default implementations are
 * {@link HorizontalServiceScalingPolicy} (replica count) and
 * {@link VerticalServiceScalingPolicy} (per-replica resource size).</p>
 *
 * @since CloudSim Plus 9.0.0
 */
public interface ServiceScalingPolicy {
    /** Direction of a scaling event. */
    enum Direction { UP, DOWN }

    /** Null Object placeholder. */
    ServiceScalingPolicy NULL = new ServiceScalingPolicy() {
        @Override public boolean needScaling(final Service service) { return false; }
        @Override public void scale(final Service service) { /* no-op */ }
    };

    /**
     * @return {@code true} if {@code service} has crossed a threshold and a
     *         {@link #scale(Service) scaling action} should be performed.
     */
    boolean needScaling(Service service);

    /** Performs the scaling action on {@code service}. */
    void scale(Service service);
}
