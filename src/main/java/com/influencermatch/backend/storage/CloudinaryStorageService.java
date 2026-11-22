package com.influencermatch.backend.storage;

import com.cloudinary.Cloudinary;
import com.cloudinary.utils.ObjectUtils;
import com.influencermatch.backend.exception.BusinessException;
import com.influencermatch.backend.exception.ErrorCode;
import com.influencermatch.backend.exception.ValidationException;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import javax.imageio.ImageIO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

/**
 * Shared Cloudinary storage service for avatars, campaign images, deliverables, and crawled media.
 * Encapsulates direct byte downloads (with crawler-safe headers) and cloud persistence.
 */
@Slf4j
@Service
public class CloudinaryStorageService {

  public static final long MAX_IMAGE_BYTES = 10 * 1024 * 1024; // 10 MB

  public static final String FOLDER_CREATOR_AVATAR = "influencermatch/creators/avatars";
  public static final String FOLDER_CAMPAIGN_COVER = "influencermatch/campaigns/covers";
  public static final String FOLDER_CAMPAIGN_DELIVERABLE = "influencermatch/campaigns/deliverables";
  public static final String FOLDER_POST_THUMBNAIL = "influencermatch/posts/thumbnails";

  private final Cloudinary cloudinary;
  private final HttpClient httpClient;

  @Autowired
  public CloudinaryStorageService(@Autowired(required = false) Cloudinary cloudinary) {
    this.cloudinary = cloudinary;
    this.httpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build();
  }

  public boolean isConfigured() {
    return cloudinary != null;
  }

  /**
   * Uploads an image uploaded directly via multipart form-data.
   */
  public StoredMedia uploadFile(MultipartFile file, String folder, String filenamePrefix) {
    if (!isConfigured()) {
      throw new BusinessException(
          ErrorCode.INTERNAL_ERROR,
          "Cloud storage is not configured. Please set CLOUDINARY credentials in environment.");
    }

    if (file == null || file.isEmpty() || file.getSize() > MAX_IMAGE_BYTES) {
      throw new ValidationException("Choose a valid image file up to 10 MB.");
    }

    byte[] bytes;
    try {
      bytes = file.getBytes();
    } catch (IOException e) {
      throw new ValidationException("Cannot read uploaded image file.");
    }

    String format = validateImage(bytes);
    String publicId = generatePublicId(filenamePrefix);

    return executeUpload(bytes, folder, publicId, format);
  }

  /**
   * Downloads an image from an external/temporary URL (e.g. TikTok signed CDN URL)
   * and uploads it to Cloudinary. Returns Optional.empty() if download or upload fails,
   * allowing callers to gracefully keep previous images.
   */
  public Optional<StoredMedia> uploadFromUrl(String remoteUrl, String folder, String filenamePrefix) {
    if (!isConfigured()) {
      log.warn("Cloudinary not configured; skipping cloud sync for remote URL: {}", remoteUrl);
      return Optional.empty();
    }

    if (remoteUrl == null || remoteUrl.isBlank() || !remoteUrl.startsWith("http")) {
      return Optional.empty();
    }

    // If already hosted on Cloudinary, skip re-uploading
    if (remoteUrl.contains("res.cloudinary.com")) {
      log.debug("Remote URL is already a Cloudinary asset: {}", remoteUrl);
      return Optional.empty();
    }

    try {
      log.info("Downloading remote image from {} for upload to {}", remoteUrl, folder);
      HttpRequest request = HttpRequest.newBuilder()
          .uri(URI.create(remoteUrl))
          .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
          .header("Accept", "image/avif,image/webp,image/apng,image/svg+xml,image/*,*/*;q=0.8")
          .header("Accept-Language", "en-US,en;q=0.9,vi;q=0.8")
          .timeout(Duration.ofSeconds(15))
          .GET()
          .build();

      HttpResponse<byte[]> response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
      if (response.statusCode() != 200 || response.body() == null || response.body().length == 0) {
        log.warn("Failed to download image from {}: HTTP status {}", remoteUrl, response.statusCode());
        return Optional.empty();
      }

      byte[] bytes = response.body();
      if (bytes.length > MAX_IMAGE_BYTES) {
        log.warn("Downloaded image exceeds max limit of {} bytes (got {})", MAX_IMAGE_BYTES, bytes.length);
        return Optional.empty();
      }

      String format = validateImage(bytes);
      String publicId = generatePublicId(filenamePrefix);

      StoredMedia stored = executeUpload(bytes, folder, publicId, format);
      return Optional.of(stored);
    } catch (Exception ex) {
      log.warn("Error uploading remote image from URL: {} to Cloudinary: {}", remoteUrl, ex.getMessage());
      return Optional.empty();
    }
  }

  /**
   * Deletes a stored file from Cloudinary by its publicId / storage key.
   */
  public void deleteFile(String storageKey) {
    if (!isConfigured() || storageKey == null || storageKey.isBlank()) {
      return;
    }
    try {
      cloudinary.uploader().destroy(storageKey, ObjectUtils.emptyMap());
      log.info("Deleted asset from Cloudinary: {}", storageKey);
    } catch (Exception ex) {
      log.warn("Failed to delete asset {} from Cloudinary: {}", storageKey, ex.getMessage());
    }
  }

  @SuppressWarnings("rawtypes")
  private StoredMedia executeUpload(byte[] bytes, String folder, String publicId, String format) {
    try {
      Map uploadParams = ObjectUtils.asMap(
          "folder", folder,
          "public_id", publicId,
          "overwrite", true,
          "resource_type", "image"
      );

      Map result = cloudinary.uploader().upload(bytes, uploadParams);

      String secureUrl = (String) result.get("secure_url");
      String key = (String) result.get("public_id");
      String resFormat = (String) result.getOrDefault("format", format);
      Number sizeNum = (Number) result.getOrDefault("bytes", bytes.length);
      Number widthNum = (Number) result.get("width");
      Number heightNum = (Number) result.get("height");

      String mimeType = "image/" + (resFormat != null ? resFormat.toLowerCase(Locale.ROOT) : format);

      return new StoredMedia(
          secureUrl,
          key,
          mimeType,
          sizeNum.longValue(),
          widthNum != null ? widthNum.intValue() : null,
          heightNum != null ? heightNum.intValue() : null,
          LocalDateTime.now()
      );
    } catch (IOException e) {
      log.error("Cloudinary upload failed: {}", e.getMessage(), e);
      throw new BusinessException(ErrorCode.INTERNAL_ERROR, "Failed to upload image to cloud storage: " + e.getMessage());
    }
  }

  private String generatePublicId(String prefix) {
    String cleanPrefix = (prefix == null || prefix.isBlank())
        ? "img"
        : prefix.trim().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_-]", "_");
    if (cleanPrefix.length() > 40) {
      cleanPrefix = cleanPrefix.substring(0, 40);
    }
    return cleanPrefix + "_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
  }

  public static String validateImage(byte[] bytes) {
    if (bytes == null || bytes.length == 0 || bytes.length > MAX_IMAGE_BYTES) {
      throw new ValidationException("Image exceeds allowed size (up to 10 MB).");
    }
    try (var input = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
      var readers = ImageIO.getImageReaders(input);
      if (!readers.hasNext()) {
        throw new ValidationException("Only valid image formats (JPG, PNG, WEBP) are supported.");
      }
      var reader = readers.next();
      try {
        String format = reader.getFormatName().toLowerCase(Locale.ROOT);
        if (!format.equals("png") && !format.equals("jpeg") && !format.equals("jpg") && !format.equals("webp")) {
          throw new ValidationException("Only JPG, PNG and WEBP images are supported.");
        }
        reader.setInput(input);
        int width = reader.getWidth(0), height = reader.getHeight(0);
        if (width < 1 || height < 1 || (long) width * height > 25_000_000) {
          throw new ValidationException("Image must not exceed 25 megapixels.");
        }
        reader.read(0); // Validate decodability
        return format.equals("png") ? "png" : (format.equals("webp") ? "webp" : "jpg");
      } finally {
        reader.dispose();
      }
    } catch (IOException e) {
      throw new ValidationException("Image file is damaged or invalid.");
    }
  }
}
