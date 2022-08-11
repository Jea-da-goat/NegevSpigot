package net.minecraft.server.network;

import com.google.common.primitives.Ints;
import com.mojang.authlib.GameProfile;
import com.mojang.authlib.exceptions.AuthenticationUnavailableException;
import com.mojang.logging.LogUtils;
import java.math.BigInteger;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.security.PrivateKey;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import javax.annotation.Nullable;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import net.minecraft.DefaultUncaughtExceptionHandler;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.Connection;
import net.minecraft.network.PacketSendListener;
import net.minecraft.network.TickablePacketListener;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.protocol.game.ClientboundDisconnectPacket;
import net.minecraft.network.protocol.login.ClientboundGameProfilePacket;
import net.minecraft.network.protocol.login.ClientboundHelloPacket;
import net.minecraft.network.protocol.login.ClientboundLoginCompressionPacket;
import net.minecraft.network.protocol.login.ClientboundLoginDisconnectPacket;
import net.minecraft.network.protocol.login.ServerLoginPacketListener;
import net.minecraft.network.protocol.login.ServerboundCustomQueryPacket;
import net.minecraft.network.protocol.login.ServerboundHelloPacket;
import net.minecraft.network.protocol.login.ServerboundKeyPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Crypt;
import net.minecraft.util.CryptException;
import net.minecraft.util.RandomSource;
import net.minecraft.util.SignatureValidator;
import net.minecraft.world.entity.player.ProfilePublicKey;
import org.apache.commons.lang3.Validate;
import org.slf4j.Logger;

// CraftBukkit start
import org.bukkit.craftbukkit.util.Waitable;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.event.player.PlayerPreLoginEvent;
// CraftBukkit end

public class ServerLoginPacketListenerImpl implements TickablePacketListener, ServerLoginPacketListener {

    private static final AtomicInteger UNIQUE_THREAD_ID = new AtomicInteger(0);
    static final Logger LOGGER = LogUtils.getLogger();
    private static final int MAX_TICKS_BEFORE_LOGIN = 600;
    private static final RandomSource RANDOM = new org.bukkit.craftbukkit.util.RandomSourceWrapper(new java.util.Random()); // Paper - This is called across threads, make safe
    private final byte[] nonce;
    final MinecraftServer server;
    public final Connection connection;
    public ServerLoginPacketListenerImpl.State state;
    private int tick;
    public @Nullable
    GameProfile gameProfile;
    private final String serverId;
    @Nullable
    private ServerPlayer delayedAcceptPlayer;
    @Nullable
    private ProfilePublicKey.Data profilePublicKeyData;
    public String hostname = ""; // CraftBukkit - add field
    public boolean iKnowThisMayNotBeTheBestIdeaButPleaseDisableUsernameValidation = false; // Paper - username validation overriding
    private int velocityLoginMessageId = -1; // Paper - Velocity support

    public ServerLoginPacketListenerImpl(MinecraftServer server, Connection connection) {
        this.state = ServerLoginPacketListenerImpl.State.HELLO;
        this.serverId = "";
        this.server = server;
        this.connection = connection;
        this.nonce = Ints.toByteArray(ServerLoginPacketListenerImpl.RANDOM.nextInt());
    }

    @Override
    public void tick() {
        // Paper start - Do not allow logins while the server is shutting down
        if (!MinecraftServer.getServer().isRunning()) {
            this.disconnect(org.bukkit.craftbukkit.util.CraftChatMessage.fromString(org.spigotmc.SpigotConfig.restartMessage)[0]);
            return;
        }
        // Paper end
        if (this.state == ServerLoginPacketListenerImpl.State.READY_TO_ACCEPT) {
            // Paper start - prevent logins to be processed even though disconnect was called
            if (connection.isConnected()) {
                this.handleAcceptedLogin();
            }
            // Paper end
        } else if (this.state == ServerLoginPacketListenerImpl.State.DELAY_ACCEPT) {
            ServerPlayer entityplayer = this.server.getPlayerList().getActivePlayer(this.gameProfile.getId()); // Paper

            if (entityplayer == null) {
                this.state = ServerLoginPacketListenerImpl.State.READY_TO_ACCEPT;
                this.placeNewPlayer(this.delayedAcceptPlayer);
                this.delayedAcceptPlayer = null;
            }
        }

        if (this.tick++ == 600) {
            this.disconnect(Component.translatable("multiplayer.disconnect.slow_login"));
        }

    }

    // CraftBukkit start
    @Deprecated
    public void disconnect(String s) {
        this.disconnect(org.bukkit.craftbukkit.util.CraftChatMessage.fromString(s, true)[0]); // Paper - Fix hex colors not working in some kick messages
    }
    // CraftBukkit end

    @Override
    public Connection getConnection() {
        return this.connection;
    }

    public void disconnect(Component reason) {
        try {
            ServerLoginPacketListenerImpl.LOGGER.info("Disconnecting {}: {}", this.getUserName(), reason.getString());
            this.connection.send(new ClientboundLoginDisconnectPacket(reason));
            this.connection.disconnect(reason);
        } catch (Exception exception) {
            ServerLoginPacketListenerImpl.LOGGER.error("Error whilst disconnecting player", exception);
        }

    }

    private static final java.util.concurrent.ExecutorService authenticatorPool = new java.util.concurrent.ThreadPoolExecutor(0, 16, 60L, java.util.concurrent.TimeUnit.SECONDS, new java.util.concurrent.SynchronousQueue<>(),  new com.google.common.util.concurrent.ThreadFactoryBuilder().setNameFormat("User Authenticator #%d").setUncaughtExceptionHandler(new DefaultUncaughtExceptionHandler(LOGGER)).build()); // Paper - Cache authenticator threads

    // Spigot start
    public void initUUID()
    {
        UUID uuid;
        if ( connection.spoofedUUID != null )
        {
            uuid = connection.spoofedUUID;
        } else
        {
            uuid = UUIDUtil.createOfflinePlayerUUID( this.gameProfile.getName() );
        }

        this.gameProfile = new GameProfile( uuid, this.gameProfile.getName() );

        if (connection.spoofedProfile != null)
        {
            for ( com.mojang.authlib.properties.Property property : connection.spoofedProfile )
            {
                if ( !ServerHandshakePacketListenerImpl.PROP_PATTERN.matcher( property.getName() ).matches() ) continue;
                this.gameProfile.getProperties().put( property.getName(), property );
            }
        }
    }

    public void handleAcceptedLogin() {
        ProfilePublicKey profilepublickey = null;

        if (!this.server.usesAuthentication()) {
            // this.gameProfile = this.createFakeProfile(this.gameProfile); // Spigot - Moved to initUUID
            // Spigot end
        } else {
            try {
                SignatureValidator signaturevalidator = this.server.getServiceSignatureValidator();

                profilepublickey = ServerLoginPacketListenerImpl.validatePublicKey(this.profilePublicKeyData, this.gameProfile.getId(), signaturevalidator, this.server.enforceSecureProfile());
            } catch (ProfilePublicKey.ValidationException profilepublickey_b) {
                // ServerLoginPacketListenerImpl.LOGGER.error("Failed to validate profile key: {}", profilepublickey_b.getMessage()); // Paper - unnecessary log
                if (!this.connection.isMemoryConnection()) {
                    this.disconnect(profilepublickey_b.getComponent());
                    return;
                }
            }
        }

        // CraftBukkit start - fire PlayerLoginEvent
        ServerPlayer s = this.server.getPlayerList().canPlayerLogin(this, this.gameProfile, profilepublickey, hostname);

        if (s == null) {
            // this.disconnect(ichatbasecomponent);
            // CraftBukkit end
        } else {
            this.state = ServerLoginPacketListenerImpl.State.ACCEPTED;
            if (this.server.getCompressionThreshold() >= 0 && !this.connection.isMemoryConnection()) {
                this.connection.send(new ClientboundLoginCompressionPacket(this.server.getCompressionThreshold()), PacketSendListener.thenRun(() -> {
                    this.connection.setupCompression(this.server.getCompressionThreshold(), true);
                }));
            }

            this.connection.send(new ClientboundGameProfilePacket(this.gameProfile));
            ServerPlayer entityplayer = this.server.getPlayerList().getActivePlayer(this.gameProfile.getId()); // Paper

            try {
                ServerPlayer entityplayer1 = this.server.getPlayerList().getPlayerForLogin(this.gameProfile, s); // CraftBukkit - add player reference

                if (entityplayer != null) {
                    this.state = ServerLoginPacketListenerImpl.State.DELAY_ACCEPT;
                    this.delayedAcceptPlayer = entityplayer1;
                } else {
                    this.placeNewPlayer(entityplayer1);
                }
            } catch (Exception exception) {
                ServerLoginPacketListenerImpl.LOGGER.error("Couldn't place player in world", exception);
                MutableComponent ichatmutablecomponent = Component.translatable("multiplayer.disconnect.invalid_player_data");
                // Paper start
                if (MinecraftServer.getServer().isDebugging()) {
                    exception.printStackTrace();
                }
                // Paper end

                this.connection.send(new ClientboundDisconnectPacket(ichatmutablecomponent));
                this.connection.disconnect(ichatmutablecomponent);
            }
        }

    }

    private void placeNewPlayer(ServerPlayer player) {
        this.server.getPlayerList().placeNewPlayer(this.connection, player);
    }

    @Override
    public void onDisconnect(Component reason) {
        ServerLoginPacketListenerImpl.LOGGER.info("{} lost connection: {}", this.getUserName(), reason.getString());
    }

    public String getUserName() {
        // Paper start
        String ip = io.papermc.paper.configuration.GlobalConfiguration.get().logging.logPlayerIpAddresses ? String.valueOf(this.connection.getRemoteAddress()) : "<ip address withheld>";
        return this.gameProfile != null ? this.gameProfile + " (" + ip + ")" : String.valueOf(ip);
        // Paper end
    }

    @Nullable
    private static ProfilePublicKey validatePublicKey(@Nullable ProfilePublicKey.Data publicKeyData, UUID playerUuid, SignatureValidator servicesSignatureVerifier, boolean shouldThrowOnMissingKey) throws ProfilePublicKey.ValidationException {
        if (publicKeyData == null) {
            if (shouldThrowOnMissingKey) {
                throw new ProfilePublicKey.ValidationException(ProfilePublicKey.MISSING_PROFILE_PUBLIC_KEY);
            } else {
                return null;
            }
        } else {
            return ProfilePublicKey.createValidated(servicesSignatureVerifier, playerUuid, publicKeyData, Duration.ZERO);
        }
    }

    // Paper start - validate usernames
    public static boolean validateUsername(String in) {
        if (in == null || in.isEmpty() || in.length() > 16) {
            return false;
        }

        for (int i = 0, len = in.length(); i < len; ++i) {
            char c = in.charAt(i);

            if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || (c == '_' || c == '.')) {
                continue;
            }

            return false;
        }

        return true;
    }
    // Paper end - validate usernames

    @Override
    public void handleHello(ServerboundHelloPacket packet) {
        Validate.validState(this.state == ServerLoginPacketListenerImpl.State.HELLO, "Unexpected hello packet", new Object[0]);
        Validate.validState(ServerLoginPacketListenerImpl.isValidUsername(packet.name()), "Invalid characters in username", new Object[0]);
        // Paper start - validate usernames
        if (io.papermc.paper.configuration.GlobalConfiguration.get().proxies.isProxyOnlineMode() && io.papermc.paper.configuration.GlobalConfiguration.get().unsupportedSettings.performUsernameValidation) {
            if (!this.iKnowThisMayNotBeTheBestIdeaButPleaseDisableUsernameValidation && !validateUsername(packet.name())) {
                ServerLoginPacketListenerImpl.this.disconnect("Failed to verify username!");
                return;
            }
        }
        // Paper end - validate usernames
        this.profilePublicKeyData = (ProfilePublicKey.Data) packet.publicKey().orElse(null); // CraftBukkit - decompile error
        GameProfile gameprofile = this.server.getSingleplayerProfile();

        if (gameprofile != null && packet.name().equalsIgnoreCase(gameprofile.getName())) {
            this.gameProfile = gameprofile;
            this.state = ServerLoginPacketListenerImpl.State.READY_TO_ACCEPT;
        } else {
            this.gameProfile = new GameProfile((UUID) null, packet.name());
            if (this.server.usesAuthentication() && !this.connection.isMemoryConnection()) {
                this.state = ServerLoginPacketListenerImpl.State.KEY;
                this.connection.send(new ClientboundHelloPacket("", this.server.getKeyPair().getPublic().getEncoded(), this.nonce));
            } else {
                // Paper start - Velocity support
                if (io.papermc.paper.configuration.GlobalConfiguration.get().proxies.velocity.enabled) {
                    this.velocityLoginMessageId = java.util.concurrent.ThreadLocalRandom.current().nextInt();
                    net.minecraft.network.FriendlyByteBuf buf = new net.minecraft.network.FriendlyByteBuf(io.netty.buffer.Unpooled.buffer());
                    buf.writeByte(com.destroystokyo.paper.proxy.VelocityProxy.MAX_SUPPORTED_FORWARDING_VERSION);
                    net.minecraft.network.protocol.login.ClientboundCustomQueryPacket packet1 = new net.minecraft.network.protocol.login.ClientboundCustomQueryPacket(this.velocityLoginMessageId, com.destroystokyo.paper.proxy.VelocityProxy.PLAYER_INFO_CHANNEL, buf);
                    this.connection.send(packet1);
                    return;
                }
                // Paper end
                // Spigot start
            // Paper start - Cache authenticator threads
            authenticatorPool.execute(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            ServerLoginPacketListenerImpl.this.initUUID();
                            new LoginHandler().fireEvents();
                        } catch (Exception ex) {
                            ServerLoginPacketListenerImpl.this.disconnect("Failed to verify username!");
                            server.server.getLogger().log(java.util.logging.Level.WARNING, "Exception verifying " + ServerLoginPacketListenerImpl.this.gameProfile.getName(), ex);
                        }
                    }
            });
            // Paper end
                // Spigot end
            }

        }
    }

    public static boolean isValidUsername(String name) {
        return name.chars().filter((i) -> {
            return i <= 32 || i >= 127;
        }).findAny().isEmpty();
    }

    @Override
    public void handleKey(ServerboundKeyPacket packet) {
        Validate.validState(this.state == ServerLoginPacketListenerImpl.State.KEY, "Unexpected key packet", new Object[0]);

        final String s;

        try {
            PrivateKey privatekey = this.server.getKeyPair().getPrivate();

            if (this.profilePublicKeyData != null) {
                ProfilePublicKey profilepublickey = new ProfilePublicKey(this.profilePublicKeyData);

                if (!packet.isChallengeSignatureValid(this.nonce, profilepublickey)) {
                    throw new IllegalStateException("Protocol error");
                }
            } else if (!packet.isNonceValid(this.nonce, privatekey)) {
                throw new IllegalStateException("Protocol error");
            }

            SecretKey secretkey = packet.getSecretKey(privatekey);
            // Paper start
//            Cipher cipher = Crypt.getCipher(2, secretkey);
//            Cipher cipher1 = Crypt.getCipher(1, secretkey);
            // Paper end

            s = (new BigInteger(Crypt.digestData("", this.server.getKeyPair().getPublic(), secretkey))).toString(16);
            this.state = ServerLoginPacketListenerImpl.State.AUTHENTICATING;
            this.connection.setupEncryption(secretkey); // Paper
        } catch (CryptException cryptographyexception) {
            throw new IllegalStateException("Protocol error", cryptographyexception);
        }

        // Paper start - Cache authenticator threads
        authenticatorPool.execute(new Runnable() {
            public void run() {
                GameProfile gameprofile = ServerLoginPacketListenerImpl.this.gameProfile;

                try {
                    ServerLoginPacketListenerImpl.this.gameProfile = ServerLoginPacketListenerImpl.this.server.getSessionService().hasJoinedServer(new GameProfile((UUID) null, gameprofile.getName()), s, this.getAddress());
                    if (ServerLoginPacketListenerImpl.this.gameProfile != null) {
                        // CraftBukkit start - fire PlayerPreLoginEvent
                        if (!ServerLoginPacketListenerImpl.this.connection.isConnected()) {
                            return;
                        }

                        new LoginHandler().fireEvents();
                    } else if (ServerLoginPacketListenerImpl.this.server.isSingleplayer()) {
                        ServerLoginPacketListenerImpl.LOGGER.warn("Failed to verify username but will let them in anyway!");
                        ServerLoginPacketListenerImpl.this.gameProfile = gameprofile;
                        ServerLoginPacketListenerImpl.this.state = ServerLoginPacketListenerImpl.State.READY_TO_ACCEPT;
                    } else {
                        ServerLoginPacketListenerImpl.this.disconnect(Component.translatable("multiplayer.disconnect.unverified_username"));
                        ServerLoginPacketListenerImpl.LOGGER.error("Username '{}' tried to join with an invalid session", gameprofile.getName());
                    }
                } catch (AuthenticationUnavailableException authenticationunavailableexception) {
                    if (ServerLoginPacketListenerImpl.this.server.isSingleplayer()) {
                        ServerLoginPacketListenerImpl.LOGGER.warn("Authentication servers are down but will let them in anyway!");
                        ServerLoginPacketListenerImpl.this.gameProfile = gameprofile;
                        ServerLoginPacketListenerImpl.this.state = ServerLoginPacketListenerImpl.State.READY_TO_ACCEPT;
                    } else {
                        ServerLoginPacketListenerImpl.this.disconnect(io.papermc.paper.adventure.PaperAdventure.asVanilla(io.papermc.paper.configuration.GlobalConfiguration.get().messages.kick.authenticationServersDown)); // Paper
                        ServerLoginPacketListenerImpl.LOGGER.error("Couldn't verify username because servers are unavailable");
                    }
                    // CraftBukkit start - catch all exceptions
                } catch (Exception exception) {
                    ServerLoginPacketListenerImpl.this.disconnect("Failed to verify username!");
                    server.server.getLogger().log(java.util.logging.Level.WARNING, "Exception verifying " + gameprofile.getName(), exception);
                    // CraftBukkit end
                }

            }

            @Nullable
            private InetAddress getAddress() {
                SocketAddress socketaddress = ServerLoginPacketListenerImpl.this.connection.getRemoteAddress();

                return ServerLoginPacketListenerImpl.this.server.getPreventProxyConnections() && socketaddress instanceof InetSocketAddress ? ((InetSocketAddress) socketaddress).getAddress() : null;
            }
        });
        // Paper end
    }

    // Spigot start
    public class LoginHandler {

        public void fireEvents() throws Exception {
                            // Paper start - Velocity support
                            if (ServerLoginPacketListenerImpl.this.velocityLoginMessageId == -1 && io.papermc.paper.configuration.GlobalConfiguration.get().proxies.velocity.enabled) {
                                disconnect("This server requires you to connect with Velocity.");
                                return;
                            }
                            // Paper end
                        String playerName = ServerLoginPacketListenerImpl.this.gameProfile.getName();
                        java.net.InetAddress address = ((java.net.InetSocketAddress) ServerLoginPacketListenerImpl.this.connection.getRemoteAddress()).getAddress();
                        java.net.InetAddress rawAddress = ((java.net.InetSocketAddress) connection.getRawAddress()).getAddress(); // Paper
                        java.util.UUID uniqueId = ServerLoginPacketListenerImpl.this.gameProfile.getId();
                        final org.bukkit.craftbukkit.CraftServer server = ServerLoginPacketListenerImpl.this.server.server;

                        // Paper start
                        com.destroystokyo.paper.profile.PlayerProfile profile = com.destroystokyo.paper.profile.CraftPlayerProfile.asBukkitMirror(ServerLoginPacketListenerImpl.this.gameProfile);
                        AsyncPlayerPreLoginEvent asyncEvent = new AsyncPlayerPreLoginEvent(playerName, address, rawAddress, uniqueId, profile, ServerLoginPacketListenerImpl.this.hostname); // Paper - add rawAddress & hostname
                        server.getPluginManager().callEvent(asyncEvent);
                        profile = asyncEvent.getPlayerProfile();
                        profile.complete(true); // Paper - setPlayerProfileAPI
                        gameProfile = com.destroystokyo.paper.profile.CraftPlayerProfile.asAuthlibCopy(profile);
                        playerName = gameProfile.getName();
                        uniqueId = gameProfile.getId();
                        // Paper end

                        if (PlayerPreLoginEvent.getHandlerList().getRegisteredListeners().length != 0) {
                            final PlayerPreLoginEvent event = new PlayerPreLoginEvent(playerName, address, uniqueId);
                            if (asyncEvent.getResult() != PlayerPreLoginEvent.Result.ALLOWED) {
                                event.disallow(asyncEvent.getResult(), asyncEvent.kickMessage()); // Paper - Adventure
                            }
                            Waitable<PlayerPreLoginEvent.Result> waitable = new Waitable<PlayerPreLoginEvent.Result>() {
                                @Override
                                protected PlayerPreLoginEvent.Result evaluate() {
                                    server.getPluginManager().callEvent(event);
                                    return event.getResult();
                                }};

                            ServerLoginPacketListenerImpl.this.server.processQueue.add(waitable);
                            if (waitable.get() != PlayerPreLoginEvent.Result.ALLOWED) {
                                ServerLoginPacketListenerImpl.this.disconnect(io.papermc.paper.adventure.PaperAdventure.asVanilla(event.kickMessage())); // Paper - Adventure
                                return;
                            }
                        } else {
                            if (asyncEvent.getLoginResult() != AsyncPlayerPreLoginEvent.Result.ALLOWED) {
                                ServerLoginPacketListenerImpl.this.disconnect(io.papermc.paper.adventure.PaperAdventure.asVanilla(asyncEvent.kickMessage())); // Paper - Adventure
                                return;
                            }
                        }
                        // CraftBukkit end
                        ServerLoginPacketListenerImpl.LOGGER.info("UUID of player {} is {}", ServerLoginPacketListenerImpl.this.gameProfile.getName(), ServerLoginPacketListenerImpl.this.gameProfile.getId());
                        ServerLoginPacketListenerImpl.this.state = ServerLoginPacketListenerImpl.State.READY_TO_ACCEPT;
        }
    }
    // Spigot end

    public void handleCustomQueryPacket(ServerboundCustomQueryPacket packet) {
        // Paper start - Velocity support
        if (io.papermc.paper.configuration.GlobalConfiguration.get().proxies.velocity.enabled && packet.getTransactionId() == this.velocityLoginMessageId) {
            net.minecraft.network.FriendlyByteBuf buf = packet.getData();
            if (buf == null) {
                this.disconnect("This server requires you to connect with Velocity.");
                return;
            }

            if (!com.destroystokyo.paper.proxy.VelocityProxy.checkIntegrity(buf)) {
                this.disconnect("Unable to verify player details");
                return;
            }

            int version = buf.readVarInt();
            if (version > com.destroystokyo.paper.proxy.VelocityProxy.MAX_SUPPORTED_FORWARDING_VERSION) {
                throw new IllegalStateException("Unsupported forwarding version " + version + ", wanted upto " + com.destroystokyo.paper.proxy.VelocityProxy.MAX_SUPPORTED_FORWARDING_VERSION);
            }

            java.net.SocketAddress listening = this.connection.getRemoteAddress();
            int port = 0;
            if (listening instanceof java.net.InetSocketAddress) {
                port = ((java.net.InetSocketAddress) listening).getPort();
            }
            this.connection.address = new java.net.InetSocketAddress(com.destroystokyo.paper.proxy.VelocityProxy.readAddress(buf), port);

            this.gameProfile = com.destroystokyo.paper.proxy.VelocityProxy.createProfile(buf);

            // We should already have this, but, we'll read it out anyway
            //noinspection NonStrictComparisonCanBeEquality
            if (version >= com.destroystokyo.paper.proxy.VelocityProxy.MODERN_FORWARDING_WITH_KEY_V2) {
                final ProfilePublicKey.Data forwardedKeyData = com.destroystokyo.paper.proxy.VelocityProxy.readForwardedKey(buf);
                final UUID signer = com.destroystokyo.paper.proxy.VelocityProxy.readSignerUuidOrElse(buf, this.gameProfile.getId());
                if (this.profilePublicKeyData == null) {
                    try {
                        ServerLoginPacketListenerImpl.validatePublicKey(forwardedKeyData, signer, this.server.getServiceSignatureValidator(), this.server.enforceSecureProfile());
                        this.profilePublicKeyData = forwardedKeyData;
                    } catch (ProfilePublicKey.ValidationException err) {
                        this.disconnect("Unable to validate forwarded player key");
                    }
                }
            }

            // Proceed with login
            authenticatorPool.execute(() -> {
                try {
                    new LoginHandler().fireEvents();
                } catch (Exception ex) {
                    disconnect("Failed to verify username!");
                    server.server.getLogger().log(java.util.logging.Level.WARNING, "Exception verifying " + gameProfile.getName(), ex);
                }
            });
            return;
        }
        // Paper end
        this.disconnect(Component.translatable("multiplayer.disconnect.unexpected_query_response"));
    }

    protected GameProfile createFakeProfile(GameProfile profile) {
        UUID uuid = UUIDUtil.createOfflinePlayerUUID(profile.getName());

        return new GameProfile(uuid, profile.getName());
    }

    public static enum State {

        HELLO, KEY, AUTHENTICATING, NEGOTIATING, READY_TO_ACCEPT, DELAY_ACCEPT, ACCEPTED;

        private State() {}
    }
}
