package com.influencermatch.backend.campaign.service;

import com.influencermatch.backend.exception.BusinessException;
import com.influencermatch.backend.exception.ErrorCode;
import com.influencermatch.backend.exception.ValidationException;
import jakarta.annotation.PreDestroy;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.util.UUID;
import javax.imageio.ImageIO;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;

/** Public campaign covers; independent of private collaboration video objects. */
@Component
public class CampaignImageStorage {
  static final long MAX_BYTES = 5 * 1024 * 1024;
  private final S3Client client;
  private final String bucket;
  private final String publicBase;

  @org.springframework.beans.factory.annotation.Autowired(required = false)
  private com.influencermatch.backend.storage.CloudinaryStorageService cloudinaryStorageService;

  public CampaignImageStorage(
      @Value("${CAMPAIGN_IMAGE_R2_ENDPOINT:${COLLABORATION_VIDEO_R2_ENDPOINT:}}") String endpoint,
      @Value("${CAMPAIGN_IMAGE_R2_BUCKET:}") String bucket,
      @Value("${CAMPAIGN_IMAGE_R2_ACCESS_KEY_ID:${COLLABORATION_VIDEO_R2_ACCESS_KEY_ID:}}") String accessKey,
      @Value("${CAMPAIGN_IMAGE_R2_SECRET_ACCESS_KEY:${COLLABORATION_VIDEO_R2_SECRET_ACCESS_KEY:}}") String secret,
      @Value("${CAMPAIGN_IMAGE_PUBLIC_BASE_URL:}") String publicBase) {
    this.bucket = bucket;
    this.publicBase = publicBase.replaceAll("/+$", "");
    if (bucket.isBlank() || endpoint.isBlank() || accessKey.isBlank() || secret.isBlank() || publicBase.isBlank()) {
      client = null;
      return;
    }
    URI publicUri = URI.create(this.publicBase);
    if (!"https".equalsIgnoreCase(publicUri.getScheme()) || publicUri.getHost() == null
        || publicUri.getUserInfo() != null || publicUri.getQuery() != null || publicUri.getFragment() != null) {
      throw new IllegalStateException("Campaign image public base URL must be an HTTPS base URL");
    }
    client = S3Client.builder().endpointOverride(URI.create(endpoint))
        .region(software.amazon.awssdk.regions.Region.of("auto"))
        .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(accessKey, secret)))
        .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build())
        .overrideConfiguration(c -> c.apiCallTimeout(Duration.ofSeconds(20)))
        .build();
  }

  public record Image(String imageUrl) {}

  public Image upload(UUID brandId, MultipartFile file) {
    if (cloudinaryStorageService != null && cloudinaryStorageService.isConfigured()) {
      var stored = cloudinaryStorageService.uploadFile(
          file,
          com.influencermatch.backend.storage.CloudinaryStorageService.FOLDER_CAMPAIGN_COVER,
          brandId.toString()
      );
      return new Image(stored.url());
    }

    byte[] bytes;
    if (file == null || file.isEmpty() || file.getSize() > MAX_BYTES) {
      throw new ValidationException("Choose a JPG or PNG image up to 5 MB.");
    }
    try {
      bytes = file.getBytes();
    } catch (IOException e) {
      throw new ValidationException("Cannot read image file.");
    }
    String format = validateImage(bytes);
    if (client == null) throw unavailable();
    String key = "campaign-images/" + brandId + "/" + UUID.randomUUID() + "." + format;
    try {
      client.putObject(r -> r.bucket(bucket).key(key)
          .contentType(format.equals("png") ? "image/png" : "image/jpeg")
          .cacheControl("public, max-age=31536000, immutable"), RequestBody.fromBytes(bytes));
    } catch (RuntimeException e) {
      throw unavailable();
    }
    return new Image(publicBase + "/" + key);
  }

  static String validateImage(byte[] bytes) {
    if (bytes.length == 0 || bytes.length > MAX_BYTES) throw new ValidationException("Image exceeds 5 MB.");
    try (var input = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
      var readers = ImageIO.getImageReaders(input);
      if (!readers.hasNext()) throw new ValidationException("Only JPG and PNG images are supported.");
      var reader = readers.next();
      try {
        String format = reader.getFormatName().toLowerCase(java.util.Locale.ROOT);
        if (!format.equals("png") && !format.equals("jpeg") && !format.equals("jpg")) {
          throw new ValidationException("Only JPG and PNG images are supported.");
        }
        reader.setInput(input);
        int width = reader.getWidth(0), height = reader.getHeight(0);
        if (width < 1 || height < 1 || (long) width * height > 20_000_000) {
          throw new ValidationException("Image must not exceed 20 megapixels.");
        }
        reader.read(0); // Validate decodability, not just filename or declared MIME type.
        return format.equals("png") ? "png" : "jpg";
      } finally {
        reader.dispose();
      }
    } catch (IOException e) {
      throw new ValidationException("Image file is damaged or invalid.");
    }
  }

  private BusinessException unavailable() {
    return new BusinessException(ErrorCode.CAMPAIGN_IMAGE_UNAVAILABLE, "Campaign image storage is not available. You can use an HTTPS image link instead.");
  }

  @PreDestroy
  void close() { if (client != null) client.close(); }
}
