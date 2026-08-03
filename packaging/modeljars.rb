class Modeljars < Formula
  desc "Discover and securely prefetch qualified local AI models"
  homepage "https://modeljars.org"
  version "${VERSION}"
  license "Apache-2.0"

  on_macos do
    on_arm do
      url "${BASE}/modeljars-macos-aarch64"
      sha256 "${SHA_MAC_ARM}"
    end
    on_intel do
      url "${BASE}/modeljars-macos-x86_64"
      sha256 "${SHA_MAC_X64}"
    end
  end

  on_linux do
    on_arm do
      url "${BASE}/modeljars-linux-aarch64"
      sha256 "${SHA_LINUX_ARM}"
    end
    on_intel do
      url "${BASE}/modeljars-linux-x86_64"
      sha256 "${SHA_LINUX_X64}"
    end
  end

  def install
    bin.install Dir["modeljars-*"].first => "modeljars"
  end

  test do
    assert_match version.to_s, shell_output("#{bin}/modeljars version")
    assert_match "ALIAS", shell_output("#{bin}/modeljars search gemma")
  end
end
