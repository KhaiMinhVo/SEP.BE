package com.influencermatch.backend.storage;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.cloudinary.Cloudinary;
import com.cloudinary.Uploader;
import java.awt.image.BufferedImage;
import java.io.*;
import java.net.URI;
import java.net.http.*;
import java.util.Map;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;

class CloudinaryStorageServiceTest {
  private byte[] png() throws IOException {
    var bytes = new ByteArrayOutputStream();
    ImageIO.write(new BufferedImage(8, 8, BufferedImage.TYPE_INT_RGB), "png", bytes);
    return bytes.toByteArray();
  }

  @Test void uploadsDecodedBytesAndReturnsCloudMetadata() throws Exception {
    var cloud = mock(Cloudinary.class);
    var uploader = mock(Uploader.class);
    when(cloud.uploader()).thenReturn(uploader);
    when(uploader.upload(any(byte[].class), anyMap())).thenReturn(Map.of(
        "secure_url", "https://res.cloudinary.com/test/image/upload/asset.png", "public_id", "folder/asset",
        "format", "png", "bytes", 100, "width", 8, "height", 8));
    var storage = new CloudinaryStorageService(cloud);
    var media = storage.uploadFile(new MockMultipartFile("file", "fake.jpg", "image/jpeg", png()), "folder", "creator");
    assertThat(media.storageKey()).isEqualTo("folder/asset");
    assertThat(media.mimeType()).isEqualTo("image/png");
    assertThat(media.updatedAt()).isNotNull();
    verify(uploader).upload(any(byte[].class), anyMap());
  }

  @Test void temporaryUrlIsDownloadedBeforeCloudUploadAndFailuresReturnEmpty() throws Exception {
    var cloud = mock(Cloudinary.class);
    var uploader = mock(Uploader.class);
    when(cloud.uploader()).thenReturn(uploader);
    when(uploader.upload(any(byte[].class), anyMap())).thenReturn(Map.of(
        "secure_url", "https://cloud.example/avatar.png", "public_id", "avatars/key", "format", "png"));
    var client = mock(HttpClient.class);
    @SuppressWarnings("unchecked") HttpResponse<InputStream> response = mock(HttpResponse.class);
    when(response.statusCode()).thenReturn(200);
    when(response.headers()).thenReturn(HttpHeaders.of(Map.of(), (a, b) -> true));
    when(response.body()).thenAnswer(invocation -> new ByteArrayInputStream(png()));
    when(client.send(any(HttpRequest.class), org.mockito.ArgumentMatchers.<HttpResponse.BodyHandler<InputStream>>any()))
        .thenReturn(response);
    var storage = new CloudinaryStorageService(cloud);
    ReflectionTestUtils.setField(storage, "httpClient", client);
    assertThat(storage.uploadFromUrl("https://8.8.8.8/avatar?expires=123", "avatars", "alice"))
        .hasValueSatisfying(media -> assertThat(media.storageKey()).isEqualTo("avatars/key"));
    when(response.statusCode()).thenReturn(403);
    assertThat(storage.uploadFromUrl("https://8.8.8.8/expired", "avatars", "alice")).isEmpty();
    verify(uploader, times(1)).upload(any(byte[].class), anyMap());
  }

  @Test void rejectsPrivateAddressesCredentialsAndInvalidSchemes() {
    for (String url : new String[] { "http://8.8.8.8/a", "https://127.0.0.1/a", "https://[::1]/a",
        "https://10.0.0.1/a", "https://169.254.169.254/a", "https://user:password@8.8.8.8/a", "file:///etc/passwd" }) {
      assertThatThrownBy(() -> CloudinaryStorageService.validateRemoteUri(URI.create(url))).isInstanceOf(IOException.class);
    }
  }

  @Test void missingCloudOrInvalidUploadNeverReturnsSuccess() {
    assertThat(new CloudinaryStorageService(null).uploadFromUrl("https://8.8.8.8/a", "avatars", "a")).isEmpty();
    var storage = new CloudinaryStorageService(mock(Cloudinary.class));
    assertThatThrownBy(() -> storage.uploadFile(new MockMultipartFile("file", "x.png", "image/png", "not an image".getBytes()), "a", "b"))
        .isInstanceOf(com.influencermatch.backend.exception.ValidationException.class);
  }
}
