//JAVA 25
//RUNTIME_OPTIONS --add-modules=jdk.incubator.vector
//RUNTIME_OPTIONS --enable-native-access=ALL-UNNAMED -Xmx4g
//DEPS org.modeljars.composite:harriet:0.1.61

import com.integrallis.models.api.ResumableInferenceBackend;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.modeljars.composite.harriet.Harriet;

/**
 * What one resumption point retains.
 *
 * Keeping a second resumption point -- one after the evidence as well as one after the evidence
 * and the rubric -- costs a second copy of the model's linear state. Resumption exposes only its
 * position, and LinearStateSnapshot.bytes() is not public, so the cost is measured from the
 * outside: collect, read the live heap, take a capture that stays strongly reachable, collect and
 * read again. The difference is the snapshot.
 *
 * Ten captures are held in turn so the figure is not one reading of a noisy number, and the
 * progression also shows whether a capture is a fixed cost or grows with the prefix.
 *
 * This measures the released 0.1.61 backend. The capture itself is backend behaviour and is
 * unchanged by the runtime's decision about how many points to keep.
 */
class snapshot_cost {

  private static final String EVIDENCE = """
      Ticket 4471, opened by the account holder on 14 September. The customer reports that their \
      September subscription was charged twice, eleven minutes apart, against the same card ending \
      4042. Both charges appear as captured, not authorised, in the billing export. The customer \
      contacted support twice: once through the web form, once by reply to the receipt email, and \
      has had no response in nine days. Their account is paid through to December and has no \
      prior billing disputes.""";

  public static void main(String... args) {
    quietLibraries();
    try (var harriet = Harriet.open()) {
      var backend = harriet.backend();
      if (!(backend instanceof ResumableInferenceBackend resumable) || !resumable.supportsResumption()) {
        System.out.println("this backend does not resume; nothing to measure");
        return;
      }

      var tokens = backend.tokenizer().encode(EVIDENCE);
      System.out.printf("evidence: %d tokens%n", tokens.length);
      backend.reset();
      backend.prefill(tokens, 0);

      long baseline = liveHeapBytes();
      System.out.printf("%-10s %14s %14s%n", "CAPTURES", "LIVE HEAP MIB", "PER CAPTURE KIB");
      System.out.printf("%-10d %14.1f %14s%n", 0, baseline / 1048576.0, "-");

      // Held in a list so every capture stays strongly reachable and none can be collected
      // between readings.
      List<ResumableInferenceBackend.Resumption> held = new ArrayList<>();
      for (int count = 1; count <= 10; count++) {
        held.add(resumable.capture());
        long live = liveHeapBytes();
        System.out.printf(
            "%-10d %14.1f %14.1f%n",
            count, live / 1048576.0, (live - baseline) / 1024.0 / count);
      }
      System.out.printf("positions held: %d%n", held.size());
    }
  }

  /** Live heap after a collection. A collection is a request, which is why this is repeated. */
  private static long liveHeapBytes() {
    var runtime = Runtime.getRuntime();
    for (int attempt = 0; attempt < 3; attempt++) {
      System.gc();
    }
    return runtime.totalMemory() - runtime.freeMemory();
  }

  private static void quietLibraries() {
    Logger.getLogger("org.modeljars").setLevel(Level.WARNING);
    Logger.getLogger("com.integrallis").setLevel(Level.WARNING);
  }
}
