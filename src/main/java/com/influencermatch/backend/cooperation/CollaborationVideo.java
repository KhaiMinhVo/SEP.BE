package com.influencermatch.backend.cooperation;

import com.influencermatch.backend.exception.NotFoundException;
import com.influencermatch.backend.exception.ValidationException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
@RequiredArgsConstructor
public class CollaborationVideo {
  public static final long MAX_BYTES = 100_000_000;
  private static final Duration SESSION_LIFETIME = Duration.ofHours(24);
  private static final Logger LOG = LoggerFactory.getLogger(CollaborationVideo.class);
  private final DealStore store;
  private final CollaborationVideoStorage storage;
  private final TransactionTemplate transactions;

  boolean required() {
    return storage.required();
  }

  CollaborationVideoStorage.UploadGrant open(UUID unit, UUID actor, ExecutionDtos.VideoUpload ask) {
    String name = ask.fileName().trim();
    if (!name.toLowerCase(Locale.ROOT).endsWith(".mp4") || name.indexOf('/') >= 0
        || name.indexOf('\\') >= 0 || name.codePoints().anyMatch(Character::isISOControl)) {
      throw new ValidationException("Chọn tệp video MP4 hợp lệ.");
    }
    UUID id = UUID.randomUUID();
    var grant = storage.upload(id, ask.sizeBytes());
    store.db.update(
        "insert into collaboration_video_upload "
            + "(id,deliverable_id,actor_id,expected_revision,file_name,size_bytes,expires_at) values(?,?,?,?,?,?,?)",
        id, unit, actor, ask.expectedRevision(), name, ask.sizeBytes(),
        Timestamp.from(store.now().plus(SESSION_LIFETIME)));
    return grant;
  }

  void validateSubmission(UUID id, UUID unit, UUID actor, long revision) {
    var upload = store.optional(
        "select * from collaboration_video_upload where id=? and deliverable_id=? and actor_id=? for update",
        id, unit, actor).orElseThrow(() -> new NotFoundException("Không tìm thấy tệp video đã chọn."));
    var expiresAt = ((Timestamp) upload.get("expires_at")).toInstant();
    if (!expiresAt.isAfter(store.now())) throw DealStore.conflict("Phiên tải video đã hết hạn. Hãy chọn lại tệp.");
    if (((Number) upload.get("expected_revision")).longValue() != revision
        || store.optional("select id from collaboration_submission where video_upload_id=?", id).isPresent()) {
      throw DealStore.conflict("Phiên tải video không còn khớp với ấn phẩm.");
    }
    storage.verify(id, ((Number) upload.get("size_bytes")).longValue());
  }

  /** The entry point must check the caller's collaboration or campaign history access first. */
  public CollaborationVideoStorage.Playback playback(UUID collaboration, UUID unit, UUID submission) {
    var row = store.optional(
        "select s.video_upload_id from collaboration_submission s "
            + "join collaboration_deliverable d on d.id=s.deliverable_id "
            + "where d.collaboration_id=? and d.id=? and s.id=? and s.video_upload_id is not null",
        collaboration, unit, submission).orElseThrow(() -> new NotFoundException("Không tìm thấy video đã nộp."));
    return storage.playback((UUID) row.get("video_upload_id"));
  }

  @Scheduled(initialDelay = 60000, fixedDelay = 3600000)
  public void cleanExpired() {
    if (!storage.available()) return;
    try {
      transactions.executeWithoutResult(status -> {
        var rows = store.db.queryForList(
            "select u.id from collaboration_video_upload u where u.expires_at<=? "
                + "and not exists (select 1 from collaboration_submission s where s.video_upload_id=u.id) "
                + "order by u.expires_at limit 100 for update of u skip locked",
            store.time());
        for (var row : rows) {
          UUID id = (UUID) row.get("id");
          storage.delete(id);
          store.db.update("delete from collaboration_video_upload where id=?", id);
        }
      });
    } catch (RuntimeException error) {
      LOG.warn("Collaboration video cleanup will retry ({})", error.getClass().getSimpleName());
    }
  }
}
