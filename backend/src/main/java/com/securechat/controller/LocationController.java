package com.securechat.controller;

import com.securechat.model.Message;
import com.securechat.service.GeoLocationService;
import com.securechat.service.MessageService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/location")
public class LocationController {

    private static final Logger log = LoggerFactory.getLogger(LocationController.class);

    private final GeoLocationService geoLocationService;
    private final MessageService messageService;

    public LocationController(GeoLocationService geoLocationService, MessageService messageService) {
        this.geoLocationService = geoLocationService;
        this.messageService = messageService;
    }

    record VerifyRequest(
        @NotNull(message = "lat is required") Double lat,
        @NotNull(message = "lon is required") Double lon,
        @NotBlank(message = "messageId is required") String messageId
    ) {}

    @PostMapping("/verify")
    public ResponseEntity<?> verify(
            @AuthenticationPrincipal UserDetails user,
            @Valid @RequestBody VerifyRequest body) {

        if (!geoLocationService.isCoarseValid(body.lat(), body.lon())) {
            return ResponseEntity.ok(Map.of("valid", false, "reason", "Invalid coordinates"));
        }

        UUID messageId;
        try {
            messageId = UUID.fromString(body.messageId());
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", "Invalid messageId format"));
        }

        Message message = messageService.getMessage(messageId, user.getUsername());

        if (message.getExpired()) {
            return ResponseEntity.ok(Map.of("valid", false, "reason", "Message has expired"));
        }

        double distance = geoLocationService.getDistance(
                message.getSenderLat(), message.getSenderLon(),
                body.lat(), body.lon());
        boolean valid = distance <= message.getRadiusMeters();

        log.info("LOCATION VERIFY | user={} | locked=({}, {}) | receiver=({}, {}) | radius={}m | distance={}m | valid={}",
                user.getUsername(),
                message.getSenderLat(), message.getSenderLon(),
                body.lat(), body.lon(),
                message.getRadiusMeters(), Math.round(distance), valid);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("valid", valid);
        response.put("distance", Math.round(distance));
        response.put("radius", message.getRadiusMeters());

        if (valid && message.getKeyShard() != null) {
            response.put("keyShard", message.getKeyShard());
            if (message.getBurnAfterRead()) {
                messageService.burnMessage(message.getId());
            }
        } else if (!valid) {
            response.put("message", String.format("You are %d m away, need to be within %d m",
                    Math.round(distance), message.getRadiusMeters()));
        }

        return ResponseEntity.ok(response);
    }
}
