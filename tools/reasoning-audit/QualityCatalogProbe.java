//JAVA 25+
//DEPS org.modeljars:modeljars:0.1.73
//DEPS org.modeljars:modeljars-catalog:0.1.73
//DEPS com.fasterxml.jackson.core:jackson-databind:2.22.3
//SOURCES ../../examples/flint/ggml-org-flint-chat-demo.java
//RUNTIME_OPTIONS --add-modules=jdk.incubator.vector --enable-native-access=ALL-UNNAMED
/* Copyright 2026 Integrallis Software, LLC. SPDX-License-Identifier: Apache-2.0 */
import com.fasterxml.jackson.databind.ObjectMapper;
import com.integrallis.models.api.*;
import com.integrallis.models.runtime.chat.ChatMessage;
import com.integrallis.models.runtime.chat.ChatTemplate;
import java.nio.file.*;
import java.util.*;
import org.modeljars.ModelJars;

/** Records exact prompts and answers for paired, manifest-driven default-chat diagnostics. */
public class QualityCatalogProbe {
  public static void main(String[] args) throws Exception {
    if (args.length != 2) throw new IllegalArgumentException("JOB_JSON OUTPUT_JSON");
    var mapper = new ObjectMapper();
    var job = mapper.readTree(Path.of(args[0]).toFile());
    var report = new LinkedHashMap<String,Object>();
    report.put("job", job);
    report.put("java", System.getProperty("java.runtime.version"));
    report.put("scope", "Paired chat diagnosis; not a RAG admission verdict");
    var cases = new ArrayList<Map<String,Object>>();
    report.put("cases", cases);
    var descriptor = org.modeljars.ModelJarRegistry.fromClasspath().descriptors().stream()
        .filter(d -> d.alias().equals(job.path("id").asText())).findFirst().orElseThrow();
    try (var runtime = ModelJars.openRuntime(descriptor.markerCoordinate().toString())) {
      report.put("artifactSha256", runtime.descriptor().sha256().orElseThrow());
      report.put("diagnostics", runtime.pipeline().diagnostics());
      for (var mode : job.path("modes")) {
        var template = mode.path("template").asText().equals("qualified")
            ? runtime.chatTemplate() : ChatTemplate.parse(mode.path("template").asText());
        for (var test : job.path("cases")) {
          runtime.pipeline().resetContext();
          var messages = new ArrayList<ChatMessage>();
          if (mode.has("system")) messages.add(ChatMessage.system(mode.path("system").asText()));
          messages.add(ChatMessage.user(test.path("input").asText()));
          var prompt = template.render(messages);
          if (mode.has("prefix")) {
            var builder = ModelPrompt.builder().control(mode.path("prefix").asText());
            if (mode.has("system")) builder.text(mode.path("system").asText()+"\n\n");
            prompt = builder.text(test.path("input").asText()).control(mode.path("suffix").asText()).build();
          }
          var raw = new StringBuilder(); var visible = new StringBuilder();
          var display = new ModelJarsDemo.AnswerStream(visible::append, false, prompt.text(), descriptor.markerCoordinate().toString());
          var result = new LinkedHashMap<String,Object>();
          result.put("mode", mode.path("name").asText()); result.put("caseId",test.path("id").asText());
          result.put("prompt", prompt.text()); result.put("expected", test.path("expected").asText());
          result.put("promptTokenIds", runtime.tokenizer().encode(prompt));
          result.put("template", template.toString());
          var options = SamplingOptions.builder().temperature(0).seed(42).topK(1).topP(1)
              .repetitionPenalty(1).maxTokens(job.path("maxTokens").asInt(512)).build();
          result.put("sampling", options);
          long start = System.nanoTime();
          runtime.pipeline().generate(prompt, options, new TokenStream() {
            public void onToken(String text) { raw.append(text); display.onToken(text); }
            public void onComplete() { display.onComplete(); }
            public void onComplete(GenerationUsage usage, StopReason reason) {
              result.put("usage", usage); result.put("stopReason", reason.name()); display.onComplete(usage, reason);
            }
            public void onError(Throwable failure) { throw new IllegalStateException(failure); }
          });
          result.put("elapsedMillis", (System.nanoTime()-start)/1e6);
          result.put("raw", raw.toString()); result.put("visible", visible.toString());
          result.put("exactAnswer", visible.toString().strip().equals(test.path("expected").asText()));
          cases.add(result);
          mapper.writerWithDefaultPrettyPrinter().writeValue(Path.of(args[1]).toFile(), report);
          System.out.println(job.path("id").asText()+" "+result.get("mode")+" "+result.get("caseId")+" exact="+result.get("exactAnswer")+" stop="+result.get("stopReason"));
        }
      }
      report.put("complete", true);
    } catch (Throwable failure) {
      report.put("complete", false); report.put("failure", failure.toString()); failure.printStackTrace();
    }
    mapper.writerWithDefaultPrettyPrinter().writeValue(Path.of(args[1]).toFile(), report);
    if (!Boolean.TRUE.equals(report.get("complete"))) System.exit(1);
  }
}
