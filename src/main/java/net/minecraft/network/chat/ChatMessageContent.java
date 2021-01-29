package net.minecraft.network.chat;

import java.util.Objects;
import net.minecraft.network.FriendlyByteBuf;

// Paper start
public record ChatMessageContent(String plain, Component decorated, ChatDecorator.Result decorationResult) {

    public ChatMessageContent(String plain, Component decorated) {
        this(plain, decorated, new ChatDecorator.ModernResult(decorated, true, false));
    }

    public ChatMessageContent withDecorationResult(ChatDecorator.Result result) {
        return new ChatMessageContent(this.plain, this.decorated, result);
    }
    // Paper end
    public ChatMessageContent(String content) {
        this(content, Component.literal(content));
    }

    public boolean isDecorated() {
        return !this.decorated.equals(Component.literal(this.plain));
    }

    public static ChatMessageContent read(FriendlyByteBuf buf) {
        String string = buf.readUtf(256);
        Component component = buf.readNullable(FriendlyByteBuf::readComponent);
        return new ChatMessageContent(string, Objects.requireNonNullElse(component, Component.literal(string)));
    }

    public static void write(FriendlyByteBuf buf, ChatMessageContent contents) {
        buf.writeUtf(contents.plain(), 256);
        Component component = contents.isDecorated() ? contents.decorated() : null;
        buf.writeNullable(component, FriendlyByteBuf::writeComponent);
    }
}
