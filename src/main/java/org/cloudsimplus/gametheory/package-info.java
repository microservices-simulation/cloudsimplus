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
/**
 * Game-theoretic primitives for microservice resource scheduling.
 *
 * <p>The package implements the building blocks of the
 * <b>HHG-MS</b> framework — <i>Hierarchical Hybrid Game-theoretic model for
 * MicroServices</i> — described in the companion paper
 * <i>"A Hierarchical Hybrid Game-Theoretic Framework for Optimizing
 * Collaboration in Microservices Architectures"</i>.
 * </p>
 *
 * <p>Currently exposed:</p>
 * <ul>
 *   <li>{@link org.cloudsimplus.gametheory.MicroserviceProfile} — per-service
 *       profile (Cobb-Douglas exponents, SLA threshold, regulariser, ...);</li>
 *   <li>{@link org.cloudsimplus.gametheory.ResourcePool} — global capacities
 *       and unit prices for the four resources {@code (CPU, RAM, BW, Storage)};</li>
 *   <li>{@link org.cloudsimplus.gametheory.ResourceVector} — immutable
 *       4-resource allocation vector;</li>
 *   <li>{@link org.cloudsimplus.gametheory.CallGraph} — call DAG with edge
 *       call-probabilities {@code π_ji};</li>
 *   <li>{@link org.cloudsimplus.gametheory.UtilityModel} — pure-function math
 *       layer for capacity μ, latency T, revenue V, utility U and effective
 *       arrival rates λ.</li>
 * </ul>
 *
 * <p>Solvers are in {@link org.cloudsimplus.gametheory.solver}; high-level
 * orchestration entry points are in {@link org.cloudsimplus.gametheory.layers}.</p>
 *
 * @since CloudSim Plus 9.0.0
 */
package org.cloudsimplus.gametheory;
