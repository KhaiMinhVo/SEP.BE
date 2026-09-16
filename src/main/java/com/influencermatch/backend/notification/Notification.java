package com.influencermatch.backend.notification;
import com.influencermatch.backend.entity.User; import jakarta.persistence.*; import lombok.*; import java.time.LocalDateTime; import java.util.UUID;
@Entity @Table(name="notification") @Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder public class Notification {
 @Id @GeneratedValue(strategy=GenerationType.UUID) @Column(name="notificationId") private UUID id;
 @ManyToOne(fetch=FetchType.LAZY,optional=false) @JoinColumn(name="userId",nullable=false) private User user;
 @Column(nullable=false,length=100) private String type; @Column(nullable=false,length=255) private String title; @Column(nullable=false) private String message;
 @Column(length=100) private String entityType; private UUID entityId; @Column(name="read",nullable=false) private boolean read;
 private LocalDateTime readAt; private LocalDateTime expiresAt; @Column(nullable=false) private LocalDateTime createdAt;
}
