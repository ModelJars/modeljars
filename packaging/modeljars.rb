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
    search = shell_output("#{bin}/modeljars search fintech --output json")
    assert_match "king3djbl_nexus_finance_gguf_q4_k_m", search
  end
end
