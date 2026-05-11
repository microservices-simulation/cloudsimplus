# Porting CloudNativeSim Concepts to CloudSim Plus

> **Audience:** Claude Code (and any contributor) who will implement, in this
> repository (`cloudsimplus/`), the cloud-native simulation features that
> CloudNativeSim built on top of vanilla CloudSim 3.0.
>
> **Source paper / project:** *CloudNativeSim: A Toolkit for Modeling and
> Simulation of Cloud-Native Applications* — Wu et al., 2025
> (`../CloudNativeSim/cloudnativeSim.pdf`).

---

## 0. Goal in one sentence

Re-implement, idiomatically inside CloudSim Plus, the **DAG-based microservice
call-chain simulation, dynamic request generation, file-based registration, QoS
metrics export, and pluggable allocation / scaling / migration policies** that
CloudNativeSim adds to CloudSim 3.0 — *without* duplicating things CloudSim
Plus already provides.

The end result must let a user write something analogous to the
`SockShopExample` (sockshop YAML/JSON service registration → simulation →
per-API statistics → CSV/JSON export) but using CloudSim Plus' modern,
sealed-interface architecture and the existing
`org.cloudsimplus.services` / `org.cloudsimplus.kubernetes` packages.

---

## 1. What already exists in CloudSim Plus and must be reused

Before adding anything, **reuse these existing packages**. Do not re-create
parallel hierarchies under different names.

| CloudNativeSim concept | Existing CloudSim Plus equivalent | Notes |
|---|---|---|
| `core.CloudNativeSim` (extends CloudSim) | `org.cloudsimplus.core.CloudSimPlus` | Already the simulation engine |
| `extend.NativeBroker` | `org.cloudsimplus.brokers.DatacenterBrokerSimple` and `org.cloudsimplus.services.ServiceBrokerSimple` | Extend the existing broker, do **not** create a new one from scratch |
| `extend.NativeVm` | `org.cloudsimplus.vms.Vm` / `VmSimple` | Already exists |
| `extend.NativePe`, `provisioner.*` | `org.cloudsimplus.resources.Pe`, `org.cloudsimplus.provisioners.*` | Already exists |
| `entity.Pod`, `entity.Container` | `org.cloudsimplus.kubernetes.KubernetesPod`, `KubernetesContainer` | Reuse the kubernetes package |
| `entity.ReplicaSet` | `org.cloudsimplus.kubernetes` (selectors, labels) + new replica-set abstraction | Augment, don't duplicate |
| `entity.Service` / `ServiceGraph` | `org.cloudsimplus.services.Service`, `ServiceCall`, `ServiceRequest`, `ServiceBrokerSimple` | These already model a synchronous DAG call tree — extend them, don't fork |
| `entity.RpcCloudlet` | **NOT NEEDED** — see §3 | CloudSim Plus' regular `Cloudlet` plus the pre/post split in `ServiceCall` already covers RPC-style staged work |
| `policy.allocation.ServiceAllocationPolicy*` | `org.cloudsimplus.allocationpolicies.VmAllocationPolicy*` | Implement service-level allocation as a thin layer on top |
| `policy.scaling.HorizontalScalingPolicy` / `VerticalScalingPolicy` | `org.cloudsimplus.autoscaling.HorizontalVmScalingSimple` / `VerticalVmScalingSimple` | Use existing autoscaling, just add **service-level** triggers |
| `policy.migration.InstanceMigrationPolicy*` | `org.cloudsimplus.allocationpolicies.migration.VmAllocationPolicyMigration*` | Already exists |
| `core.Generator` | New — see §4 | No equivalent yet |
| `core.Register` | New — see §5 | No equivalent yet |
| `core.Reporter` / `core.Exporter` | Partially: `ServiceRequestStatistics`. Needs extension — see §7 | |

**Rule of thumb:** if a class with the right responsibility already exists in
`org.cloudsimplus.*`, extend / compose with it. New classes go under a new
sub-package, e.g. `org.cloudsimplus.services.cloudnative` or directly in
`org.cloudsimplus.services` if they belong to the existing API.

---

## 2. Things from CloudNativeSim we deliberately do NOT port

Skip these. The stated reason is given.

- **`entity.RpcCloudlet`** and `policy.cloudletScheduler.NativeCloudletScheduler*`.
  The user has explicitly stated we don't need RpcCloudlet. CloudSim Plus
  already models a request's compute work via standard `Cloudlet`s, and the
  pre-call / post-call split is already first-class in
  `ServiceCall.lengthBeforeCalls` / `lengthAfterCalls`. We do not need the
  separate `RpcCloudlet` abstraction nor its custom `NativeCloudletScheduler`
  hierarchy — the existing `CloudletScheduler*` classes plus
  `ServiceBrokerSimple` already chain them through finish-listeners.
- **`core.CloudNativeSim` subclass of `CloudSim`** — `CloudSimPlus` is already
  the engine; do not subclass it.
- **`extend.CloudNativeSimTag`** custom int tags — CloudSim Plus uses typed
  events (`SimEvent` + `CloudSimTag` enum and `ServiceEventTags`). Use the
  existing tag enums, add new constants only if truly needed.
- **Grafana / web visualization assets** under `visualization/` — out of scope
  for the library port. We export the same metric files; rendering is left to
  the user.
- **`Tools.java` ad-hoc YAML / JSON readers** — replace with a clean Jackson
  ObjectMapper-based loader (Jackson + SnakeYAML are already viable transitive
  deps; if not, prefer Jackson with `jackson-dataformat-yaml`).

---

## 3. What we DO need to add

Below is the canonical mapping. For each item, **(a)** what to build, **(b)**
where it goes, **(c)** the CloudNativeSim source file to study, **(d)** the
key behavior to preserve.

### 3.1 Service DAG with API-aware chains

CloudSim Plus already has `ServiceCall` (a per-call node with children) and
`ServiceRequest` (the root entry point). What is missing relative to
CloudNativeSim is a **graph-level view** of services that lets us:

1. Register services with `labels`, `apiList`, and parent/child `calls` once,
   then at runtime *derive* the `ServiceCall` tree for each incoming request
   by looking up the service-chain associated with its API.
2. Expose source / sink discovery and per-API service chains
   (CloudNativeSim's `ServiceGraph.getSources`, `.getSinks`, `.buildServiceChains`).

**Add:** a new class `org.cloudsimplus.services.ServiceGraph` that holds:

```text
serviceHierarchy        : Map<Service, List<Service>>   // forward (calls)
reverseServiceHierarchy : Map<Service, List<Service>>   // backward (parents)
inDegree                : Map<Service, Integer>
outDegree               : Map<Service, Integer>
serviceChains           : Map<Api, List<Service>>       // per-API topo-sorted chain
```

API operations to expose:

- `addService(Service s, Service parent)` — wire the DAG.
- `deleteService(Service s)` — clean up edges / degrees.
- `getCalls(Service s)` / `getParentServices(Service s)`.
- `getSources(List<Service> chain)` / `getSinks(List<Service> chain)`.
- `buildServiceChains(List<Api> apis)` — populate the per-API ordered chains
  used by the broker when fanning out a `ServiceRequest`.

**Reference:**
`../CloudNativeSim/modules/src/main/java/entity/ServiceGraph.java` (full DAG
API) and `entity/Service.java` (helpers like `getChildServicesInChain`).

**Critical-path / latency accounting** — CloudNativeSim computes a request's
end-to-end latency as the **max over sink services of the cumulative path
delay** (see `Application.updateResponseTimeByCriticalPath`). Reproduce this
in `ServiceBrokerSimple` (or a subclass) by:

- For each completed call, recording per-`Service` path delay on the
  `ServiceRequest` (extend `ServiceRequest` with
  `Map<Service, Double> nodeDelay`).
- When the last sink completes, set
  `request.finishTime = submissionTime + max(nodeDelay over sinks)` if
  honoring the critical-path semantic. (The current
  `ServiceBrokerSimple.completeCall` only uses root-finish time, which is
  correct for a synchronous tree but does not match the parallel-fanout
  semantic CloudNativeSim assumed; document the choice clearly in JavaDoc.)

### 3.2 API entity (request type with weight + SLO)

CloudNativeSim has an `API` class carrying a name, a weight (used for weighted
random sampling by the request generator), an SLO threshold, request history,
and per-API RPS / SLO / delay statistics.

**Add:** `org.cloudsimplus.services.Api` (named `Api` to follow Java naming).

```java
public class Api {
    String name;            // e.g. "GET /catalogue"
    double weight;          // for weighted random selection
    double sloThreshold;    // seconds; default 5.0
    List<Service> serviceChain;          // resolved by ServiceGraph.buildServiceChains
    List<ServiceRequest> requests;       // every request bound to this API
    List<Double> rpsHistory;             // sampled per request-interval

    double getAverageDelay();            // mean responseTime over requests
    int getSloViolations();              // count where responseTime >= sloThreshold
    double getAvgRps();                  // average over rpsHistory
}
```

The generator (§4) selects an API by weighted random; the broker (§3.1)
materializes the ServiceCall tree from `api.getServiceChain()` for each
generated `ServiceRequest`.

**Reference:** `../CloudNativeSim/modules/src/main/java/entity/API.java`.

### 3.3 Service registration metadata

Extend `org.cloudsimplus.services.Service` (the interface) with:

- `getLabels()` / `getApiList()` — already familiar from
  CloudNativeSim's `Service`.
- A graph back-reference: `ServiceGraph getServiceGraph()`.

The default implementation `ServiceSimple` should hold these as fields.
Round-robin or random VM selection via `selectVm()` already exists; keep that.

### 3.4 ReplicaSet abstraction (optional but useful)

`KubernetesPod`s are already the container-of-VMs analogue. CloudNativeSim's
`ReplicaSet` is essentially a named group of identical pods used for
horizontal scaling. Two reasonable choices:

1. **Reuse Kubernetes selectors:** treat all pods matching a label selector
   as a "replica set". Add `ReplicaSet` as a thin view over a label query.
2. **Add `org.cloudsimplus.services.ReplicaSet`** carrying `prefix`, `replicas
   : List<Pod>`, `replicate()` to clone the canonical pod, and aggregate
   utilization helpers (`getMin/Max/AvgUtilizationOfCpu`).

Prefer (1) if `LabelSelector` already supports it; otherwise (2).

**Reference:** `../CloudNativeSim/modules/src/main/java/entity/ReplicaSet.java`.

### 3.5 Service-level allocation / migration / scaling policies

CloudSim Plus ships VM-level versions of all of these. Add a thin
**service-level** orchestration layer:

- `ServiceAllocationPolicy` interface with one default implementation
  `ServiceAllocationPolicySimple`. Responsibility: place each replica
  (`KubernetesPod`/`Vm`) using the underlying `VmAllocationPolicy` while
  enforcing label/affinity rules from the service definition.
- `ServiceScalingPolicy` interface (`needScaling(Service)` /
  `scaling(Service)`) with `HorizontalServiceScalingPolicy` and
  `VerticalServiceScalingPolicy` that wrap the existing
  `HorizontalVmScalingSimple` / `VerticalVmScalingSimple` but trigger off
  **service-aggregated** CPU usage history (mean of replica usages over the
  last N samples).
- `InstanceMigrationPolicy` — the VM migration plumbing already exists; this
  layer just decides *which* replica to migrate when service-level pressure
  is detected.

**Reference:**
- `../CloudNativeSim/modules/src/main/java/policy/allocation/ServiceAllocationPolicySimple.java`
- `../CloudNativeSim/modules/src/main/java/policy/scaling/HorizontalScalingPolicy.java`
- `../CloudNativeSim/modules/src/main/java/policy/scaling/VerticalScalingPolicy.java`

### 3.6 Sample utilization histories per replica

CloudNativeSim's `Exporter.usageOfCpuHistory` / `usageOfRamHistory` /
`usageOfReceiveBwHistory` / `usageOfTransmitBwHistory` are
`Map<instanceUid, List<UsageData(timestamp, session, usage)>>`.

CloudSim Plus already exposes utilization through `Vm.getCpuPercentUtilization()`
etc. and listeners. Add a `ResourceUsageRecorder` that:

- Listens on a fixed sampling interval (the simulation's
  `schedulingInterval`, default 10s).
- Records per-replica CPU/RAM (and optionally rec/trans BW) into a
  `Map<String, List<UsageSample>>`.
- Powers both the scaling policies (which read recent N samples) and the
  end-of-run report.

Define `UsageSample(double timestamp, double session, double usage)` —
matching CloudNativeSim's `UsageData`.

**Reference:** `core/Exporter.java`, `extend/UsageData.java`,
`core/Reporter.printResourceUsage`.

---

## 4. Request generator (`Generator`)

Direct port of `core.Generator`, but as a CloudSim Plus citizen.

**Add:** `org.cloudsimplus.services.RequestGenerator` (or
`ClientRequestGenerator`).

Configurable via builder/setters:

- `finalClients` (target user count) and `spawnRate` (clients added per
  second until target reached).
- `waitTimeSpan = [min, max]` — each "client" sleeps a random number of
  seconds in that interval between successive requests.
- Or, alternative mode: `finalRps` (constant arrival rate).
- `timeLimit` — generation horizon (seconds).
- `numLimit` — hard cap on total requests generated.
- `meanLength` / `stdDevLength` — Gaussian-distributed cloudlet length
  (millions of instructions) used when materializing each call's
  `lengthBeforeCalls`/`lengthAfterCalls`. CloudNativeSim multiplies this by
  the number of "endpoints" (1 + number of children that the source service
  invokes within the chain) — preserve that for the source-service cloudlet.
- `apis` — list of `Api`, used for **weighted random selection** via
  pre-computed cumulative weights. Identical algorithm as
  `Generator.getRandomAPI` (binary or linear search over cumulative weights).

The generator is invoked once per simulation tick; it returns a
`List<ServiceRequest>`. Each request is then handed to the
`ServiceBrokerSimple.submitRequest(...)`, which uses
`api.getServiceChain()` to build the matching `ServiceCall` tree and fires
it.

**Tick scheduling** — CloudNativeSim does this in
`Application.startClients()` and `processRequestGenerate`: it schedules a
`REQUEST_GENERATE` event every `requestInterval` (= 1s) up to `timeLimit`,
and every `schedulingInterval` it also fires `SERVICE_SCALING`. Reproduce
this with `simulation.send(...)` self-events on the broker.

**Reference:**
- `../CloudNativeSim/modules/src/main/java/core/Generator.java`
- `Application.processRequestGenerate`, `Application.startClients`.

---

## 5. File-based registration (YAML / JSON)

Direct port of `core.Register`. Two input files — services (JSON) and
instances (YAML) — match the SockShop example shape.

**Services file (JSON)** — top-level keys:

```json
{
  "APIs": [
    { "name": "GET /catalogue", "weight": 2.0 }
  ],
  "services": [
    {
      "name":   "front-end",
      "labels": ["front-end"],
      "calls":  ["carts", "orders", "catalogue", "user", "payment"],
      "APIs":   ["GET /catalogue", "GET /login", ...]
    }
  ]
}
```

**Instances file (YAML)** — each entry:

```yaml
instances:
  - prefix: carts
    type: pod
    labels: [carts]
    replicas: 2
    size: 500
    rec_bw: 100
    trans_bw: 100
    requests: { share: 100, ram: 200 }
    limits:   { share: 300, ram: 500 }
```

**Add:** `org.cloudsimplus.services.config.ServiceRegistry` (or `Register`).
Load both files and produce:

- `List<Api>`
- `ServiceGraph` with all services and their `calls` parent-child edges.
- `List<KubernetesPod>` (or your `Replica`/`Instance` type) with the right
  resource requests/limits, labels, replica counts.

**Default values for missing fields** — match CloudNativeSim's defaults
(`requests_share=100`, `requests_ram=200`, `limits_share=1024`,
`limits_ram=1000`).

Use Jackson (`ObjectMapper`) for JSON and Jackson with the YAML factory
(`YAMLFactory`) for YAML. Avoid hand-rolled key-path parsers; use
strongly-typed DTOs.

Sample input files to verify against:
- `../CloudNativeSim/examples/src/sockshop/services.json`
- `../CloudNativeSim/examples/src/sockshop/instances.yaml`

**Reference:** `../CloudNativeSim/modules/src/main/java/core/Register.java`.

---

## 6. Wiring example (target user code)

The end goal is to support an example like the following (rewritten in the
CloudSim Plus idiom). Place it under
`src/test/java/org/cloudsimplus/examples/services/SockShopExample.java`.

```java
public class SockShopExample {
    public static void main(String[] args) {
        var sim    = new CloudSimPlus();

        // Datacenter / hosts / VMs (existing CloudSim Plus builders)
        var dc     = createDatacenter(sim, /* numHosts= */ 1);
        var vms    = createVms(/* num= */ 3);

        // Service broker (CloudNative-aware)
        var broker = new ServiceBrokerSimple(sim, "Broker0");
        broker.submitVmList(vms);

        // ----- File-based registration -----
        var reg    = new ServiceRegistry()
                         .servicesFile("examples/sockshop/services.json")
                         .instancesFile("examples/sockshop/instances.yaml")
                         .load();

        var apis     = reg.getApis();              // List<Api>
        var graph    = reg.getServiceGraph();      // ServiceGraph
        var replicas = reg.getInstances();         // List<KubernetesPod>

        // Build per-API service chains once everything is registered
        graph.buildServiceChains(apis);

        // Per-service policies
        for (var s : graph.getAllServices()) {
            s.setServiceScalingPolicy(new HorizontalServiceScalingPolicy());
        }

        // Request generator
        var gen = new RequestGenerator()
                      .apis(apis)
                      .finalClients(300)
                      .spawnRate(30)
                      .waitTimeSpan(5, 15)
                      .timeLimit(600)
                      .meanLength(10).stdDevLength(10);
        broker.setRequestGenerator(gen);

        // Allocation policy (service-level wrapper around VmAllocationPolicySimple)
        broker.setServiceAllocationPolicy(new ServiceAllocationPolicySimple());
        broker.setSchedulingInterval(10);

        sim.start();

        // ----- Reporting -----
        var reporter = new ServiceReporter(broker, apis, graph);
        reporter.printApiStatistics();          // ASCII table per-API + aggregate
        reporter.printResourceUsage();          // CPU / RAM averages per replica
        reporter.writeApiStatisticsCsv("./API_Statistics.csv");
        reporter.writeResourceUsageCsv("./Resource_Report.csv");
    }
}
```

The exact builder names / fluent calls are flexible; what matters is parity
with the CloudNativeSim `SockShopExample.java` flow.

**Reference:** `../CloudNativeSim/examples/src/sockshop/SockShopExample.java`.

---

## 7. Metrics & output — the part that must match CloudNativeSim closely

This is the bit the user explicitly wants preserved. The library should
produce *the same shape of output* as CloudNativeSim, even if the underlying
engine differs.

### 7.1 Per-API and aggregate ASCII table (Reporter.printApiStatistics)

For each `Api` and an aggregate row, render an ASCII table with these
columns/rows:

```
+----------------------------+-----------+
| API Metrics                |   Value   |
+----------------------------+-----------+
| Total Requests             | <int>     |
| Failure Rate               | <pct>%    |
| RPS                        | <float>   |
| Average Delay              | <float> s |
| SLO Violation Rate         | <pct>%    |
+----------------------------+-----------+
```

Use `de.vandermeer.asciitable.AsciiTable` (already used by CloudNativeSim's
`Reporter` and present in CloudSim Plus' dependency tree if available; if
not, add it or substitute a small ASCII table helper).

Also produce a per-API table (one row per API), same columns, when the user
calls `printApiStatistics(List<Api>)`.

### 7.2 CSV export — `API_Statistics.csv`

Header and per-API rows, matching the format CloudNativeSim emits at
`API_Statistics.csv`:

```
API Name,Total Requests,Average Delay (seconds),SLO Violation Rate,QPS
GET /catalogue,3200,2.49,0.62%,5.36
...
Aggregate,18966,2.48,0.57%,31.61
```

Notes:

- The CSV is human-friendly: numbers formatted with `DecimalFormat("###.##")`.
- The aggregate row uses the literal label `Aggregate` and is appended last.
- Be aware that CloudNativeSim's current writer has a locale bug (uses comma
  as decimal separator on locales where that is the default, breaking CSV).
  **Force `Locale.ROOT`** when formatting numbers in the CSV writer.

### 7.3 Resource usage (Reporter.printResourceUsage)

ASCII table:

```
+-----------------+-------------------+-------------------+
| Instance Name   | CPU Usage Average | RAM Usage Average |
+-----------------+-------------------+-------------------+
| carts-0         | 38.21             | 184.50            |
| carts-1         | 41.07             | 192.00            |
...
```

CSV variant: `Resource_Report.csv` with the same three columns.

A per-instance time-series CSV is also produced by
`Reporter.writeUsageDetailToCSV` — files named
`<instanceName>_<resourceType>_Usage_History.csv` with header
`Timestamp,Average`, sampled at a fixed interval (default 10s in
CloudNativeSim). Replicate this for downstream visualization (Grafana) users.

### 7.4 RPS / QPS history

- Global: `Exporter.rpsHistory : List<Double>` — one entry per
  `requestInterval` containing requests-arrived / interval.
- Per-API: `Api.rpsHistory : List<Double>`, same idea but grouped by API.
- Expose a writer that dumps these to CSV with columns
  `Timestamp,Global RPS` and `Timestamp,<api>` columns respectively, so a
  Grafana panel can plot them.

### 7.5 Service dependency / DAG export

CloudNativeSim's Grafana Node-Graph panel reads a JSON describing service
nodes and edges. CloudSim Plus' `ServiceBrokerSimple.getDAG()` already emits
something close (per-edge call counts and average latency). Standardize the
output so the user can plug it into Grafana without further transformation
(see §13 for the panel-side details).

The exporter must produce **two separate JSON files**, matching what the
Grafana `nodeGraph` panel + Infinity datasource expects:

`nodes.json`:
```json
{
  "nodes": [
    {
      "id": "front-end",
      "title": "Front-End",
      "arc__success": 0.87,
      "arc__failure": 0.13,
      "mainstat": 1000
    }
  ]
}
```

`edges.json`:
```json
{
  "edges": [
    { "id": "e1", "source": "front-end", "target": "carts" }
  ]
}
```

Field semantics (must be respected — Grafana NodeGraph parses these by name):

- `id` — unique per node / edge (string).
- `title` — human-readable label.
- `arc__success` + `arc__failure` — fractions in `[0, 1]`, must sum to 1;
  rendered as donut arcs around the node. Compute from completed vs.
  SLO-violated/failed requests touching that service.
- `mainstat` — primary numeric annotation under the node (e.g. average
  latency in ms or total request count — pick one and document it).
- `source` / `target` — node `id`s wiring an edge. Optional `mainStat` /
  `secondaryStat` per edge can also be added (call count, average latency).

Reference files: `../CloudNativeSim/visualization/demo/nodes.json` and
`../CloudNativeSim/visualization/demo/edges.json`.

Expose two writers on the reporter:

- `ServiceReporter.writeDagJson(String dirPath)` — writes both
  `nodes.json` and `edges.json` into `dirPath`.
- `ServiceReporter.writeDagJson(String nodesPath, String edgesPath)` —
  explicit per-file paths.

### 7.6 ServiceRequestStatistics

The existing `org.cloudsimplus.services.ServiceRequestStatistics` already
gives mean / median / min / max / stddev / p25/p50/p75/p90/p95/p99 of
response time. **Keep it and extend it**:

- Add per-API breakdown (a `Map<String, ResponseTimeStats>` keyed by API name).
- Add SLO violation count/rate per API and overall.
- Add a `toJson()` that's safe to feed into downstream tooling.

This is a strict superset of the CloudNativeSim
`Exporter.getApiStatistics(...)` aggregate metrics.

### 7.7 Reporter class

Create `org.cloudsimplus.services.reporting.ServiceReporter` collecting all
the printers / writers above. Method names should mirror CloudNativeSim's
familiar API:

- `printApiStatistics()` / `printApiStatistics(List<Api>)`
- `printResourceUsage()` / `printResourceUsage(boolean toCsv, String path)`
- `printChains(ServiceGraph, List<Api>)` / `printGlobalDependencies(ServiceGraph)`
  — ASCII tree of each per-API chain.
- `writeStatisticsToCsv(List<Api>, String path)`
- `writeUsageDetailToCsv(Map<String,List<UsageSample>>, String resourceType, String path)`
- `writeDagJson(String path)`

**Reference:** `../CloudNativeSim/modules/src/main/java/core/Reporter.java`
and `core/Exporter.java`.

---

## 7-bis. Grafana visualization (panels + datasources)

CloudNativeSim ships three Grafana dashboards under
`../CloudNativeSim/visualization/styles/`:

1. **`service dependency.json`** — a single `nodeGraph` panel that visualizes
   the microservice DAG. Datasource: **`yesoreyeram-infinity-datasource`**
   (Infinity), `type: json`, pulling `nodes.json` and `edges.json` over HTTP
   (the original points at raw GitHub URLs; in practice you point it at the
   files written by the simulator, served either from disk via Infinity's
   `Inline`/`URL` mode or from a static HTTP server).
2. **`instance usage.json`** — two `timeseries` panels for per-instance
   `cpu_usage_average` and `ram_usage_average`. Datasource: **MySQL**, query
   `SELECT instance_name, ram_usage_average FROM <db>.grafana_table LIMIT 50`
   (and the analogous one for CPU). The simulator therefore needs to either
   write rows directly into a MySQL table OR (preferred) write CSV that an
   ETL step ingests into MySQL.
3. **`request statics.json`** — three `timeseries` panels for per-API RPS,
   average delay and SLO violation rate over time.

The library port must produce **inputs** that work with all three panels
without forcing the user to rewrite the dashboards. The pipeline is:

```
                       ┌──────────────────────────┐
                       │ ServiceBrokerSimple +    │
                       │ ResourceUsageRecorder    │
                       └────────────┬─────────────┘
                                    │ end-of-run
            ┌───────────────────────┼─────────────────────────┐
            ▼                       ▼                         ▼
    nodes.json /            API_Statistics.csv         <instance>_cpu_Usage_History.csv
    edges.json              global_rps_history.csv     <instance>_ram_Usage_History.csv
    (Infinity JSON)         per_api_rps_history.csv    Resource_Report.csv  (MySQL ingestion)
                            (Infinity CSV / MySQL)
```

### 7-bis.1 What the simulator writes

Add these writers on `ServiceReporter` (all paths are user-supplied):

| Method | File(s) | Consumed by |
|---|---|---|
| `writeDagJson(dir)` | `nodes.json`, `edges.json` | Service-dependency panel (Infinity / JSON) |
| `writeApiStatisticsCsv(path)` | `API_Statistics.csv` | Request-statistics panel (CSV / MySQL) |
| `writeResourceUsageCsv(path)` | `Resource_Report.csv` | Instance-usage panel (MySQL — table `grafana_table`, columns `instance_name, cpu_usage_average, ram_usage_average`) |
| `writeUsageDetailCsv(dir)` | `<instance>_cpu_Usage_History.csv`, `<instance>_ram_Usage_History.csv` | Instance-usage time-series panels |
| `writeGlobalRpsHistoryCsv(path)` | `global_rps_history.csv` (cols `Timestamp,RPS`) | Request-statistics RPS panel |
| `writePerApiRpsHistoryCsv(path)` | `per_api_rps_history.csv` (cols `Timestamp,<api1>,<api2>,...`) | Request-statistics per-API panels |

All numeric formatting must use `Locale.ROOT` (avoid the `,` decimal-separator
bug seen in CloudNativeSim's writer).

### 7-bis.2 Column / table contract for MySQL ingestion

The shipped Grafana `instance usage.json` queries `mybatis.grafana_table`.
Document the expected schema so the user can `CREATE TABLE` and `LOAD DATA
INFILE` from the CSV without surprises:

```sql
CREATE TABLE grafana_table (
  instance_name      VARCHAR(255) PRIMARY KEY,
  cpu_usage_average  DOUBLE,
  ram_usage_average  DOUBLE
);
```

The `Resource_Report.csv` header (`Instance Name,CPU Usage Average,RAM Usage
Average`) maps 1:1 onto these columns.

For time-series ingestion (per-instance history):

```sql
CREATE TABLE instance_usage_history (
  instance_name  VARCHAR(255),
  resource_type  ENUM('cpu','ram'),
  ts             DOUBLE,
  avg_usage      DOUBLE,
  PRIMARY KEY (instance_name, resource_type, ts)
);
```

### 7-bis.3 Bundled dashboard JSONs

Copy the three dashboard JSONs from CloudNativeSim into
`src/main/resources/grafana/` (renamed without spaces, e.g.
`service-dependency.json`, `instance-usage.json`, `request-statistics.json`)
so that the example ships them. Adjust the Infinity URLs in
`service-dependency.json` to use a `${nodes_url}` / `${edges_url}` Grafana
variable (with sensible defaults pointing at `file://` or `http://localhost`)
instead of hard-coding the original GitHub raw URLs.

### 7-bis.4 README / docs section

The new package's README must include a **Visualization** section that:

1. Tells the user to start a Grafana >= 10 instance with the
   `yesoreyeram-infinity-datasource` plugin and a MySQL datasource (or
   alternatives like Postgres / SQLite that map onto the same query).
2. Explains how to import the three bundled dashboards.
3. Documents the file → panel mapping (the table in §7-bis.1).
4. Provides a copy-pasteable `docker-compose.yml` snippet to spin up Grafana
   + MySQL pre-loaded with `grafana_table` from `Resource_Report.csv` for a
   smoke test (optional but recommended).

### 7-bis.5 What we deliberately skip

- The original Infinity URLs point at `github.com/.../master/gui/*.json` —
  do **not** keep those raw URLs in the shipped dashboard JSON; they break
  for any forked install.
- The original `id` field of each dashboard JSON is hard-coded; let Grafana
  re-assign it on import.
- Live, push-based metrics (Prometheus scraping the simulator) — out of
  scope; CloudNativeSim itself only does file-based export, and the port
  preserves that contract.

---

## 8. Suggested package layout

```
org.cloudsimplus.services
├── Service.java                      [exists]
├── ServiceSimple.java                [exists, extend with labels/apis/graph back-ref]
├── ServiceCall.java                  [exists]
├── ServiceRequest.java               [exists, add nodeDelay map]
├── ServiceBroker.java                [exists]
├── ServiceBrokerSimple.java          [exists, extend with generator + DAG-aware request fan-out]
├── ServiceRequestStatistics.java     [exists, extend per-API + SLO]
│
├── Api.java                          [NEW — §3.2]
├── ServiceGraph.java                 [NEW — §3.1]
├── ReplicaSet.java                   [NEW or label-based — §3.4]
│
├── generator
│   └── RequestGenerator.java         [NEW — §4]
│
├── config
│   ├── ServiceRegistry.java          [NEW — §5, the file loader (Register port)]
│   ├── ServicesFileDto.java          [NEW — Jackson DTO for services.json]
│   └── InstancesFileDto.java         [NEW — Jackson DTO for instances.yaml]
│
├── policy
│   ├── allocation
│   │   ├── ServiceAllocationPolicy.java        [NEW]
│   │   └── ServiceAllocationPolicySimple.java  [NEW]
│   ├── scaling
│   │   ├── ServiceScalingPolicy.java           [NEW]
│   │   ├── HorizontalServiceScalingPolicy.java [NEW]
│   │   └── VerticalServiceScalingPolicy.java   [NEW]
│   └── migration
│       └── InstanceMigrationPolicy.java        [NEW, thin wrapper]
│
└── reporting
    ├── UsageSample.java                        [NEW — §3.6]
    ├── ResourceUsageRecorder.java              [NEW — §3.6]
    ├── ServiceReporter.java                    [NEW — §7.7]
    └── grafana
        ├── DagJsonWriter.java                  [NEW — §7.5 / §7-bis]
        ├── RpsHistoryCsvWriter.java            [NEW — §7-bis]
        └── UsageHistoryCsvWriter.java          [NEW — §7-bis]
```

Also add, under `src/main/resources/grafana/`:

```
service-dependency.json   [shipped Grafana dashboard, Infinity datasource]
instance-usage.json       [shipped Grafana dashboard, MySQL datasource]
request-statistics.json   [shipped Grafana dashboard, MySQL/CSV datasource]
```

---

## 9. Step-by-step implementation order

1. **`Api`** + extend `ServiceRequest` with `Map<Service,Double> nodeDelay`.
   Unit-test cumulative-weight selection and SLO violation counting against
   synthetic data.
2. **`ServiceGraph`** + integrate it with `ServiceSimple`. Port the in/out
   degree, `getSources` / `getSinks` / `buildServiceChains`. Unit-test
   against the SockShop services.json topology.
3. **`UsageSample` + `ResourceUsageRecorder`**. Hook into
   `Vm`/`Pod`-level utilization listeners. Unit-test that samples line up
   with `schedulingInterval`.
4. **`RequestGenerator`** in client-mode and rps-mode. Unit-test arrival
   counts under deterministic seeds.
5. **`ServiceRegistry`** for JSON+YAML loading. Round-trip test: load
   SockShop files → build graph → assert exact same service chain length per
   API as CloudNativeSim produces.
6. **`ServiceAllocationPolicySimple`** — wraps `VmAllocationPolicySimple`,
   honors label affinity and request/limit fields.
7. Extend **`ServiceBrokerSimple`** to:
   - tick the generator every `requestInterval` until `timeLimit`,
   - resolve each `ServiceRequest` to a `ServiceCall` tree from
     `api.getServiceChain()`,
   - keep the existing call-tree execution engine,
   - emit `nodeDelay` updates so critical-path latency is computed.
8. **Scaling policies** (horizontal + vertical service-level) + scheduling
   self-events every `schedulingInterval`.
9. **`ServiceReporter`** — ASCII tables + CSV + DAG JSON. Force
   `Locale.ROOT`.
10. **`SockShopExample`** end-to-end. Compare CSV output against
    `../CloudNativeSim/API_Statistics.csv` (numbers will differ because
    schedulers differ, but the schema and aggregate-row format must match).

---

## 10. Concrete CloudNativeSim files to read for each piece

| Building | Read these files first |
|---|---|
| `Api` | `entity/API.java` |
| `ServiceGraph` | `entity/ServiceGraph.java`, helpers in `entity/Service.java` |
| `RequestGenerator` | `core/Generator.java`, `core/Application.startClients`, `processRequestGenerate` |
| `ServiceRegistry` | `core/Register.java`, `examples/src/sockshop/{services.json,instances.yaml}` |
| Critical-path latency | `core/Application.processCloudlets`, `updateResponseTimeByCriticalPath` |
| Resource recorder | `core/Exporter.java`, `extend/UsageData.java` |
| Allocation | `policy/allocation/ServiceAllocationPolicySimple.java` |
| Scaling | `policy/scaling/HorizontalScalingPolicy.java`, `policy/scaling/VerticalScalingPolicy.java` |
| Reporter | `core/Reporter.java`, top-level `API_Statistics.csv` |
| End-to-end example | `examples/src/sockshop/SockShopExample.java` |

---

## 11. Naming & style notes

- Follow CloudSim Plus conventions: **sealed interfaces** for top-level
  abstractions, **`*Simple`** for the default impl, **`*NULL`** static
  fields for null-objects, Lombok `@Getter` / `@Setter` / `@Builder`,
  Java 25, 4-space indent, GPLv3 header on every new file.
- Avoid mutable global static maps (CloudNativeSim's
  `Service.serviceNameMap`, `Instance.instanceUidMap`,
  `ReplicaSet.replicaSetMap`, `Exporter.usageOfCpuHistory`). Pass these via
  the broker / simulation context.
- The CloudNativeSim source uses Chinese inline comments — translate any
  ported logic to English.
- Keep the new code free of `RpcCloudlet` / `NativeCloudletScheduler`
  references. The user has explicitly excluded these.

---

## 12. Acceptance checklist

The port is "done" when a fresh checkout can:

- [ ] Build with `mvn clean install` — no new test failures.
- [ ] Run `SockShopExample` against the SockShop YAML/JSON files.
- [ ] Print an ASCII per-API table and an aggregate table that include
      `Total Requests`, `RPS`, `Average Delay`, `SLO Violation Rate`.
- [ ] Write `API_Statistics.csv` with the exact header
      `API Name,Total Requests,Average Delay (seconds),SLO Violation Rate,QPS`
      and a final `Aggregate,...` row.
- [ ] Write `Resource_Report.csv` with header
      `Instance Name,CPU Usage Average,RAM Usage Average`.
- [ ] Emit a DAG JSON consumable by Grafana NodeGraph (writes both
      `nodes.json` and `edges.json` in the format documented in §7.5
      with `arc__success` / `arc__failure` / `mainstat` fields on nodes).
- [ ] Ship the three Grafana dashboards (`service-dependency.json`,
      `instance-usage.json`, `request-statistics.json`) under
      `src/main/resources/grafana/` with no hard-coded GitHub raw URLs.
- [ ] Provide CSV writers (`Resource_Report.csv`, per-instance time-series,
      `global_rps_history.csv`, `per_api_rps_history.csv`) whose schemas
      match the queries in the shipped dashboards.
- [ ] Trigger horizontal scaling at least once when a service's
      averaged-recent CPU utilization crosses the upper threshold.
- [ ] All new public types ship with JavaDoc and a Null-object placeholder
      where appropriate.
- [ ] No reference to `RpcCloudlet` or `NativeCloudletScheduler` anywhere in
      the new code.
