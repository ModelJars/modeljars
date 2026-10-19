## Isolated host: Hetzner ccx33, 8 dedicated vCPU EPYC-Milan, idle

| batch | 0.1.61 pass 1 | 0.1.61 pass 2 | 0.1.62 pass 1 | 0.1.62 pass 2 |
| --- | --- | --- | --- | --- |
| one rubric, 1 question | 5,480 ms | 5,541 ms | 5,121 ms | 5,427 ms |
| one rubric, 2 questions | 5,884 ms | 5,941 ms | 5,491 ms | 5,841 ms |
| one rubric, 3 questions | 6,343 ms | 6,416 ms | 5,895 ms | 6,276 ms |
| three rubrics, 1 question | 5,419 ms | 5,450 ms | 5,010 ms | 5,367 ms |
| three rubrics, 2 questions | 10,789 ms | 10,898 ms | 6,333 ms | 6,727 ms |
| three rubrics, 3 questions | 16,497 ms | 16,618 ms | 7,766 ms | 8,300 ms |
| no rubric, 3 questions | 5,638 ms | 5,679 ms | 5,180 ms | 5,527 ms |

| | 0.1.61 pass 1 | 0.1.61 pass 2 | 0.1.62 pass 1 | 0.1.62 pass 2 |
| --- | --- | --- | --- | --- |
| load average at start | 3.53 | 7.01 | 6.32 | 7.53 |
| live heap after a collection | 822 MiB | 822 MiB | 822 MiB | 822-823 MiB |
| three rubrics: 3 questions / 1 question | **3.04x** | **3.05x** | **1.55x** | **1.55x** |
| one rubric: 3 questions / 1 question | **1.16x** | **1.16x** | **1.15x** | **1.16x** |
| cost of each question after the first, three rubrics | **5,539 ms** | **5,584 ms** | **1,378 ms** | **1,466 ms** |

Measured verdicts, 15 probability/confidence pairs, against 0.1.61 pass 1 (the discarded warm-up batch is excluded):

- 0.1.61 pass 2: **identical**
- 0.1.62 pass 1: **identical**
- 0.1.62 pass 2: **identical**

The discarded warm-up answer ranges 0.786489 to 0.798666 across these runs, a spread of 0.012177. It is the process's first forward pass, which is why it is discarded. On the isolated host it moves between two runs of the same build as much as it does between builds.

## Workstation, contended: not comparable between runs

| batch | 0.1.61, earlier A | 0.1.61, earlier B | 0.1.61, same probe | two points, superseded | 0.1.62, same probe | 0.1.62, contended |
| --- | --- | --- | --- | --- | --- | --- |
| one rubric, 1 question | 14,723 ms | 12,940 ms | 29,727 ms | 13,950 ms | 18,537 ms | 26,747 ms |
| one rubric, 2 questions | 15,919 ms | 15,039 ms | 30,866 ms | 15,171 ms | 22,731 ms | 31,279 ms |
| one rubric, 3 questions | 16,635 ms | 15,726 ms | 25,935 ms | 16,247 ms | 26,506 ms | 33,768 ms |
| three rubrics, 1 question | 14,792 ms | 13,590 ms | 21,896 ms | 14,177 ms | 21,329 ms | 27,948 ms |
| three rubrics, 2 questions | 32,387 ms | 29,292 ms | 41,903 ms | 19,185 ms | 28,505 ms | 36,506 ms |
| three rubrics, 3 questions | 44,005 ms | 43,335 ms | 61,461 ms | 22,178 ms | 34,028 ms | 45,530 ms |
| no rubric, 3 questions | 14,464 ms | 14,950 ms | 21,349 ms | 14,429 ms | 22,284 ms | 29,964 ms |

| | 0.1.61, earlier A | 0.1.61, earlier B | 0.1.61, same probe | two points, superseded | 0.1.62, same probe | 0.1.62, contended |
| --- | --- | --- | --- | --- | --- | --- |
| load average at start | not recorded | not recorded | 10.01 | not recorded | 16.59 | not recorded |
| live heap after a collection | not reported | not reported | 823 MiB | not reported | 822 MiB | 823 MiB |
| three rubrics: 3 questions / 1 question | **2.97x** | **3.19x** | **2.81x** | **1.56x** | **1.60x** | **1.63x** |
| one rubric: 3 questions / 1 question | **1.13x** | **1.22x** | **0.87x** | **1.16x** | **1.43x** | **1.26x** |
| cost of each question after the first, three rubrics | **14,606 ms** | **14,872 ms** | **19,782 ms** | **4,000 ms** | **6,350 ms** | **8,791 ms** |

Measured verdicts, 15 probability/confidence pairs, against 0.1.61, earlier A (the discarded warm-up batch is excluded):

- 0.1.61, earlier B: **identical**
- 0.1.61, same probe: **identical**
- two points, superseded: **identical**
- 0.1.62, same probe: **identical**
- 0.1.62, contended: **identical**

The discarded warm-up answer ranges 0.809192 to 0.809192 across these runs, a spread of 0.000000. It is the process's first forward pass, which is why it is discarded.

