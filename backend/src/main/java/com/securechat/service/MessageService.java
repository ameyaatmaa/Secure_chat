package com.securechat.service;

import com.securechat.model.Message;
import com.securechat.model.User;
import com.securechat.repository.MessageRepository;
import com.securechat.repository.UserRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.UUID;

@Service
public class MessageService {

    private final MessageRepository messageRepository;
    private final UserRepository userRepository;
    private final SteganographyService steganographyService;
    private final GeoLocationService geoLocationService;
    private final Path imageStoragePath;

    public MessageService(MessageRepository messageRepository,
                          UserRepository userRepository,
                          SteganographyService steganographyService,
                          GeoLocationService geoLocationService,
                          @Value("${app.image.storage-path}") String storagePath) throws IOException {
        this.messageRepository = messageRepository;
        this.userRepository = userRepository;
        this.steganographyService = steganographyService;
        this.geoLocationService = geoLocationService;
        this.imageStoragePath = Paths.get(storagePath);
        Files.createDirectories(this.imageStoragePath);
    }

    public Message sendMessage(String senderUsername, String receiverUsername,
                                MultipartFile imageFile, String encryptedPayloadBase64,
                                String encryptedKey, double lat, double lon, int radius) throws Exception {
        validateImageType(imageFile);

        User sender = userRepository.findByUsername(senderUsername)
                .orElseThrow(() -> new IllegalArgumentException("Sender not found"));
        User receiver = userRepository.findByUsername(receiverUsername)
                .orElseThrow(() -> new IllegalArgumentException("Receiver not found"));

        if (!geoLocationService.isCoarseValid(lat, lon)) {
            throw new IllegalArgumentException("Invalid sender coordinates");
        }

        BufferedImage image = readImage(imageFile.getInputStream());
        byte[] payload = java.util.Base64.getDecoder().decode(encryptedPayloadBase64);
        BufferedImage encodedImage = steganographyService.embed(image, payload);

        String filename = UUID.randomUUID() + ".png";
        Path outputPath = imageStoragePath.resolve(filename);
        ImageIO.write(encodedImage, "PNG", outputPath.toFile());

        Message message = Message.builder()
                .sender(sender)
                .receiver(receiver)
                .imageFilename(filename)
                .encryptedKey(encryptedKey)
                .senderLat(lat)
                .senderLon(lon)
                .radiusMeters(radius)
                .build();
        return messageRepository.save(message);
    }

    public List<Message> getInbox(String username) {
        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new IllegalArgumentException("User not found"));
        return messageRepository.findByReceiverIdOrderByCreatedAtDesc(user.getId());
    }

    public Message getMessage(UUID id, String username) {
        Message message = messageRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Message not found"));
        if (!message.getReceiver().getUsername().equals(username)) {
            throw new SecurityException("Access denied");
        }
        return message;
    }

    public Path getImagePath(UUID id, String username) {
        Message message = getMessage(id, username);
        return imageStoragePath.resolve(message.getImageFilename());
    }

    private void validateImageType(MultipartFile file) throws IOException {
        byte[] header = new byte[8];
        try (InputStream is = file.getInputStream()) {
            is.read(header);
        }
        // PNG magic bytes: 89 50 4E 47
        // BMP magic bytes: 42 4D
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
