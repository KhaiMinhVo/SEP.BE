package com.influencermatch.backend.campaign.service;

import static org.junit.jupiter.api.Assertions.*;

import com.influencermatch.backend.exception.BusinessException;
import com.influencermatch.backend.exception.ErrorCode;
import com.influencermatch.backend.exception.ValidationException;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

class CampaignImageStorageTest {
  private byte[] image(String format) throws Exception {
    var bytes = new ByteArrayOutputStream();
    ImageIO.write(new BufferedImage(8, 8, BufferedImage.TYPE_INT_RGB), format, bytes);
    return bytes.toByteArray();
  }

  @Test void detectsActualFormat() throws Exception {
    assertEquals("png", CampaignImageStorage.validateImage(image("png")));
    assertEquals("jpg", CampaignImageStorage.validateImage(image("jpeg")));
  }

  @Test void rejectsFakeImagesEmptyOversizeAndUnsupportedFormat() throws Exception {
    assertThrows(ValidationException.class, () -> CampaignImageStorage.validateImage("<svg></svg>".getBytes(StandardCharsets.UTF_8)));
    assertThrows(ValidationException.class, () -> CampaignImageStorage.validateImage(new byte[0]));
    assertThrows(ValidationException.class, () -> CampaignImageStorage.validateImage(new byte[(int) CampaignImageStorage.MAX_BYTES + 1]));
    assertThrows(ValidationException.class, () -> CampaignImageStorage.validateImage(image("gif")));
  }

  @Test void missingStorageDoesNotReturnFakeSuccess() throws Exception {
    var storage = new CampaignImageStorage("", "", "", "", "");
    var file = new MockMultipartFile("file", "cover.png", "image/png", image("png"));
    var error = assertThrows(BusinessException.class, () -> storage.upload(UUID.randomUUID(), file));
    assertEquals(ErrorCode.CAMPAIGN_IMAGE_UNAVAILABLE, error.code());
  }
}
