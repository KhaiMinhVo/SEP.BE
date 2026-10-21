package com.influencermatch.backend.campaign;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import com.influencermatch.backend.support.PostgresApiTestSupport;
import com.influencermatch.backend.user.enums.Role;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class CampaignPostgresIntegrationTest extends PostgresApiTestSupport {
  @Test void fullLifecycleCreatesOneContextAndPreservesProfile() throws Exception {
    var owner=account(Role.BRAND);var brand=createBrand(owner);var id=createCampaign(owner,brand);
    var body=campaignBody();body.put("name"," Updated ");body.put("niches",List.of(" Beauty ","Beauty"));
    var updated=call(owner,put("/campaigns/"+id),body,200).path("data");assertThat(updated.path("name").asText()).isEqualTo("Updated");
    assertThat(updated.path("niches").size()).isEqualTo(1);
    assertThat(jdbc.queryForObject("select count(*) from campaign_context where campaign_id=?",Long.class,id)).isZero();
    for(String state:List.of("READY_FOR_DISCOVERY","ACTIVE","COMPLETED"))
      call(owner,patch("/campaigns/"+id+"/status"),Map.of("status",state),200);
    assertThat(jdbc.queryForObject("select count(*) from campaign_context where campaign_id=?",Long.class,id)).isEqualTo(1);
    assertThat(jdbc.queryForObject("select context_data->'niches'->>0 from campaign_context where campaign_id=?",String.class,id)).isEqualTo("Beauty");
    assertThat(jdbc.queryForObject("select context_version from brand_context where brand_profile_id=?",Integer.class,brand)).isEqualTo(1);
    var page=call(owner,get("/brands/"+brand+"/campaigns?size=1"),null,200).path("data");
    assertThat(page.path("totalElements").asLong()).isEqualTo(1);assertThat(page.path("items").get(0).path("id").asText()).isEqualTo(id.toString());
  }
  @ParameterizedTest @ValueSource(strings={"dates","followers","budget","negative","platforms","blankName"})
  void invalidCreateDoesNotWriteCampaignOrContext(String field) throws Exception {
    var owner=account(Role.BRAND);var brand=createBrand(owner);var body=campaignBody();
    switch(field){case "dates"->{body.put("startDate","2026-01-02");body.put("endDate","2026-01-01");}
      case "followers"->{body.put("followerMin",10);body.put("followerMax",9);}
      case "budget"->{body.put("budgetMin",10);body.put("budgetMax",9);}
      case "negative"->body.put("budgetMin",-1);case "platforms"->body.put("platforms",List.of());case "blankName"->body.put("name"," ");}
    var result=call(owner,post("/brands/"+brand+"/campaigns"),body,400);
    if(List.of("dates","followers","budget").contains(field))assertThat(result.path("code").asText()).isEqualTo("INVALID_CAMPAIGN_RANGE");
    assertThat(jdbc.queryForObject("select count(*) from campaign where brand_profile_id=?",Long.class,brand)).isZero();
  }
  @Test void ownershipAndReadOnlyAdminAreEnforced() throws Exception {
    var owner=account(Role.BRAND);var brand=createBrand(owner);var id=createCampaign(owner,brand);
    var other=account(Role.BRAND);var admin=account(Role.ADMIN);
    call(other,get("/campaigns/"+id),null,403);call(other,put("/campaigns/"+id),campaignBody(),403);
    call(other,post("/brands/"+brand+"/campaigns"),campaignBody(),403);
    call(admin,get("/campaigns/"+id),null,200);call(admin,put("/campaigns/"+id),campaignBody(),403);
    call(null,get("/campaigns/"+id),null,401);call(owner,get("/campaigns/"+UUID.randomUUID()),null,404);
    assertThat(jdbc.queryForObject("select status from campaign where id=?",String.class,id)).isEqualTo("DRAFT");
  }
  @Test void archiveIsIdempotentAndRejectedTransitionsDoNotWrite() throws Exception {
    var owner=account(Role.BRAND);var id=createCampaign(owner,createBrand(owner));
    assertThat(call(owner,patch("/campaigns/"+id+"/status"),Map.of("status","ACTIVE"),409).path("code").asText()).isEqualTo("INVALID_CAMPAIGN_TRANSITION");
    call(owner,patch("/campaigns/"+id+"/archive"),null,200);call(owner,patch("/campaigns/"+id+"/archive"),null,200);
    assertThat(call(owner,put("/campaigns/"+id),campaignBody(),409).path("code").asText()).isEqualTo("CAMPAIGN_NOT_EDITABLE");
    assertThat(jdbc.queryForObject("select status from campaign where id=?",String.class,id)).isEqualTo("ARCHIVED");
    assertThat(jdbc.queryForObject("select count(*) from campaign_context where campaign_id=?",Long.class,id)).isZero();
  }
  @Test void contextInsertFailureRollsBackReadyTransition() throws Exception {
    var owner=account(Role.BRAND);var id=createCampaign(owner,createBrand(owner));
    jdbc.execute("CREATE FUNCTION campaign_context_test_failure() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF NEW.campaign_id='"+id+"'::uuid THEN RAISE EXCEPTION 'context test failure'; END IF; RETURN NEW; END $$");
    jdbc.execute("CREATE TRIGGER campaign_context_test_failure BEFORE INSERT ON campaign_context FOR EACH ROW EXECUTE FUNCTION campaign_context_test_failure()");
    try {call(owner,patch("/campaigns/"+id+"/status"),Map.of("status","READY_FOR_DISCOVERY"),500);}
    finally {jdbc.execute("DROP TRIGGER campaign_context_test_failure ON campaign_context");jdbc.execute("DROP FUNCTION campaign_context_test_failure()");}
    assertThat(jdbc.queryForObject("select status from campaign where id=?",String.class,id)).isEqualTo("DRAFT");
    assertThat(jdbc.queryForObject("select count(*) from campaign_context where campaign_id=?",Long.class,id)).isZero();
  }
}
