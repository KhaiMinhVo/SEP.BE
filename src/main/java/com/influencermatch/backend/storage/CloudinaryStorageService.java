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
        .followRedirects(HttpClient.Redirect.NEVER)
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
      log.warn("Cloudinary not configured; skipping remote image sync");
      return Optional.empty();
    }

    if (remoteUrl == null || remoteUrl.isBlank() || !remoteUrl.startsWith("http")) {
      return Optional.empty();
    }

    try {
      byte[] bytes = downloadImage(remoteUrl);
      String format = validateImage(bytes);
      String publicId = generatePublicId(filenamePrefix);

      StoredMedia stored = executeUpload(bytes, folder, publicId, format);
      return Optional.of(stored);
    } catch (Exception ex) {
      log.warn("Remote image download/upload failed ({}); keeping previous image", ex.getClass().getSimpleName());
      if (ex instanceof InterruptedException) Thread.currentThread().interrupt();
      return Optional.empty();
    }
  }

  /** Validate every redirect and bound downloaded bytes before decoding untrusted images. */
  private byte[] downloadImage(String url) throws IOException, InterruptedException {
    URI target = URI.create(url);
    for (int redirects = 0; redirects <= 3; redirects++) {
      validateRemoteUri(target);
      var request = HttpRequest.newBuilder(target)
          .header("User-Agent", "Mozilla/5.0")
          .header("Accept", "image/png,image/jpeg,image/webp")
          .timeout(Duration.ofSeconds(15)).GET().build();
      var response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
      try (var body = response.body()) {
        if (response.statusCode() >= 300 && response.statusCode() < 400) {
          target = target.resolve(response.headers().firstValue("location")
              .orElseThrow(() -> new IOException("Missing image redirect")));
          continue;
        }
        if (response.statusCode() != 200) throw new IOException("Remote image unavailable");
        if (response.headers().firstValueAsLong("content-length").orElse(0) > MAX_IMAGE_BYTES)
          throw new IOException("Image too large");
        byte[] bytes = body.readNBytes((int) MAX_IMAGE_BYTES + 1);
        if (bytes.length == 0 || bytes.length > MAX_IMAGE_BYTES) throw new IOException("Invalid image size");
        return bytes;
      }
    }
    throw new IOException("Too many image redirects");
  }

  static void validateRemoteUri(URI uri) throws IOException {
    if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null
        || uri.getUserInfo() != null || uri.getFragment() != null
        || (uri.getPort() != -1 && uri.getPort() != 443)) {
      throw new IOException("A public HTTPS image URL is required");
    }
    for (var address : java.net.InetAddress.getAllByName(uri.getHost())) {
      byte[] ip = address.getAddress();
      if (address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress()
          || address.isSiteLocalAddress() || address.isMulticastAddress()
          || (ip.length == 16 && (ip[0] & 0xfe) == 0xfc)
          || (ip.length == 4 && ((ip[0] & 0xff) == 0 || (ip[0] & 0xff) >= 224
              || ((ip[0] & 0xff) == 100 && (ip[1] & 0xff) >= 64 && (ip[1] & 0xff) <= 127)))) {
        throw new IOException("Private image addresses are not allowed");
      }
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
      if (secureUrl == null || !secureUrl.startsWith("https://") || key == null || key.isBlank()) {
        throw new BusinessException(ErrorCode.INTERNAL_ERROR, "Cloud storage did not confirm the uploaded image.");
      }
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
          LocalDateTime.now(java.time.ZoneOffset.UTC)
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
    if (bytes == null || bytes.length < 12 || bytes.length > MAX_IMAGE_BYTES) {
      throw new ValidationException("Image exceeds allowed size (up to 10 MB).");
    }

    // 1. Detect WebP by magic bytes: 'R' 'I' 'F' 'F' ... 'W' 'E' 'B' 'P'
    if (bytes[0] == 'R' && bytes[1] == 'I' && bytes[2] == 'F' && bytes[3] == 'F'
        && bytes[8] == 'W' && bytes[9] == 'E' && bytes[10] == 'B' && bytes[11] == 'P') {
      return "webp";
    }

    // 2. Detect GIF by magic bytes: 'G' 'I' 'F'
    if (bytes[0] == 'G' && bytes[1] == 'I' && bytes[2] == 'F') {
      return "gif";
    }

    // 3. For PNG / JPEG, validate via ImageIO
    try (var input = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
      var readers = ImageIO.getImageReaders(input);
      if (!readers.hasNext()) {
        // Fallback magic bytes for JPEG (FF D8 FF) and PNG (89 50 4E 47)
        if ((bytes[0] & 0xFF) == 0xFF && (bytes[1] & 0xFF) == 0xD8) {
          return "jpg";
        }
        if ((bytes[0] & 0xFF) == 0x89 && bytes[1] == 0x50 && bytes[2] == 0x4E && bytes[3] == 0x47) {
          return "png";
        }
        throw new ValidationException("Only valid image formats (JPG, PNG, WEBP, GIF) are supported.");
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
        return format.equals("png") ? "png" : "jpg";
      } finally {
        reader.dispose();
      }
    } catch (IOException e) {
      throw new ValidationException("Image file is damaged or invalid.");
    }
  }
}
