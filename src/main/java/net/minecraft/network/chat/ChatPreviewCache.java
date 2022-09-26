package net.minecraft.network.chat;

import javax.annotation.Nullable;

public class ChatPreviewCache {
    @Nullable
    private ChatPreviewCache.Result result;

    public void set(String query, Component preview) {
        this.result = new ChatPreviewCache.Result(query, preview);
    }

    @Nullable
    public Component pull(String query) {
        ChatPreviewCache.Result result = this.result;
        if (result != null && result.matches(query)) {
            this.result = null;
            return result.preview();
        } else {
            return null;
        }
    }

    static record Result(String query, Component preview) {
        public boolean matches(String query) {
            return this.query.equals(query);
        }
    }
}
