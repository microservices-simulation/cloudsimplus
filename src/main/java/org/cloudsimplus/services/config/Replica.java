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
package org.cloudsimplus.services.config;

import org.cloudsimplus.vms.Vm;

/**
 * One concrete replica produced by the {@link ServiceRegistry}: a VM
 * sized per its {@link ReplicaSpec}, addressable by a stable
 * {@link #name() instance UID} (e.g. {@code "carts-0"}).
 *
 * @param name  the unique instance name (used as the UID by
 *              {@link org.cloudsimplus.services.reporting.ResourceUsageRecorder})
 * @param vm    the backing virtual machine
 * @param spec  the resource specification this replica was built from
 * @since CloudSim Plus 9.0.0
 */
public record Replica(String name, Vm vm, ReplicaSpec spec) {
}
