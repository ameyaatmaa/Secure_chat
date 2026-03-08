package com.securechat.repository;

import com.securechat.model.Message;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import java.util.List;
import java.util.UUID;

public interface MessageRepository extends JpaRepository<Message, UUID> {
    @Query("SELECT m FROM Message m JOIN FETCH m.sender WHERE m.receiver.id = :receiverId ORDER BY m.createdAt DESC")
    List<Message> findByReceiverIdOrderByCreatedAtDesc(UUID receiverId);
}
