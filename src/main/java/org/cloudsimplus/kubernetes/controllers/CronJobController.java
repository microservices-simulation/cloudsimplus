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
import org.cloudsimplus.kubernetes.Namespace;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * CronJob controller — fires a {@link JobController} every
 * {@link #getIntervalSeconds()} simulated seconds. Mirrors a simplified subset
 * of Kubernetes' CronJob: a fixed-period schedule rather than full cron syntax,
 * since a discrete-event simulator doesn't need wall-clock cron semantics.
 *
 * <p>Each fire instantiates a fresh, independent {@link JobController} via
 * {@link #getJobFactory() jobFactory} and registers it with the broker. Past
 * jobs remain registered for inspection but are not retried.</p>
 *
 * @since CloudSim Plus 9.0.0
 */
@Getter @Setter @Accessors(chain = true)
public class CronJobController implements Controller {

    private static final Logger LOG = LoggerFactory.getLogger(CronJobController.class.getSimpleName());

    /** Factory of fresh Job controllers; called once per fire. */
    public interface JobFactory {
        JobController create(long uid, int firingIndex);
    }

    private final long uid;
    private final String name;
    private final Namespace namespace;
    private final JobFactory jobFactory;

    /** Seconds between consecutive Job firings. */
    private double intervalSeconds = 60.0;

    /** Optional initial delay before the first fire. */
    private double startDelaySeconds;

    private int fired;
    private double lastFiredAt = -1;
    private ControllerManager manager;

    public CronJobController(final long uid, final String name, final Namespace namespace, final JobFactory jobFactory) {
        this.uid = uid;
        this.name = name;
        this.namespace = namespace;
        this.jobFactory = jobFactory;
    }

    @Override
    public String getKind() {
        return "CronJob";
    }

    @Override
    public void reconcile() {
        final double now = manager.broker().getSimulation().clock();
        if (lastFiredAt < 0) {
            if (now < startDelaySeconds) {
                return;
            }
        } else if (now - lastFiredAt < intervalSeconds) {
            return;
        }
        fireJob();
        lastFiredAt = now;
    }

    private void fireJob() {
        final long jobUid = manager.allocateUid();
        final var job = jobFactory.create(jobUid, fired++);
        manager.register(job);
        LOG.info("{}: CronJob '{}': firing job '{}' (#{})",
            manager.broker().getSimulation().clockStr(), name, job.getName(), fired - 1);
    }
}
