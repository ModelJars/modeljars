//JAVA 25
//RUNTIME_OPTIONS --add-modules=jdk.incubator.vector
//RUNTIME_OPTIONS --enable-native-access=ALL-UNNAMED -Xmx4g
//DEPS org.modeljars.composite:harriet:0.1.61

import com.integrallis.models.decisions.AnswerSpace;
import com.integrallis.models.decisions.Choice;
import com.integrallis.models.decisions.Noul;
import com.integrallis.models.decisions.Score;
import com.integrallis.models.decisions.Verdict;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.modeljars.composite.harriet.Harriet;
import org.modeljars.ModelJarDecisionRuntime;

/**
 * What asking several questions about one piece of evidence actually costs.
 *
 * Harriet.decideAll says the evidence is "prefilled once and resumed per question, so a second
 * question about the same state costs a fraction of the first". That holds under a condition the
 * sentence does not state. The resumption point is captured after the SHARED PREFIX, which is the
 * evidence AND the rubric
 * (ModelJarDecisionRuntime.sharedPrefix: `criteria.isEmpty() ? state : state + "\n" + criteria`),
 * and it is reused only when the next question's shared prefix is byte-identical. Questions that
 * carry different rubrics -- a boolean, a choice over queues, an ordered severity, which is exactly
 * what a batch of real decisions looks like -- therefore re-read the evidence each time.
 *
 * This probe measures both shapes against the same evidence at one, two and three questions. It
 * prints each verdict's probability to six places as well as its latency, so the same run also
 * serves as the before/after check on any change to the prefix handling: the answers must not move.
 */
class grouping {

  /** Long enough that re-reading it costs more than the question that follows it. */
  private static final String EVIDENCE = """
      Ticket 4471, opened by the account holder on 14 September. The customer reports that their \
      September subscription was charged twice, eleven minutes apart, against the same card ending \
      4042. Both charges appear as captured, not authorised, in the billing export. The customer \
      contacted support twice: once through the web form, once by reply to the receipt email, and \
      has had no response in nine days. Their account is paid through to December and has no \
      prior billing disputes. The duplicate has not been reversed by the nightly reconciliation \
      job, which the billing export shows ran successfully on each of those nine nights. The \
      customer states that the second charge has taken their current account below zero and that \
      their bank has applied an overdraft fee, which they are asking to be reimbursed as well. \
      They have not threatened a chargeback and have asked, twice, simply to be called back.""";

  public static void main(String... args) {
    quietLibraries();
    try (var harriet = Harriet.open()) {
      // The first forward pass of the process pages 2.55 GiB of weights in and is not comparable
      // to anything after it: measured at 18,159 ms against 1,724 ms for the question that
      // followed it. Warm the runtime and throw that batch away.
      System.out.printf("%-44s %8s %9s %9s%n", "DISCARDED WARM-UP", "MS", "MS/Q", "HEAP MIB");
      measure(harriet, "warm-up, not a measurement", sameRubric().subList(0, 1));

      System.out.printf("%n%-44s %8s %9s %9s%n", "BATCH", "MS", "MS/Q", "HEAP MIB");
      measure(harriet, "one rubric, 1 question", sameRubric().subList(0, 1));
      measure(harriet, "one rubric, 2 questions", sameRubric().subList(0, 2));
      measure(harriet, "one rubric, 3 questions", sameRubric());
      measure(harriet, "three rubrics, 1 question", mixedRubrics().subList(0, 1));
      measure(harriet, "three rubrics, 2 questions", mixedRubrics().subList(0, 2));
      measure(harriet, "three rubrics, 3 questions", mixedRubrics());

      // What the rubric costs by sitting in the shared prefix: the same three questions with no
      // written rules at all share one prefix -- the evidence -- so every question after the
      // first resumes it. This is the headroom, not a recommendation: the same javadoc records
      // accuracy 0.7500 without rubrics against 0.8917 with them.
      measure(harriet, "no rubric, 3 questions", noRubric());
    }
  }

  /**
   * Three questions whose rubric is identical, so every shared prefix is the same string and the
   * capture taken after the first is reused by the rest.
   */
  private static List<AnswerSpace> sameRubric() {
    var whenTrue = "the ticket text supports the statement without inference";
    var whenFalse = "the ticket text does not support the statement, or says nothing about it";
    return List.of(
        new Noul("The customer was charged more than once.", whenTrue, whenFalse),
        new Noul("The customer is out of pocket right now.", whenTrue, whenFalse),
        new Noul("The customer has threatened a chargeback.", whenTrue, whenFalse));
  }

  /**
   * The three questions the support demo actually asks: a boolean, a choice and an ordered score,
   * each with its own rubric. No two shared prefixes match, so nothing is reused.
   */
  private static List<AnswerSpace> mixedRubrics() {
    return List.of(
        new Noul(
            "This ticket is about billing.",
            "the customer describes a charge, refund, invoice or subscription payment",
            "the customer describes delivery, product faults, accounts or anything not monetary"),
        new Choice(
            "Which queue should handle this?",
            List.of("billing", "technical", "shipping"),
            Map.of(
                "billing", "charges, refunds, invoices and subscription payments",
                "technical", "errors, outages and product faults",
                "shipping", "delivery, tracking and returns in transit")),
        new Score(
            "How urgent is this ticket?",
            List.of("can wait", "this week", "today"),
            Map.of(
                "can wait", "no money is at stake and nothing is blocked",
                "this week", "money is at stake but the customer is not blocked",
                "today", "the customer is out of pocket with no path forward")));
  }

  /** The same three propositions with no written rules, so the prefix is the evidence alone. */
  private static List<AnswerSpace> noRubric() {
    return List.of(
        new Noul("The customer was charged more than once."),
        new Noul("The customer is out of pocket right now."),
        new Noul("The customer has threatened a chargeback."));
  }

  /**
   * Times one batch against evidence nothing else in this run has asked about.
   *
   * The marker matters: the runtime holds one resumption point, so a batch asked about evidence a
   * previous batch already read would resume that read and measure a cache hit rather than the
   * batch. Every batch here starts from a cold evidence prefill, which is the case a caller
   * handling a new ticket is in.
   */
  private static void measure(ModelJarDecisionRuntime harriet, String label, List<AnswerSpace> spaces) {
    var evidence = EVIDENCE + " Internal marker: " + label + ".";
    long began = System.nanoTime();
    List<Verdict> verdicts = Harriet.decideAll(harriet, spaces, evidence);
    long millis = (System.nanoTime() - began) / 1_000_000;
    System.out.printf("%-44s %8d %9d %9d%n", label, millis, millis / spaces.size(), retainedMiB());
    for (int index = 0; index < verdicts.size(); index++) {
      var verdict = verdicts.get(index);
      System.out.printf(
          "    q%d %-14s p=%.6f  confidence=%.6f%n",
          index + 1, verdict.winner(), verdict.probabilityOf(verdict.winner()), verdict.confidence());
    }
  }

  /**
   * Live heap after a collection, in MiB.
   *
   * The runtime holds a resumption point per decision, and each point is a copy of the model's
   * linear state. How much that copy costs is not visible from the public API -- Resumption exposes
   * only its position -- so it is observed from the outside: the same probe on two builds, read
   * after a collection, differs by what the extra point retains. A forced collection is a request
   * and not a guarantee, which is why this is reported for every batch rather than once.
   */
  private static long retainedMiB() {
    var runtime = Runtime.getRuntime();
    System.gc();
    return (runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024);
  }

  private static void quietLibraries() {
    Logger.getLogger("org.modeljars").setLevel(Level.WARNING);
    Logger.getLogger("com.integrallis").setLevel(Level.WARNING);
  }
}
