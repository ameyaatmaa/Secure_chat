package com.securechat.controller;

import com.securechat.model.Message;
import com.securechat.service.GeoLocationService;
import com.securechat.service.MessageService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/location")
public class LocationController {

    private final GeoLocationService geoLocationService;
    private final MessageService messageService;

    public LocationController(GeoLocationService geoLocationService, MessageService messageService) {
        this.geoLocationService = geoLocationService;
        this.messageService = messageService;
    }

    @PostMapping("/verify")
    public ResponseEntity<?> verify(
            @AuthenticationPrincipal UserDetails user,
            @RequestBody Map<String, Object> body) {
        double lat = ((Number) body.get("lat")).doubleValue();
        double lon = ((Number) body.get("lon")).doubleValue();
        UUID messageId = UUID.fromString((String) body.get("messageId"));

        if (!geoLocationService.isCoarseValid(lat, lon)) {
            return ResponseEntity.ok(Map.of("valid", false, "reason", "Invalid coordinates"));
        }

        Message message = messageService.getMessage(messageId, user.getUsername());
        boolean valid = geoLocationService.isWithinRadius(
                message.getSenderLat(), message.getSenderLon(),
                lat, lon, message.getRadiusMeters());

        return ResponseEntity.ok(Map.of("valid", valid));
    }
}
