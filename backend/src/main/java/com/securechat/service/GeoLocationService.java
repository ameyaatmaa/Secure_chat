package com.securechat.service;

import com.securechat.util.HaversineCalculator;
import org.springframework.stereotype.Service;

@Service
public class GeoLocationService {

    public boolean isCoarseValid(double lat, double lon) {
        if (lat == 0.0 && lon == 0.0) return false; // null island
        return lat >= -90 && lat <= 90 && lon >= -180 && lon <= 180;
    }

    public boolean isWithinRadius(double senderLat, double senderLon,
                                   double receiverLat, double receiverLon,
                                   int radiusMeters) {
        if (!isCoarseValid(receiverLat, receiverLon)) return false;
        return HaversineCalculator.distanceMeters(senderLat, senderLon, receiverLat, receiverLon)
                <= radiusMeters;
    }
}
