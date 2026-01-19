package me.mapacheee.extendedhorizons.viewdistance.service.cache;

import java.util.UUID;

public record ChunkCacheKey(UUID worldId, long chunkKey) {
}
