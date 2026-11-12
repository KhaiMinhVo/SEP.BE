package com.influencermatch.backend.audit.service;

import com.influencermatch.backend.audit.dto.SystemStatusResponseDto;
import com.zaxxer.hikari.HikariDataSource;
import com.zaxxer.hikari.HikariPoolMXBean;
import java.lang.management.ManagementFactory;
import java.sql.Connection;
import java.time.Instant;
import javax.sql.DataSource;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Luồng AD-02: Gathers verifiable, honest system telemetry without simulated or mock statuses.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SystemMonitoringService {

  private final DataSource dataSource;
  private final JdbcTemplate jdbcTemplate;

  public SystemStatusResponseDto getSystemStatus() {
    Runtime runtime = Runtime.getRuntime();
    long totalMemory = runtime.totalMemory();
    long freeMemory = runtime.freeMemory();
    long usedMemory = totalMemory - freeMemory;
    long maxMemory = runtime.maxMemory();
    int processors = runtime.availableProcessors();
    long uptimeMs = ManagementFactory.getRuntimeMXBean().getUptime();

    SystemStatusResponseDto.JvmMetrics jvm =
        SystemStatusResponseDto.JvmMetrics.builder()
            .uptimeMs(uptimeMs)
            .totalMemoryBytes(totalMemory)
            .freeMemoryBytes(freeMemory)
            .usedMemoryBytes(usedMemory)
            .maxMemoryBytes(maxMemory)
            .availableProcessors(processors)
            .build();

    // Check Database Connectivity
    String dbStatus = "UP";
    long dbResponseTimeMs = -1L;
    String dbProductName = "Unknown";

    try {
      long start = System.currentTimeMillis();
      jdbcTemplate.queryForObject("SELECT 1", Integer.class);
      dbResponseTimeMs = System.currentTimeMillis() - start;

      try (Connection connection = dataSource.getConnection()) {
        dbProductName = connection.getMetaData().getDatabaseProductName() + " " + connection.getMetaData().getDatabaseProductVersion();
      }
    } catch (Exception e) {
      log.error("Database health check ping failed: {}", e.getMessage());
      dbStatus = "DOWN";
    }

    SystemStatusResponseDto.DatabaseMetrics database =
        SystemStatusResponseDto.DatabaseMetrics.builder()
            .status(dbStatus)
            .responseTimeMs(dbResponseTimeMs)
            .databaseProductName(dbProductName)
            .build();

    // Check Connection Pool
    SystemStatusResponseDto.ConnectionPoolMetrics poolMetrics = null;
    if (dataSource instanceof HikariDataSource hikari) {
      HikariPoolMXBean poolMxBean = hikari.getHikariPoolMXBean();
      poolMetrics =
          SystemStatusResponseDto.ConnectionPoolMetrics.builder()
              .poolName(hikari.getPoolName())
              .maximumPoolSize(hikari.getMaximumPoolSize())
              .activeConnections(poolMxBean != null ? poolMxBean.getActiveConnections() : null)
              .idleConnections(poolMxBean != null ? poolMxBean.getIdleConnections() : null)
              .totalConnections(poolMxBean != null ? poolMxBean.getTotalConnections() : null)
              .threadsAwaitingConnection(poolMxBean != null ? poolMxBean.getThreadsAwaitingConnection() : null)
              .build();
    }

    String overallStatus = "UP".equals(dbStatus) ? "HEALTHY" : "UNHEALTHY";

    return SystemStatusResponseDto.builder()
        .overallStatus(overallStatus)
        .timestamp(Instant.now())
        .jvm(jvm)
        .database(database)
        .connectionPool(poolMetrics)
        .build();
  }
}

