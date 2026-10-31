/* Copyright 2026 Integrallis Software, LLC. SPDX-License-Identifier: Apache-2.0 */
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.integrallis.models.api.GenerationUsage;
import com.integrallis.models.api.StopReason;
import java.nio.file.*;

/** Replays retained live completions through the final generated display parser, without inference. */
public class ReasoningReplay {
  public static void main(String[] args) throws Exception {
    var mapper = new ObjectMapper();
    Path input=Path.of(args[0]),output=Path.of(args[1]);
    try(var paths=Files.walk(input)) {
      for(Path path:paths.filter(p->p.toString().endsWith(".json")).toList()) {
        var report=mapper.readTree(path.toFile());
        if(!report.has("cases")) continue;
        for(var item:report.withArray("cases")) {
          var c=(ObjectNode)item;
          var text=new StringBuilder();
          var display=new ModelJarsDemo.AnswerStream(text::append,false,c.path("prompt").asText());
          String raw=c.path("raw").asText();
          // Character boundaries exercise delimiters independently of the original tokenizer.
          for(int i=0;i<raw.length();i++)display.onToken(raw.substring(i,i+1));
          display.onComplete(new GenerationUsage(c.path("usage").path("promptTokens").asInt(),
              c.path("usage").path("completionTokens").asInt()),StopReason.valueOf(c.path("stopReason").asText()));
          c.set("livePreviousVisible",c.get("visible"));c.put("visible",text.toString());
          boolean leak=java.util.regex.Pattern.compile("(?i)</?(think|thinking|reasoning)>|<\\|channel>?|<channel\\|>").matcher(text).find();
          c.put("reasoningMarkerLeak",leak);c.put("hasFinalAnswer",display.hasAnswer());
          c.put("status",leak?"REASONING_LEAK":!display.hasAnswer()?"NO_FINAL_ANSWER":"VISIBLE_ANSWER");
        }
        ((ObjectNode)report).put("displayVerification","Final generated parser replay over retained live raw completions; elapsedMillis remains original inference timing, not replay time");
        Path target=output.resolve(input.relativize(path));Files.createDirectories(target.getParent());
        mapper.writerWithDefaultPrettyPrinter().writeValue(target.toFile(),report);
      }
    }
  }
}
