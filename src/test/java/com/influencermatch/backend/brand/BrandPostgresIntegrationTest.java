package com.influencermatch.backend.brand;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import com.influencermatch.backend.support.PostgresApiTestSupport;
import com.influencermatch.backend.user.enums.Role;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class BrandPostgresIntegrationTest extends PostgresApiTestSupport {
  @Test void createUpdateAndListKeepProfileAndContextConsistent() throws Exception {
    var owner=account(Role.BRAND);var other=account(Role.BRAND);createBrand(other);
    var body=Map.of("businessName","  Acme  ","productCategories",List.of(" Skin ","Skin"),
        "preferredPlatforms",List.of("TIKTOK","TIKTOK"));
    var created=call(owner,post("/brands"),body,201).path("data");var id=UUID.fromString(created.path("id").asText());
    assertThat(created.path("businessName").asText()).isEqualTo("Acme");assertThat(created.path("productCategories").size()).isEqualTo(1);
    assertThat(created.path("m4Version").asInt()).isEqualTo(1);
    call(owner,put("/brands/"+id),Map.of("businessName","Updated","targetMarkets",List.of(" VN ","VN")),200);
    var me=call(owner,get("/brands/me"),null,200).path("data");assertThat(me.path("m4Version").asInt()).isEqualTo(2);
    assertThat(jdbc.queryForObject("select profile_context->>'businessName' from brand_context where brand_profile_id=?",String.class,id)).isEqualTo("Updated");
    var page=call(owner,get("/brands?page=0&size=1"),null,200).path("data");
    assertThat(page.path("totalElements").asLong()).isEqualTo(1);assertThat(page.path("items").get(0).path("id").asText()).isEqualTo(id.toString());
    assertThat(call(owner,get("/brands?page=1&size=1"),null,200).path("data").path("items").size()).isZero();
  }
  @Test void duplicateAndForeignAccessNeverChangeProfileOrContext() throws Exception {
    var owner=account(Role.BRAND);var id=createBrand(owner);var foreign=account(Role.BRAND);
    assertThat(call(owner,post("/brands"),Map.of("businessName","Duplicate"),409).path("code").asText()).isEqualTo("BRAND_ALREADY_EXISTS");
    call(foreign,get("/brands/"+id),null,403);call(foreign,put("/brands/"+id),Map.of("businessName","Stolen"),403);
    call(foreign,post("/brands"),Map.of("businessName","Stolen","userId",owner.getId()),403);
    assertThat(jdbc.queryForObject("select business_name from brand_profile where id=?",String.class,id)).isEqualTo("Test Brand");
    assertThat(jdbc.queryForObject("select context_version from brand_context where brand_profile_id=?",Integer.class,id)).isEqualTo(1);
  }
  @ParameterizedTest @ValueSource(strings={"blank","tooLong","badWebsite","blankCategory"})
  void invalidProfileDoesNotCreateAnyRows(String kind) throws Exception {
    var owner=account(Role.BRAND);var body=new HashMap<String,Object>();body.put("businessName","Valid");
    switch(kind){case "blank"->body.put("businessName"," ");case "tooLong"->body.put("businessName","x".repeat(201));
      case "badWebsite"->body.put("website","not-a-url");case "blankCategory"->body.put("productCategories",List.of(" "));}
    call(owner,post("/brands"),body,400);
    assertThat(jdbc.queryForObject("select count(*) from brand_profile where user_id=?",Long.class,owner.getId())).isZero();
  }
  @Test void adminHasReadOnlySupportAndOtherRolesAreDenied() throws Exception {
    var owner=account(Role.BRAND);var id=createBrand(owner);var admin=account(Role.ADMIN);
    call(admin,get("/brands/"+id),null,200);call(admin,put("/brands/"+id),Map.of("businessName","Admin edit"),403);
    call(admin,post("/brands"),Map.of("businessName","Admin create","userId",owner.getId()),403);
    for(var role:List.of(Role.CREATOR,Role.DATA_MANAGER))call(account(role),get("/brands/"+id),null,403);
    call(null,get("/brands/"+id),null,401);call(owner,get("/brands/"+UUID.randomUUID()),null,404);
    assertThat(jdbc.queryForObject("select business_name from brand_profile where id=?",String.class,id)).isEqualTo("Test Brand");
  }
  @Test void contextFailureRollsBackProfileCreationAndUpdate() throws Exception {
    var owner=account(Role.BRAND);String marker="rollback-"+UUID.randomUUID();
    jdbc.execute("CREATE FUNCTION brand_context_test_failure() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF NEW.profile_context->>'businessName'='"+marker+"' THEN RAISE EXCEPTION 'context test failure'; END IF; RETURN NEW; END $$");
    jdbc.execute("CREATE TRIGGER brand_context_test_failure BEFORE INSERT OR UPDATE ON brand_context FOR EACH ROW EXECUTE FUNCTION brand_context_test_failure()");
    try {
      call(owner,post("/brands"),Map.of("businessName",marker),500);
      assertThat(jdbc.queryForObject("select count(*) from brand_profile where user_id=?",Long.class,owner.getId())).isZero();
      var id=createBrand(owner);
      call(owner,put("/brands/"+id),Map.of("businessName",marker),500);
      assertThat(jdbc.queryForObject("select business_name from brand_profile where id=?",String.class,id)).isEqualTo("Test Brand");
      assertThat(jdbc.queryForObject("select context_version from brand_context where brand_profile_id=?",Integer.class,id)).isEqualTo(1);
    } finally {jdbc.execute("DROP TRIGGER brand_context_test_failure ON brand_context");jdbc.execute("DROP FUNCTION brand_context_test_failure()");}
  }
}
