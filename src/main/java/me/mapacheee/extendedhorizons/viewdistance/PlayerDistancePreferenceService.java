package me.mapacheee.extendedhorizons.viewdistance;

import com.thewinterframework.service.annotation.Service;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class PlayerDistancePreferenceService {

    private final Map<UUID, Integer> preferredDistances = new ConcurrentHashMap<>();

    public void set(UUID playerId, int distance) {
        if (playerId == null) return;
        if (distance < 2) return;
        preferredDistances.put(playerId, distance);
    }

    public Integer get(UUID playerId) {
        if (playerId == null) return null;
        return preferredDistances.get(playerId);
    }

    public int getOrDefault(UUID playerId, int fallback) {
        if (playerId == null) return fallback;
        Integer value = preferredDistances.get(playerId);
        if (value == null || value < 2) return fallback;
        return value;
    }

    public void remove(UUID playerId) {
        if (playerId == null) return;
        preferredDistances.remove(playerId);
    }
}
