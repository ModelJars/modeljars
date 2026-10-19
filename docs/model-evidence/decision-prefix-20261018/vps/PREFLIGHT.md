# Preflight: isolated host for the decision-prefix measurement

Written before provisioning, per `VPS-BENCHMARK-OPERATIONS.md`.

**Why.** The workstation cannot produce comparable absolute times for this experiment: it runs the
user's editor and screen recorder, and two runs of the same probe on identical code differed by
1.14x, with one contended pair 2x slower on paths the change cannot touch. The within-run ratios
were consistent there (2.81-3.19x on 0.1.61 against 1.56-1.63x on the candidate), but the
milliseconds are not publishable. This host is for absolutes measured back to back under one
condition.

**What is measured.** `grouping.java` on the released 0.1.61 artifact and on the 0.1.62 candidate:
one, two and three questions about one 250-token ticket, in three shapes (one shared rubric, three
rubrics, no rubric), after a discarded warm-up batch, reporting per-batch latency, live heap after a
collection, and every verdict to six places.

**Shape and price, resolved live from the Hetzner API on 2026-10-19:**

| | |
| --- | --- |
| server type | `ccx33`: 8 dedicated vCPU, 32 GB RAM, 240 GB disk, `cpu_type: dedicated`, not deprecated |
| image | `ubuntu-24.04` x86-64 |
| location | `ash` (Ashburn), the location the retired reference host used |
| price | USD 0.2660/hour gross, USD 165.99/month gross |
| ssh key | Hetzner key `vectors-bench-v2`, id 114663461, fingerprint `60:4e:57:38:96:48:c5:45:73:10:9d:37:f7:4f:74:cf`, which matches the local `~/.ssh/id_ed25519.pub` MD5 fingerprint exactly |
| name | `modeljars-bench-cpu-20261019` |

**Duration ceiling.** 3 hours, so at most USD 0.80. **Stop condition:** both probe logs retrieved
and verified, or the ceiling, whichever comes first. The server and its firewall are deleted
immediately afterwards and their absence confirmed by a fresh inventory call.

**Isolation.** A provider firewall permits TCP/22 from this workstation's observed /32 only, because
a fresh Hetzner address draws enough unsolicited SSH traffic to trip `MaxStartups` during setup.
The firewall is a separate resource and is deleted after the server.

**Host keys.** An experiment-specific `known_hosts` file under this directory, with the
fingerprint recorded here after first connection. The workstation's global file is not touched:
cloud IPv4 addresses are recycled and a stale entry would be a correct warning about a different
machine.

**Secrets.** `projects/.env` is never copied to the host. The candidate reaches it as three
locally built Maven artifacts, nothing else.

## Teardown, 2026-10-19

| | |
| --- | --- |
| server | `modeljars-bench-cpu-20261019`, id `171283997`, ipv4 `178.156.173.194`, deleted (HTTP 200) |
| firewall | `modeljars-bench-cpu-20261019-ssh`, id `11840169`, deleted (HTTP 204) after the server |
| host key | `SHA256:J1VAsiTWIIoJ6bGehDHzQ2BG2CFkKZrmPf8YzU7xxco` (ED25519), recorded here; the experiment's `known_hosts` file was deleted with the server and the workstation's global file was never touched |
| lifetime | about 50 minutes against a 3-hour ceiling, so roughly USD 0.22 of the USD 0.80 ceiling |
| closing audit | zero Hetzner servers, volumes, floating IPs, primary IPs and snapshots. One firewall remains: `modeljars-granite41-hybrid-20260916-ssh`, id `11630670`, `applied_to=0`, which predates this run and is already recorded in `VPS-BENCHMARK-OPERATIONS.md`. It was not touched. |

The host ran Temurin 25.0.4.1 and JBang 0.142.0, installed for this run. `projects/.env` was never
copied to it; the candidate arrived as three Maven artifacts whose SHA-256 was compared on both
sides (`modeljars-0.1.62.jar` begins `8d2573c3d8bf2a1cf0371eca`). Baseline in `host-baseline.txt`:
8 vCPU AMD EPYC-Milan presented as 4 cores with 2 threads each, 30 GiB RAM, 215 GiB free, load
0.00 at first contact.
