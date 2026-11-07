package com.influencermatch.backend.recommendation.engine.presentation;

import java.util.ArrayList;
import java.util.List;

/**
 * Phase 6: Maximal Marginal Relevance (MMR) Diversity Reranker.
 * Selects candidates sequentially balancing individual Match Score
 * and diversity (dissimilarity) with already selected candidates:
 * MMR(i) = lambda * rel(i) - (1 - lambda) * max_{s in S} sim(i, s).
 * Crucial: MMR does NOT alter the candidate's original Match Score!
 */
public class MmrReranker {

  public static final double DEFAULT_LAMBDA = 0.70;

  public interface MmrCandidate {
    double matchScore(); // 0 to 100
    String niche();
    String category();
    float[] embedding(); // May be null
  }

  public <T extends MmrCandidate> List<T> rerank(List<T> candidates, int k, double lambda) {
    if (candidates == null || candidates.isEmpty() || k <= 0) {
      return List.of();
    }

    int targetK = Math.min(k, candidates.size());
    List<T> unselected = new ArrayList<>(candidates);
    List<T> selected = new ArrayList<>(targetK);

    while (selected.size() < targetK && !unselected.isEmpty()) {
      T bestCandidate = null;
      double bestScore = -Double.MAX_VALUE;
      int bestIndex = -1;

      for (int i = 0; i < unselected.size(); i++) {
        T cand = unselected.get(i);
        double rel = Math.max(0.0, Math.min(1.0, cand.matchScore() / 100.0));

        double maxSim = 0.0;
        if (!selected.isEmpty()) {
          for (T s : selected) {
            double sim = calculateSimilarity(cand, s);
            if (sim > maxSim) {
              maxSim = sim;
            }
          }
        }

        double mmrScore = (lambda * rel) - ((1.0 - lambda) * maxSim);
        if (mmrScore > bestScore) {
          bestScore = mmrScore;
          bestCandidate = cand;
          bestIndex = i;
        }
      }

      if (bestCandidate != null) {
        selected.add(bestCandidate);
        unselected.remove(bestIndex);
      } else {
        break;
      }
    }

    return selected;
  }

  private <T extends MmrCandidate> double calculateSimilarity(T a, T b) {
    // If both have vector embeddings, use vector cosine similarity
    if (a.embedding() != null && b.embedding() != null && a.embedding().length == b.embedding().length) {
      float[] v1 = a.embedding();
      float[] v2 = b.embedding();
      double dot = 0.0;
      double norm1 = 0.0;
      double norm2 = 0.0;
      for (int i = 0; i < v1.length; i++) {
        dot += v1[i] * v2[i];
        norm1 += v1[i] * v1[i];
        norm2 += v2[i] * v2[i];
      }
      if (norm1 > 1e-9 && norm2 > 1e-9) {
        double cos = dot / (Math.sqrt(norm1) * Math.sqrt(norm2));
        return (Math.max(-1.0, Math.min(1.0, cos)) + 1.0) / 2.0;
      }
    }

    // Fallback: categorical niche similarity
    String n1 = a.niche() != null ? a.niche().trim().toLowerCase() : "";
    String n2 = b.niche() != null ? b.niche().trim().toLowerCase() : "";
    String c1 = a.category() != null ? a.category().trim().toLowerCase() : "";
    String c2 = b.category() != null ? b.category().trim().toLowerCase() : "";

    if (!n1.isEmpty() && n1.equals(n2)) return 1.0;
    if (!c1.isEmpty() && c1.equals(c2)) return 0.70;
    return 0.10;
  }
}
