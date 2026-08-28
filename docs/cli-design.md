# ModelJars CLI design

The CLI covers model discovery, dependencies, provenance, cache management, host capabilities, and
small local interactions. Its `run` and `embed` commands call the same in-process ModelJars and
Models APIs as an application. They do not introduce a second inference implementation or delegate
to an external model server.

## Product precedents

- [Ollama's CLI](https://docs.ollama.com/cli) establishes the terse local-model vocabulary:
  `ls`, `show`, `pull`, `rm`, and a `run` command that becomes interactive when its prompt is
  omitted.
- [Cactus](https://docs.cactuscompute.com/) and
  [Needle](https://cactuscompute.com/needle) demonstrate direct local model interaction and
  structured tool-oriented models without requiring a resident server.
- [Docker `info`](https://docs.docker.com/reference/cli/docker/system/info/) and
  [Docker `inspect`](https://docs.docker.com/reference/cli/docker/inspect/) provide the useful split
  between machine/runtime capabilities and exact object metadata. Docker's
  [formatting support](https://docs.docker.com/engine/cli/formatting/) also demonstrates why human
  tables and machine output must be separate contracts.
- The [Hugging Face CLI](https://huggingface.co/docs/huggingface_hub/en/guides/cli) reinforces model
  search filters, human-readable sizes, quiet downloads whose last line is the local path, an
  environment command, aliases, and JSON output for automation.
- [GitHub CLI formatting](https://cli.github.com/manual/gh_help_formatting) treats structured JSON
  output as an explicit automation surface rather than asking scripts to scrape decorative tables.

This leads to the ModelJars surface: `search/available/models`, `list/ls`, `show/inspect`, `pull`,
`remove/rm/delete`, `alias/nickname`, `run/chat`, `embed/embedding`,
`snippet/coordinates/coords/dependency/deps`, `info/system/env`, `cache-dir`, and `version`. The
snippet command generates dependency declarations; the CLI deliberately does not expose a
shell-script generator under the ambiguous name `generate-completion`.

## Framework selection

[Picocli](https://picocli.info/) supplies typed options, subcommands and aliases, inherited global
options, generated help, ANSI-aware usage, and a supported GraalVM annotation processor. The
shipped CLI keeps its own small responsive table renderer because its
columns are catalog-specific and must degrade predictably on narrow terminals.

[JLine](https://jline.org/docs/terminal/) supplies the zero-argument interactive shell with line
editing, persistent history, and Picocli-aware tab completion. Model-taking commands complete both
catalog aliases and non-conflicting persistent nicknames. Its terminal and `Display`
abstractions also render model downloads on stderr without corrupting the interactive prompt or
machine-readable stdout. Supplying a command retains deterministic one-shot execution while using
JLine only when stderr is a capable terminal. [OSHI](https://www.oshi.ooo/) offers broad hardware
inventory, but its JNA and
JDK-25 FFM variants add native-access and packaging constraints to a binary released for five
platforms. The current bounded host probe uses JDK management APIs plus direct, timeout-limited OS
commands, degrades to explicit `unknown` values, and has parser tests for macOS, NVIDIA/Linux, and
Windows output.

## Output and capability rules

- Tables are for people. They use human byte sizes, right-aligned numeric columns, and color only on
  a terminal. The default catalog and cache views are compact. Installed-model identifiers and
  requested dependency coordinates are never truncated; `--details` adds complete capability and
  backend metadata as labelled continuation lines.
- JSON is the stable structured interface. Plain output is tab- or key/value-oriented. Neither ever
  contains ANSI escapes.
- Pull progress is structured in `modeljars-core`, not parsed from log messages. Terminal rendering
  is throttled independently of byte events; redirected output uses stable phase lines, and quiet
  output is path-only.
- `NO_COLOR`, `--color never`, and redirected stdout disable color; `--color always` is useful for
  snapshots and demos.
- Hardware inventory is not backend availability. A discovered GPU is shown as detected while GPU
  model offload remains unsupported. An Apple-silicon host is eligible for Apple Foundation Models;
  the Java `backend-apple` runtime remains responsible for checking Apple Intelligence and model
  availability.
- Cache removal requires an exact alias, source ID, or coordinate and refuses symbolic links.
- `run` renders conversation history with the qualified chat template and streams the Models
  `InferencePipeline`; `embed` calls the qualified embedding runtime. Both use the shared verified
  cache and reject models that lack the corresponding qualification.
- Chat metrics distinguish model load time, TTFT, total generation time, exact input/output token
  counts, and decode throughput. Embedding metrics report load and execution time, vector width,
  and norm. Human, plain, and JSON output carry the same measurements.
