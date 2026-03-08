package com.securechat.controller;

import com.securechat.model.Message;
import com.securechat.service.MessageService;
import org.springframework.core.io.PathResource;
import org.springframework.core.io.Resource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/messages")
public class MessageController {

    private final MessageService messageService;

    public MessageController(MessageService messageService) {
        this.messageService = messageService;
    }

    @PostMapping("/send")
    public ResponseEntity<?> send(
            @AuthenticationPrincipal UserDetails user,
            @RequestParam("image") MultipartFile image,
            @RequestParam("encryptedPayload") String encryptedPayload,
            @RequestParam("encryptedKey") String encryptedKey,
            @RequestParam("receiverUsername") String receiverUsername,
            @RequestParam("lat") double lat,
            @RequestParam("lon") double lon,
            @RequestParam(value = "radius", defaultValue = "50") int radius) throws Exception {
        Message msg = messageService.sendMessage(user.getUsername(), receiverUsername,
                image, encryptedPayload, encryptedKey, lat, lon, radius);
        return ResponseEntity.ok(Map.of("messageId", msg.getId()));
    }

    @GetMapping("/inbox")
    public ResponseEntity<List<Map<String, Object>>> inbox(
            @AuthenticationPrincipal UserDetails user) {
        List<Map<String, Object>> result = messageService.getInbox(user.getUsername())
                .stream()
                .map(m -> Map.of(
                        "id", m.getId(),
                        "senderUsername", m.getSender().getUsername(),
                        "createdAt", m.getCreatedAt()
                ))
                .toList();
        return ResponseEntity.ok(result);
    }

    @GetMapping("/{id}")
    public ResponseEntity<?> getMessage(
            @AuthenticationPrincipal UserDetails user,
            @PathVariable UUID id) {
        Message msg = messageService.getMessage(id, user.getUsername());
        return ResponseEntity.ok(Map.of(
                "encryptedKey", msg.getEncryptedKey(),
                "senderLat", msg.getSenderLat(),
                "senderLon", msg.getSenderLon(),
                "radiusMeters", msg.getRadiusMeters()
        ));
    }

    @GetMapping("/{id}/image")
    public ResponseEntity<Resource> getImage(
            @AuthenticationPrincipal UserDetails user,
            @PathVariable UUID id) throws Exception {
        Path imagePath = messageService.getImagePath(id, user.getUsername());
        Resource resource = new PathResource(imagePath);
        return ResponseEntity.ok()
                .contentType(MediaType.IMAGE_PNG)
                .body(resource);
    }
}
