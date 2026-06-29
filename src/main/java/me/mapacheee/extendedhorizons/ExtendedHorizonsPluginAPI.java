package me.mapacheee.extendedhorizons;

import com.google.common.base.Preconditions;

public interface ExtendedHorizonsPluginAPI {
    final class Provider {
        private static ExtendedHorizonsPluginAPI instance;

        private Provider() {}

        public static ExtendedHorizonsPluginAPI getInstance() {
            Preconditions.checkNotNull(instance, "ExtendedHorizonsPluginAPI instance has not been set yet");
            return instance;
        }

        public static void setInstance(final ExtendedHorizonsPluginAPI newInstance) {
            Preconditions.checkNotNull(newInstance, "ExtendedHorizonsPluginAPI instance cannot be null");
            Preconditions.checkArgument(instance == null, "ExtendedHorizonsPluginAPI instance has already been set");
            Provider.instance = newInstance;
        }
    }
}
