package com.influencermatch.backend.recommendation.research.eval;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class G8ReproducibilityVerifyTest {

  @Test
  void rerunStudyAIntoIsolatedReproducibilityDir() throws Exception {
    Path outDir = Path.of("docs", "research", "reproducibility");
    var summary = new MainExperimentExecutor().executeAll(outDir);
    System.out.println("REPRO_SUMMARY: worlds=" + summary.totalWorldsExecuted()
        + ", queries=" + summary.totalQueriesExecuted()
        + ", failed=" + summary.failedRuns()
        + ", empty=" + summary.emptyRuns()
        + ", elapsedMs=" + summary.elapsedMs()
        + ", sha256=" + summary.outputSha256());
    assertEquals(480, summary.totalWorldsExecuted());
    assertEquals(3840, summary.totalQueriesExecuted());
    assertEquals(0L, summary.failedRuns());
  }
}
