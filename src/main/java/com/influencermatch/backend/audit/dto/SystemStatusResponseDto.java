package com.influencermatch.backend.audit.dto;

import java.time.Instant;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Luồng AD-02: Verifiable system metrics response (JVM, Database connectivity, and Connection Pool).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SystemStatusResponseDto {

  private String overallStatus;
  private Instant timestamp;
  private JvmMetrics jvm;
  private DatabaseMetrics database;
  private ConnectionPoolMetrics connectionPool;

  @Data
  @Builder
  @NoArgsConstructor
  @AllArgsConstructor
  public static class JvmMetrics {
    private long uptimeMs;
    private long totalMemoryBytes;
    private long freeMemoryBytes;
    private long usedMemoryBytes;
    private long maxMemoryBytes;
    private int availableProcessors;
  }

  @Data
  @Builder
  @NoArgsConstructor
  @AllArgsConstructor
  public static class DatabaseMetrics {
    private String status;
    private long responseTimeMs;
    private String databaseProductName;
  }

  @Data
  @Builder
  @NoArgsConstructor
  @AllArgsConstructor
  public static class ConnectionPoolMetrics {
    private String poolName;
    private Integer activeConnections;
    private Integer idleConnections;
    private Integer totalConnections;
    private Integer threadsAwaitingConnection;
    private Integer maximumPoolSize;
  }
}

