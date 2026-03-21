package me.mapacheee.extendedhorizons.viewdistance;

import com.thewinterframework.service.annotation.Service;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class ClientViewDistanceService {

    private final Map<UUID, Integer> viewDistances = new ConcurrentHashMap<>();

    public void set(UUID playerId, int distance) {
        if (playerId == null) return;
        if (distance < 2) return;
        viewDistances.put(playerId, distance);
    }

    public int getOrDefault(UUID playerId, int fallback) {
        if (playerId == null) return fallback;
        Integer value = viewDistances.get(playerId);
        if (value == null || value < 2) return fallback;
        return value;
    }

    public void remove(UUID playerId) {
        if (playerId == null) return;
        viewDistances.remove(playerId);
    }
}

