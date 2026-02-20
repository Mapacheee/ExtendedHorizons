package me.mapacheee.extendedhorizons.viewdistance.service.packet;

import com.google.inject.Inject;
import com.thewinterframework.service.annotation.Service;
import me.mapacheee.extendedhorizons.shared.service.ConfigService;
import me.mapacheee.extendedhorizons.viewdistance.service.nms.NMSPacketAccess;

/**
 * Service responsible for estimating the size of packets in bytes.
 * This is used for bandwidth tracking and rate limiting.
 */
@Service
public class PacketSizeEstimator {

    private final ConfigService configService;
    private final NMSPacketAccess nmsPacketAccess;

    @Inject
    public PacketSizeEstimator(ConfigService configService, NMSPacketAccess nmsPacketAccess) {
        this.configService = configService;
        this.nmsPacketAccess = nmsPacketAccess;
    }

    /**
     * Estimates the size of a packet in bytes.
     * 
     * @param packet The packet object (NMS packet)
     * @return Estimated size in bytes
     */
    public long estimatePacketSize(Object packet) {
        int size = nmsPacketAccess.getPacketSize(packet);

        if (size < 0) {
            return configService.get().bandwidthSaver().estimatedPacketSize();
        }

        return size;
    }
}
