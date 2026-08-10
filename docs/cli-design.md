# ModelJars CLI design

The CLI is intentionally a model discovery, dependency, provenance, cache, and host-capability tool.
Inference stays in the Java runtime, so commands such as `run`, `serve`, and `ps` should not imply
that the small native CLI contains a second inference stack.

## Product precedents

- [Ollama's CLI](https://docs.ollama.com/cli) establishes the terse local-model vocabulary:
  `ls`, `show`, `pull`, `rm`, and command-specific help.
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

This leads to the ModelJars surface: `search/available`, `list/ls`, `show/inspect`, `pull`,
`remove/rm`, `snippet/coordinates/coords/dependency/deps`, `info/system/env`, `cache-dir`, and
`version`. The snippet command generates dependency declarations; the CLI deliberately does not
expose a shell-script generator under the ambiguous name `generate-completion`.

## Framework selection

[Picocli](https://picocli.info/) supplies typed options, subcommands and aliases, inherited global
options, generated help, ANSI-aware usage, and a supported GraalVM annotation processor. The
shipped CLI keeps its own small responsive table renderer because its
columns are catalog-specific and must degrade predictably on narrow terminals.

[JLine](https://jline.org/docs/terminal/) supplies the zero-argument interactive shell with line
editing, persistent history, and Picocli-aware tab completion. Supplying a command bypasses JLine
and retains deterministic one-shot execution. [OSHI](https://www.oshi.ooo/) offers broad hardware
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
- `NO_COLOR`, `--color never`, and redirected stdout disable color; `--color always` is useful for
  snapshots and demos.
- Hardware inventory is not backend availability. A discovered GPU is shown as detected while GPU
  model offload remains unsupported. An Apple-silicon host is eligible for Apple Foundation Models;
  the Java `backend-apple` runtime remains responsible for checking Apple Intelligence and model
  availability.
- Cache removal requires an exact alias, source ID, or coordinate and refuses symbolic links.
