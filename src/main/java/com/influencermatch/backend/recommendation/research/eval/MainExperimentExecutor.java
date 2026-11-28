package com.influencermatch.backend.recommendation.research.eval;

import com.influencermatch.backend.recommendation.research.dgp.DgpConfig.DgpRegime;
import com.influencermatch.backend.recommendation.research.dgp.DgpConfig.MissingnessIntensity;
import com.influencermatch.backend.recommendation.research.dgp.DgpConfig.MissingnessMechanism;
import com.influencermatch.backend.recommendation.research.eval.ExperimentRunner.MethodWorldMetrics;
import com.influencermatch.backend.recommendation.research.eval.ExperimentRunner.PairedWorldEvaluationReport;
import com.influencermatch.backend.recommendation.research.eval.ExperimentRunner.ResearchScorer;
import com.influencermatch.backend.recommendation.research.eval.ExperimentRunner.RunStatus;
import com.influencermatch.backend.recommendation.research.eval.PilotRunner.PilotWorldSpec;
import com.influencermatch.backend.recommendation.research.scorer.EvidenceConditionedShrinkageScorer;
import com.influencermatch.backend.recommendation.research.scorer.EvidenceConditionedShrinkageScorer.PriorMode;
import com.influencermatch.backend.recommendation.research.scorer.ResearchBaselineScorers;
import com.influencermatch.backend.recommendation.research.scorer.ResearchBaselineScorers.BaselineMethod;
import com.influencermatch.backend.recommendation.research.validation.ResearchJsonMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * G8 Locked Main Experiment Executor.
 * Runs 8 conditions x 30..60 independent worlds (seeds 10001L..10060L), 8 queries/world,
 * enforces MCSE <= 0.005 stopping rule (no p-value optional stopping), and writes all artifacts.
 */
public final class MainExperimentExecutor {

  private static final Instant START_AS_OF = Instant.parse("2026-09-01T00:00:00Z");
  private static final double MCSE_TARGET = 0.005;
  private static final int INITIAL_WORLDS = 30;
  private static final int MAX_WORLDS = 60;
  private static final int BATCH_SIZE = 10;
  private static final long FIRST_MAIN_SEED = 10001L;

  public record PairedStats(
      String condition, String comparison, int nWorlds,
      double meanDiff, double sd, double mcse, double ciLow, double ciHigh
  ) {}

  public record ExecutionSummary(
      int totalWorldsExecuted,
      int totalQueriesExecuted,
      long failedRuns,
      long emptyRuns,
      long elapsedMs,
      String outputSha256,
      List<PairedStats> pairedStats
  ) {}

  public ExecutionSummary executeAll(Path outputDir) throws IOException {
    long tStart = System.nanoTime();
    Files.createDirectories(outputDir);

    PilotRunner pilot = new PilotRunner();
    List<ResearchScorer> scorers = List.of(
        ResearchBaselineScorers.create(BaselineMethod.STRICT_GATING_BASELINE_V1, false, false),
        ResearchBaselineScorers.create(BaselineMethod.NEUTRAL_IMPUTATION_BASELINE_V1, false, false),
        ResearchBaselineScorers.create(BaselineMethod.AVAILABLE_FEATURE_RENORMALISATION_BASELINE_V1, false, false),
        EvidenceConditionedShrinkageScorer.asResearchScorer(PriorMode.EMPIRICAL_OBSERVED, false),
        ResearchBaselineScorers.create(BaselineMethod.LEGACY_REFERENCE_V1, false, false),
        EvidenceConditionedShrinkageScorer.asResearchScorer(PriorMode.NEUTRAL_FIXED_050, false),
        ResearchBaselineScorers.create(BaselineMethod.LEGACY_REFERENCE_V1, false, true)
    );

    StringBuilder perQueryJsonl = new StringBuilder();
    StringBuilder perWorldCsv = new StringBuilder(
        "condition,seed,method,status,queries,ndcgAt10,regretAt10,candCov,mainCov,fillAt10,fullKAvail,meanSm1,meanSm2,meanSm3,meanSm4\n");
    StringBuilder perCondCsv = new StringBuilder(
        "condition,nWorlds,method,ndcgAt10,regretAt10,candCov,mainCov,fillAt10,fullKAvail,failedRuns,emptyRuns\n");
    StringBuilder pairedCsv = new StringBuilder(
        "condition,comparison,nWorlds,meanDiff,sd,mcse,ci95Low,ci95High\n");

    int totalWorlds = 0;
    int totalQueries = 0;
    long totalFailed = 0;
    long totalEmpty = 0;
    List<PairedStats> allPairedStats = new ArrayList<>();
    Map<String, Integer> worldsPerCondition = new LinkedHashMap<>();

    for (DgpRegime regime : List.of(DgpRegime.DGP_A, DgpRegime.DGP_B)) {
      for (MissingnessMechanism mech : List.of(MissingnessMechanism.MCAR, MissingnessMechanism.MNAR)) {
        for (MissingnessIntensity intensity : List.of(MissingnessIntensity.LOW, MissingnessIntensity.HIGH)) {
          String cond = regime.name() + "|" + mech.name() + "|" + intensity.name();
          List<Map<String, WorldAgg>> worldAggs = new ArrayList<>();

          int nWorlds = 0;
          while (nWorlds < MAX_WORLDS) {
            int batch = (nWorlds == 0) ? INITIAL_WORLDS : BATCH_SIZE;
            for (int w = 0; w < batch; w++) {
              long seed = FIRST_MAIN_SEED + nWorlds + w;
              var spec = new PilotWorldSpec(regime, mech, intensity, seed, 3, 40, 6, 8, START_AS_OF);
              List<PairedWorldEvaluationReport> qReports = pilot.runPilotWorld(spec, scorers);
              totalQueries += qReports.size();

              Map<String, WorldAgg> byMethod = aggregateWorld(cond, seed, qReports, scorers, perQueryJsonl);
              for (WorldAgg wa : byMethod.values()) {
                perWorldCsv.append(wa.toCsvRow()).append('\n');
                totalFailed += wa.failedRuns();
                totalEmpty += wa.emptyRuns();
              }
              worldAggs.add(byMethod);
            }
            nWorlds += batch;
            if (maxPrimaryMcse(worldAggs) <= MCSE_TARGET) {
              break;
            }
          }

          totalWorlds += nWorlds;
          worldsPerCondition.put(cond, nWorlds);

          // Per-condition summary
          for (ResearchScorer s : scorers) {
            String m = s.scorerVersion();
            double ndcg = worldAggs.stream().mapToDouble(w -> w.get(m).ndcg()).average().orElse(0.0);
            double reg = worldAggs.stream().mapToDouble(w -> w.get(m).regret()).average().orElse(0.0);
            double cCov = worldAggs.stream().mapToDouble(w -> w.get(m).candCov()).average().orElse(0.0);
            double mCov = worldAggs.stream().mapToDouble(w -> w.get(m).mainCov()).average().orElse(0.0);
            double fill = worldAggs.stream().mapToDouble(w -> w.get(m).fill()).average().orElse(0.0);
            double fullK = worldAggs.stream().mapToDouble(w -> w.get(m).fullK()).average().orElse(0.0);
            long fails = worldAggs.stream().mapToLong(w -> w.get(m).failedRuns()).sum();
            long empties = worldAggs.stream().mapToLong(w -> w.get(m).emptyRuns()).sum();
            perCondCsv.append(String.format(Locale.ROOT,
                "%s,%d,%s,%.6f,%.6f,%.6f,%.6f,%.6f,%.6f,%d,%d%n",
                cond, nWorlds, m, ndcg, reg, cCov, mCov, fill, fullK, fails, empties));
          }

          // Paired comparisons at world level
          String shrinkKey = scorers.get(3).scorerVersion();
          Map<String, String> comps = new LinkedHashMap<>();
          comps.put("Shrinkage_vs_Neutral", scorers.get(1).scorerVersion());
          comps.put("Shrinkage_vs_AFR", scorers.get(2).scorerVersion());
          comps.put("Shrinkage_vs_Strict", scorers.get(0).scorerVersion());
          comps.put("Shrinkage_vs_Legacy", scorers.get(4).scorerVersion());
          for (var entry : comps.entrySet()) {
            PairedStats ps = computePairedStats(cond, entry.getKey(), worldAggs, shrinkKey, entry.getValue());
            allPairedStats.add(ps);
            pairedCsv.append(String.format(Locale.ROOT,
                "%s,%s,%d,%.6f,%.6f,%.6f,%.6f,%.6f%n",
                ps.condition(), ps.comparison(), ps.nWorlds(), ps.meanDiff(), ps.sd(), ps.mcse(), ps.ciLow(), ps.ciHigh()));
          }
        }
      }
    }

    long elapsedMs = (System.nanoTime() - tStart) / 1_000_000L;
    Files.writeString(outputDir.resolve("per_query_metrics.jsonl"), perQueryJsonl.toString(), StandardCharsets.UTF_8);
    Files.writeString(outputDir.resolve("per_world_metrics.csv"), perWorldCsv.toString(), StandardCharsets.UTF_8);
    Files.writeString(outputDir.resolve("per_condition_summary.csv"), perCondCsv.toString(), StandardCharsets.UTF_8);
    Files.writeString(outputDir.resolve("paired_comparisons.csv"), pairedCsv.toString(), StandardCharsets.UTF_8);

    String outputHash = sha256(perCondCsv + "\n" + pairedCsv);
    Map<String, Object> manifest = new LinkedHashMap<>();
    manifest.put("protocolVersion", "protocol.v1");
    manifest.put("dgpVersion", PilotRunner.PILOT_DGP_VERSION);
    manifest.put("metricVersion", EvaluationMetrics.METRIC_VERSION);
    manifest.put("executedAt", Instant.now().toString());
    manifest.put("javaVersion", System.getProperty("java.version"));
    manifest.put("osName", System.getProperty("os.name"));
    manifest.put("totalWorlds", totalWorlds);
    manifest.put("totalQueries", totalQueries);
    manifest.put("worldsPerCondition", worldsPerCondition);
    manifest.put("mcseTarget", MCSE_TARGET);
    manifest.put("elapsedMs", elapsedMs);
    manifest.put("failedRuns", totalFailed);
    manifest.put("emptyRuns", totalEmpty);
    manifest.put("outputSha256", outputHash);
    Files.writeString(outputDir.resolve("manifest.json"), ResearchJsonMapper.toDeterministicJson(manifest), StandardCharsets.UTF_8);

    return new ExecutionSummary(totalWorlds, totalQueries, totalFailed, totalEmpty, elapsedMs, outputHash, List.copyOf(allPairedStats));
  }

  private record WorldAgg(
      String condition, long seed, String method, String status, int queries,
      double ndcg, double regret, double candCov, double mainCov, double fill, double fullK,
      double sm1, double sm2, double sm3, double sm4, long failedRuns, long emptyRuns
  ) {
    String toCsvRow() {
      return String.format(Locale.ROOT,
          "%s,%d,%s,%s,%d,%.6f,%.6f,%.6f,%.6f,%.6f,%.6f,%.6f,%.6f,%.6f,%.6f",
          condition, seed, method, status, queries, ndcg, regret, candCov, mainCov, fill, fullK, sm1, sm2, sm3, sm4);
    }
  }

  private static Map<String, WorldAgg> aggregateWorld(
      String cond, long seed, List<PairedWorldEvaluationReport> qReports,
      List<ResearchScorer> scorers, StringBuilder perQueryJsonl) {

    Map<String, WorldAgg> map = new LinkedHashMap<>();
    for (int mIdx = 0; mIdx < scorers.size(); mIdx++) {
      String method = scorers.get(mIdx).scorerVersion();
      double ndcg = 0, reg = 0, cCov = 0, mCov = 0, fill = 0, fullK = 0, sm1 = 0, sm2 = 0, sm3 = 0, sm4 = 0;
      long fails = 0, empties = 0;
      int scoredQueries = 0;

      for (int q = 0; q < qReports.size(); q++) {
        var rep = qReports.get(q);
        MethodWorldMetrics mm = rep.methodMetrics().get(mIdx);
        double qMainCov = (double) mm.mainCount() / rep.universeCreatorIds().size();
        double qFullK = mm.mainCount() >= 10 ? 1.0 : 0.0;
        if (mm.status() == RunStatus.FAILED) fails++;
        if (mm.status() == RunStatus.EMPTY_RANKING) empties++;
        ndcg += mm.ndcgAt10();
        reg += mm.expectedUtilityRegretAt10();
        cCov += mm.candidateScoringCoverage();
        mCov += qMainCov;
        fill += mm.listFillRateAt10();
        fullK += qFullK;

        var rawOut = rep.rawScorerOutputs().get(method);
        if (rawOut != null && (!rawOut.mainRanked().isEmpty() || !rawOut.provisionalRanked().isEmpty())) {
          var all = new ArrayList<>(rawOut.mainRanked());
          all.addAll(rawOut.provisionalRanked());
          sm1 += all.stream().mapToDouble(r -> r.sm1()).average().orElse(0.0);
          sm2 += all.stream().mapToDouble(r -> r.sm2()).average().orElse(0.0);
          sm3 += all.stream().mapToDouble(r -> r.sm3()).average().orElse(0.0);
          sm4 += all.stream().mapToDouble(r -> r.sm4()).average().orElse(0.0);
          scoredQueries++;
        }

        perQueryJsonl.append(String.format(Locale.ROOT,
            "{\"condition\":\"%s\",\"seed\":%d,\"query\":%d,\"method\":\"%s\",\"status\":\"%s\",\"ndcgAt10\":%.6f,\"regretAt10\":%.6f,\"candCov\":%.4f,\"mainCov\":%.4f,\"fill\":%.4f}%n",
            cond, seed, q, method, mm.status().name(), mm.ndcgAt10(), mm.expectedUtilityRegretAt10(),
            mm.candidateScoringCoverage(), qMainCov, mm.listFillRateAt10()));
      }

      int qn = qReports.size();
      int sn = Math.max(1, scoredQueries);
      String status = fails > 0 ? "PARTIAL_FAILED" : (empties == qn ? "EMPTY" : "SUCCESS");
      map.put(method, new WorldAgg(cond, seed, method, status, qn,
          ndcg / qn, reg / qn, cCov / qn, mCov / qn, fill / qn, fullK / qn,
          sm1 / sn, sm2 / sn, sm3 / sn, sm4 / sn, fails, empties));
    }
    return map;
  }

  private static double maxPrimaryMcse(List<Map<String, WorldAgg>> worldAggs) {
    var first = worldAggs.getFirst();
    List<String> keys = List.copyOf(first.keySet());
    String shrink = keys.get(3);
    double max = 0.0;
    for (int idx : List.of(0, 1, 2, 4)) {
      max = Math.max(max, computePairedStats("", "", worldAggs, shrink, keys.get(idx)).mcse());
    }
    return max;
  }

  private static PairedStats computePairedStats(
      String cond, String comp, List<Map<String, WorldAgg>> worldAggs, String mA, String mB) {
    int n = worldAggs.size();
    double[] diffs = new double[n];
    double sum = 0.0;
    for (int i = 0; i < n; i++) {
      diffs[i] = worldAggs.get(i).get(mA).ndcg() - worldAggs.get(i).get(mB).ndcg();
      sum += diffs[i];
    }
    double mean = sum / n;
    double var = 0.0;
    for (double d : diffs) var += (d - mean) * (d - mean);
    double sd = Math.sqrt(var / Math.max(1, n - 1));
    double mcse = sd / Math.sqrt(n);
    double tCrit = (n <= 30) ? 2.045 : ((n <= 40) ? 2.023 : ((n <= 50) ? 2.010 : 2.001));
    return new PairedStats(cond, comp, n, mean, sd, mcse, mean - tCrit * mcse, mean + tCrit * mcse);
  }

  private static String sha256(String text) {
    try {
      byte[] d = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(d);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }
}
