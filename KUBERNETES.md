# Kubernetes Cluster Simulation in CloudSim Plus

A first-class Kubernetes simulation layer built on top of CloudSim Plus. Express
your scenarios in K8s terms — Pods, Containers, Nodes, Namespaces, Services,
Deployments, ReplicaSets, StatefulSets, DaemonSets, Jobs, CronJobs — with
labels, selectors, taints, tolerations, NodeAffinity, PodAffinity, restart
policies, liveness/readiness probes, init containers, HPA, and Cluster
Autoscaler. The existing CloudSim Plus simulation engine, scheduler, and
resource model power it underneath.

This document inventories everything that was added and shows how to use it.

---

## Table of contents

- [Mental model: how K8s maps onto CloudSim Plus](#mental-model)
- [Phase 1 — object model + scheduler](#phase-1)
- [Phase 2 — controllers, kubelet, autoscaling, pod affinity](#phase-2)
- [How to use it (worked example)](#how-to-use-it)
- [File-by-file inventory](#file-by-file-inventory)
- [Test coverage](#test-coverage)
- [Out of scope (future work)](#out-of-scope)

---

## Mental model

Kubernetes objects map onto CloudSim Plus primitives without any duplication of
the existing simulation engine:

| Kubernetes object | CloudSim Plus realization |
|---|---|
| **Node** | `KubernetesNode` extends `TopologyAwareHost` |
| **Pod** | `KubernetesPod` extends `VmSimple` |
| **Container** | `KubernetesContainer` extends `CloudletSimple` |
| **Namespace** | `Namespace` (lightweight value class) |
| **Labels / Selectors** | `LabelSet` + `LabelSelector` (with `In`/`NotIn`/`Exists`/`DoesNotExist`) |
| **Taints / Tolerations** | `Taint` (NoSchedule / PreferNoSchedule / NoExecute) + `Toleration` |
| **NodeAffinity** | `NodeAffinity` — required + preferred selectors over node labels |
| **PodAffinity / PodAntiAffinity** | `PodAffinity` — same/different topology bucket (hostname / zone / region) |
| **Service** | `KubernetesService` extends `ServiceSimple` (selector-driven endpoints) |
| **kube-scheduler** | `KubernetesScheduler` extends `VmAllocationPolicyTopologyAware` (filter + score, layered on existing rack/AZ/region/cost/latency policies) |
| **Cluster control plane** | `KubernetesClusterBroker` extends `ServiceBrokerSimple` |
| **kubelet** | `Kubelet` (broker-resident, container submission + probes + restartPolicy) |
| **ReplicaSet / Deployment / StatefulSet / DaemonSet / Job / CronJob** | `*Controller` classes implementing `Controller` |
| **HPA** | `HorizontalPodAutoscaler` (reuses `Tick` + utilization sampling) |
| **Cluster Autoscaler** | `ClusterAutoscaler` (provisions from a `NodePool` on unschedulable pods) |
| **PodCondition / PodPhase** | enums updated by the `Kubelet` |
| **restartPolicy** | `RestartPolicy` enum on each container (Always / OnFailure / Never) |
| **Liveness / Readiness probe** | `LivenessProbe` / `ReadinessProbe` evaluated periodically by the kubelet |

**Sealed-interface compliance** is preserved throughout: every entity extends
an existing concrete subclass (`VmSimple`, `HostSimple` via `TopologyAwareHost`,
`CloudletSimple`, `DatacenterBrokerSimple` via `ServiceBrokerSimple`,
`ServiceSimple`) — never a sealed interface directly.

**Architecture** (from the v1 plan and applied throughout v2): the K8s control
plane is **broker-resident reconciliation loops**, not a separate `SimEntity`.
A periodic *controller tick* (default 1.0 s; configurable via
`broker.setControllerTickIntervalSeconds(...)`) drives every loop —
controllers, kubelet probes, autoscalers — through a shared `Tick` interface.
Controllers are loosely coupled to their pods via owner-reference labels
(`cloudsimplus.kubernetes/controller-uid` and `controller-kind`), mirroring
real Kubernetes.

---

## Phase 1

The base object model and scheduler. No CloudSim Plus modifications were
required beyond opening up two helper methods of `VmAllocationPolicyTopologyAware`
(`passesStrictConstraints` / `score`) from `private` to `protected` so the
K8s scheduler can compose with `super.…(…)`.

**Topology and metadata**

- [`Resources`](src/main/java/org/cloudsimplus/kubernetes/Resources.java) — record `(milliCpu, memMiB)` plus parsers for K8s strings (`"500m"`, `"256Mi"`, `"1Gi"`) and a configurable millicores ↔ MIPS converter (`DEFAULT_MIPS_PER_CORE = 1000`).
- [`LabelSet`](src/main/java/org/cloudsimplus/kubernetes/LabelSet.java), [`LabelSelector`](src/main/java/org/cloudsimplus/kubernetes/LabelSelector.java) — immutable label maps with full `matchLabels` + `matchExpressions` support.
- [`Taint`](src/main/java/org/cloudsimplus/kubernetes/Taint.java), [`Toleration`](src/main/java/org/cloudsimplus/kubernetes/Toleration.java), [`NodeAffinity`](src/main/java/org/cloudsimplus/kubernetes/NodeAffinity.java).
- [`Namespace`](src/main/java/org/cloudsimplus/kubernetes/Namespace.java) — name + labels, with `DEFAULT` and `KUBE_SYSTEM` constants.

**Cluster entities**

- [`KubernetesNode`](src/main/java/org/cloudsimplus/kubernetes/KubernetesNode.java) — adds `nodeName`, `labels`, `taints`, `schedulable` on top of `TopologyAwareHost` (rack/AZ/region/cost/latency inherited).
- [`KubernetesContainer`](src/main/java/org/cloudsimplus/kubernetes/KubernetesContainer.java) — adds `containerName`, `image`, `requests` / `limits` (Resources). PE count derived from CPU request (ceil to whole cores, floor at 1).
- [`KubernetesPod`](src/main/java/org/cloudsimplus/kubernetes/KubernetesPod.java) — adds `podName`, `namespace`, `labels`, `containers`, `nodeSelector`, `nodeAffinity`, `tolerations`. Pod compute capacity is sized as `mipsPerCore` MIPS per PE; total PEs = sum of container PE counts; RAM = sum of container `limits.memMiB` (or `requests` when limits unset).
- [`KubernetesService`](src/main/java/org/cloudsimplus/kubernetes/KubernetesService.java) — selector-driven dynamic endpoints. Backing pods are recomputed on every routing call, so newly-created pods (e.g. from a ReplicaSet scale-up) flow into the endpoint set without any manual registration.

**Scheduler & broker**

- [`KubernetesScheduler`](src/main/java/org/cloudsimplus/kubernetes/scheduler/KubernetesScheduler.java) — strict filters: nodeSelector, NodeAffinity required, taint covers, schedulable flag, PodAffinity required (Phase 2). Score: NodeAffinity preferred bonus, PreferNoSchedule penalty, PodAffinity preferred contributions. Composes cleanly with the parent's cost/latency/spread/rack-anti-affinity scoring.
- [`KubernetesClusterBroker`](src/main/java/org/cloudsimplus/kubernetes/KubernetesClusterBroker.java) — extends `ServiceBrokerSimple` so the call-graph engine (`ServiceCall` / `ServiceRequest`) is inherited. Manages pods, services, namespaces, controllers, the kubelet, and a configurable controller tick.

**Builders**

- [`PodBuilder`](src/main/java/org/cloudsimplus/kubernetes/builders/PodBuilder.java), [`ContainerBuilder`](src/main/java/org/cloudsimplus/kubernetes/builders/ContainerBuilder.java), [`NodeBuilder`](src/main/java/org/cloudsimplus/kubernetes/builders/NodeBuilder.java) — fluent constructors that accept K8s-style strings.

---

## Phase 2

Reconciliation loops for declarative pod management, kubelet behaviors,
autoscaling, and pod-level affinity.

### Tick framework + controller plumbing

- [`Tick`](src/main/java/org/cloudsimplus/kubernetes/lifecycle/Tick.java) — `@FunctionalInterface tick(double clockTime)`. Anything that wants periodic action implements it and registers via `broker.registerTick(t)`.
- [`Controller`](src/main/java/org/cloudsimplus/kubernetes/controllers/Controller.java) — interface with `onPodCreated`, `onPodLost`, `reconcile`, plus the owner-reference label constants.
- [`ControllerManager`](src/main/java/org/cloudsimplus/kubernetes/controllers/ControllerManager.java) — broker-resident registry. Routes pod-lifecycle events to the controller identified by the pod's `controller-uid` label. Allocates fresh UIDs on demand. Implements `reconcileAll()` invoked on every controller tick.
- [`PodTemplate`](src/main/java/org/cloudsimplus/kubernetes/controllers/PodTemplate.java) — `IntFunction<KubernetesPod>` factory; controllers stamp owner-reference labels on every pod they create.

### Workload controllers (Deployment + ReplicaSet + StatefulSet + DaemonSet + Job + CronJob)

- [`ReplicaSetController`](src/main/java/org/cloudsimplus/kubernetes/controllers/ReplicaSetController.java) — maintains `desiredReplicas`. `reconcile()` spawns or destroys pods to converge. Pods are tracked by an internal ordinal-keyed map; scale-down picks the highest ordinal first.
- [`UpdateStrategy`](src/main/java/org/cloudsimplus/kubernetes/controllers/UpdateStrategy.java) — sealed interface with `RollingUpdate(maxSurge, maxUnavailable)` and `Recreate`.
- [`DeploymentController`](src/main/java/org/cloudsimplus/kubernetes/controllers/DeploymentController.java) — owns two child ReplicaSets (`newRs` / `oldRs`). `updateTemplate(t)` archives the current RS as old and starts the new RS at zero replicas. Each tick advances the rollout by one step under the `RollingUpdate` algorithm (or all-at-once under `Recreate`).
- [`StatefulSetController`](src/main/java/org/cloudsimplus/kubernetes/controllers/StatefulSetController.java) — like ReplicaSet but pods get stable, ordinal-suffixed names (`db-0`, `db-1`, ...). Scale-up fills the lowest free ordinal; scale-down removes the highest.
- [`DaemonSetController`](src/main/java/org/cloudsimplus/kubernetes/controllers/DaemonSetController.java) — ensures one pod per matching node. Reacts to node additions on subsequent ticks; pins each pod to its target node via a `kubernetes.io/hostname` selector.
- [`JobController`](src/main/java/org/cloudsimplus/kubernetes/controllers/JobController.java) — runs up to `parallelism` pods at a time until `completions` succeed. Forces `restartPolicy=Never` on its pods (Job manages retries via `backoffLimit`, matching real K8s).
- [`CronJobController`](src/main/java/org/cloudsimplus/kubernetes/controllers/CronJobController.java) — fires a fresh `JobController` every `intervalSeconds` simulated seconds (with optional initial delay).

### Kubelet behaviors

- [`Kubelet`](src/main/java/org/cloudsimplus/kubernetes/lifecycle/Kubelet.java) — broker-resident; called by the broker on each pod's host-allocation event. Responsibilities:
  - **Init container ordering**: submits init containers one at a time, chained by finish listeners. Main containers are submitted as a batch only after the last init container completes.
  - **restartPolicy**: on container exit, applies the policy (`ALWAYS`/`ON_FAILURE`/`NEVER`) by resetting and re-submitting the cloudlet.
  - **Probes**: evaluated on every controller tick. Liveness failure (after `failureThreshold` consecutive misses) cancels the cloudlet and restarts per `restartPolicy`. Readiness failures flip the pod's `READY` condition; `KubernetesService` automatically excludes non-Ready pods from endpoints.
- [`PodCondition`](src/main/java/org/cloudsimplus/kubernetes/lifecycle/PodCondition.java) — `POD_SCHEDULED`, `INITIALIZED`, `CONTAINERS_READY`, `READY`.
- [`PodPhase`](src/main/java/org/cloudsimplus/kubernetes/lifecycle/PodPhase.java) — `PENDING`, `RUNNING`, `SUCCEEDED`, `FAILED`, `UNKNOWN`.
- [`RestartPolicy`](src/main/java/org/cloudsimplus/kubernetes/lifecycle/RestartPolicy.java).
- [`Probe`](src/main/java/org/cloudsimplus/kubernetes/lifecycle/Probe.java), [`LivenessProbe`](src/main/java/org/cloudsimplus/kubernetes/lifecycle/LivenessProbe.java), [`ReadinessProbe`](src/main/java/org/cloudsimplus/kubernetes/lifecycle/ReadinessProbe.java) — `Predicate<KubernetesContainer>` plus K8s timing knobs (`initialDelaySeconds`, `periodSeconds`, `failureThreshold`, `successThreshold`).

### Autoscaling

- [`HorizontalPodAutoscaler`](src/main/java/org/cloudsimplus/kubernetes/autoscaling/HorizontalPodAutoscaler.java) — wraps a `ReplicaSetController` or `DeploymentController` via static `of(...)` factories. On each tick: averages CPU% over Ready pods and adjusts `desiredReplicas` toward `targetCpuUtilization`. Clamped to `[minReplicas, maxReplicas]` and gated by a configurable `cooldownSeconds`.
- [`NodePool`](src/main/java/org/cloudsimplus/kubernetes/autoscaling/NodePool.java) — `(name, Supplier<KubernetesNode> template, min, max)`.
- [`ClusterAutoscaler`](src/main/java/org/cloudsimplus/kubernetes/autoscaling/ClusterAutoscaler.java) — provisions new nodes from a `NodePool` template when pods are unschedulable, and decommissions empty nodes after `scaleDownAfterSeconds` of idle time (down to `pool.min`).

### Pod-to-pod affinity

- [`PodAffinity`](src/main/java/org/cloudsimplus/kubernetes/PodAffinity.java) — required + preferred rules, each pairing a `LabelSelector` with a `TopologyKey` (`HOSTNAME` / `ZONE` / `REGION`) and a direction (`affinity` / `antiAffinity`).
- [`KubernetesScheduler`](src/main/java/org/cloudsimplus/kubernetes/scheduler/KubernetesScheduler.java) — required rules feed into `passesStrictConstraints`; preferred rules contribute to the score. Placed pods are discovered live from each datacenter host's `vmList`, so the scheduler doesn't need a back-reference to the broker.

### Broker integration

[`KubernetesClusterBroker`](src/main/java/org/cloudsimplus/kubernetes/KubernetesClusterBroker.java) gained:
- `controllerManager`, `kubelet`, `controllerTickIntervalSeconds`
- `addController(Controller)`, `registerTick(Tick)`
- pod-lifecycle listeners that route events to the kubelet + controller manager (`onPodPlaced`, `onPodLost`)
- `getNodes()`, `placedPodsOnNode(node)`, `getDatacenters()` query helpers
- `processEvent` overload to handle the periodic `K8S_TICK_TAG` self-event and reschedule itself

---

## How to use it

### Minimal working example

```java
final var sim = new CloudSimPlus();

// 1. Define the cluster — three nodes in a single datacenter.
final var nodes = List.of(
    NodeBuilder.of("worker-1").pes(4, 1000).ram(8_192).rack("r1").build(),
    NodeBuilder.of("worker-2").pes(4, 1000).ram(8_192).rack("r2").build(),
    NodeBuilder.of("worker-3").pes(4, 1000).ram(8_192).rack("r3").build());
new DatacenterSimple(sim, nodes,
    new KubernetesScheduler(VmAllocationPolicyTopologyAware.Policy.COST_OPTIMIZED));

// 2. Stand up the cluster broker.
final var broker = new KubernetesClusterBroker(sim);

// 3. Declare a Deployment of 3 replicas with a rolling-update strategy.
final var template = new PodTemplate(ord -> PodBuilder.of("web-" + ord)
    .label("app", "web")
    .container(ContainerBuilder.of("nginx")
        .image("nginx:1.21")
        .cpu("500m").mem("256Mi")
        .length(50_000)        // simulated work, in MI
        .build())
    .build());

final var deployment = new DeploymentController(
    broker.getControllerManager().allocateUid(),
    "web",
    Namespace.DEFAULT,
    template,
    /* replicas */ 3
).setStrategy(UpdateStrategy.RollingUpdate.defaults());
broker.addController(deployment);

// 4. (Optional) Front it with a K8s Service.
broker.addService(new KubernetesService(
    "web", Namespace.DEFAULT, LabelSelector.matchLabel("app", "web")));

// 5. Run.
sim.terminateAt(60.0);
sim.start();
```

After the simulation, the deployment has produced 3 pods spread across the 3
nodes (the parent cost-optimized policy + the controller's tick reconcile both
cooperate).

### Adding HPA

```java
final var hpa = HorizontalPodAutoscaler.of(deployment, /* target */ 0.7)
    .setMinReplicas(2).setMaxReplicas(10);
broker.registerTick(hpa);
```

### Adding cluster autoscaling

```java
final var pool = new NodePool(
    "extra-worker",
    () -> NodeBuilder.of("extra-worker-" + System.nanoTime())
        .pes(4, 1000).ram(8_192).build(),
    /* min */ 0, /* max */ 5);
final var ca = new ClusterAutoscaler(broker, pool);
broker.registerTick(ca);
```

### Probes + restartPolicy + init containers

```java
ContainerBuilder.of("api")
    .image("api:1.4").cpu("500m").mem("256Mi").length(20_000)
    .restartPolicy(RestartPolicy.ON_FAILURE)
    .livenessProbe(new LivenessProbe(c -> c.getStatus() != Cloudlet.Status.FAILED)
        .setPeriodSeconds(10).setFailureThreshold(3))
    .readinessProbe(new ReadinessProbe(c -> c.getFinishedLengthSoFar() > 1000))
    .build();
```

```java
PodBuilder.of("web")
    .container(ContainerBuilder.of("init-db").length(2_000).asInitContainer().build())
    .container(ContainerBuilder.of("nginx").length(50_000).build())
    .build();
```

### Microservice call graphs (inherited from the existing services package)

`KubernetesClusterBroker` extends `ServiceBrokerSimple`, so `ServiceCall` /
`ServiceRequest` flows through K8s services unchanged:

```java
final var backend = new KubernetesService(
    "backend", Namespace.DEFAULT, LabelSelector.matchLabel("tier", "backend"));
broker.addService(backend);

broker.submitRequest(new ServiceRequest(0, new ServiceCall(backend, /* MI */ 5_000)));
```

---

## File-by-file inventory

```
src/main/java/org/cloudsimplus/kubernetes/
├── KubernetesClusterBroker.java           # broker (extends ServiceBrokerSimple)
├── KubernetesContainer.java               # extends CloudletSimple
├── KubernetesNode.java                    # extends TopologyAwareHost
├── KubernetesPod.java                     # extends VmSimple
├── KubernetesService.java                 # extends ServiceSimple (selector-driven)
├── LabelSelector.java                     # matchLabels + matchExpressions
├── LabelSet.java                          # immutable label map
├── Namespace.java                         # logical scope
├── NodeAffinity.java                      # required + preferred over node labels
├── PodAffinity.java                       # peer-pod affinity / anti-affinity
├── Resources.java                         # K8s-style resource record + parsers
├── Taint.java                             # NoSchedule / PreferNoSchedule / NoExecute
├── Toleration.java                        # Equal / Exists operators
│
├── builders/
│   ├── ContainerBuilder.java              # fluent KubernetesContainer
│   ├── NodeBuilder.java                   # fluent KubernetesNode
│   └── PodBuilder.java                    # fluent KubernetesPod
│
├── controllers/
│   ├── Controller.java                    # base interface + owner-ref label keys
│   ├── ControllerManager.java             # registry + event router
│   ├── PodTemplate.java                   # Supplier-based pod factory
│   ├── UpdateStrategy.java                # sealed RollingUpdate / Recreate
│   ├── ReplicaSetController.java
│   ├── DeploymentController.java
│   ├── StatefulSetController.java
│   ├── DaemonSetController.java
│   ├── JobController.java
│   └── CronJobController.java
│
├── lifecycle/
│   ├── Tick.java                          # @FunctionalInterface
│   ├── Kubelet.java                       # init ordering + probes + restartPolicy
│   ├── Probe.java                         # base + timing knobs
│   ├── LivenessProbe.java
│   ├── ReadinessProbe.java
│   ├── RestartPolicy.java                 # Always / OnFailure / Never
│   ├── PodCondition.java                  # PodScheduled / Initialized / ContainersReady / Ready
│   └── PodPhase.java                      # Pending / Running / Succeeded / Failed / Unknown
│
├── scheduler/
│   └── KubernetesScheduler.java           # extends VmAllocationPolicyTopologyAware
│
└── autoscaling/
    ├── HorizontalPodAutoscaler.java       # implements Tick
    ├── NodePool.java                      # template + min/max
    └── ClusterAutoscaler.java             # implements Tick
```

A single, minimal change was made to existing CloudSim Plus code:

- [`VmAllocationPolicyTopologyAware`](src/main/java/org/cloudsimplus/allocationpolicies/VmAllocationPolicyTopologyAware.java) — `passesStrictConstraints` and `score` were elevated from `private` to `protected` so `KubernetesScheduler` (in a different package) can compose with `super.…(…)`.

---

## Test coverage

**Unit tests** under `src/test/java/org/cloudsimplus/kubernetes/`:

| Test class | Tests | Covers |
|---|---|---|
| [`LabelSelectorTest`](src/test/java/org/cloudsimplus/kubernetes/LabelSelectorTest.java) | 7 | `matchLabels`, all match-expression operators, K8s missing-key semantics |
| [`TolerationTest`](src/test/java/org/cloudsimplus/kubernetes/TolerationTest.java) | 5 | `Equal` / `Exists` operators, effect scoping, `coversAll` over a node |
| [`NodeAffinityTest`](src/test/java/org/cloudsimplus/kubernetes/NodeAffinityTest.java) | 4 | required-OR, preferred-weight summing, weight-range validation |
| [`PodAffinityTest`](src/test/java/org/cloudsimplus/kubernetes/PodAffinityTest.java) | 5 | topology-bucket equality (HOSTNAME / ZONE / REGION), required + preferred mix, weight validation |
| [`ResourcesTest`](src/test/java/org/cloudsimplus/kubernetes/ResourcesTest.java) | 9 | parsing K8s CPU / memory specs, MIPS conversion, malformed-spec rejection |
| [`KubernetesServiceTest`](src/test/java/org/cloudsimplus/kubernetes/KubernetesServiceTest.java) | 5 | selector-matched endpoints, round-robin, namespace isolation, `Vm.NULL` on empty |

**Integration tests** under `src/test/java/org/cloudsimplus/integrationtests/`:

| Test class | Tests | Covers |
|---|---|---|
| [`KubernetesClusterTest`](src/test/java/org/cloudsimplus/integrationtests/KubernetesClusterTest.java) | 8 | nodeSelector, taint repulsion, toleration, cordon, preferred NodeAffinity tie-break, ServiceRequest call-graph routing through K8s Services, container-as-cloudlet submission, rack anti-affinity |
| [`KubernetesControllersTest`](src/test/java/org/cloudsimplus/integrationtests/KubernetesControllersTest.java) | 5 | ReplicaSet convergence, RS replacement on pod loss, Deployment initial rollout, rolling-update template change, init-container ordering + INITIALIZED condition |
| [`KubernetesAdvancedTest`](src/test/java/org/cloudsimplus/integrationtests/KubernetesAdvancedTest.java) | 5 | PodAntiAffinity HOSTNAME spread, StatefulSet ordinal-named pods, DaemonSet one-per-node, Job runs to completion, CronJob fires at interval |

**Total: 53 tests, all green.** Build passes both `mvn test` and
`mvn test -Pintegration-tests`. The only pre-existing failures
(`UtilizationModelPlanetLabTest`) are an unrelated Windows-path bug in the
existing test suite.

---

## Out of scope

Phase-3 backlog (additive on top of what's in place):

- VPA (Vertical Pod Autoscaler) — `VerticalVmScaling` already exists; only the recommendation loop needs writing
- NetworkPolicy, Ingress, NodePort / LoadBalancer service types
- Persistent volumes (PV / PVC), StatefulSet volume claim templates
- ConfigMaps, Secrets
- Admission controllers, mutating / validating webhooks
- Multi-cluster federation
- RBAC, ServiceAccounts
- Pod priority and preemption (lays naturally on the existing scheduler)
- Strict `OrderedReady` podManagementPolicy on StatefulSet
- Full cron expression parsing in `CronJobController` (currently fixed-period)
