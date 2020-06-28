package net.minecraft.network.chat;

import java.util.UUID;
import javax.annotation.Nullable;
import net.minecraft.Util;
import net.minecraft.world.entity.player.ProfilePublicKey;

public record ChatSender(UUID profileId, @Nullable ProfilePublicKey profilePublicKey) {
    public static final ChatSender SYSTEM = new ChatSender(Util.NIL_UUID, (ProfilePublicKey)null);

    // Paper start
    public ChatSender {
        com.google.common.base.Preconditions.checkNotNull(profileId, "uuid cannot be null");
    }
    // Paper end

    public boolean isSystem() {
        return SYSTEM.equals(this);
    }
}
