package com.influencermatch.backend.creator;

import static org.assertj.core.api.Assertions.*;
import com.influencermatch.backend.support.PostgresApiTestSupport;
import java.util.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;

class CreatorMergePostgresIntegrationTest extends PostgresApiTestSupport {
  @Test void forwardUpgradePreservesOwnersAndSeparatesRegistrationFromClaimInvitations() {
    String schema="master_merge_"+UUID.randomUUID().toString().replace("-", "");
    Flyway.configure().dataSource(jdbc.getDataSource()).schemas(schema).defaultSchema(schema).target("14").load().migrate();
    UUID owner=UUID.randomUUID(), channel=UUID.randomUUID(), invite=UUID.randomUUID();
    try {
      jdbc.update("insert into "+schema+".\"user\"(id,email,full_name,role,status,created_at,updated_at) values(?,'merge@example.test','Creator','CREATOR','ACTIVE',now(),now())",owner);
      jdbc.update("insert into "+schema+".creator(id,platform,external_id,user_name,owner_user_id,source_type,authorized_at,created_at,updated_at) values(?,'TIKTOK','legacy','legacy',?,'AUTHORIZED',now(),now(),now())",channel,owner);
      jdbc.update("insert into "+schema+".creator_invitation(id,email,token_hash,expires_at,last_sent_at,invited_by) values(?,'invited@example.test',repeat('a',64),now()+interval '48 hours',now(),?)",invite,owner);
      var flyway=Flyway.configure().dataSource(jdbc.getDataSource()).schemas(schema).defaultSchema(schema).load();
      assertThat(flyway.migrate().migrationsExecuted).isEqualTo(2);
      assertThat(flyway.validateWithResult().validationSuccessful).isTrue();
      assertThat(jdbc.queryForObject("select count(*) from "+schema+".creator_master where user_id=?",Long.class,owner)).isEqualTo(1);
      assertThat(jdbc.queryForObject("select m.user_id from "+schema+".creator c join "+schema+".creator_master m on m.id=c.master_id where c.id=?",UUID.class,channel)).isEqualTo(owner);
      assertThat(jdbc.queryForObject("select owner_user_id from "+schema+".creator where id=?",UUID.class,channel)).isEqualTo(owner);
      assertThat(jdbc.queryForObject("select source_type from "+schema+".creator where id=?",String.class,channel)).isEqualTo("AUTHORIZED");
      assertThat(jdbc.queryForObject("select email from "+schema+".creator_invitation where id=?",String.class,invite)).isEqualTo("invited@example.test");
      assertThat(jdbc.queryForObject("select count(*) from "+schema+".creator_claim_invitation",Long.class)).isZero();
    } finally { jdbc.execute("drop schema "+schema+" cascade"); }
  }
}
