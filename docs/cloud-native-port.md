# Cloud-Native Services Port

This document describes the cloud-native simulation features added to
CloudSim Plus, ported from
[CloudNativeSim](https://github.com/CyanStarNight/CloudNativeSim) into the
existing `org.cloudsimplus.services.*` and `org.cloudsimplus.kubernetes.*`
package hierarchies. The full design contract lives in
[`CLOUDNATIVE_PORT.md`](../CLOUDNATIVE_PORT.md) at the repo root; this file
is the user-facing complement.

## What was added

| Capability | Class / package | Notes |
|---|---|---|
| Per-API request type with weight + SLO | `org.cloudsimplus.services.Api` | weighted random selection, SLO violation counting, per-API RPS history |
| DAG-based microservice graph | `org.cloudsimplus.services.ServiceGraph` | source/sink discovery, per-API topologically sorted chains, in/out-degree |
| Service metadata extension | `Service.getLabels()` / `getApiList()` / `getServiceGraph()` | default methods on the existing `Service` interface |
| Per-replica usage sampling | `org.cloudsimplus.services.reporting.{UsageSample, ResourceUsageRecorder}` | hooks into `Simulation#addOnClockTickListener`, samples at `schedulingInterval` |
| Request generator | `org.cloudsimplus.services.generator.RequestGenerator` | client- and RPS-based modes, Gaussian length, weighted API picker |
| File-based registration (JSON+YAML) | `org.cloudsimplus.services.config.{ServiceRegistry,ServicesFileDto,InstancesFileDto,Replica,ReplicaSpec}` | Jackson `ObjectMapper` + `YAMLFactory`, strongly-typed DTOs |
| Service-level allocation | `org.cloudsimplus.services.policy.allocation.{ServiceAllocationPolicy,ServiceAllocationPolicySimple}` | label-affinity matching, name-prefix fallback |
| Service-level scaling | `org.cloudsimplus.services.policy.scaling.{ServiceScalingPolicy,HorizontalServiceScalingPolicy,VerticalServiceScalingPolicy}` | reads `ResourceUsageRecorder`; configurable thresholds, callback for the actual scale-up/down action |
| Broker driving | `ServiceBrokerSimple` extensions | schedules generator + scheduling self-events; expands API → ServiceCall tree; records `nodeDelay` for critical-path latency |
| Metrics output | `org.cloudsimplus.services.reporting.ServiceReporter` and `.grafana.{DagJsonWriter,RpsHistoryCsvWriter,UsageHistoryCsvWriter}` | ASCII tables + CSVs (Locale.ROOT) + Grafana NodeGraph JSON |
| Grafana dashboards | `src/main/resources/grafana/{service-dependency,instance-usage,request-statistics}.json` | Infinity URLs replaced with `${nodes_url}` / `${edges_url}` template variables |

The following CloudNativeSim concepts were intentionally **not** ported:
- `entity.RpcCloudlet` and the `NativeCloudletScheduler*` hierarchy — the
  existing `ServiceCall` pre/post split + standard `CloudletScheduler*`
  cover the same use case.
- The custom `core.CloudNativeSim` subclass of CloudSim — `CloudSimPlus`
  is already the simulation engine.
- The `extend.CloudNativeSimTag` int enum — CloudSim Plus already uses
  typed event tags (`CloudSimTag` + `ServiceEventTags`).
- The hand-rolled `Tools.java` YAML/JSON key-path parser — replaced with
  Jackson + DTOs.

## SockShop example

`src/test/java/org/cloudsimplus/examples/services/SockShopExample.java`
runs the full
[SockShop](https://microservices-demo.github.io/) microservice topology
end-to-end. Inputs at `src/test/resources/sockshop/services.json` +
`instances.yaml` are vendored unchanged from CloudNativeSim's example tree
so the test is self-contained.

```bash
mvn -Dtest=SockShopExample test
```

The example:
1. Starts a 32-PE / 64-GB host datacenter.
2. Loads the SockShop services + instances files through `ServiceRegistry`.
3. Runs label-based service-level allocation
   (`ServiceAllocationPolicySimple.match(...)`).
4. Wires a `RequestGenerator` (20 clients, 5 / s spawn, 15 s horizon),
   a `ResourceUsageRecorder` (10 s sampling), and a
   `HorizontalServiceScalingPolicy` per service.
5. After `sim.start()`, emits the metric files described in the next
   section into a `@TempDir`. The JUnit assertions verify that all six
   spec-mandated artifacts exist with the documented headers.

## Metric files

Every artifact named in §7-bis.1 of the port spec is produced by
`ServiceReporter`:

| File | Header / shape | Consumed by |
|---|---|---|
| `API_Statistics.csv` | `API Name,Total Requests,Average Delay (seconds),SLO Violation Rate,QPS` + `Aggregate,...` row | request-statistics dashboard |
| `Resource_Report.csv` | `Instance Name,CPU Usage Average,RAM Usage Average` | instance-usage dashboard (loads 1:1 into MySQL) |
| `<instance>_cpu_Usage_History.csv` / `<instance>_ram_Usage_History.csv` | `Timestamp,Average` | instance-usage time-series panels |
| `global_rps_history.csv` | `Timestamp,RPS` | request-statistics dashboard |
| `per_api_rps_history.csv` | `Timestamp,<api1>,<api2>,...` | request-statistics per-API panels |
| `nodes.json` | `{"nodes":[{"id","title","arc__success","arc__failure","mainstat"}]}` | service-dependency dashboard |
| `edges.json` | `{"edges":[{"id","source","target"}]}` | service-dependency dashboard |

Numbers are formatted with `Locale.ROOT` so `,` is the column separator and
`.` is the decimal separator regardless of the host locale (the original
CloudNativeSim writer had a comma-decimal-separator bug on European
locales that broke CSV consumers).

## Grafana setup

Three Grafana >= 10 dashboards ship under `src/main/resources/grafana/`:

| Dashboard | Datasource | What it shows |
|---|---|---|
| `service-dependency.json` | `yesoreyeram-infinity-datasource` (JSON) | Node-graph panel of the service DAG, nodes coloured by SLO success/failure rate. |
| `instance-usage.json` | MySQL | Per-instance CPU + RAM averages from `grafana_table`. |
| `request-statistics.json` | MySQL | Per-API RPS, average delay, SLO violation rate over time. |

### MySQL ingestion contract

The instance-usage dashboard queries `grafana_table` with this schema:

```sql
CREATE TABLE grafana_table (
  instance_name      VARCHAR(255) PRIMARY KEY,
  cpu_usage_average  DOUBLE,
  ram_usage_average  DOUBLE
);

LOAD DATA LOCAL INFILE 'Resource_Report.csv'
  INTO TABLE grafana_table
  FIELDS TERMINATED BY ',' ENCLOSED BY '"' LINES TERMINATED BY '\n'
  IGNORE 1 ROWS
  (instance_name, cpu_usage_average, ram_usage_average);
```

For per-instance time series, a second table is recommended:

```sql
CREATE TABLE instance_usage_history (
  instance_name VARCHAR(255),
  resource_type ENUM('cpu','ram'),
  ts            DOUBLE,
  avg_usage     DOUBLE,
  PRIMARY KEY (instance_name, resource_type, ts)
);
```

### Manual setup steps

1. Install Grafana >= 10 with the
   `yesoreyeram-infinity-datasource` plugin and a MySQL (or compatible)
   datasource configured.
2. Serve the simulator's JSON output via any static HTTP server, e.g.:
   ```bash
   cd /path/to/sockshop-output && python3 -m http.server 8000
   ```
3. Set the dashboard variables `nodes_url` /
   `edges_url` to `http://<host>:8000/nodes.json` and
   `http://<host>:8000/edges.json` after import. The shipped JSONs
   default to `http://localhost:8000/`.
4. `LOAD DATA LOCAL INFILE` the CSVs into the documented tables (or wire
   them through a small ETL script).
5. Import each dashboard JSON via Grafana's Dashboards → Import flow.
   Grafana will assign a fresh `id` on import; the shipped JSONs no
   longer carry hard-coded ones.

## What's not included

- **Prometheus / push-based metrics**: out of scope. The library only
  exports files (matching CloudNativeSim's contract).
- **`docker-compose.yml`** for Grafana + MySQL: deferred to keep the
  delivery focused. The manual steps above are enough to bring the
  dashboards up against an existing Grafana instance.
