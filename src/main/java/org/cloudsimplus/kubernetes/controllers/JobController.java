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
package org.cloudsimplus.kubernetes.controllers;

import lombok.Getter;
import lombok.Setter;
import lombok.experimental.Accessors;
import org.cloudsimplus.cloudlets.Cloudlet;
import org.cloudsimplus.kubernetes.KubernetesPod;
import org.cloudsimplus.kubernetes.LabelSet;
import org.cloudsimplus.kubernetes.Namespace;
import org.cloudsimplus.kubernetes.lifecycle.RestartPolicy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * Job controller — spawns pods (up to {@link #getParallelism()} at a time) and
 * counts successful completions until {@link #getCompletions()} is reached.
 * After that, the controller stops creating new pods.
 *
 * <p>{@link #isComplete()} reports terminal status; integrations
 * ({@link CronJobController}) poll it.</p>
 *
 * @since CloudSim Plus 9.0.0
 */
@Getter @Setter @Accessors(chain = true)
public class JobController implements Controller {

    private static final Logger LOG = LoggerFactory.getLogger(JobController.class.getSimpleName());

    private final long uid;
    private final String name;
    private final Namespace namespace;
    private final PodTemplate template;

    private int completions = 1;
    private int parallelism = 1;
    private int backoffLimit = 6;

    private int succeeded;
    private int failures;
    private int created;
    private int nextOrdinal;

    private final List<KubernetesPod> active = new ArrayList<>();
    private ControllerManager manager;

    public JobController(final long uid, final String name, final Namespace namespace, final PodTemplate template) {
        this.uid = uid;
        this.name = name;
        this.namespace = namespace;
        this.template = template;
    }

    @Override
    public String getKind() {
        return "Job";
    }

    /** @return {@code true} once the desired number of completions has been reached or the backoff limit exceeded. */
    public boolean isComplete() {
        return succeeded >= completions || failures > backoffLimit;
    }

    @Override
    public void onPodCreated(final KubernetesPod pod) {
        // When a pod is created, register a finish-listener on each of its
        // containers; aggregate to track success/failure of the whole pod.
        for (final var c : pod.getContainers()) {
            c.addOnFinishListener(info -> onContainerFinished(pod, info.getCloudlet()));
        }
    }

    @Override
    public void onPodLost(final KubernetesPod pod) {
        active.remove(pod);
    }

    @Override
    public void reconcile() {
        if (isComplete()) {
            return;
        }
        while (active.size() < parallelism && (created - failures) < completions) {
            spawn();
        }
    }

    private void spawn() {
        final var pod = template.create(nextOrdinal++);
        pod.setPodName(name + "-" + (nextOrdinal - 1));
        pod.setNamespace(namespace);
        pod.setLabels(pod.getLabels().merge(LabelSet.of()
            .with(LABEL_CONTROLLER_UID, Long.toString(uid))
            .with(LABEL_CONTROLLER_KIND, getKind())
            .build()));
        // Job pods don't restart; the controller handles retries via backoffLimit (matches K8s).
        pod.getContainers().forEach(c -> c.setRestartPolicy(RestartPolicy.NEVER));
        active.add(pod);
        created++;
        LOG.info("{}: Job '{}': starting attempt {} ({}/{} succeeded)",
            manager.broker().getSimulation().clockStr(), name, pod.getPodName(), succeeded, completions);
        manager.broker().submitPod(pod);
    }

    private void onContainerFinished(final KubernetesPod pod, final Cloudlet cloudlet) {
        // Use identity-based check; Vm subclasses' equals/compareTo aren't suitable for set membership.
        boolean isActive = false;
        for (final var p : active) {
            if (p == pod) { isActive = true; break; }
        }
        if (!isActive) {
            return;
        }
        final boolean failed = cloudlet.getStatus() == Cloudlet.Status.FAILED
                            || cloudlet.getStatus() == Cloudlet.Status.FAILED_RESOURCE_UNAVAILABLE;
        if (failed) {
            failures++;
        }
        // For simplicity treat the pod as done after the first container finishes
        // (matches single-container Job semantics, which is the common case).
        // Identity-based removal; can't trust List.remove(Object) given Vm's compareTo.
        active.removeIf(p -> p == pod);
        if (!failed) {
            succeeded++;
        }
        if (isComplete()) {
            LOG.info("{}: Job '{}': complete ({} succeeded, {} failed)",
                manager.broker().getSimulation().clockStr(), name, succeeded, failures);
        }
    }
}
