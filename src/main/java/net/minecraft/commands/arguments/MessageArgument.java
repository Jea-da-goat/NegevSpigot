package net.minecraft.commands.arguments;

import com.google.common.collect.Lists;
import com.mojang.brigadier.StringReader;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.logging.LogUtils;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import javax.annotation.Nullable;
import net.minecraft.commands.CommandSigningContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.arguments.selector.EntitySelector;
import net.minecraft.commands.arguments.selector.EntitySelectorParser;
import net.minecraft.network.chat.ChatDecorator;
import net.minecraft.network.chat.ChatMessageContent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.PlayerChatMessage;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.FilteredText;
import net.minecraft.server.players.PlayerList;
import org.slf4j.Logger;

public class MessageArgument implements SignedArgument<MessageArgument.Message> {
    private static final Collection<String> EXAMPLES = Arrays.asList("Hello world!", "foo", "@e", "Hello @p :)");
    private static final Logger LOGGER = LogUtils.getLogger();

    public static MessageArgument message() {
        return new MessageArgument();
    }

    public static Component getMessage(CommandContext<CommandSourceStack> context, String name) throws CommandSyntaxException {
        MessageArgument.Message message = context.getArgument(name, MessageArgument.Message.class);
        return message.resolveComponent(context.getSource());
    }

    public static MessageArgument.ChatMessage getChatMessage(CommandContext<CommandSourceStack> context, String name) throws CommandSyntaxException {
        MessageArgument.Message message = context.getArgument(name, MessageArgument.Message.class);
        Component component = message.resolveComponent(context.getSource());
        CommandSigningContext commandSigningContext = context.getSource().getSigningContext();
        PlayerChatMessage playerChatMessage = commandSigningContext.getArgument(name);
        if (playerChatMessage == null) {
            ChatMessageContent chatMessageContent = new ChatMessageContent(message.text, component);
            return new MessageArgument.ChatMessage(PlayerChatMessage.system(chatMessageContent));
        } else {
            return new MessageArgument.ChatMessage(ChatDecorator.attachIfNotDecorated(playerChatMessage, component));
        }
    }

    public MessageArgument.Message parse(StringReader stringReader) throws CommandSyntaxException {
        return MessageArgument.Message.parseText(stringReader, true);
    }

    public Collection<String> getExamples() {
        return EXAMPLES;
    }

    @Override
    public String getSignableText(MessageArgument.Message value) {
        return value.getText();
    }

    @Override
    public CompletableFuture<Component> resolvePreview(CommandSourceStack source, MessageArgument.Message format) throws CommandSyntaxException {
        return format.resolveDecoratedComponent(source);
    }

    @Override
    public Class<MessageArgument.Message> getValueType() {
        return MessageArgument.Message.class;
    }

    static void logResolutionFailure(CommandSourceStack source, CompletableFuture<?> future) {
        future.exceptionally((throwable) -> {
            LOGGER.error("Encountered unexpected exception while resolving chat message argument from '{}'", source.getDisplayName().getString(), throwable);
            return null;
        });
    }

    public static record ChatMessage(PlayerChatMessage signedArgument) {
        public void resolve(CommandSourceStack source, Consumer<PlayerChatMessage> callback) {
            MinecraftServer minecraftServer = source.getServer();
            source.getChatMessageChainer().append(() -> {
                CompletableFuture<FilteredText> completableFuture = this.filterPlainText(source, this.signedArgument.signedContent().plain());
                CompletableFuture<PlayerChatMessage> completableFuture2 = minecraftServer.getChatDecorator().decorate(source.getPlayer(), this.signedArgument);
                return CompletableFuture.allOf(completableFuture, completableFuture2).thenAcceptAsync((void_) -> {
                    PlayerChatMessage playerChatMessage = completableFuture2.join().filter(completableFuture.join().mask());
                    callback.accept(playerChatMessage);
                }, minecraftServer);
            });
        }

        private CompletableFuture<FilteredText> filterPlainText(CommandSourceStack source, String text) {
            ServerPlayer serverPlayer = source.getPlayer();
            return serverPlayer != null && this.signedArgument.hasSignatureFrom(serverPlayer.getUUID()) ? serverPlayer.getTextFilter().processStreamMessage(text) : CompletableFuture.completedFuture(FilteredText.passThrough(text));
        }

        public void consume(CommandSourceStack source) {
            if (!this.signedArgument.signer().isSystem()) {
                this.resolve(source, (message) -> {
                    PlayerList playerList = source.getServer().getPlayerList();
                    playerList.broadcastMessageHeader(message, Set.of());
                });
            }

        }
    }

    public static class Message {
        final String text;
        private final MessageArgument.Part[] parts;

        public Message(String contents, MessageArgument.Part[] selectors) {
            this.text = contents;
            this.parts = selectors;
        }

        public String getText() {
            return this.text;
        }

        public MessageArgument.Part[] getParts() {
            return this.parts;
        }

        CompletableFuture<Component> resolveDecoratedComponent(CommandSourceStack source) throws CommandSyntaxException {
            Component component = this.resolveComponent(source);
            CompletableFuture<Component> completableFuture = source.getServer().getChatDecorator().decorate(source.getPlayer(), component);
            MessageArgument.logResolutionFailure(source, completableFuture);
            return completableFuture;
        }

        Component resolveComponent(CommandSourceStack source) throws CommandSyntaxException {
            return this.toComponent(source, source.hasPermission(2));
        }

        public Component toComponent(CommandSourceStack source, boolean canUseSelectors) throws CommandSyntaxException {
            if (this.parts.length != 0 && canUseSelectors) {
                MutableComponent mutableComponent = Component.literal(this.text.substring(0, this.parts[0].getStart()));
                int i = this.parts[0].getStart();

                for(MessageArgument.Part part : this.parts) {
                    Component component = part.toComponent(source);
                    if (i < part.getStart()) {
                        mutableComponent.append(this.text.substring(i, part.getStart()));
                    }

                    if (component != null) {
                        mutableComponent.append(component);
                    }

                    i = part.getEnd();
                }

                if (i < this.text.length()) {
                    mutableComponent.append(this.text.substring(i));
                }

                return mutableComponent;
            } else {
                return Component.literal(this.text);
            }
        }

        public static MessageArgument.Message parseText(StringReader reader, boolean canUseSelectors) throws CommandSyntaxException {
            String string = reader.getString().substring(reader.getCursor(), reader.getTotalLength());
            if (!canUseSelectors) {
                reader.setCursor(reader.getTotalLength());
                return new MessageArgument.Message(string, new MessageArgument.Part[0]);
            } else {
                List<MessageArgument.Part> list = Lists.newArrayList();
                int i = reader.getCursor();

                while(true) {
                    int j;
                    EntitySelector entitySelector;
                    while(true) {
                        if (!reader.canRead()) {
                            return new MessageArgument.Message(string, list.toArray(new MessageArgument.Part[0]));
                        }

                        if (reader.peek() == '@') {
                            j = reader.getCursor();

                            try {
                                EntitySelectorParser entitySelectorParser = new EntitySelectorParser(reader);
                                entitySelector = entitySelectorParser.parse();
                                break;
                            } catch (CommandSyntaxException var8) {
                                if (var8.getType() != EntitySelectorParser.ERROR_MISSING_SELECTOR_TYPE && var8.getType() != EntitySelectorParser.ERROR_UNKNOWN_SELECTOR_TYPE) {
                                    throw var8;
                                }

                                reader.setCursor(j + 1);
                            }
                        } else {
                            reader.skip();
                        }
                    }

                    list.add(new MessageArgument.Part(j - i, reader.getCursor() - i, entitySelector));
                }
            }
        }
    }

    public static class Part {
        private final int start;
        private final int end;
        private final EntitySelector selector;

        public Part(int start, int end, EntitySelector selector) {
            this.start = start;
            this.end = end;
            this.selector = selector;
        }

        public int getStart() {
            return this.start;
        }

        public int getEnd() {
            return this.end;
        }

        public EntitySelector getSelector() {
            return this.selector;
        }

        @Nullable
        public Component toComponent(CommandSourceStack source) throws CommandSyntaxException {
            return EntitySelector.joinNames(this.selector.findEntities(source));
        }
    }
}
