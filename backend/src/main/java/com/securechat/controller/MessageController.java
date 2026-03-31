package com.securechat.controller;

import com.securechat.model.Message;
import com.securechat.service.MessageService;
import org.springframework.core.io.PathResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.nio.file.Path;
import java.util.LinkedHashMap;
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
            @RequestParam(value = "image", required = false) MultipartFile image,
            @RequestParam("encryptedPayload") String encryptedPayload,
            @RequestParam("encryptedKey") String encryptedKey,
            @RequestParam("keyShard") String keyShard,
            @RequestParam("receiverUsername") String receiverUsername,
            @RequestParam(value = "lat", required = false) Double lat,
            @RequestParam(value = "lon", required = false) Double lon,
            @RequestParam(value = "radius", defaultValue = "100000") int radius,
            @RequestParam(value = "geoLocked", defaultValue = "true") boolean geoLocked,
            @RequestParam(value = "burnAfterRead", defaultValue = "false") boolean burnAfterRead,
            @RequestParam(value = "file", required = false) MultipartFile file) throws Exception {

        if (radius <= 0 || radius > 10_000_000) {
            throw new IllegalArgumentException("radius must be between 1 and 10000000 meters");
        }
        if (encryptedKey.length() > 4096) {
            throw new IllegalArgumentException("encryptedKey exceeds maximum allowed length");
        }

        Message msg;
        if (file != null && !file.isEmpty()) {
            msg = messageService.sendDocument(user.getUsername(), receiverUsername,
                    file, encryptedPayload, encryptedKey, keyShard,
                    lat, lon, radius, geoLocked, burnAfterRead);
        } else {
            msg = messageService.sendMessage(user.getUsername(), receiverUsername,
                    image, encryptedPayload, encryptedKey, keyShard,
                    lat, lon, radius, geoLocked, burnAfterRead);
        }
        return ResponseEntity.ok(Map.of("messageId", msg.getId()));
    }

    @GetMapping("/inbox")
    public ResponseEntity<?> inbox(@AuthenticationPrincipal UserDetails user) {
        long unreadCount = messageService.getUnreadCount(user.getUsername());
        var messages = messageService.getInbox(user.getUsername())
                .stream()
                .map(m -> {
                    Map<String, Object> map = new LinkedHashMap<>();
                    map.put("id", m.getId());
                    map.put("senderUsername", m.getSender().getUsername());
                    map.put("createdAt", m.getCreatedAt());
                    map.put("expired", m.getExpired());
                    map.put("isRead", m.getIsRead());
                    map.put("isDocument", m.getIsDocument());
                    map.put("fileName", m.getFileName());
                    map.put("burnAfterRead", m.getBurnAfterRead());
                    map.put("expiresAt", m.getExpiresAt());
                    map.put("geoLocked", m.getGeoLocked());
                    return map;
                })
                .toList();
        return ResponseEntity.ok(Map.of("unreadCount", unreadCount, "messages", messages));
    }

    @GetMapping("/{id}")
    public ResponseEntity<?> getMessage(
            @AuthenticationPrincipal UserDetails user,
            @PathVariable UUID id) {
        Message msg = messageService.getMessage(id, user.getUsername());
        messageService.markAsRead(id, user.getUsername());

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("encryptedKey", msg.getEncryptedKey());
        response.put("senderLat", msg.getSenderLat());
        response.put("senderLon", msg.getSenderLon());
        response.put("radiusMeters", msg.getRadiusMeters());
        response.put("geoLocked", msg.getGeoLocked());
        response.put("expired", msg.getExpired());
        response.put("expiresAt", msg.getExpiresAt());
        response.put("burnAfterRead", msg.getBurnAfterRead());
        response.put("isDocument", msg.getIsDocument());
        response.put("fileName", msg.getFileName());

        if (!msg.getGeoLocked() && msg.getKeyShard() != null) {
            response.put("keyShard", msg.getKeyShard());
            if (msg.getBurnAfterRead()) {
                messageService.burnMessage(id);
            }
        }
        return ResponseEntity.ok(response);
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

    @GetMapping("/{id}/file")
    public ResponseEntity<Resource> getFile(
            @AuthenticationPrincipal UserDetails user,
            @PathVariable UUID id) throws Exception {
        Message msg = messageService.getMessage(id, user.getUsername());
        Path docPath = messageService.getDocumentPath(id, user.getUsername());
        Resource resource = new PathResource(docPath);
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + msg.getFileName() + ".enc\"")
                .body(resource);
    }
}
