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
package org.cloudsimplus.services;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import lombok.Getter;
import lombok.NonNull;
import org.cloudsimplus.brokers.DatacenterBrokerSimple;
import org.cloudsimplus.cloudlets.Cloudlet;
import org.cloudsimplus.cloudlets.CloudletSimple;
import org.cloudsimplus.core.CloudSimPlus;
import org.cloudsimplus.core.events.SimEvent;
import org.cloudsimplus.services.generator.RequestGenerator;
import org.cloudsimplus.services.reporting.ResourceUsageRecorder;
import org.cloudsimplus.utilizationmodels.UtilizationModelFull;
import org.cloudsimplus.vms.Vm;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Drives a tree of {@link ServiceCall}s to completion on top of the regular
 * cloudlet/VM machinery.
 *
 * <p>For each call in the graph, the broker generates up to two cloudlets:
 * a <i>pre</i>-cloudlet ({@link ServiceCall#getLengthBeforeCalls()} MI) that
 * runs on the call's target service before any child is invoked, and a
 * <i>post</i>-cloudlet ({@link ServiceCall#getLengthAfterCalls()} MI) that runs
 * after all children have returned. Cloudlets with length 0 are skipped.</p>
 *
 * <p>The chain itself is implemented through cloudlet finish-listeners: when a
 * pre-cloudlet finishes, the broker either fires the first child (or the
 * post-cloudlet, if there are no children); when a child completes, the broker
 * fires the next sibling; and when the post-cloudlet finishes, the broker
 * notifies the parent so it can advance.</p>
 *
 * <p>Inter-service network latency can be modelled per call via
 * {@link ServiceCall#setNetworkDelay(double)}, which is mapped onto the cloudlet's
 * {@link Cloudlet#getSubmissionDelay() submission delay}.</p>
 *
 * @since CloudSim Plus 9.0.0
 */
public class ServiceBrokerSimple extends DatacenterBrokerSimple implements ServiceBroker {
    private static final Logger LOG = LoggerFactory.getLogger(ServiceBrokerSimple.class.getSimpleName());

    private final List<Service> services = new ArrayList<>();
    private final List<ServiceRequest> requests = new ArrayList<>();
    private final List<ServiceRequest> pendingFireOnStart = new ArrayList<>();
    private long nextRequestId;

    // Tracking for calls details and DAG
    private final List<CallDetail> callDetails = new ArrayList<>();
    private final Map<String, Map<String, EdgeData>> dag = new LinkedHashMap<>();
    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();

    /** Cumulative path delay tracking, used for the critical-path latency calc. */
    private final Map<ServiceCall, Double> cumulativeDelay = new IdentityHashMap<>();

    /** Cloud-native extensions (steps 7+). */
    @Getter private RequestGenerator requestGenerator;
    @Getter private ServiceGraph serviceGraph;
    @Getter private ResourceUsageRecorder resourceUsageRecorder;
    @Getter private double requestInterval = 1.0;
    @Getter private double serviceSchedulingInterval = 10.0;

    /** Per-API request count accumulated since the last RPS sample. */
    private final Map<Api, Integer> rpsBucket = new HashMap<>();
    /** Total request count in the global bucket since the last RPS sample. */
    private int globalRpsBucket;
    /** Sampled global RPS history, written by the reporter. */
    @Getter private final List<Double> globalRpsHistory = new ArrayList<>();

    public ServiceBrokerSimple(final CloudSimPlus simulation) {
        super(simulation);
    }

    public ServiceBrokerSimple(final CloudSimPlus simulation, final String name) {
        super(simulation, name);
    }

    // -------------- ServiceBroker API --------------

    @Override
    public ServiceBroker addService(@NonNull final Service service) {
        if (service == Service.NULL || services.contains(service)) {
            return this;
        }
        if (service.getId() < 0) {
            service.setId(services.size());
        }
        services.add(service);
        return this;
    }

    @Override
    public List<Service> getServices() {
        return Collections.unmodifiableList(services);
    }

    @Override
    public ServiceBroker submitRequest(@NonNull final ServiceRequest request) {
        if (request.getId() < 0) {
            request.setId(nextRequestId++);
        }
        request.setSubmissionTime(getSimulation().clock());
        requests.add(request);

        // If the request was generated from an Api with a built service chain,
        // expand its placeholder root call into the full ServiceCall tree.
        if (request.getApi() != null && serviceGraph != null
            && !request.getApi().getServiceChain().isEmpty()) {
            expandCallTreeFromApi(request);
        }

        // Bookkeeping for RPS history (per-API + global).
        if (request.getApi() != null) {
            rpsBucket.merge(request.getApi(), 1, Integer::sum);
        }
        globalRpsBucket++;

        if (isStarted()) {
            scheduleRequestStart(request);
        } else {
            pendingFireOnStart.add(request);
        }
        return this;
    }

    /**
     * Configures the {@link RequestGenerator} that will be ticked every
     * {@link #getRequestInterval() requestInterval} seconds once the broker
     * starts. Pass {@code null} to disable automatic generation.
     */
    public ServiceBrokerSimple setRequestGenerator(final RequestGenerator gen) {
        this.requestGenerator = gen;
        return this;
    }

    /**
     * Configures the {@link ServiceGraph} used to expand requests' call trees
     * from their {@link Api#getServiceChain() service chains}.
     */
    public ServiceBrokerSimple setServiceGraph(final ServiceGraph graph) {
        this.serviceGraph = graph;
        return this;
    }

    /**
     * Configures the {@link ResourceUsageRecorder} that will be ticked every
     * {@link #getServiceSchedulingInterval() schedulingInterval} seconds.
     */
    public ServiceBrokerSimple setResourceUsageRecorder(final ResourceUsageRecorder rec) {
        this.resourceUsageRecorder = rec;
        return this;
    }

    /** Sets the request-generation cadence (seconds). Must be &gt; 0. */
    public ServiceBrokerSimple setRequestInterval(final double seconds) {
        if (seconds <= 0) {
            throw new IllegalArgumentException("requestInterval must be > 0");
        }
        this.requestInterval = seconds;
        return this;
    }

    /** Sets the service-scheduling cadence (seconds). Must be &gt; 0. */
    public ServiceBrokerSimple setServiceSchedulingInterval(final double seconds) {
        if (seconds <= 0) {
            throw new IllegalArgumentException("serviceSchedulingInterval must be > 0");
        }
        this.serviceSchedulingInterval = seconds;
        return this;
    }

    @Override
    public ServiceBroker submitRequests(final List<ServiceRequest> reqs) {
        reqs.forEach(this::submitRequest);
        return this;
    }

    @Override
    public List<ServiceRequest> getRequests() {
        return Collections.unmodifiableList(requests);
    }

    @Override
    public List<ServiceRequest> getFinishedRequests() {
        return requests.stream().filter(ServiceRequest::isFinished).toList();
    }

    @Override
    public ServiceRequestStatistics getStatistics() {
        return new ServiceRequestStatistics(this);
    }

    @Override
    public String getCallsDetails() {
        return gson.toJson(callDetails);
    }

    @Override
    public String getDAG() {
        // Convert dag to a more JSON-friendly structure
        Map<String, Map<String, Map<String, Object>>> dagJson = new LinkedHashMap<>();
        for (Map.Entry<String, Map<String, EdgeData>> fromEntry : dag.entrySet()) {
            String from = fromEntry.getKey();
            Map<String, Map<String, Object>> toMap = new LinkedHashMap<>();
            for (Map.Entry<String, EdgeData> toEntry : fromEntry.getValue().entrySet()) {
                String to = toEntry.getKey();
                EdgeData data = toEntry.getValue();
                Map<String, Object> edgeInfo = new LinkedHashMap<>();
                edgeInfo.put("callCount", data.count);
                edgeInfo.put("averageLatency", data.getAverageLatency());
                toMap.put(to, edgeInfo);
            }
            dagJson.put(from, toMap);
        }
        return gson.toJson(dagJson);
    }

    // -------------- Lifecycle hooks --------------

    @Override
    public void startInternal() {
        super.startInternal();
        // Pending requests get fired only after VMs are created. We register a one-shot listener
        // that drains the pending queue as soon as all submitted VMs are up.
        addOnVmsCreatedListener(info -> {
            drainPendingRequests();
            // Kick off the periodic generator/scheduling self-events once VMs exist.
            if (requestGenerator != null) {
                schedule(0.0, ServiceEventTags.REQUEST_GENERATE);
            }
            if (resourceUsageRecorder != null) {
                schedule(0.0, ServiceEventTags.SERVICE_SCHEDULE);
            }
        });
    }

    private void drainPendingRequests() {
        if (pendingFireOnStart.isEmpty()) {
            return;
        }
        final var snapshot = new ArrayList<>(pendingFireOnStart);
        pendingFireOnStart.clear();
        snapshot.forEach(this::scheduleRequestStart);
    }

    private void scheduleRequestStart(final ServiceRequest req) {
        // Apply the request-level submissionDelay (e.g. arrival time) plus any per-call
        // network delay on the root call. The root call is then fired exactly like any
        // other call, attached to itself as a synthetic parent-less node.
        fireCall(req.getRootCall(), null, req, req.getSubmissionDelay());
    }

    // -------------- Call execution engine --------------

    /**
     * Fires {@code call} as part of {@code request}, using {@code parent} as its parent
     * in the runtime call tree. {@code extraDelay} is added to whatever {@link ServiceCall#getNetworkDelay()}
     * already specifies (used for the root call's submission delay).
     */
    private void fireCall(final ServiceCall call, final ServiceCall parent,
                          final ServiceRequest request, final double extraDelay) {
        call.setParent(parent);
        call.setRequest(request);

        final Vm vm = call.getService().selectVm();
        if (vm == Vm.NULL) {
            LOG.warn("{}: {}: No VM available for service '{}' (request {}). Aborting call.",
                getSimulation().clockStr(), getName(), call.getService().getName(), request.getId());
            completeCall(call); // best-effort: mark as completed and unwind
            return;
        }
        call.setAssignedVm(vm);

        if (call.isRoot()) {
            request.setStartTime(getSimulation().clock() + extraDelay);
        }

        // Record call detail
        String callerService = parent != null ? parent.getService().getName() : "root";
        String calleeService = call.getService().getName();
        double handshakeDuration = call.getNetworkDelay();
        double simulationTime = getSimulation().clock();
        callDetails.add(new CallDetail(callerService, calleeService, handshakeDuration, simulationTime));

        runPrePhase(call, extraDelay);
    }

    private void runPrePhase(final ServiceCall call, final double extraDelay) {
        call.setState(ServiceCall.State.RUNNING_PRE);
        call.setStartTime(getSimulation().clock());

        final long len = call.getLengthBeforeCalls();
        final double delay = extraDelay + Math.max(call.getNetworkDelay(), 0);

        if (len <= 0 && delay <= 0) {
            // No pre-work and no delay: short-circuit straight to children/post.
            afterPrePhase(call);
            return;
        }

        if (len <= 0) {
            // Pure delay (network hop) with no compute: schedule a self-event.
            schedule(delay, ServiceEventTags.CALL_ADVANCE, new CallAdvance(call, Phase.AFTER_PRE));
            return;
        }

        final var pre = newCloudlet(call, len, call.getRequestBytes(), 1);
        if (delay > 0) {
            pre.setSubmissionDelay(delay);
        }
        call.setPreCloudlet(pre);
        pre.addOnFinishListener(info -> handleCloudletFinish(call, Phase.AFTER_PRE));
        submitCloudlet(pre);
    }

    private void runPostPhase(final ServiceCall call) {
        call.setState(ServiceCall.State.RUNNING_POST);
        final long len = call.getLengthAfterCalls();
        if (len <= 0) {
            completeCall(call);
            return;
        }

        final var post = newCloudlet(call, len, 1, call.getResponseBytes());
        call.setPostCloudlet(post);
        post.addOnFinishListener(info -> handleCloudletFinish(call, Phase.AFTER_POST));
        submitCloudlet(post);
    }

    /**
     * Called after the pre-cloudlet of {@code call} finishes.
     * Either dispatches the first child or jumps straight to the post-phase.
     */
    private void afterPrePhase(final ServiceCall call) {
        if (call.getChildren().isEmpty()) {
            runPostPhase(call);
        } else {
            call.setState(ServiceCall.State.WAITING_CHILD);
            call.setCurrentChildIndex(0);
            fireCall(call.getChildren().get(0), call, call.getRequest(), 0);
        }
    }

    /**
     * Called when a child of {@code parent} finishes. Advances to the next child
     * or runs the post-phase if there are no more.
     */
    private void onChildCompleted(final ServiceCall parent) {
        final int next = parent.getCurrentChildIndex() + 1;
        if (next < parent.getChildren().size()) {
            parent.setCurrentChildIndex(next);
            fireCall(parent.getChildren().get(next), parent, parent.getRequest(), 0);
        } else {
            runPostPhase(parent);
        }
    }

    private void completeCall(final ServiceCall call) {
        call.setState(ServiceCall.State.COMPLETED);
        call.setFinishTime(getSimulation().clock());

        // Update DAG if not root
        if (!call.isRoot()) {
            String from = call.getParent().getService().getName();
            String to = call.getService().getName();
            double latency = call.getFinishTime() - call.getStartTime();
            dag.computeIfAbsent(from, k -> new LinkedHashMap<>())
               .computeIfAbsent(to, k -> new EdgeData())
               .count++;
            dag.get(from).get(to).sumLatency += latency;
        }

        // Critical-path bookkeeping: cumulative delay from the root to this call.
        final double parentDelay = call.isRoot()
            ? 0.0
            : cumulativeDelay.getOrDefault(call.getParent(), 0.0);
        final double localElapsed = Math.max(0, call.getElapsedTime());
        final double cumulative = parentDelay + localElapsed;
        cumulativeDelay.put(call, cumulative);
        call.getRequest().recordNodeDelay(call.getService(), cumulative);

        if (call.isRoot()) {
            final var req = call.getRequest();
            // Critical path: max cumulative delay over the chain's sinks (or
            // root finish, whichever is greater). For the synchronous,
            // sequential-fanout default this naturally reduces to the root's
            // elapsed time; for parallel fanout it's the longest path.
            double finishTime = getSimulation().clock();
            if (req.getApi() != null && serviceGraph != null
                && !req.getApi().getServiceChain().isEmpty()) {
                final var sinks = serviceGraph.getSinks(req.getApi().getServiceChain());
                double maxPath = 0.0;
                for (final var sink : sinks) {
                    maxPath = Math.max(maxPath, req.getNodeDelay().getOrDefault(sink, 0.0));
                }
                if (maxPath > 0) {
                    finishTime = req.getSubmissionTime() + maxPath;
                }
            }
            req.setFinishTime(finishTime);
            LOG.info("{}: {}: Request {} (root service '{}') finished. Response time: {}s.",
                getSimulation().clockStr(), getName(), req.getId(),
                call.getService().getName(), formatTime(req.getResponseTime()));
            // Best-effort cleanup of the per-request entries to avoid unbounded growth.
            cumulativeDelay.keySet().removeIf(c -> c.getRequest() == req);
            return;
        }
        onChildCompleted(call.getParent());
    }

    /**
     * Centralised dispatcher invoked whenever a cloudlet generated by this broker finishes.
     */
    private void handleCloudletFinish(final ServiceCall call, final Phase phase) {
        switch (phase) {
            case AFTER_PRE  -> afterPrePhase(call);
            case AFTER_POST -> completeCall(call);
        }
    }

    // -------------- Process incoming self-events --------------

    @Override
    public void processEvent(final SimEvent evt) {
        if (evt.getTag() == ServiceEventTags.CALL_ADVANCE && evt.getData() instanceof CallAdvance ca) {
            handleCloudletFinish(ca.call(), ca.phase());
            return;
        }
        if (evt.getTag() == ServiceEventTags.REQUEST_GENERATE) {
            tickGenerator();
            return;
        }
        if (evt.getTag() == ServiceEventTags.SERVICE_SCHEDULE) {
            tickServiceSchedule();
            return;
        }
        super.processEvent(evt);
    }

    /**
     * Drives one tick of the registered {@link RequestGenerator} (if any) and
     * schedules the next tick. All requests produced this tick are immediately
     * submitted via {@link #submitRequest(ServiceRequest)}.
     */
    private void tickGenerator() {
        if (requestGenerator == null) {
            return;
        }
        final double clock = getSimulation().clock();
        final var batch = requestGenerator.generate(clock);
        for (final var req : batch) {
            submitRequest(req);
        }

        // Sample RPS into the global + per-API histories.
        globalRpsHistory.add(globalRpsBucket / requestInterval);
        globalRpsBucket = 0;
        for (final var entry : new HashMap<>(rpsBucket).entrySet()) {
            entry.getKey().recordRpsSample(entry.getValue(), (int) Math.max(1, requestInterval));
        }
        rpsBucket.clear();

        // Re-arm the next tick only while the generator still has work to do.
        if (clock + requestInterval <= requestGenerator.getTimeLimit()
            && requestGenerator.getTotalGenerated() < requestGenerator.getNumLimit()) {
            schedule(requestInterval, ServiceEventTags.REQUEST_GENERATE);
        }
    }

    /**
     * Drives one tick of the {@link ResourceUsageRecorder} (if any). Service
     * scaling/migration policy triggers will be plugged in step 8.
     */
    private void tickServiceSchedule() {
        if (resourceUsageRecorder != null) {
            resourceUsageRecorder.recordSample(getSimulation().clock());
        }
        // Re-arm only while the generator is still producing requests, or
        // there are pending requests that haven't finished yet. Otherwise the
        // simulation has nothing left to do and would loop forever on the
        // periodic self-event alone.
        final boolean genActive = requestGenerator != null
            && getSimulation().clock() + serviceSchedulingInterval
                <= requestGenerator.getTimeLimit()
            && requestGenerator.getTotalGenerated() < requestGenerator.getNumLimit();
        final boolean pending = requests.stream().anyMatch(r -> !r.isFinished());
        if (genActive || pending) {
            schedule(serviceSchedulingInterval, ServiceEventTags.SERVICE_SCHEDULE);
        }
    }

    /**
     * Expands the placeholder {@link ServiceRequest#getRootCall() root call} of
     * {@code request} into the full {@link ServiceCall} tree dictated by its
     * {@link Api#getServiceChain() service chain}.
     *
     * <p>Children are added in a breadth-first walk constrained to the chain
     * (so services outside the chain are pruned), and cycles are broken by
     * tracking visited services along each path. A leaf in the chain
     * contributes no children.</p>
     */
    private void expandCallTreeFromApi(final ServiceRequest request) {
        final var api = request.getApi();
        final var chain = api.getServiceChain();
        final var rootCall = request.getRootCall();

        // Pin the root call to the chain's source (first by topological order).
        // Root sources are services in the chain with no parent inside it.
        final var sources = serviceGraph.getSources(chain);
        if (sources.isEmpty()) {
            return; // ill-formed chain — leave the placeholder alone.
        }
        rootCall.setService(sources.getFirst());

        // Recursively attach children from the graph, restricted to chain
        // membership and visited-set deduplication.
        final long perCallLength = rootCall.getLengthBeforeCalls();
        attachChildren(rootCall, chain, new HashSet<>(Set.of(rootCall.getService())), perCallLength);
    }

    private void attachChildren(final ServiceCall parent,
                                final List<Service> chain,
                                final Set<Service> visited,
                                final long perCallLength) {
        for (final Service child : serviceGraph.getCalls(parent.getService())) {
            if (!chain.contains(child) || !visited.add(child)) {
                continue;
            }
            final var childCall = new ServiceCall(child, perCallLength);
            parent.addChild(childCall);
            attachChildren(childCall, chain, visited, perCallLength);
        }
    }

    // -------------- Helpers --------------

    private Cloudlet newCloudlet(final ServiceCall call, final long lengthMI,
                                 final long fileSize, final long outputSize) {
        final var c = new CloudletSimple(lengthMI, call.getPesNumber());
        c.setFileSize(Math.max(1, fileSize));
        c.setOutputSize(Math.max(1, outputSize));
        c.setUtilizationModelCpu(new UtilizationModelFull());
        c.setVm(call.getAssignedVm());
        return c;
    }

    private static String formatTime(final double seconds) {
        return seconds < 0 ? "n/a" : "%.4f".formatted(seconds);
    }

    private enum Phase { AFTER_PRE, AFTER_POST }

    private record CallAdvance(ServiceCall call, Phase phase) {}

    /**
     * Details of a service call for monitoring and analysis.
     */
    public record CallDetail(
        String callerService,
        String calleeService,
        double handshakeDuration,
        double simulationTime
    ) {}

    private static class EdgeData {
        int count = 0;
        double sumLatency = 0.0;

        double getAverageLatency() {
            return count == 0 ? 0.0 : sumLatency / count;
        }
    }
}
