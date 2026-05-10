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
package org.cloudsimplus.services.generator;

import lombok.Getter;
import lombok.NonNull;
import org.cloudsimplus.services.Api;
import org.cloudsimplus.services.ServiceCall;
import org.cloudsimplus.services.ServiceRequest;
import org.cloudsimplus.services.ServiceSimple;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Generates {@link ServiceRequest}s on a fixed cadence, mirroring the
 * {@code core.Generator} from CloudNativeSim.
 *
 * <p>Two arrival regimes are supported:</p>
 * <ul>
 *     <li><b>Client-based</b> (default): clients are spawned at
 *         {@link #getSpawnRate()} per second up to {@link #getFinalClients()};
 *         once spawned, each client waits a uniformly random number of seconds
 *         in {@link #getWaitTimeSpan()} between successive requests.</li>
 *     <li><b>RPS-based</b>: a constant arrival rate of
 *         {@link #getFinalRps()} requests per second.</li>
 * </ul>
 *
 * <p>Each generated request is bound to one {@link Api}, picked via weighted
 * random sampling over the {@link Api#getWeight() API weights} (cumulative
 * weights, identical algorithm to the reference implementation).</p>
 *
 * <p>The generator is stateless about the simulation time — it expects the
 * broker to call {@link #generate(double)} on every tick (typically every
 * {@code requestInterval} = 1 s).</p>
 *
 * @since CloudSim Plus 9.0.0
 */
public class RequestGenerator {
    /** Generation regime: by client population or by constant arrival rate. */
    public enum Mode { BY_CLIENTS, BY_RPS }

    @Getter private Mode mode = Mode.BY_CLIENTS;

    @Getter private List<Api> apis = new ArrayList<>();

    @Getter private int finalClients = -1;
    @Getter private int currentClients;
    @Getter private int spawnRate;
    @Getter private int[] waitTimeSpan = {5, 15};

    @Getter private int finalRps = -1;

    @Getter private double timeLimit = Double.MAX_VALUE;
    @Getter private int numLimit = Integer.MAX_VALUE;

    @Getter private double meanLength = 10;
    @Getter private double stdDevLength = 0;

    @Getter private long nextRequestId;
    @Getter private double previousTime;
    @Getter private int totalGenerated;

    private final List<Integer> clientWaitingStatus = new ArrayList<>();
    private double[] cumulativeWeights;

    private Random random = new Random();

    /**
     * Configures client-based generation.
     *
     * @param finalClients maximum number of concurrent clients
     * @param spawnRate    new clients spawned per second until {@code finalClients}
     *                     is reached
     * @return this generator, to enable chaining
     */
    public RequestGenerator clientsMode(final int finalClients, final int spawnRate) {
        if (finalClients <= 0 || spawnRate <= 0) {
            throw new IllegalArgumentException("finalClients and spawnRate must be > 0");
        }
        this.mode = Mode.BY_CLIENTS;
        this.finalClients = finalClients;
        this.spawnRate = spawnRate;
        this.currentClients = 0;
        this.clientWaitingStatus.clear();
        return this;
    }

    /**
     * Configures RPS-based generation.
     *
     * @param finalRps the constant requests-per-second arrival rate
     * @return this generator, to enable chaining
     */
    public RequestGenerator rpsMode(final int finalRps) {
        if (finalRps <= 0) {
            throw new IllegalArgumentException("finalRps must be > 0");
        }
        this.mode = Mode.BY_RPS;
        this.finalRps = finalRps;
        return this;
    }

    /**
     * Sets the per-client inter-request wait time bounds (inclusive lower /
     * exclusive upper, in seconds). Used in {@link Mode#BY_CLIENTS} only.
     */
    public RequestGenerator waitTimeSpan(final int min, final int max) {
        if (min < 0 || max <= min) {
            throw new IllegalArgumentException("waitTimeSpan must satisfy 0 <= min < max");
        }
        this.waitTimeSpan = new int[]{min, max};
        return this;
    }

    /** Caps the total simulation time during which requests can be generated. */
    public RequestGenerator timeLimit(final double seconds) {
        if (seconds <= 0) {
            throw new IllegalArgumentException("timeLimit must be > 0");
        }
        this.timeLimit = seconds;
        return this;
    }

    /** Caps the total number of requests generated by this generator. */
    public RequestGenerator numLimit(final int count) {
        if (count <= 0) {
            throw new IllegalArgumentException("numLimit must be > 0");
        }
        this.numLimit = count;
        return this;
    }

    /**
     * Configures the per-call cloudlet length distribution (Gaussian, in MI).
     */
    public RequestGenerator length(final double mean, final double stdDev) {
        if (mean <= 0 || stdDev < 0) {
            throw new IllegalArgumentException("mean must be > 0, stdDev >= 0");
        }
        this.meanLength = mean;
        this.stdDevLength = stdDev;
        return this;
    }

    /**
     * Registers the APIs this generator may sample from. Calling this resets
     * the cumulative-weights cache.
     */
    public RequestGenerator apis(@NonNull final List<Api> apis) {
        this.apis = new ArrayList<>(apis);
        recomputeCumulativeWeights();
        return this;
    }

    /** Replaces the random number generator (handy for deterministic tests). */
    public RequestGenerator random(@NonNull final Random random) {
        this.random = random;
        return this;
    }

    /**
     * Returns the next cloudlet length (in MI), drawn from a truncated Gaussian
     * (resampled until positive).
     */
    public long generateCloudletLength() {
        long length;
        do {
            length = (long) Math.round(meanLength + random.nextGaussian() * stdDevLength);
        } while (length <= 0);
        return length;
    }

    /**
     * Picks an API by weighted random sampling.
     *
     * @return one of the registered {@link Api}s, never {@code null}
     * @throws IllegalStateException if no APIs are registered
     */
    public Api pickRandomApi() {
        if (apis.isEmpty() || cumulativeWeights == null) {
            throw new IllegalStateException("No APIs registered. Call apis(...) first.");
        }
        final double rnd = random.nextDouble() * cumulativeWeights[cumulativeWeights.length - 1];
        for (int i = 0; i < cumulativeWeights.length; i++) {
            if (rnd < cumulativeWeights[i]) {
                return apis.get(i);
            }
        }
        return apis.get(apis.size() - 1);
    }

    /**
     * Drives one tick of the generator. Should be called on a fixed
     * {@code requestInterval} cadence (default 1 s).
     *
     * <p>Returns a list of newly generated, unstarted {@link ServiceRequest}s,
     * each pre-populated with a placeholder {@link ServiceCall} on the first
     * service of its API's chain. The broker is expected to expand the call
     * tree from {@link Api#getServiceChain()} before firing the request.</p>
     *
     * @param clock the current simulation time in seconds
     * @return the (possibly empty) list of new requests for this tick
     */
    public List<ServiceRequest> generate(final double clock) {
        final List<ServiceRequest> out = new ArrayList<>();
        if (clock > timeLimit || totalGenerated >= numLimit) {
            previousTime = clock;
            return out;
        }
        switch (mode) {
            case BY_CLIENTS -> generateByClients(clock, out);
            case BY_RPS     -> generateByRps(clock, out);
        }
        previousTime = clock;
        return out;
    }

    private void generateByClients(final double clock, final List<ServiceRequest> out) {
        if (finalClients <= 0) {
            throw new IllegalStateException("finalClients must be configured (call clientsMode)");
        }
        final int gap = (int) Math.max(0, clock - previousTime);

        // Spawn new clients up to the cap.
        if (currentClients < finalClients) {
            int newClients = spawnRate * Math.max(1, gap);
            newClients = Math.min(newClients, finalClients - currentClients);
            for (int i = 0; i < newClients; i++) {
                clientWaitingStatus.add(0);
            }
            currentClients += newClients;
        }

        for (int i = 0; i < clientWaitingStatus.size(); i++) {
            final int wait = clientWaitingStatus.get(i);
            if (wait - gap <= 0) {
                if (totalGenerated >= numLimit) {
                    break;
                }
                out.add(buildRequest(clock));
                totalGenerated++;
                final int span = waitTimeSpan[1] - waitTimeSpan[0];
                final int next = waitTimeSpan[0] + (span > 0 ? random.nextInt(span) : 0);
                clientWaitingStatus.set(i, next);
            } else {
                clientWaitingStatus.set(i, wait - gap);
            }
        }
    }

    private void generateByRps(final double clock, final List<ServiceRequest> out) {
        if (finalRps <= 0) {
            throw new IllegalStateException("finalRps must be configured (call rpsMode)");
        }
        final double gap = clock - previousTime;
        final int n = (int) Math.round(gap * finalRps);
        for (int i = 0; i < n; i++) {
            if (totalGenerated >= numLimit) {
                break;
            }
            out.add(buildRequest(clock));
            totalGenerated++;
        }
    }

    private ServiceRequest buildRequest(final double clock) {
        final var api = pickRandomApi();
        // The broker expands the full ServiceCall tree from api.getServiceChain().
        // We attach a placeholder leaf call on the chain's first service, or
        // a NULL service if the chain has not been built yet.
        final var firstService = api.getServiceChain().isEmpty()
            ? new ServiceSimple(api.getName())
            : api.getServiceChain().getFirst();
        final var rootCall = new ServiceCall(firstService, generateCloudletLength());
        final var req = new ServiceRequest(nextRequestId++, rootCall);
        req.setApi(api);
        req.setSubmissionTime(clock);
        api.addRequest(req);
        return req;
    }

    private void recomputeCumulativeWeights() {
        cumulativeWeights = new double[apis.size()];
        double sum = 0;
        for (int i = 0; i < apis.size(); i++) {
            sum += apis.get(i).getWeight();
            cumulativeWeights[i] = sum;
        }
    }
}
