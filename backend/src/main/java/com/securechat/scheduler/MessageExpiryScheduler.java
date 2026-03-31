package com.securechat.scheduler;

import com.securechat.model.Message;
import com.securechat.repository.MessageRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.List;

@Component
public class MessageExpiryScheduler {

    private static final Logger log = LoggerFactory.getLogger(MessageExpiryScheduler.class);

    private final MessageRepository messageRepository;
    private final Path imageStoragePath;

    public MessageExpiryScheduler(MessageRepository messageRepository,
                                   @Value("${app.image.storage-path}") String storagePath) {
        this.messageRepository = messageRepository;
        this.imageStoragePath = Paths.get(storagePath);
    }

    @Scheduled(fixedRate = 120_000) // every 2 minutes
    @Transactional
    public void purgeExpiredMessages() {
        List<Message> expired = messageRepository.findExpiredMessages(Instant.now());
        if (expired.isEmpty()) return;

        log.info("Purging {} expired messages", expired.size());
        for (Message msg : expired) {
            if (msg.getImageFilename() != null) {
                try {
                    Files.deleteIfExists(imageStoragePath.resolve(msg.getImageFilename()));
                } catch (Exception e) {
                    log.warn("Failed to delete image {}: {}", msg.getImageFilename(), e.getMessage());
                }
            }
            if (msg.getIsDocument() && msg.getFileName() != null) {
                try {
                    String encFileName = msg.getId() + ".enc";
                    Files.deleteIfExists(imageStoragePath.resolve(encFileName));
                } catch (Exception e) {
                    log.warn("Failed to delete document for {}: {}", msg.getId(), e.getMessage());
                }
            }
            messageRepository.expireMessage(msg.getId());
        }
    }
}
