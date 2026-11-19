package com.influencermatch.backend.cooperation;

import com.influencermatch.backend.exception.BusinessException;
import com.influencermatch.backend.exception.ErrorCode;
import com.influencermatch.backend.exception.ValidationException;
import jakarta.annotation.PreDestroy;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

@Component
public class CollaborationVideoStorage {
  private static final String CONTENT_TYPE = "video/mp4";
  private static final Set<String> MP4_BRANDS = Set.of(
      "isom", "iso2", "iso3", "iso4", "iso5", "iso6", "iso7", "iso8", "iso9",
      "mp41", "mp42", "avc1", "dash", "M4V ", "MSNV");
  private static final Duration UPLOAD_TIME = Duration.ofMinutes(30);
  private static final Duration PLAYBACK_TIME = Duration.ofMinutes(10);
  private final boolean required;
  private final String bucket;
  private final S3Client client;
  private final S3Presigner presigner;

  public CollaborationVideoStorage(
      @Value("${COLLABORATION_VIDEO_REQUIRED:false}") boolean required,
      @Value("${COLLABORATION_VIDEO_R2_ENDPOINT:}") String endpoint,
      @Value("${COLLABORATION_VIDEO_R2_BUCKET:}") String bucket,
      @Value("${COLLABORATION_VIDEO_R2_ACCESS_KEY_ID:}") String accessKey,
      @Value("${COLLABORATION_VIDEO_R2_SECRET_ACCESS_KEY:}") String secret) {
    this.required = required;
    this.bucket = bucket;
    if (endpoint.isBlank() && bucket.isBlank() && accessKey.isBlank() && secret.isBlank() && !required) {
      this.client = null;
      this.presigner = null;
      return;
    }
    if (endpoint.isBlank() || bucket.isBlank() || accessKey.isBlank() || secret.isBlank()) {
      throw new IllegalStateException("All collaboration video storage settings are required together");
    }
    var uri = URI.create(endpoint);
    var credentials = StaticCredentialsProvider.create(AwsBasicCredentials.create(accessKey, secret));
    var settings = S3Configuration.builder()
        .pathStyleAccessEnabled(true).checksumValidationEnabled(false).build();
    var region = Region.of("auto");
    this.client = S3Client.builder().endpointOverride(uri).region(region)
        .credentialsProvider(credentials).serviceConfiguration(settings)
        .overrideConfiguration(c -> c.apiCallTimeout(Duration.ofSeconds(5)))
        .build();
    this.presigner = S3Presigner.builder().endpointOverride(uri).region(region)
        .credentialsProvider(credentials).serviceConfiguration(settings).build();
  }

  public boolean required() {
    return required;
  }

  boolean available() {
    return client != null;
  }

  public record UploadGrant(UUID uploadId, String url, Map<String, String> headers, Instant expiresAt) {}

  public record Playback(String url, Instant expiresAt) {}

  UploadGrant upload(UUID id, long size) {
    requireStorage();
    var request = presigner.presignPutObject(p -> p.signatureDuration(UPLOAD_TIME)
        .putObjectRequest(r -> r.bucket(bucket).key(key(id)).contentType(CONTENT_TYPE)
            .contentLength(size).ifNoneMatch("*").cacheControl("private, no-store")));
    var headers = new LinkedHashMap<String, String>();
    request.signedHeaders().forEach((name, values) -> {
      if (!"host".equalsIgnoreCase(name) && !"content-length".equalsIgnoreCase(name)) {
        headers.put(name, String.join(",", values));
      }
    });
    return new UploadGrant(id, request.url().toString(), headers, request.expiration());
  }

  Playback playback(UUID id) {
    requireStorage();
    var request = presigner.presignGetObject(p -> p.signatureDuration(PLAYBACK_TIME)
        .getObjectRequest(r -> r.bucket(bucket).key(key(id))));
    return new Playback(request.url().toString(), request.expiration());
  }

  void verify(UUID id, long size) {
    requireStorage();
    try {
      var metadata = client.headObject(r -> r.bucket(bucket).key(key(id)));
      if (metadata.contentLength() != size || !CONTENT_TYPE.equals(metadata.contentType())) {
        throw new ValidationException("Dung lượng hoặc định dạng video không hợp lệ.");
      }
      var header = client.getObjectAsBytes(r -> r.bucket(bucket).key(key(id))
          .range("bytes=0-15")).asByteArray();
      if (header.length < 16 || !"ftyp".equals(new String(header, 4, 4, StandardCharsets.US_ASCII))
          || !MP4_BRANDS.contains(new String(header, 8, 4, StandardCharsets.US_ASCII))) {
        throw new ValidationException("Tệp không phải video MP4 hợp lệ.");
      }
    } catch (S3Exception error) {
      if (error.statusCode() == 404) {
        throw new ValidationException("Video chưa được tải lên. Vui lòng tải lên và thử lại.");
      }
      throw unavailable();
    } catch (software.amazon.awssdk.core.exception.SdkException error) {
      throw unavailable();
    }
  }

  void delete(UUID id) {
    requireStorage();
    client.deleteObject(r -> r.bucket(bucket).key(key(id)));
  }

  private static String key(UUID id) {
    return "collaboration-submissions/" + id + ".mp4";
  }

  private void requireStorage() {
    if (!available()) throw unavailable();
  }

  private static BusinessException unavailable() {
    return new BusinessException(ErrorCode.COLLABORATION_VIDEO_UNAVAILABLE, "Kho video tạm thời chưa sẵn sàng.");
  }

  @PreDestroy
  void close() {
    if (presigner != null) presigner.close();
    if (client != null) client.close();
  }
}
