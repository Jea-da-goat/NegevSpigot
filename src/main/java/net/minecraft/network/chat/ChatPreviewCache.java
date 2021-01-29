package net.minecraft.network.chat;

import javax.annotation.Nullable;
import net.minecraft.Util;

public class ChatPreviewCache {
    @Nullable
    private ChatPreviewCache.Result result;

    public void set(String query, Component preview) {
        // Paper start
        this.set(query, new ChatDecorator.ModernResult(java.util.Objects.requireNonNull(preview), true, false));
    }
    public void set(String query, ChatDecorator.Result decoratorResult) {
        this.result = new ChatPreviewCache.Result(query, java.util.Objects.requireNonNull(decoratorResult));
        // Paper end
    }

    @Nullable
    public Component pull(String query) {
        // Paper start
        return net.minecraft.Util.mapNullable(this.pullFull(query), Result::preview);
    }
    public @Nullable Result pullFull(String query) {
        // Paper end
        ChatPreviewCache.Result result = this.result;
        if (result != null && result.matches(query)) {
            this.result = null;
            return result; // Paper
        } else {
            return null;
        }
    }

    // Paper start
    public record Result(String query, ChatDecorator.Result decoratorResult) {

        public Component preview() {
            return this.decoratorResult.component();
        }
        // Paper end
        public boolean matches(String query) {
            return this.query.equals(query);
        }
    }
}
