package com.securechat.repository;

import com.securechat.model.Message;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface MessageRepository extends JpaRepository<Message, UUID> {
    @Query("SELECT m FROM Message m JOIN FETCH m.sender WHERE m.receiver.id = :receiverId ORDER BY m.createdAt DESC")
    List<Message> findByReceiverIdOrderByCreatedAtDesc(UUID receiverId);

    @Query("SELECT COUNT(m) FROM Message m WHERE m.receiver.id = :receiverId AND m.isRead = false AND m.expired = false")
    long countUnreadByReceiverId(UUID receiverId);

    @Query("SELECT m FROM Message m WHERE m.expiresAt < :now AND m.expired = false")
    List<Message> findExpiredMessages(Instant now);

    @Modifying
    @Query("UPDATE Message m SET m.keyShard = null, m.expired = true WHERE m.id = :id")
    void expireMessage(UUID id);
}
