# 0027 — A cluster that grows with its load

## Problem

The bank is a `StatefulSet` of three, always (`deploy/k8s/bank.yaml`). A busier hour gets the same three pods, and
a quiet night keeps all three. Most of what a changing size needs is already built (spec 0003):

- pods find each other through the pods API;
- a `Lease` breaks an even split;
- SIGTERM drains the producer and hands the shards over before the pod goes;
- `scripts/chaos.sh` already scales to four and back under load with the ledger conserved.

What is missing is something that decides the size. Nothing on kind could decide it on CPU either, since kind has
no metrics-server (`docs/bring-up.md`).

## Not doing

- **Scaling on the bank's own meters.** No prometheus-adapter and no KEDA. Requests and p99 are already in
  Prometheus, so they could follow, but as a spec of their own once CPU has been shown to lag or lead.
- **The other services.** bank-checks, bank-approvals and bank-access keep their fixed replicas. Screening is the
  next bottleneck under load (the README's screened row), and it is the next spec.
- **More write capacity.** Every pod writes to the same two journal databases (spec 0004). More pods add CPU for
  sagas, screening calls and serialisation, not Postgres commits. Past that, the answer is another journal
  database, not another pod.
- **Scaling to zero or below three.** Three keeps a majority through one pod lost, as `ClusterBelowSize` already
  assumes.
- **Changing read-model partitions with the size.** Eight workers (`partitions = 4` × two databases) cap read-side
  spread at eight pods, and changing the count replays them (spec 0014).

## Shape

**An HPA on the StatefulSet**, `deploy/k8s/bank-autoscale.yaml`:

```yaml
apiVersion: autoscaling/v2
kind: HorizontalPodAutoscaler
metadata: { name: lark-bank }
spec:
  scaleTargetRef: { apiVersion: apps/v1, kind: StatefulSet, name: lark-bank }
  minReplicas: 3
  maxReplicas: 6
  metrics:
    - type: Resource
      resource: { name: cpu, target: { type: Utilization, averageUtilization: 70 } }
  behavior:
    # One pod at a time: each joins, takes its shards and warms up before the next is judged.
    scaleUp: { stabilizationWindowSeconds: 60, policies: [ { type: Pods, value: 1, periodSeconds: 90 } ] }
    # Slowly down: every leave moves shards and passivates entities, and churn costs more than an idle pod.
    scaleDown: { stabilizationWindowSeconds: 600, policies: [ { type: Pods, value: 1, periodSeconds: 120 } ] }
```

The StatefulSet drops `replicas`, so a Flux reconcile does not fight the HPA. `OrderedReady` stays: scale-up is one
pod at a time anyway, and scale-down removes the highest ordinal, the youngest node.

**metrics-server on kind**, installed by `scripts/operators.sh` with `--kubelet-insecure-tls`, since kind's kubelets
have self-signed certificates.

**Connections that fit six.** Every pod opens `DATABASE_POOL_SIZE` per journal database: 48 × 6 = 288 on
`bank-db-0`, which also holds the checks, Approvals, access, Estate and Pocket ID. The pool drops to 32, which gives
192 at six pods, inside `max_connections: 400`.

**A PodDisruptionBudget**, `maxUnavailable: 1`, in the base rather than only at home, so a drain never takes two
nodes while the HPA is also moving one.

**A load that steps.** Proofload gains a `ramp` scenario: `transfers` at a quarter of `RATE` for a minute, then half,
then full, then a quarter again, so one run shows the HPA going up and coming back down. `scripts/load.sh ramp` runs
it and, beside the report, writes the replica count every 10 s from `kubectl get hpa`.

**Seen on the dashboards.** Grafana's bank board gains "replicas, desired and current", from
`kube_statefulset_status_replicas` if kube-state-metrics is present, or else from `lark_cluster_members`. The
runbook gains "The bank is scaling", covering what a scale-up looks like, why scale-down is slow, and how to pin the
size by hand:

```bash
kubectl -n lark-bank patch hpa lark-bank -p '{"spec":{"minReplicas":5}}'
```

## Why this shape

CPU is the one signal an HPA reads with nothing installed beside it but metrics-server. The bank's work per transfer
is mostly CPU in the JVM: sagas, Protobuf, screening calls and tracing. The CPU request is already one core per pod,
so utilisation means something.

The alternative is scaling on `http_server_request_duration_seconds` p99 through prometheus-adapter. That follows
what a customer feels more closely, but p99 also rises when Postgres is the bottleneck, and then more pods make it
worse. CPU does not rise in that case.

Recommended: CPU now, and request latency as its own spec if the ramp shows CPU reacting late.

## Depends on

Nothing in Lark or Pelican. Joining, leaving and moving shards on a changing membership are already Lark's (lark
specs 0080, 0096, 0099). `Rebalance.byLoad` (lark spec 0090) would also move busy shards to a new pod, but hash
placement already gives it its share, so it is not needed here.

## Stack

- [ ] **`autoscale-metrics`** — metrics-server on kind in `scripts/operators.sh`, and the pool at 32.
      Done when: `kubectl top pods -n lark-bank` lists every bank pod on kind, and `transfers` at 400/s runs clean
      at pool 32.
- [ ] **`autoscale-hpa`** — the HPA, `replicas` dropped from the StatefulSet, and the base PDB.
      Done when: on kind, `kubectl get hpa lark-bank` shows a CPU target and three replicas at rest, and a
      reconcile leaves the replica count alone.
- [ ] **`autoscale-ramp`** — the `ramp` scenario and `scripts/load.sh ramp` recording replicas.
      Done when: one ramp run on kind goes past three replicas and returns to three, with `LEDGER CONSERVED` and no
      member `Downed` in the logs.
- [ ] **`autoscale-seen`** — the Grafana panel, the runbook section, and the README's "What it carries" with the
      ramp's numbers.
      Done when: the panel shows the ramp's replicas, and `docs/services.md` says the bank runs three to six nodes.

## Acceptance

```bash
nix develop .#ci -c ./gradlew build
scripts/up.sh && scripts/load.sh ramp       # exits 0 on LEDGER CONSERVED
kubectl -n lark-bank get hpa lark-bank -w   # replicas rise past 3 under the ramp, and fall back after
```

## Open questions

1. **Six at most?** Six fits the connections and stays under the eight read-model workers. Home has three machines
   and *required* anti-affinity, one pod per machine, so the HPA can never place a fourth there. Recommended: six
   on kind; at home, keep three and leave the HPA out of the home overlay until anti-affinity there is preferred.
2. **70% CPU?** At one core a pod, the README's 200 transfers/s per node at small sizes suggests a pod near 70%
   is near its p99 knee. Recommended: 70, and adjust it from the ramp's run.
3. **Pool at 32 or less?** Recommended: 32. The ramp shows whether a pod waits on its pool at 400/s. If it does,
   the budget is `max_connections`, not the pool.
4. **Scale-down window, ten minutes?** Recommended: ten. A leave moves about a sixth of the shards and passivates
   their entities. A flapping cluster costs more than one idle pod for ten minutes.
