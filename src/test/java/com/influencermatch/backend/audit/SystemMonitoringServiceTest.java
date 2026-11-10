package com.influencermatch.backend.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

import com.influencermatch.backend.audit.dto.SystemStatusResponseDto;
import com.influencermatch.backend.audit.service.SystemMonitoringService;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class SystemMonitoringServiceTest {

  private final DataSource dataSource = mock(DataSource.class);
  private final JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
  private final SystemMonitoringService service = new SystemMonitoringService(dataSource, jdbcTemplate);

  @Test
  void collectsAuthenticMetricsWhenDatabaseIsUp() throws Exception {
    Connection connection = mock(Connection.class);
    DatabaseMetaData metaData = mock(DatabaseMetaData.class);

    when(dataSource.getConnection()).thenReturn(connection);
    when(connection.getMetaData()).thenReturn(metaData);
    when(metaData.getDatabaseProductName()).thenReturn("PostgreSQL");
    when(metaData.getDatabaseProductVersion()).thenReturn("16.0");
    when(jdbcTemplate.queryForObject(eq("SELECT 1"), eq(Integer.class))).thenReturn(1);

    SystemStatusResponseDto status = service.getSystemStatus();

    assertThat(status.getOverallStatus()).isEqualTo("HEALTHY");
    assertThat(status.getDatabase().getStatus()).isEqualTo("UP");
    assertThat(status.getDatabase().getDatabaseProductName()).contains("PostgreSQL 16.0");
    assertThat(status.getJvm().getUptimeMs()).isPositive();
    assertThat(status.getJvm().getTotalMemoryBytes()).isPositive();
    assertThat(status.getJvm().getAvailableProcessors()).isPositive();
  }

  @Test
  void handlesDatabasePingFailureGracefully() {
    when(jdbcTemplate.queryForObject(anyString(), eq(Integer.class)))
        .thenThrow(new RuntimeException("Connection refused"));

    SystemStatusResponseDto status = service.getSystemStatus();

    assertThat(status.getOverallStatus()).isEqualTo("UNHEALTHY");
    assertThat(status.getDatabase().getStatus()).isEqualTo("DOWN");
    assertThat(status.getJvm().getUptimeMs()).isPositive();
  }
}

