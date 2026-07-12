# ModelJars.org Operations and Local Model Candidate Report

Date: 2026-07-11

This report preserves the setup decisions for ModelJars.org and maps local
coding-model candidates against the current Java `models` runtime. The goal is
to make ModelJars useful as both:

- a neutral JVM marker-JAR catalog for discoverable model coordinates; and
- a compatibility layer that tells Java applications which local runtimes can
  execute a model.

The public site is ModelJars.org. If ModelJars.com is later acquired, use the
same DNS and GitHub Pages pattern below for that domain or redirect it to the
`.org` site.

## 1. ModelJars.org Setup Runbook

### Repository and Hosting

- GitHub org: `ModelJars`
- Repository: `ModelJars/modeljars`
- GitHub Pages mode: GitHub Actions
- Custom domain: `modeljars.org`
- Site source directory: `site/`
- Deployed files include:
  - `site/index.html`
  - `site/catalog.json`
  - `site/assets/app.js`
  - `site/assets/styles.css`
  - `site/CNAME`

The Pages workflow is `.github/workflows/pages.yml`. It deploys `site/` when
site files or the Pages workflow change.

### DNSimple Automation

The manual DNS workflow is `.github/workflows/configure-dns.yml`. It expects
these repository Actions secrets:

```text
DNSIMPLE_TOKEN
DNSIMPLE_ACCOUNT_ID
```

The workflow reconciles these records:

| Name | Type | Content |
|---|---|---|
| `@` | A | `185.199.108.153` |
| `@` | A | `185.199.109.153` |
| `@` | A | `185.199.110.153` |
| `@` | A | `185.199.111.153` |
| `@` | AAAA | `2606:50c0:8000::153` |
| `@` | AAAA | `2606:50c0:8001::153` |
| `@` | AAAA | `2606:50c0:8002::153` |
| `@` | AAAA | `2606:50c0:8003::153` |
| `www` | CNAME | `modeljars.github.io` |

The workflow also deletes conflicting apex `ALIAS` and `CNAME` records before
creating the GitHub Pages records. DNSimple uses an empty record `name` for the
zone apex in the v2 API.

Run the workflow:

```bash
gh workflow run configure-dns.yml --repo ModelJars/modeljars --ref main
gh run watch <run-id> --repo ModelJars/modeljars --exit-status
```

Verify DNS:

```bash
dig +short modeljars.org A
dig +short modeljars.org AAAA
dig +time=2 +tries=1 +short www.modeljars.org CNAME
```

Expected output:

```text
185.199.108.153
185.199.109.153
185.199.110.153
185.199.111.153

2606:50c0:8000::153
2606:50c0:8001::153
2606:50c0:8002::153
2606:50c0:8003::153

modeljars.github.io.
```

### HTTPS

GitHub Pages provisions the certificate after the custom domain resolves to
GitHub Pages. Immediately after DNS setup, the API can report:

```text
https_certificate.state = new
https_enforced = false
```

Trying to force HTTPS before the certificate is issued returns:

```text
Unavailable for your site because a certificate has not yet been issued for your domain
```

After the certificate state becomes ready, enforce HTTPS:

```bash
gh api --method PUT repos/ModelJars/modeljars/pages \
  -f build_type='workflow' \
  -f cname='modeljars.org' \
  -F https_enforced=true
```

Check the Pages state:

```bash
gh api repos/ModelJars/modeljars/pages
```

### Branch Protection During Bootstrap

`main` is protected. Normal changes should go through PRs. During initial
bootstrap, admin enforcement was temporarily disabled to push workflow-only
setup commits, then immediately restored.

Disable only when necessary:

```bash
gh api --method DELETE \
  repos/ModelJars/modeljars/branches/main/protection/enforce_admins
```

Restore immediately:

```bash
gh api --method POST \
  repos/ModelJars/modeljars/branches/main/protection/enforce_admins
```

Confirm:

```bash
gh api repos/ModelJars/modeljars/branches/main/protection/enforce_admins
```

### Single-Maintainer Bootstrap Reviews

When the organization has only one member, required code-owner and last-push
approval create a bootstrap deadlock: GitHub does not allow an author to approve
their own PR, including with `gh pr merge --admin`. After all required checks
pass, remove only the PR review requirement, merge, and restore it immediately:

```bash
gh api --method DELETE \
  repos/ModelJars/modeljars/branches/main/protection/required_pull_request_reviews

gh pr merge <pr-number> --repo ModelJars/modeljars --squash --delete-branch

gh api --method PUT \
  repos/ModelJars/modeljars/branches/main/protection/required_pull_request_reviews \
  -F dismiss_stale_reviews=true \
  -F require_code_owner_reviews=true \
  -F require_last_push_approval=true \
  -F required_approving_review_count=1
```

Do not remove status checks, branch restrictions, conversation resolution, or
linear-history requirements. Once a second maintainer can review PRs, this
bootstrap exception is no longer necessary.

## 2. Current Java Runtime Boundaries

The `projects/models` pure-Java backend is intentionally narrow today.

Current implemented surface:

- GGUF v2/v3 parser with memory-mapped tensor access.
- Llama-family decoder path.
- Metadata prefixes accepted by `LlamaConfig`: `llama`, `qwen2`, `qwen3`.
- Tensor storage types recognized by the parser include many GGUF types.
- Executed tensor paths cover F32, F16, Q4_0, Q5_0, Q8_0, Q4_K, and Q6_K.
- Tokenizers cover GPT-2-style byte-level BPE, Llama SentencePiece score merges,
  and a simple plain-BPE fallback.
- Strict end-to-end fixtures cover Qwen3 0.6B/1.7B, Qwen2.5-Coder
  0.5B/1.5B/3B, SmolLM2 360M, TinyLlama 1.1B, and DeepSeek-Coder 1.3B Q4_K_M.
  Qwen2.5-Coder 7B is in the strict large-model suite.

Important gaps:

- There is no Jinja/chat-template engine yet.
- Q5_K and newer K-quant variants are not executed yet.
- Gemma, Mistral v3/Tekken, BigCode/StarCoder, Phi, and other tokenizer families
  are not first-class.
- Split GGUF files are not resolved as a file set.
- MoE expert routing, MLA, diffusion decoding, hybrid attention, and
  multimodal projectors are not implemented.
- Larger models can be cataloged by ModelJars now, but pure-Java execution
  requires both runtime feature support and real reference tests.

Support levels used in the model table:

| Level | Meaning |
|---|---|
| `catalog-ready` | ModelJars can publish marker metadata now. This does not mean pure-Java execution works. |
| `external-runner` | A local runtime such as llama.cpp, Ollama, LM Studio, or vLLM can run it; ModelJars can point to that runner. |
| `near pure-java` | Same broad architecture family as the current backend, but needs targeted validation or small feature work. |
| `requires runtime work` | Needs new tokenizer, quantization, architecture, MoE, multimodal, or decoding support. |

## 3. Model Metadata ModelJars Should Track

The first marker uses `META-INF/modeljars/registry.properties`. As the catalog
grows, each descriptor should include enough compatibility metadata to decide
whether Java can execute the model or only catalog it.

Recommended fields:

| Field | Purpose |
|---|---|
| `sourceId` | Stable upstream identifier, for example `hf://Qwen/Qwen2.5-Coder-7B-Instruct-GGUF`. |
| `sourceUri` | Browser URL for the upstream model card. |
| `markerCoordinate` | Maven coordinate for the marker JAR. |
| `modelVersion` | Upstream model version where available. |
| `variant` | Quantization or serving variant, for example `q4_0`, `q8_0`, `q4_k_m`, `bf16`. |
| `format` | `gguf`, `safetensors`, `onnx`, `mlx`, etc. |
| `architecture` | Runtime architecture key, for example `qwen2`, `qwen3`, `gemma`, `starcoder2`, `deepseek2`. |
| `tokenizer` | Tokenizer family, for example `gpt2-bpe`, `sentencepiece`, `tekken`, `bigcode`, `gemma`. |
| `quantization` | Exact tensor storage family and whether the runtime implements it. |
| `localPath` | Default local cache path, never a required hard-coded path. |
| `files` | File list for split GGUF or sidecar artifacts. |
| `sha256` | Integrity check for each file when available. |
| `license` | License identifier and whether the model is gated or has use restrictions. |
| `capabilities` | `chat`, `text-generation`, `code-completion`, `fim`, `embedding`, `multimodal`, etc. |
| `backends` | Compatibility flags for `pure-java`, `llama.cpp`, `ollama`, `apple-foundation`, `onnx`, `mlx`. |
| `features` | Required runtime features: `qkv-bias`, `sliding-window`, `rope-scaling`, `moe`, `mla`, `mtp`, `diffusion`, `mmproj`. |
| `promptTemplates` | Chat and FIM templates, ideally stored as separate named resources. |

## 4. Candidate Model Matrix

The KDnuggets article "Top 7 Coding Models You Can Run Locally in 2026" lists
seven GGUF-oriented local models. That list is useful, but most are beyond the
current pure-Java backend because they use modern quantization variants,
MoE/hybrid architectures, multimodal projectors, or non-standard decoding.

The broader candidate list below prioritizes local coding usefulness,
availability of local artifacts, licensing posture, and likelihood of Java
runtime support.

| Candidate | Local artifact shape | Current ModelJars support | Current pure-Java support | What we need next |
|---|---|---|---|---|
| Qwen3 0.6B/1.7B GGUF | Official Q4_0/Q8_0 GGUF fixtures | Markers implemented | Supported by strict real-model tests; 0.6B has an exact llama.cpp token reference | Add chat templates and broader long-context quality tests. |
| Qwen2.5-Coder 0.5B/1.5B/3B Instruct GGUF | Official GGUF variants from Qwen; small enough for frequent CI/manual tests | Markers added for 0.5B/1.5B Q4_0/Q8_0 and 3B Q4_0 | Supported by strict real-file integration tests; 0.5B has an exact llama.cpp token reference | Add chat/FIM templates and performance baselines. |
| Qwen2.5-Coder 7B Instruct GGUF | Official GGUF variants; realistic local coding model | Q4_0 marker added | Supported by the strict `slowTest` large-model path | Add performance and memory benchmarks; keep it outside the default PR cache. |
| Qwen2.5-Coder 14B/32B Instruct GGUF | Official GGUF variants; strong coding target | Catalog-ready | Requires validation and larger-model performance work | Add split GGUF support, larger KV-cache memory controls, K-quant support if using Q4_K/Q6_K, and reference tests. |
| DeepSeek-Coder 1.3B Instruct | Pinned Q4_K_M GGUF with mixed Q4_K/Q5_0/Q8_0/Q6_K tensors | Marker implemented | Supported by strict download, checksum, tokenizer, tensor-inventory, legacy-RoPE, and llama.cpp token-reference tests | Add chat/FIM templates and performance baselines. |
| DeepSeek-Coder 6.7B Instruct | Local GGUF conversions exist; older dense coder | Catalog-ready after source/license verification | Requires runtime work | Add `deepseek` metadata alias if tensor layout matches; validate tokenizer; add chat/FIM templates. |
| DeepSeek-Coder-V2-Lite Instruct | Local quantized runners exist; MoE model | Catalog-ready | Requires runtime work | Implement DeepSeek-V2 architecture, MoE expert routing, MLA if present, tokenizer/template support. |
| Codestral 22B v0.1 | Local GGUF conversions exist; Mistral-family code model | Catalog-ready with license warning | Requires runtime work | Add Mistral/Codestral tokenizer support, architecture metadata prefix, sliding-window or attention variants if used, license gating metadata. |
| StarCoder2 3B/7B/15B | Local quantized artifacts exist; BigCode family | Catalog-ready | Requires runtime work | Implement StarCoder2/GPT-style decoder differences, BigCode tokenizer/FIM, tensor naming, and reference tests. |
| IBM Granite Code 3B/8B/20B/34B | Local HF weights; some GGUF conversions exist | Catalog-ready | Requires runtime work | Confirm GGUF tensor layout, add Granite architecture/tokenizer support, add Apache-2-friendly markers. |
| CodeLlama 7B/13B/34B Instruct | Many GGUF variants; older but stable baseline | Catalog-ready with Meta license metadata | Near pure-Java now that Llama SentencePiece is validated | Pin a license-compliant Q4_0/Q8_0 artifact, add strict reference tests, then add chat/FIM templates. |
| Qwen3-Coder 30B-A3B Instruct GGUF | GGUF MoE coding model | Catalog-ready | Requires runtime work | Implement MoE/expert routing and any Qwen3-Coder-specific metadata; add K-quant support. |
| Qwen3-Coder 480B-A35B or newer large MoE | Local quantization possible but heavy | Catalog-only unless external runner | Requires major runtime work | Treat as external-runner first; pure Java would require MoE, split files, memory planning, and performance work. |
| North Mini Code 1.0 | KDnuggets lists GGUF and Apache 2.0; 30B-A3B MoE | Catalog-ready after upstream verification | Requires runtime work | Add model metadata, MoE routing, tokenizer, and Qwen/DeepSeek compatibility investigation. |
| Google Gemma 4 31B IT QAT Q4_0 GGUF | KDnuggets lists Q4_0 GGUF plus multimodal-related artifacts | Catalog-ready with license metadata | Requires runtime work | Add Gemma architecture and tokenizer; keep multimodal projector optional; validate QAT Q4_0 tensors. |
| DiffusionGemma 26B A4B | KDnuggets describes local runner support via a special llama.cpp branch | Catalog-only for now | Not compatible | Requires diffusion-style generation rather than autoregressive decoding, plus MoE/hybrid support. |
| Nemotron Cascade 2 30B A3B GGUF | KDnuggets lists GGUF MoE-style local model | Catalog-ready after upstream verification | Requires runtime work | Add architecture metadata, MoE routing, tokenizer, and license/use restriction metadata. |
| EXAONE 4.5 33B GGUF | KDnuggets lists GGUF and non-commercial research license | Catalog-ready with license warning | Requires runtime work | Add EXAONE architecture/tokenizer; mark non-commercial or restricted use clearly. |
| Phi-4 Mini / Phi-4 family local weights | Small local models; not code-only but useful for Java smoke work | Catalog-ready | Requires runtime work | Add Phi architecture/tokenizer support; lower priority than Qwen2.5-Coder for coding. |
| OpenCoder / OpenCoder2 style 1.5B/8B | Coding models with local weights/conversions depending on release | Catalog-ready after source verification | Depends on architecture | Investigate tensor layout and tokenizer; useful if Apache/MIT licensed and GGUF Q4_0/Q8_0 exists. |

## 5. Small Fine-Tuning Candidates

These are popular small models worth tracking for local fine-tuning and LoRA
workflows. They are not all immediate pure-Java inference targets. ModelJars
should catalog them with their training-oriented metadata, license constraints,
and preferred inference formats.

| Candidate | Size | License / access | Why it matters | Pure-Java proximity |
|---|---:|---|---|---|
| Qwen2.5-Coder 0.5B / 1.5B / 3B | 0.5B-3B | 0.5B and 1.5B Apache-2.0; 3B Qwen Research | Strong code-specific small family; official report covers 0.5B through 32B; good Java smoke ladder. | Closest path. Qwen2 GGUF markers are now added for 0.5B/1.5B Q4_0/Q8_0 and 3B Q4_0. |
| Qwen3 0.6B / 1.7B | 0.6B-1.7B | Apache-2.0 | Modern Qwen family; 0.6B already validates the backend; 1.7B is a useful next general SLM. | Very close for GGUF Q4_0/Q8_0; current code accepts `qwen3`. |
| SmolLM2 135M / 360M / 1.7B | 0.135B-1.7B | Apache-2.0 | Widely used for edge demos and inexpensive LoRA experiments; strong Hugging Face ecosystem support. | 360M Q8_0 marker is added as the first non-Qwen pure-Java validation fixture. |
| TinyLlama 1.1B Chat | 1.1B | Apache-2.0 | Older but extremely popular tiny Llama-compatible fine-tuning baseline. | Q4_0 marker and strict pure-Java SentencePiece/inference tests are implemented. |
| DeepSeek-Coder 1.3B Instruct | 1.3B | DeepSeek license | Small code-specialized baseline with permissive-looking commercial posture but non-standard license naming. | Q4_K_M marker and strict pure-Java mixed-quant inference tests are implemented; templates remain. |
| StarCoder2 3B | 3B | BigCode OpenRAIL-M | Popular code model with FIM/code-completion focus and large ecosystem. | Requires StarCoder2 architecture and BigCode tokenizer/FIM support. |
| Granite 3.3 2B Instruct | 2B | Apache-2.0 | Enterprise-friendly IBM model; useful for OSS/commercial-friendly small-model catalog entries. | Requires Granite architecture support; likely not first pure-Java target. |
| CodeGemma 2B | 2B | Gemma license, gated acknowledgement | Small code-completion-focused model from Google. | Requires Gemma architecture/tokenizer support and license-gating metadata. |
| Gemma 3 1B / 4B | 1B-4B | Gemma license, gated acknowledgement | Very popular small general-purpose family; strong fine-tuning activity. | Requires Gemma 3 architecture/tokenizer support. |
| Llama 3.2 1B / 3B | 1B-3B | Llama 3.2 license, gated | Very popular fine-tuning baseline and broad tooling support. | Requires Llama 3 tokenizer/chat-template support plus gated-license metadata. |
| Phi-4 Mini Instruct | 3.8B | MIT | Popular small Microsoft model with code/general utility and permissive license. | Requires Phi architecture and tokenizer support; useful after Qwen/Llama tokenizers mature. |
| OLMo 2 1B Instruct | 1B | Apache-2.0 | Fully open ecosystem from Ai2; strong candidate for transparent fine-tuning workflows. | Requires OLMo2 architecture/tokenizer support. |

Recommended fine-tuning catalog priority:

1. Qwen2.5-Coder 0.5B and 1.5B, because they are also pure-Java runtime
   targets.
2. SmolLM2 and TinyLlama, because they provide small Apache-2.0 Llama-family
   compatibility pressure.
3. Granite 3.3 2B and OLMo 2 1B, because they are commercially friendly and
   useful to OSS users.
4. StarCoder2 3B and DeepSeek-Coder 1.3B, because they expand the code-model
   surface.
5. Gemma, Llama 3.2, and Phi-4 Mini, once license gating and tokenizer/runtime
   support are modeled cleanly.

## 6. Recommended Execution Plan

### Track A: Catalog more models immediately

ModelJars can publish marker JARs before pure-Java execution is complete, as
long as descriptors are honest about backend compatibility.

First catalog batch:

1. Qwen2.5-Coder 0.5B/1.5B Instruct GGUF. Q4_0 and Q8_0 markers are already
   added.
2. Qwen2.5-Coder 3B Instruct GGUF. Q4_0 marker is added for the next
   pure-Java validation tier.
3. Qwen2.5-Coder 7B Instruct GGUF. Q4_0 marker is added for the large-model
   pure-Java validation tier.
4. Qwen2.5-Coder 14B or 32B Instruct GGUF.
5. CodeLlama 7B Instruct GGUF.
6. StarCoder2 3B or 7B.
7. Granite Code 3B or 8B.

Each marker should start with:

```text
backends.pure-java=false
backends.llama-cpp=true
backends.ollama=true where an Ollama model/library entry is verified
```

Then flip `pure-java` to true only after parser, tokenizer, forward pass, and
reference generation tests pass.

### Track B: Qwen2.5-Coder pure-Java support (completed foundation)

Qwen2.5-Coder is the best next family because it is close to the existing
Qwen2/Qwen3 Llama-family path and has small variants for fast iteration.

Implemented foundation:

1. ModelJars markers cover 0.5B/1.5B Q4_0/Q8_0, 3B Q4_0, and 7B Q4_0.
2. Strict integration tasks resolve, download, checksum, and run every fixture.
3. Optional Q/K/V bias tensors are loaded and applied.
4. Exact greedy-token references compare the pinned 0.5B artifact with
   llama.cpp b9960.

Chat and fill-in-the-middle template resources remain follow-up work.

### Track C: Add quantization support in `vectors` first (foundation implemented)

Most practical local artifacts use K-quants, not Q4_0. To run larger models in
pure Java, implement these in `projects/vectors` and use them from `models`:

1. Q4_K dequantization plus direct and Q8_K-activation matvec are implemented.
2. Q5_0 direct and Q8_0-activation matvec are implemented for mixed files.
3. Q6_K direct and Q8_K-activation matvec are implemented.
4. Row-wise `MemorySegment` APIs and JMH coverage are implemented in `vectors`.

The kernels are consumed by `models-backend-purejava` and validated with a
real DeepSeek-Coder Q4_K_M artifact. Q5_K and newer K-quant families remain.

### Track D: Add tokenizer families before adding architecture families

Tokenizer mismatches make output invalid even when tensor math runs.

Priority:

1. Qwen/GPT-2 byte BPE correctness tests.
2. SentencePiece/Llama tokenizer (implemented and validated with TinyLlama).
3. Mistral/Tekken tokenizer if targeting Codestral/Mistral.
4. BigCode tokenizer and FIM behavior for StarCoder2.
5. Gemma tokenizer.

### Track E: External runner compatibility

For modern MoE or very large models, ModelJars should support external local
runners before pure-Java execution.

Add descriptor support for:

- llama.cpp command templates;
- Ollama model names and tags;
- LM Studio local server metadata;
- vLLM/TGI local OpenAI-compatible endpoint metadata.

This lets Java users resolve a model through ModelJars and run it locally from
Java even when the pure-Java backend is not ready.

## 7. Source Links

- KDnuggets article:
  <https://www.kdnuggets.com/top-7-coding-models-you-can-run-locally-in-2026>
- DNSimple zone records API:
  <https://developer.dnsimple.com/v2/zones/records/>
- GitHub Pages custom domain DNS records:
  <https://docs.github.com/pages/configuring-a-custom-domain-for-your-github-pages-site/managing-a-custom-domain-for-your-github-pages-site>
- Qwen2.5-Coder:
  <https://huggingface.co/Qwen/Qwen2.5-Coder-32B-Instruct-GGUF>
- Qwen2.5-Coder technical report:
  <https://arxiv.org/abs/2409.12186>
- Qwen2.5-Coder 0.5B GGUF:
  <https://huggingface.co/Qwen/Qwen2.5-Coder-0.5B-Instruct-GGUF>
- Qwen2.5-Coder 1.5B GGUF:
  <https://huggingface.co/Qwen/Qwen2.5-Coder-1.5B-Instruct-GGUF>
- Qwen3-Coder:
  <https://huggingface.co/unsloth/Qwen3-Coder-30B-A3B-Instruct-GGUF>
- Qwen3 1.7B:
  <https://huggingface.co/Qwen/Qwen3-1.7B>
- SmolLM2 1.7B:
  <https://huggingface.co/HuggingFaceTB/SmolLM2-1.7B-Instruct>
- TinyLlama 1.1B:
  <https://huggingface.co/TinyLlama/TinyLlama-1.1B-Chat-v1.0>
- DeepSeek-Coder-V2-Lite:
  <https://huggingface.co/deepseek-ai/DeepSeek-Coder-V2-Lite-Instruct>
- DeepSeek-Coder 1.3B:
  <https://huggingface.co/deepseek-ai/deepseek-coder-1.3b-instruct>
- Codestral:
  <https://huggingface.co/mistralai/Codestral-22B-v0.1>
- StarCoder2:
  <https://huggingface.co/bigcode/starcoder2-15b>
- StarCoder2 3B:
  <https://huggingface.co/bigcode/starcoder2-3b>
- IBM Granite Code:
  <https://huggingface.co/ibm-granite/granite-8b-code-instruct>
- Granite 3.3 2B:
  <https://huggingface.co/ibm-granite/granite-3.3-2b-instruct>
- CodeGemma 2B:
  <https://huggingface.co/google/codegemma-2b>
- Gemma 3 1B:
  <https://huggingface.co/google/gemma-3-1b-it>
- Llama 3.2 1B:
  <https://huggingface.co/meta-llama/Llama-3.2-1B-Instruct>
- Phi-4 Mini:
  <https://huggingface.co/microsoft/Phi-4-mini-instruct>
- OLMo 2 1B:
  <https://huggingface.co/allenai/OLMo-2-0425-1B-Instruct>
- CodeLlama:
  <https://huggingface.co/codellama/CodeLlama-7b-Instruct-hf>
