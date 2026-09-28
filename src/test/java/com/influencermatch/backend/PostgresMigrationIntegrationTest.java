package com.influencermatch.backend;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import java.sql.*;
import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers(disabledWithoutDocker = true)
class PostgresMigrationIntegrationTest {
    @Container static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @Test void migratesV5DataThroughCurrentV10Schema() throws Exception {
        Flyway.configure().dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .target("5").load().migrate();
        try (Connection connection = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             Statement statement = connection.createStatement()) {
            statement.executeUpdate("""
                    INSERT INTO users(id,email,password,full_name,role,status,version,created_at,updated_at)
                    VALUES ('10000000-0000-0000-0000-000000000001','migration@test.local','hash','Migration User','BRAND','INACTIVE',0,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP);
                    INSERT INTO brand_profiles(id,owner_user_id,business_name,status,version,industry,categories,target_markets,target_audience,preferred_platforms,created_at,updated_at)
                    VALUES ('20000000-0000-0000-0000-000000000001','10000000-0000-0000-0000-000000000001','Migration Brand','ACTIVE',0,'Beauty',ARRAY['Skincare'],ARRAY['Vietnam'],ARRAY['Gen Z'],ARRAY['TIKTOK'],CURRENT_TIMESTAMP,CURRENT_TIMESTAMP);
                    INSERT INTO campaigns(id,brand_profile_id,name,product_service,objective,target_audience,status,version,created_at,updated_at)
                    VALUES ('30000000-0000-0000-0000-000000000001','20000000-0000-0000-0000-000000000001','Migration Campaign','Serum','AWARENESS',ARRAY['Gen Z'],'DRAFT',0,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP);
                    INSERT INTO campaign_requirements(id,campaign_id,platforms,niches,creator_tiers,locations,follower_min,follower_max,budget_min,budget_max,currency,created_at,updated_at)
                    VALUES ('40000000-0000-0000-0000-000000000001','30000000-0000-0000-0000-000000000001',ARRAY['TIKTOK'],ARRAY['skincare'],ARRAY['MICRO'],ARRAY['HCMC'],10000,50000,3000000,5000000,'VND',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)
                    """);
        }
        Flyway.configure().dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()).load().migrate();
        try (Connection connection = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             PreparedStatement statement = connection.prepareStatement("select count(*) from information_schema.tables where table_schema='public' and table_name <> 'flyway_schema_history'");
             ResultSet result = statement.executeQuery()) {
            result.next();
            assertThat(result.getInt(1)).isEqualTo(31);
        }
        try (Connection connection = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             PreparedStatement statement = connection.prepareStatement("select count(*) from information_schema.tables where table_schema='public' and table_name in ('externalIdentity','authExchangeCode')");
             ResultSet result = statement.executeQuery()) {
            result.next();
            assertThat(result.getInt(1)).isEqualTo(2);
        }
        try (Connection connection = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             PreparedStatement statement = connection.prepareStatement("select is_nullable from information_schema.columns where table_schema='public' and table_name='user' and column_name='passwordHash'");
             ResultSet result = statement.executeQuery()) {
            assertThat(result.next()).isTrue();
            assertThat(result.getString(1)).isEqualTo("YES");
        }
        try (Connection connection = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             PreparedStatement statement = connection.prepareStatement("select count(*) from information_schema.tables where table_schema='public' and table_name in ('user','refreshToken','brandProfile','brandContextM4','campaign','campaignContextM3')");
             ResultSet result = statement.executeQuery()) {
            result.next();
            assertThat(result.getInt(1)).isEqualTo(6);
        }
        try (Connection connection = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             PreparedStatement statement = connection.prepareStatement("select to_regclass('public.campaign_requirements')");
             ResultSet result = statement.executeQuery()) {
            result.next();
            assertThat(result.getString(1)).isNull();
        }
        try (Connection connection = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             PreparedStatement statement = connection.prepareStatement("select platforms[1], niches[1], locations[1], \"followerMin\", \"followerMax\", \"budgetMin\", \"budgetMax\" from \"campaign\" where \"campaignId\"='30000000-0000-0000-0000-000000000001'");
             ResultSet result = statement.executeQuery()) {
            assertThat(result.next()).isTrue();
            assertThat(result.getString(1)).isEqualTo("TIKTOK");
            assertThat(result.getString(2)).isEqualTo("skincare");
            assertThat(result.getString(3)).isEqualTo("HCMC");
            assertThat(result.getLong(4)).isEqualTo(10000);
            assertThat(result.getLong(5)).isEqualTo(50000);
            assertThat(result.getBigDecimal(6)).isEqualByComparingTo("3000000");
            assertThat(result.getBigDecimal(7)).isEqualByComparingTo("5000000");
        }
        try (Connection connection = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             PreparedStatement statement = connection.prepareStatement("select count(*) from information_schema.columns where table_schema='public' and table_name <> 'flyway_schema_history' and column_name like '%\\_%' escape '\\'");
             ResultSet result = statement.executeQuery()) {
            result.next();
            assertThat(result.getInt(1)).isZero();
        }
        try (Connection connection = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             PreparedStatement statement = connection.prepareStatement("select \"status\" from \"user\" where \"userId\"='10000000-0000-0000-0000-000000000001'");
             ResultSet result = statement.executeQuery()) {
            assertThat(result.next()).isTrue();
            assertThat(result.getString(1)).isEqualTo("DISABLED");
        }
    }
}
