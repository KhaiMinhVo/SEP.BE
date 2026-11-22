package com.influencermatch.backend.storage;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

import com.influencermatch.backend.support.PostgresApiTestSupport;
import com.influencermatch.backend.user.enums.Role;
import java.time.LocalDateTime;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.mock.web.MockMultipartFile;

/** Real API, authorization, Flyway and PostgreSQL; only external cloud IO is replaced. */
class ImageStoragePostgresIntegrationTest extends PostgresApiTestSupport {
  @MockBean CloudinaryStorageService storage;

  StoredMedia media(String key) {
    return new StoredMedia("https://res.cloudinary.com/test/image/upload/" + key + ".png",
        key, "image/png", 100, 8, 8, LocalDateTime.now());
  }

  MockMultipartFile file() {
    return new MockMultipartFile("file", "image.png", "image/png", new byte[] {1, 2, 3});
  }

  @Test void brandImageUploadPersistsMetadataEnforcesRevisionAndKeepsOldImageOnFailure() throws Exception {
    var user = account(Role.BRAND);
    var brand = createBrand(user);
    var oldRevision = call(user, get("/brands/me"), null, 200).path("data").path("profileRevision").asLong();
    when(storage.uploadFile(any(), anyString(), anyString())).thenReturn(media("logo"));
    var response = call(user, multipart("/brands/me/images/logo").file(file())
        .header("If-Match", "\"brand:" + brand + ":r" + oldRevision + "\""), null, 200).path("data");
    assertThat(response.path("logoUrl").asText()).isEqualTo(media("logo").url());
    assertThat(jdbc.queryForObject("select logo_storage_key from brand_profile where id=?", String.class, brand))
        .isEqualTo("logo");
    call(user, multipart("/brands/me/images/logo").file(file())
        .header("If-Match", "\"brand:" + brand + ":r" + oldRevision + "\""), null, 409);
    when(storage.uploadFile(any(), anyString(), anyString()))
        .thenThrow(new com.influencermatch.backend.exception.ValidationException("upload failed"));
    call(user, multipart("/brands/me/images/logo").file(file())
        .header("If-Match", "\"brand:" + brand + ":r" + response.path("profileRevision").asLong() + "\""), null, 400);
    assertThat(call(user, get("/brands/me"), null, 200).path("data").path("logoUrl").asText())
        .isEqualTo(media("logo").url());
  }

  @Test void campaignUploadAndLinkImportPersistOnlyCloudMetadataAndDenyForeignUploads() throws Exception {
    var user = account(Role.BRAND);
    var brand = createBrand(user);
    var campaign = createCampaign(user, brand);
    when(storage.isConfigured()).thenReturn(true);
    when(storage.uploadFile(any(), anyString(), anyString())).thenReturn(media("cover"));
    var image = call(user, multipart("/brands/" + brand + "/campaign-images").file(file()), null, 200).path("data");
    assertThat(image.path("storageKey").asText()).isEqualTo("cover");
    var body = campaignBody(); body.put("imageUrl", image.path("imageUrl").asText());
    call(user, put("/campaigns/" + campaign), body, 200);
    assertThat(jdbc.queryForObject("select image_storage_key from campaign where id=?", String.class, campaign))
        .isEqualTo("cover");
    verify(storage, never()).uploadFromUrl(anyString(), anyString(), anyString());
    var other = account(Role.BRAND);
    call(other, multipart("/brands/" + brand + "/campaign-images").file(file()), null, 403);
    when(storage.uploadFromUrl(anyString(), anyString(), anyString())).thenReturn(Optional.empty());
    body.put("imageUrl", "https://example.com/expired-signed-image");
    call(user, put("/campaigns/" + campaign), body, 400);
    assertThat(jdbc.queryForObject("select image_storage_key from campaign where id=?", String.class, campaign))
        .isEqualTo("cover");
    when(storage.uploadFromUrl(anyString(), anyString(), anyString())).thenReturn(Optional.of(media("imported")));
    call(user, put("/campaigns/" + campaign), body, 200);
    assertThat(jdbc.queryForObject("select image_url from campaign where id=?", String.class, campaign))
        .isEqualTo(media("imported").url());
  }
}
