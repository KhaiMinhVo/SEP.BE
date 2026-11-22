package com.influencermatch.backend.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.cloudinary.Cloudinary;
import com.cloudinary.Uploader;
import com.influencermatch.backend.exception.BusinessException;
import com.influencermatch.backend.exception.ValidationException;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Map;
import java.util.Optional;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

class CloudinaryStorageServiceTest {

  private byte[] createSamplePng(int width, int height) throws IOException {
    BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
    Graphics2D g2d = image.createGraphics();
    g2d.setColor(Color.BLUE);
    g2d.fillRect(0, 0, width, height);
    g2d.dispose();
    ByteArrayOutputStream baos = new ByteArrayOutputStream();
    ImageIO.write(image, "png", baos);
    return baos.toByteArray();
  }

  @Test
  @DisplayName("uploadFile fails when Cloudinary is not configured")
  void uploadFile_notConfigured_throwsBusinessException() throws IOException {
    CloudinaryStorageService service = new CloudinaryStorageService(null);
    assertThat(service.isConfigured()).isFalse();

    MockMultipartFile file = new MockMultipartFile(
        "file", "test.png", "image/png", createSamplePng(100, 100));

    assertThatThrownBy(() -> service.uploadFile(file, "test-folder", "prefix"))
        .isInstanceOf(BusinessException.class)
        .hasMessageContaining("Cloud storage is not configured");
  }

  @Test
  @DisplayName("uploadFile succeeds and returns stored media metadata")
  @SuppressWarnings("unchecked")
  void uploadFile_configured_success() throws IOException {
    Cloudinary cloudinary = mock(Cloudinary.class);
    Uploader uploader = mock(Uploader.class);
    when(cloudinary.uploader()).thenReturn(uploader);

    when(uploader.upload(any(byte[].class), anyMap())).thenReturn(Map.of(
        "secure_url", "https://res.cloudinary.com/test/image/upload/v123/test.png",
        "public_id", "test-folder/prefix_12345",
        "format", "png",
        "bytes", 1024,
        "width", 100,
        "height", 100
    ));

    CloudinaryStorageService service = new CloudinaryStorageService(cloudinary);
    assertThat(service.isConfigured()).isTrue();

    MockMultipartFile file = new MockMultipartFile(
        "file", "test.png", "image/png", createSamplePng(100, 100));

    StoredMedia result = service.uploadFile(file, "test-folder", "prefix");

    assertThat(result.url()).isEqualTo("https://res.cloudinary.com/test/image/upload/v123/test.png");
    assertThat(result.storageKey()).isEqualTo("test-folder/prefix_12345");
    assertThat(result.mimeType()).isEqualTo("image/png");
    assertThat(result.sizeBytes()).isEqualTo(1024L);
    assertThat(result.width()).isEqualTo(100);
    assertThat(result.height()).isEqualTo(100);
  }

  @Test
  @DisplayName("uploadFile rejects invalid non-image file")
  void uploadFile_invalidFormat_throwsValidationException() {
    Cloudinary cloudinary = mock(Cloudinary.class);
    CloudinaryStorageService service = new CloudinaryStorageService(cloudinary);

    MockMultipartFile file = new MockMultipartFile(
        "file", "test.txt", "text/plain", "Hello world".getBytes());

    assertThatThrownBy(() -> service.uploadFile(file, "test-folder", "prefix"))
        .isInstanceOf(ValidationException.class);
  }

  @Test
  @DisplayName("uploadFromUrl gracefully returns empty when URL is invalid or unconfigured")
  void uploadFromUrl_invalidUrl_returnsEmpty() {
    Cloudinary cloudinary = mock(Cloudinary.class);
    CloudinaryStorageService service = new CloudinaryStorageService(cloudinary);

    Optional<StoredMedia> result = service.uploadFromUrl("invalid-url", "folder", "prefix");
    assertThat(result).isEmpty();
  }

  @Test
  @DisplayName("uploadFromUrl skips if URL is already Cloudinary URL")
  void uploadFromUrl_alreadyCloudinary_returnsEmpty() {
    Cloudinary cloudinary = mock(Cloudinary.class);
    CloudinaryStorageService service = new CloudinaryStorageService(cloudinary);

    Optional<StoredMedia> result = service.uploadFromUrl(
        "https://res.cloudinary.com/demo/image/upload/sample.jpg", "folder", "prefix");
    assertThat(result).isEmpty();
  }

  @Test
  @DisplayName("deleteFile calls Cloudinary destroy")
  @SuppressWarnings("unchecked")
  void deleteFile_callsDestroy() throws IOException {
    Cloudinary cloudinary = mock(Cloudinary.class);
    Uploader uploader = mock(Uploader.class);
    when(cloudinary.uploader()).thenReturn(uploader);

    CloudinaryStorageService service = new CloudinaryStorageService(cloudinary);
    service.deleteFile("sample-key");

    verify(uploader).destroy(any(), anyMap());
  }
}
