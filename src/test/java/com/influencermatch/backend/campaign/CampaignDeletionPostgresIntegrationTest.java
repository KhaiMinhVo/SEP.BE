package com.influencermatch.backend.campaign;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

import com.influencermatch.backend.campaign.service.CampaignRevision;
import com.influencermatch.backend.support.PostgresApiTestSupport;
import com.influencermatch.backend.user.enums.Role;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class CampaignDeletionPostgresIntegrationTest extends PostgresApiTestSupport {
  @Test void deletesDraftAndShortlistButKeepsCreatorAndAudit() throws Exception {
    var owner = account(Role.BRAND);
    var id = createCampaign(owner, createBrand(owner));
    var creator = shortlistedCreator(id, owner);
    long revision = call(owner, get("/campaigns/" + id), null, 200).path("data").path("campaignRevision").asLong();
    call(owner, delete("/campaigns/" + id).header("If-Match", CampaignRevision.tag(id, revision)), null, 204);
    assertThat(jdbc.queryForObject("select count(*) from campaign where id=?", Integer.class, id)).isZero();
    assertThat(jdbc.queryForObject("select count(*) from shortlist_item where campaign_id=?", Integer.class, id)).isZero();
    assertThat(jdbc.queryForObject("select count(*) from creator where id=?", Integer.class, creator)).isEqualTo(1);
    assertThat(jdbc.queryForObject("select count(*) from audit_log where entity_id=? and action='DELETE_DRAFT_CAMPAIGN'", Integer.class, id)).isEqualTo(1);
  }

  @Test void linkedRelationshipPreventsDeletionOfBothCampaignAndShortlist() throws Exception {
    var owner = account(Role.BRAND);
    var id = createCampaign(owner, createBrand(owner));
    var creator = shortlistedCreator(id, owner);
    jdbc.update("insert into relationship(id,user_id,creator_id,campaign_id,stage,created_at,updated_at) values(?,?,?,?,'SHORTLISTED',now(),now())", UUID.randomUUID(), owner.getId(), creator, id);
    long revision = call(owner, get("/campaigns/" + id), null, 200).path("data").path("campaignRevision").asLong();
    call(owner, delete("/campaigns/" + id).header("If-Match", CampaignRevision.tag(id, revision)), null, 409);
    assertThat(jdbc.queryForObject("select count(*) from campaign where id=?", Integer.class, id)).isEqualTo(1);
    assertThat(jdbc.queryForObject("select count(*) from shortlist_item where campaign_id=?", Integer.class, id)).isEqualTo(1);
  }

  @Test void missingRevisionAndWrongOwnerCannotDelete() throws Exception {
    var owner = account(Role.BRAND);
    var id = createCampaign(owner, createBrand(owner));
    call(owner, delete("/campaigns/" + id), null, 428);
    call(account(Role.BRAND), delete("/campaigns/" + id).header("If-Match", CampaignRevision.tag(id, 0)), null, 403);
    assertThat(jdbc.queryForObject("select count(*) from campaign where id=?", Integer.class, id)).isEqualTo(1);
  }
}
