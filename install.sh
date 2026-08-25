#!/bin/sh
# ModelJars CLI installer. Downloads a checksummed GraalVM native executable; no Java is required.
#
#   curl -fsSL https://raw.githubusercontent.com/ModelJars/modeljars/main/install.sh | sh
#
# Overrides:
#   MODELJARS_CLI_VERSION       version to install (default: latest), for example 0.1.18
#   MODELJARS_CLI_INSTALL_DIR   target directory (default: $HOME/.local/bin)
set -eu

repository="ModelJars/modeljars"
install_directory="${MODELJARS_CLI_INSTALL_DIR:-$HOME/.local/bin}"
version="${MODELJARS_CLI_VERSION:-latest}"

case "$(uname -s)" in
  Linux) platform="linux" ;;
  Darwin) platform="macos" ;;
  *)
    echo "Unsupported operating system. On Windows, use Scoop or a release download." >&2
    exit 1
    ;;
esac

case "$(uname -m)" in
  x86_64 | amd64) architecture="x86_64" ;;
  arm64 | aarch64) architecture="aarch64" ;;
  *)
    echo "Unsupported architecture: $(uname -m)" >&2
    exit 1
    ;;
esac

asset="modeljars-${platform}-${architecture}"
if [ "$version" = "latest" ]; then
  base_url="https://github.com/${repository}/releases/latest/download"
else
  base_url="https://github.com/${repository}/releases/download/v${version}"
fi

temporary_binary="$(mktemp)"
temporary_checksum="$(mktemp)"
trap 'rm -f "$temporary_binary" "$temporary_checksum"' EXIT HUP INT TERM

echo "Installing ModelJars CLI (${platform}-${architecture}, version: ${version})..."
curl -fsSL "${base_url}/${asset}" -o "$temporary_binary"
curl -fsSL "${base_url}/${asset}.sha256" -o "$temporary_checksum"

expected_checksum="$(awk '{print $1}' "$temporary_checksum")"
if command -v sha256sum >/dev/null 2>&1; then
  actual_checksum="$(sha256sum "$temporary_binary" | awk '{print $1}')"
else
  actual_checksum="$(shasum -a 256 "$temporary_binary" | awk '{print $1}')"
fi
if [ "$actual_checksum" != "$expected_checksum" ]; then
  echo "SHA-256 mismatch for ${asset}" >&2
  exit 1
fi

mkdir -p "$install_directory"
install -m 0755 "$temporary_binary" "$install_directory/modeljars"
echo "Installed: $install_directory/modeljars"

case ":$PATH:" in
  *":$install_directory:"*) ;;
  *)
    echo "Add the install directory to PATH: export PATH=\"$install_directory:\$PATH\""
    ;;
esac

"$install_directory/modeljars" version
