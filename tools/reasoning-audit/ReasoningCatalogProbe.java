/* Copyright 2026 Integrallis Software, LLC. SPDX-License-Identifier: Apache-2.0 */
import com.integrallis.models.api.*;
import com.integrallis.models.runtime.chat.ChatMessage;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.*;
import java.util.*;
import org.modeljars.ModelJars;

/** Executes released runtimes through the exact AnswerStream emitted by the candidate CLI. */
public class ReasoningCatalogProbe {
  public static void main(String[] args) throws Exception {
    if (args.length != 2) throw new IllegalArgumentException("MODEL OUTPUT_JSON");
    if (args[0].equals("--resolve-all")) {
      var registry = org.modeljars.ModelJarRegistry.fromClasspath();
      for (String id : Files.readAllLines(Path.of(args[1]))) {
        var d = registry.descriptors().stream().filter(v -> v.alias().equals(id)).findFirst().orElseThrow();
        registry.resolve(org.modeljars.ModelJar.of(d.markerCoordinate().toString())).orElseThrow();
        System.out.println(id + " " + d.markerCoordinate());
      }
      return;
    }
    var mapper = new ObjectMapper();
    var report = new LinkedHashMap<String,Object>();
    report.put("model",args[0]);report.put("java",System.getProperty("java.runtime.version"));
    report.put("scope","default chat-demo display; not an accuracy or performance qualification");
    report.put("maxTokens",Integer.getInteger("modeljars.demo.maxTokens",64));
    report.put("contextLengthProperty",System.getProperty("models.purejava.maxContextLength", "runtime-default"));
    report.put("maxHeapBytes",Runtime.getRuntime().maxMemory());
    var cases = new ArrayList<Map<String,Object>>();report.put("cases",cases);
    var descriptor = org.modeljars.ModelJarRegistry.fromClasspath().descriptors().stream()
        .filter(d -> d.alias().equals(args[0])).findFirst().orElseThrow();
    if (Boolean.getBoolean("reasoning.audit.resolveOnly")) {
      System.out.println(descriptor.markerCoordinate()); return;
    }
    try (var runtime = ModelJars.openRuntime(descriptor.markerCoordinate().toString())) {
      report.put("artifactSha256",runtime.descriptor().sha256().orElseThrow());
      report.put("diagnostics",runtime.pipeline().diagnostics());
      boolean toolModel = runtime.descriptor().architecture().equals("needle2");
      var tools = toolModel ? List.of(new ToolSpec("set_lights", "Set the brightness of a room.",
          "{\"type\":\"object\",\"properties\":{\"room\":{\"type\":\"string\"},\"brightness\":{\"type\":\"integer\"}},\"required\":[\"room\",\"brightness\"]}")) : List.<ToolSpec>of();
      var inputs = toolModel ? List.of("Set the bedroom lights to 20 percent.") : List.of(
          "What is the capital of France? Reply with only the city name.",
          "What is 17 + 25? Reply with only the number.",
          "Context: The museum closes at 6 PM on Fridays. What is the admission price? Reply UNKNOWN if the context does not say.");
      for (String input : inputs) {
        runtime.pipeline().resetContext();
        var prompt = runtime.chatTemplate().render(List.of(ChatMessage.user(input)), tools);
        var raw = new StringBuilder();var visible = new StringBuilder();
        var output = new ModelJarsDemo.AnswerStream(visible::append,false,prompt.text(),descriptor.markerCoordinate().toString());
        var result = new LinkedHashMap<String,Object>();result.put("input",input);result.put("prompt",prompt.text());
        long start=System.nanoTime();
        var constraint = toolModel ? com.integrallis.models.runtime.ToolCallTokenConstraints.compile(
            runtime.tokenizer(), runtime.chatTemplate().toolSyntax(), tools, ignored -> List.of()).orElseThrow()
            : com.integrallis.models.runtime.TokenConstraint.unrestricted();
        runtime.pipeline().generate(prompt,SamplingOptions.builder().temperature(0).maxTokens(toolModel ? 128 : Integer.getInteger("modeljars.demo.maxTokens",64)).build(),new TokenStream(){
          public void onToken(String text){raw.append(text);output.onToken(text);}
          public void onComplete(){output.onComplete();}
          public void onComplete(GenerationUsage usage,StopReason reason){
            result.put("usage",usage);result.put("stopReason",reason.name());output.onComplete(usage,reason);
          }
          public void onError(Throwable failure){throw new IllegalStateException(failure);}
        },constraint);
        result.put("elapsedMillis",(System.nanoTime()-start)/1e6);
        result.put("raw",raw.toString());result.put("visible",visible.toString());
        boolean leaked=java.util.regex.Pattern.compile("(?i)</?(think|thinking|reasoning)>|## Thinking|<\\|channel>?|<channel\\|>").matcher(visible).find();
        result.put("reasoningMarkerLeak",leaked);result.put("hasFinalAnswer",output.hasAnswer());
        result.put("status",leaked?"REASONING_LEAK":!output.hasAnswer()?"NO_FINAL_ANSWER":"VISIBLE_ANSWER");
        cases.add(result);mapper.writerWithDefaultPrettyPrinter().writeValue(Path.of(args[1]).toFile(),report);
        System.out.println(args[0]+" "+result.get("status"));
      }
      report.put("complete",true);
    } catch(Throwable failure) {
      report.put("complete",false);report.put("failure",failure.toString());failure.printStackTrace(System.err);
    }
    mapper.writerWithDefaultPrettyPrinter().writeValue(Path.of(args[1]).toFile(),report);
  }
}
