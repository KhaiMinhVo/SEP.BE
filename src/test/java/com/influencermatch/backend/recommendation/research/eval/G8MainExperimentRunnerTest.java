package com.influencermatch.backend.recommendation.research.eval;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class G8MainExperimentRunnerTest {

  @Test
  void executeLockedMainExperimentsAndWriteArtifacts() throws Exception {
    Path outDir = Path.of("docs", "research", "results");
    var summary = new MainExperimentExecutor().executeAll(outDir);

    System.out.printf("G8_SUMMARY: worlds=%d, queries=%d, failed=%d, empty=%d, elapsedMs=%d, sha256=%s%n",
        summary.totalWorldsExecuted(), summary.totalQueriesExecuted(),
        summary.failedRuns(), summary.emptyRuns(), summary.elapsedMs(), summary.outputSha256());
    for (var ps : summary.pairedStats()) {
      System.out.printf("G8_PAIRED[%s | %s]: N=%d, mean=%.5f, sd=%.5f, mcse=%.5f, CI95=[%.5f, %.5f]%n",
          ps.condition(), ps.comparison(), ps.nWorlds(), ps.meanDiff(), ps.sd(), ps.mcse(), ps.ciLow(), ps.ciHigh());
    }

    assertThat(summary.totalWorldsExecuted()).isGreaterThanOrEqualTo(240);
    assertThat(summary.failedRuns()).isZero();
    assertThat(outDir.resolve("manifest.json")).exists();
    assertThat(outDir.resolve("per_condition_summary.csv")).exists();
    assertThat(outDir.resolve("paired_comparisons.csv")).exists();
    assertThat(outDir.resolve("per_world_metrics.csv")).exists();
    assertThat(outDir.resolve("per_query_metrics.jsonl")).exists();
  }
}
