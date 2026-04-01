package com.securechat.service;

import com.securechat.config.NotFoundException;
import com.securechat.model.Message;
import com.securechat.model.User;
import com.securechat.repository.MessageRepository;
import com.securechat.repository.UserRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

@Service
public class MessageService {

    private final MessageRepository messageRepository;
    private final UserRepository userRepository;
    private final SteganographyService steganographyService;
    private final GeoLocationService geoLocationService;
    private final Path imageStoragePath;
    private final int expiryMinutes;

    public MessageService(MessageRepository messageRepository,
                          UserRepository userRepository,
                          SteganographyService steganographyService,
                          GeoLocationService geoLocationService,
                          @Value("${app.image.storage-path}") String storagePath,
                          @Value("${app.message.expiry-minutes:30}") int expiryMinutes) throws IOException {
        this.messageRepository = messageRepository;
        this.userRepository = userRepository;
        this.steganographyService = steganographyService;
        this.geoLocationService = geoLocationService;
        this.imageStoragePath = Paths.get(storagePath);
        this.expiryMinutes = expiryMinutes;
        Files.createDirectories(this.imageStoragePath);
    }

    public Message sendMessage(String senderUsername, String receiverUsername,
                                MultipartFile imageFile, String encryptedPayloadBase64,
                                String encryptedKey, String keyShard,
                                Double lat, Double lon, int radius,
                                boolean geoLocked, boolean burnAfterRead) throws Exception {
        if (senderUsername.equals(receiverUsername)) {
            throw new IllegalArgumentException("Cannot send a message to yourself");
        }

        User sender = userRepository.findByUsername(senderUsername)
                .orElseThrow(() -> new IllegalArgumentException("Sender not found"));
        User receiver = userRepository.findByUsername(receiverUsername)
                .orElseThrow(() -> new NotFoundException("Receiver not found"));

        if (geoLocked) {
            if (lat == null || lon == null) {
                throw new IllegalArgumentException("Geo-locked messages require coordinates");
            }
            if (!geoLocationService.isCoarseValid(lat, lon)) {
                throw new IllegalArgumentException("Invalid sender coordinates");
            }
        }

        String filename = null;
        if (imageFile != null && !imageFile.isEmpty()) {
            validateImageType(imageFile);
            byte[] payload = Base64.getDecoder().decode(encryptedPayloadBase64);
            if (payload.length == 0) {
                throw new IllegalArgumentException("encryptedPayload must not be empty");
            }
            BufferedImage image = readImage(imageFile.getInputStream());
            BufferedImage encodedImage = steganographyService.embed(image, payload);
            filename = UUID.randomUUID() + ".png";
            ImageIO.write(encodedImage, "PNG", imageStoragePath.resolve(filename).toFile());
        }

        Instant now = Instant.now();
        Message message = Message.builder()
                .sender(sender)
                .receiver(receiver)
                .imageFilename(filename)
                .encryptedKey(encryptedKey)
                .keyShard(keyShard)
                .senderLat(lat)
                .senderLon(lon)
                .radiusMeters(radius)
                .geoLocked(geoLocked)
                .burnAfterRead(burnAfterRead)
                .expiresAt(now.plusSeconds((long) expiryMinutes * 60))
                .build();
        return messageRepository.save(message);
    }

    public Message sendPreEmbeddedMessage(String senderUsername, String receiverUsername,
                                           MultipartFile stegoImage, String encryptedKey, String keyShard,
                                           Double lat, Double lon, int radius,
                                           boolean geoLocked, boolean burnAfterRead) throws Exception {
        if (senderUsername.equals(receiverUsername)) {
            throw new IllegalArgumentException("Cannot send a message to yourself");
        }

        User sender = userRepository.findByUsername(senderUsername)
                .orElseThrow(() -> new IllegalArgumentException("Sender not found"));
        User receiver = userRepository.findByUsername(receiverUsername)
                .orElseThrow(() -> new NotFoundException("Receiver not found"));

        if (geoLocked) {
            if (lat == null || lon == null) {
                throw new IllegalArgumentException("Geo-locked messages require coordinates");
            }
            if (!geoLocationService.isCoarseValid(lat, lon)) {
                throw new IllegalArgumentException("Invalid sender coordinates");
            }
        }

        // Store the pre-embedded stego image directly — no server-side LSB processing
        String filename = UUID.randomUUID() + ".png";
        Files.copy(stegoImage.getInputStream(), imageStoragePath.resolve(filename));

        Instant now = Instant.now();
        Message message = Message.builder()
                .sender(sender)
                .receiver(receiver)
                .imageFilename(filename)
                .encryptedKey(encryptedKey)
                .keyShard(keyShard)
                .senderLat(lat)
                .senderLon(lon)
                .radiusMeters(radius)
                .geoLocked(geoLocked)
                .burnAfterRead(burnAfterRead)
                .expiresAt(now.plusSeconds((long) expiryMinutes * 60))
                .build();
        return messageRepository.save(message);
    }

    public Message sendDocument(String senderUsername, String receiverUsername,
                                 MultipartFile file, String encryptedPayloadBase64,
                                 String encryptedKey, String keyShard,
                                 Double lat, Double lon, int radius,
                                 boolean geoLocked, boolean burnAfterRead) throws Exception {
        if (senderUsername.equals(receiverUsername)) {
            throw new IllegalArgumentException("Cannot send a message to yourself");
        }

        User sender = userRepository.findByUsername(senderUsername)
                .orElseThrow(() -> new IllegalArgumentException("Sender not found"));
        User receiver = userRepository.findByUsername(receiverUsername)
                .orElseThrow(() -> new NotFoundException("Receiver not found"));

        if (geoLocked && lat != null && lon != null) {
            if (!geoLocationService.isCoarseValid(lat, lon)) {
                throw new IllegalArgumentException("Invalid sender coordinates");
            }
        }

        byte[] encryptedData = Base64.getDecoder().decode(encryptedPayloadBase64);

        Instant now = Instant.now();
        Message message = Message.builder()
                .sender(sender)
                .receiver(receiver)
                .encryptedKey(encryptedKey)
                .keyShard(keyShard)
                .senderLat(lat)
                .senderLon(lon)
                .radiusMeters(radius)
                .geoLocked(geoLocked)
                .burnAfterRead(burnAfterRead)
                .isDocument(true)
                .fileName(file.getOriginalFilename())
                .fileType(file.getContentType())
                .fileSize(encryptedData.length > 0 ? (long) encryptedData.length : file.getSize())
                .expiresAt(now.plusSeconds((long) expiryMinutes * 60))
                .build();
        Message saved = messageRepository.save(message);

        // Save encrypted file using the actual message ID
        String encFileName = saved.getId() + ".enc";
        Files.write(imageStoragePath.resolve(encFileName), encryptedData);

        return saved;
    }

    public List<Message> getInbox(String username) {
        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new IllegalArgumentException("User not found"));
        return messageRepository.findByReceiverIdOrderByCreatedAtDesc(user.getId());
    }

    public long getUnreadCount(String username) {
        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new IllegalArgumentException("User not found"));
        return messageRepository.countUnreadByReceiverId(user.getId());
    }

    public Message getMessage(UUID id, String username) {
        Message message = messageRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("Message not found"));
        if (!message.getReceiver().getUsername().equals(username)) {
            throw new SecurityException("Access denied");
        }
        return message;
    }

    @Transactional
    public void markAsRead(UUID id, String username) {
        Message message = getMessage(id, username);
        if (!message.getIsRead()) {
            message.setIsRead(true);
            messageRepository.save(message);
        }
    }

    @Transactional
    public void burnMessage(UUID id) {
        Message message = messageRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("Message not found"));
        if (message.getImageFilename() != null) {
            try {
                Files.deleteIfExists(imageStoragePath.resolve(message.getImageFilename()));
            } catch (Exception ignored) {}
        }
        if (message.getIsDocument()) {
            try {
                Files.deleteIfExists(imageStoragePath.resolve(message.getId() + ".enc"));
            } catch (Exception ignored) {}
        }
        messageRepository.expireMessage(id);
    }

    public Path getImagePath(UUID id, String username) {
        Message message = getMessage(id, username);
        if (message.getImageFilename() == null) {
            throw new NotFoundException("No image for this message");
        }
        Path imagePath = imageStoragePath.resolve(message.getImageFilename());
        if (!Files.exists(imagePath)) {
            throw new NotFoundException("Image file not found");
        }
        return imagePath;
    }

    public Path getDocumentPath(UUID id, String username) {
        Message message = getMessage(id, username);
        if (!message.getIsDocument()) {
            throw new IllegalArgumentException("Message is not a document");
        }
        Path docPath = imageStoragePath.resolve(message.getId() + ".enc");
        if (!Files.exists(docPath)) {
            throw new NotFoundException("Document file not found");
        }
        return docPath;
    }

    private void validateImageType(MultipartFile file) throws IOException {
        byte[] header = new byte[8];
        try (InputStream is = file.getInputStream()) {
            is.read(header);
        }
        boolean isPng = header[0] == (byte)0x89 && header[1] == 0x50 && header[2] == 0x4E && header[3] == 0x47;
        boolean isBmp = header[0] == 0x42 && header[1] == 0x4D;
        if (!isPng && !isBmp) {
            throw new IllegalArgumentException("Only PNG and BMP images are accepted");
        }
    }

    private BufferedImage readImage(InputStream is) throws IOException {
        BufferedImage img = ImageIO.read(is);
        if (img == null) throw new IllegalArgumentException("Cannot read image");
        return img;
    }
}
