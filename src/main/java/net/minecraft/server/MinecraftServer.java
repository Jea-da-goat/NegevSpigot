package net.minecraft.server;

import com.google.common.base.Splitter;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import com.mojang.authlib.GameProfile;
import com.mojang.authlib.GameProfileRepository;
import com.mojang.authlib.minecraft.MinecraftSessionService;
import com.mojang.datafixers.DataFixer;
import com.mojang.logging.LogUtils;
import it.unimi.dsi.fastutil.longs.LongIterator;
import java.awt.image.BufferedImage;
import java.io.BufferedWriter;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;
import java.net.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Stream;
import javax.annotation.Nullable;
import javax.imageio.ImageIO;
import net.minecraft.CrashReport;
import net.minecraft.ReportedException;
import net.minecraft.SharedConstants;
import net.minecraft.SystemReport;
import net.minecraft.Util;
import net.minecraft.commands.CommandSource;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.data.worldgen.features.MiscOverworldFeatures;
import net.minecraft.gametest.framework.GameTestTicker;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.ChatDecorator;
import net.minecraft.network.chat.ChatType;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundChangeDifficultyPacket;
import net.minecraft.network.protocol.game.ClientboundSetTimePacket;
import net.minecraft.network.protocol.status.ServerStatus;
import net.minecraft.obfuscate.DontObfuscate;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.DemoMode;
import net.minecraft.server.level.PlayerRespawnLogic;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerPlayerGameMode;
import net.minecraft.server.level.TicketType;
import net.minecraft.server.level.progress.ChunkProgressListener;
import net.minecraft.server.level.progress.ChunkProgressListenerFactory;
import net.minecraft.server.network.ServerConnectionListener;
import net.minecraft.server.network.TextFilter;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.repository.Pack;
import net.minecraft.server.packs.repository.PackRepository;
import net.minecraft.server.packs.resources.CloseableResourceManager;
import net.minecraft.server.packs.resources.MultiPackResourceManager;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.players.GameProfileCache;
import net.minecraft.server.players.PlayerList;
import net.minecraft.server.players.ServerOpListEntry;
import net.minecraft.server.players.UserWhiteList;
import net.minecraft.util.Crypt;
import net.minecraft.util.CryptException;
import net.minecraft.util.FrameTimer;
import net.minecraft.util.ModCheck;
import net.minecraft.util.Mth;
import net.minecraft.util.NativeModuleLister;
import net.minecraft.util.ProgressListener;
import net.minecraft.util.RandomSource;
import net.minecraft.util.SignatureValidator;
import net.minecraft.util.Unit;
import net.minecraft.util.datafix.DataFixers;
import net.minecraft.util.profiling.EmptyProfileResults;
import net.minecraft.util.profiling.ProfileResults;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.util.profiling.ResultField;
import net.minecraft.util.profiling.SingleTickProfiler;
import net.minecraft.util.profiling.jfr.JvmProfiler;
import net.minecraft.util.profiling.jfr.callback.ProfiledDuration;
import net.minecraft.util.profiling.metrics.profiling.ActiveMetricsRecorder;
import net.minecraft.util.profiling.metrics.profiling.InactiveMetricsRecorder;
import net.minecraft.util.profiling.metrics.profiling.MetricsRecorder;
import net.minecraft.util.profiling.metrics.profiling.ServerMetricsSamplersProvider;
import net.minecraft.util.profiling.metrics.storage.MetricsPersister;
import net.minecraft.util.thread.ReentrantBlockableEventLoop;
import net.minecraft.world.Difficulty;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ai.village.VillageSiege;
import net.minecraft.world.entity.npc.CatSpawner;
import net.minecraft.world.entity.npc.WanderingTraderSpawner;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.CustomSpawner;
import net.minecraft.world.level.DataPackConfig;
import net.minecraft.world.level.ForcedChunksSavedData;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.border.WorldBorder;
import net.minecraft.world.level.dimension.LevelStem;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;
import net.minecraft.world.level.storage.CommandStorage;
import net.minecraft.world.level.storage.DimensionDataStorage;
import net.minecraft.world.level.storage.LevelData;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.level.storage.LevelStorageSource;
import net.minecraft.world.level.storage.PlayerDataStorage;
import net.minecraft.world.level.storage.PrimaryLevelData;
import net.minecraft.world.level.storage.ServerLevelData;
import net.minecraft.world.level.storage.WorldData;
import net.minecraft.world.level.storage.loot.ItemModifierManager;
import net.minecraft.world.level.storage.loot.LootTables;
import net.minecraft.world.level.storage.loot.PredicateManager;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;
import org.apache.commons.lang3.Validate;
import org.slf4j.Logger;

// CraftBukkit start
import com.mojang.serialization.DynamicOps;
import com.mojang.serialization.Lifecycle;
import java.util.Random;
import jline.console.ConsoleReader;
import joptsimple.OptionSet;
import net.minecraft.server.bossevents.CustomBossEvents;
import net.minecraft.server.dedicated.DedicatedServer;
import net.minecraft.server.dedicated.DedicatedServerProperties;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.PatrolSpawner;
import net.minecraft.world.level.levelgen.PhantomSpawner;
import net.minecraft.world.level.levelgen.WorldGenSettings;
import net.minecraft.world.level.levelgen.feature.ConfiguredFeature;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import org.bukkit.Bukkit;
import org.bukkit.craftbukkit.CraftServer;
import org.bukkit.craftbukkit.Main;
import org.bukkit.craftbukkit.generator.CustomWorldChunkManager;
import org.bukkit.craftbukkit.util.CraftChatMessage;
import org.bukkit.craftbukkit.util.LazyPlayerSet;
import org.bukkit.event.player.AsyncPlayerChatPreviewEvent;
import org.bukkit.event.server.ServerLoadEvent;
// CraftBukkit end

import org.bukkit.craftbukkit.SpigotTimings; // Spigot

public abstract class MinecraftServer extends ReentrantBlockableEventLoop<TickTask> implements CommandSource, AutoCloseable {

    public static final Logger LOGGER = LogUtils.getLogger();
    public static final String VANILLA_BRAND = "vanilla";
    private static final float AVERAGE_TICK_TIME_SMOOTHING = 0.8F;
    private static final int TICK_STATS_SPAN = 100;
    public static final int MS_PER_TICK = 50;
    private static final int OVERLOADED_THRESHOLD = 2000;
    private static final int OVERLOADED_WARNING_INTERVAL = 15000;
    private static final long STATUS_EXPIRE_TIME_NS = 5000000000L;
    private static final int MAX_STATUS_PLAYER_SAMPLE = 12;
    public static final int START_CHUNK_RADIUS = 11;
    private static final int START_TICKING_CHUNK_COUNT = 441;
    private static final int AUTOSAVE_INTERVAL = 6000;
    private static final int MAX_TICK_LATENCY = 3;
    public static final int ABSOLUTE_MAX_WORLD_SIZE = 29999984;
    public static final LevelSettings DEMO_SETTINGS = new LevelSettings("Demo World", GameType.SURVIVAL, false, Difficulty.NORMAL, false, new GameRules(), DataPackConfig.DEFAULT);
    private static final long DELAYED_TASKS_TICK_EXTENSION = 50L;
    public static final GameProfile ANONYMOUS_PLAYER_PROFILE = new GameProfile(Util.NIL_UUID, "Anonymous Player");
    public LevelStorageSource.LevelStorageAccess storageSource;
    public final PlayerDataStorage playerDataStorage;
    private final List<Runnable> tickables = Lists.newArrayList();
    private MetricsRecorder metricsRecorder;
    private ProfilerFiller profiler;
    private Consumer<ProfileResults> onMetricsRecordingStopped;
    private Consumer<Path> onMetricsRecordingFinished;
    private boolean willStartRecordingMetrics;
    @Nullable
    private MinecraftServer.TimeProfiler debugCommandProfiler;
    private boolean debugCommandProfilerDelayStart;
    private ServerConnectionListener connection;
    public final ChunkProgressListenerFactory progressListenerFactory;
    private final ServerStatus status;
    private final RandomSource random;
    public final DataFixer fixerUpper;
    private String localIp;
    private int port;
    public final RegistryAccess.Frozen registryHolder;
    private Map<ResourceKey<Level>, ServerLevel> levels;
    private PlayerList playerList;
    private volatile boolean running;
    private boolean stopped;
    private int tickCount;
    protected final Proxy proxy;
    private boolean onlineMode;
    private boolean preventProxyConnections;
    private boolean pvp;
    private boolean allowFlight;
    @Nullable
    private String motd;
    private int playerIdleTimeout;
    public final long[] tickTimes;
    @Nullable
    private KeyPair keyPair;
    @Nullable
    private GameProfile singleplayerProfile;
    private boolean isDemo;
    private volatile boolean isReady;
    private long lastOverloadWarning;
    protected final Services services;
    private long lastServerStatus;
    public final Thread serverThread;
    private long nextTickTime;
    private long delayedTasksMaxNextTickTime;
    private boolean mayHaveDelayedTasks;
    private final PackRepository packRepository;
    private final ServerScoreboard scoreboard;
    @Nullable
    private CommandStorage commandStorage;
    private final CustomBossEvents customBossEvents;
    private final ServerFunctionManager functionManager;
    private final FrameTimer frameTimer;
    private boolean enforceWhitelist;
    private float averageTickTime;
    public final Executor executor;
    @Nullable
    private String serverId;
    public MinecraftServer.ReloadableResources resources;
    private final StructureTemplateManager structureTemplateManager;
    protected WorldData worldData;
    private volatile boolean isSaving;

    // CraftBukkit start
    public final DataPackConfig datapackconfiguration;
    public final DynamicOps<Tag> registryreadops;
    public org.bukkit.craftbukkit.CraftServer server;
    public OptionSet options;
    public org.bukkit.command.ConsoleCommandSender console;
    public org.bukkit.command.RemoteConsoleCommandSender remoteConsole;
    public ConsoleReader reader;
    public static int currentTick = (int) (System.currentTimeMillis() / 50);
    public java.util.Queue<Runnable> processQueue = new java.util.concurrent.ConcurrentLinkedQueue<Runnable>();
    public int autosavePeriod;
    public Commands vanillaCommandDispatcher;
    private boolean forceTicks;
    // CraftBukkit end
    // Spigot start
    public static final int TPS = 20;
    public static final int TICK_TIME = 1000000000 / MinecraftServer.TPS;
    private static final int SAMPLE_INTERVAL = 100;
    public final double[] recentTps = new double[ 3 ];
    // Spigot end
    public final io.papermc.paper.configuration.PaperConfigurations paperConfigurations;
    public static long currentTickLong = 0L; // Paper

    public static <S extends MinecraftServer> S spin(Function<Thread, S> serverFactory) {
        AtomicReference<S> atomicreference = new AtomicReference();
        Thread thread = new Thread(() -> {
            ((MinecraftServer) atomicreference.get()).runServer();
        }, "Server thread");

        thread.setUncaughtExceptionHandler((thread1, throwable) -> {
            MinecraftServer.LOGGER.error("Uncaught exception in server thread", throwable);
        });
        if (Runtime.getRuntime().availableProcessors() > 4) {
            thread.setPriority(8);
        }

        S s0 = serverFactory.apply(thread); // CraftBukkit - decompile error

        atomicreference.set(s0);
        thread.start();
        return s0;
    }

    public MinecraftServer(OptionSet options, DataPackConfig datapackconfiguration, DynamicOps<Tag> registryreadops, Thread thread, LevelStorageSource.LevelStorageAccess convertable_conversionsession, PackRepository resourcepackrepository, WorldStem worldstem, Proxy proxy, DataFixer datafixer, Services services, ChunkProgressListenerFactory worldloadlistenerfactory) {
        super("Server");
        this.metricsRecorder = InactiveMetricsRecorder.INSTANCE;
        this.profiler = this.metricsRecorder.getProfiler();
        this.onMetricsRecordingStopped = (methodprofilerresults) -> {
            this.stopRecordingMetrics();
        };
        this.onMetricsRecordingFinished = (path) -> {
        };
        this.status = new ServerStatus();
        this.random = RandomSource.create();
        this.port = -1;
        this.levels = Maps.newLinkedHashMap();
        this.running = true;
        this.tickTimes = new long[100];
        this.nextTickTime = Util.getMillis();
        this.scoreboard = new ServerScoreboard(this);
        this.customBossEvents = new CustomBossEvents();
        this.frameTimer = new FrameTimer();
        this.registryHolder = worldstem.registryAccess();
        this.worldData = worldstem.worldData();
        if (false && !this.worldData.worldGenSettings().dimensions().containsKey(LevelStem.OVERWORLD)) { // CraftBukkit - initialised later
            throw new IllegalStateException("Missing Overworld dimension data");
        } else {
            this.proxy = proxy;
            this.packRepository = resourcepackrepository;
            this.resources = new MinecraftServer.ReloadableResources(worldstem.resourceManager(), worldstem.dataPackResources());
            this.services = services;
            if (services.profileCache() != null) {
                services.profileCache().setExecutor(this);
            }

            // this.connection = new ServerConnection(this); // Spigot
            this.progressListenerFactory = worldloadlistenerfactory;
            this.storageSource = convertable_conversionsession;
            this.playerDataStorage = convertable_conversionsession.createPlayerStorage();
            this.fixerUpper = datafixer;
            this.functionManager = new ServerFunctionManager(this, this.resources.managers.getFunctionLibrary());
            this.structureTemplateManager = new StructureTemplateManager(worldstem.resourceManager(), convertable_conversionsession, datafixer);
            this.serverThread = thread;
            this.executor = Util.backgroundExecutor();
        }
        // CraftBukkit start
        this.options = options;
        this.datapackconfiguration = datapackconfiguration;
        this.registryreadops = registryreadops;
        this.vanillaCommandDispatcher = worldstem.dataPackResources().commands; // CraftBukkit
        // Try to see if we're actually running in a terminal, disable jline if not
        if (System.console() == null && System.getProperty("jline.terminal") == null) {
            System.setProperty("jline.terminal", "jline.UnsupportedTerminal");
            Main.useJline = false;
        }

        try {
            this.reader = new ConsoleReader(System.in, System.out);
            this.reader.setExpandEvents(false); // Avoid parsing exceptions for uncommonly used event designators
        } catch (Throwable e) {
            try {
                // Try again with jline disabled for Windows users without C++ 2008 Redistributable
                System.setProperty("jline.terminal", "jline.UnsupportedTerminal");
                System.setProperty("user.language", "en");
                Main.useJline = false;
                this.reader = new ConsoleReader(System.in, System.out);
                this.reader.setExpandEvents(false);
            } catch (IOException ex) {
                MinecraftServer.LOGGER.warn((String) null, ex);
            }
        }
        Runtime.getRuntime().addShutdownHook(new org.bukkit.craftbukkit.util.ServerShutdownThread(this));
        this.paperConfigurations = services.paperConfigurations(); // Paper
    }
    // CraftBukkit end

    private void readScoreboard(DimensionDataStorage persistentStateManager) {
        ServerScoreboard scoreboardserver = this.getScoreboard();

        Objects.requireNonNull(scoreboardserver);
        Function<net.minecraft.nbt.CompoundTag, net.minecraft.world.scores.ScoreboardSaveData> function = scoreboardserver::createData; // CraftBukkit - decompile error
        ServerScoreboard scoreboardserver1 = this.getScoreboard();

        Objects.requireNonNull(scoreboardserver1);
        persistentStateManager.computeIfAbsent(function, scoreboardserver1::createData, "scoreboard");
    }

    protected abstract boolean initServer() throws IOException;

    protected void loadLevel(String s) { // CraftBukkit
        if (!JvmProfiler.INSTANCE.isRunning()) {
            ;
        }

        boolean flag = false;
        ProfiledDuration profiledduration = JvmProfiler.INSTANCE.onWorldLoadedStarted();

        this.loadWorld0(s); // CraftBukkit

        if (profiledduration != null) {
            profiledduration.finish();
        }

        if (flag) {
            try {
                JvmProfiler.INSTANCE.stop();
            } catch (Throwable throwable) {
                MinecraftServer.LOGGER.warn("Failed to stop JFR profiling", throwable);
            }
        }

    }

    // CraftBukkit start
    private void loadWorld0(String s) {
        LevelStorageSource.LevelStorageAccess worldSession = this.storageSource;
        RegistryAccess.Frozen iregistrycustom_dimension = this.registryHolder;
        PrimaryLevelData overworldData = (PrimaryLevelData) worldSession.getDataTag(registryreadops, datapackconfiguration, iregistrycustom_dimension.allElementsLifecycle());
        if (overworldData == null) {
            LevelSettings worldsettings;
            WorldGenSettings generatorsettings;

            if (this.isDemo()) {
                worldsettings = MinecraftServer.DEMO_SETTINGS;
                generatorsettings = WorldPresets.demoSettings(iregistrycustom_dimension);
            } else {
                DedicatedServerProperties dedicatedserverproperties = ((DedicatedServer) this).getProperties();

                worldsettings = new LevelSettings(dedicatedserverproperties.levelName, dedicatedserverproperties.gamemode, dedicatedserverproperties.hardcore, dedicatedserverproperties.difficulty, false, new GameRules(), this.datapackconfiguration);
                generatorsettings = this.options.has("bonusChest") ? dedicatedserverproperties.getWorldGenSettings(iregistrycustom_dimension).withBonusChest() : dedicatedserverproperties.getWorldGenSettings(iregistrycustom_dimension);
            }

            overworldData = new PrimaryLevelData(worldsettings, generatorsettings, Lifecycle.stable());
        }

        WorldGenSettings overworldSettings = overworldData.worldGenSettings();
        Registry<LevelStem> iregistry = overworldSettings.dimensions();
        for (LevelStem worldDimension : iregistry) {
            ResourceKey<LevelStem> dimensionKey = iregistry.getResourceKey(worldDimension).get();

            ServerLevel world;
            int dimension = 0;

            if (dimensionKey == LevelStem.NETHER) {
                if (this.isNetherEnabled()) {
                    dimension = -1;
                } else {
                    continue;
                }
            } else if (dimensionKey == LevelStem.END) {
                if (this.server.getAllowEnd()) {
                    dimension = 1;
                } else {
                    continue;
                }
            } else if (dimensionKey != LevelStem.OVERWORLD) {
                dimension = -999;
            }

            String worldType = (dimension == -999) ? dimensionKey.location().getNamespace() + "_" + dimensionKey.location().getPath() : org.bukkit.World.Environment.getEnvironment(dimension).toString().toLowerCase();
            String name = (dimensionKey == LevelStem.OVERWORLD) ? s : s + "_" + worldType;
            if (dimension != 0) {
                File newWorld = LevelStorageSource.getStorageFolder(new File(name).toPath(), dimensionKey).toFile();
                File oldWorld = LevelStorageSource.getStorageFolder(new File(s).toPath(), dimensionKey).toFile();
                File oldLevelDat = new File(new File(s), "level.dat"); // The data folders exist on first run as they are created in the PersistentCollection constructor above, but the level.dat won't

                if (!newWorld.isDirectory() && oldWorld.isDirectory() && oldLevelDat.isFile()) {
                    MinecraftServer.LOGGER.info("---- Migration of old " + worldType + " folder required ----");
                    MinecraftServer.LOGGER.info("Unfortunately due to the way that Minecraft implemented multiworld support in 1.6, Bukkit requires that you move your " + worldType + " folder to a new location in order to operate correctly.");
                    MinecraftServer.LOGGER.info("We will move this folder for you, but it will mean that you need to move it back should you wish to stop using Bukkit in the future.");
                    MinecraftServer.LOGGER.info("Attempting to move " + oldWorld + " to " + newWorld + "...");

                    if (newWorld.exists()) {
                        MinecraftServer.LOGGER.warn("A file or folder already exists at " + newWorld + "!");
                        MinecraftServer.LOGGER.info("---- Migration of old " + worldType + " folder failed ----");
                    } else if (newWorld.getParentFile().mkdirs()) {
                        if (oldWorld.renameTo(newWorld)) {
                            MinecraftServer.LOGGER.info("Success! To restore " + worldType + " in the future, simply move " + newWorld + " to " + oldWorld);
                            // Migrate world data too.
                            try {
                                com.google.common.io.Files.copy(oldLevelDat, new File(new File(name), "level.dat"));
                                org.apache.commons.io.FileUtils.copyDirectory(new File(new File(s), "data"), new File(new File(name), "data"));
                            } catch (IOException exception) {
                                MinecraftServer.LOGGER.warn("Unable to migrate world data.");
                            }
                            MinecraftServer.LOGGER.info("---- Migration of old " + worldType + " folder complete ----");
                        } else {
                            MinecraftServer.LOGGER.warn("Could not move folder " + oldWorld + " to " + newWorld + "!");
                            MinecraftServer.LOGGER.info("---- Migration of old " + worldType + " folder failed ----");
                        }
                    } else {
                        MinecraftServer.LOGGER.warn("Could not create path for " + newWorld + "!");
                        MinecraftServer.LOGGER.info("---- Migration of old " + worldType + " folder failed ----");
                    }
                }

                try {
                    worldSession = LevelStorageSource.createDefault(this.server.getWorldContainer().toPath()).createAccess(name, dimensionKey);
                } catch (IOException ex) {
                    throw new RuntimeException(ex);
                }
            }

            org.bukkit.generator.ChunkGenerator gen = this.server.getGenerator(name);
            org.bukkit.generator.BiomeProvider biomeProvider = this.server.getBiomeProvider(name);

            PrimaryLevelData worlddata = (PrimaryLevelData) worldSession.getDataTag((DynamicOps) this.registryreadops, datapackconfiguration, iregistrycustom_dimension.allElementsLifecycle());
            if (worlddata == null) {
                LevelSettings worldsettings;
                WorldGenSettings generatorsettings;

                if (this.isDemo()) {
                    worldsettings = MinecraftServer.DEMO_SETTINGS;
                    generatorsettings = WorldPresets.demoSettings(iregistrycustom_dimension);
                } else {
                    DedicatedServerProperties dedicatedserverproperties = ((DedicatedServer) this).getProperties();

                    worldsettings = new LevelSettings(dedicatedserverproperties.levelName, dedicatedserverproperties.gamemode, dedicatedserverproperties.hardcore, dedicatedserverproperties.difficulty, false, new GameRules(), this.datapackconfiguration);
                    generatorsettings = this.options.has("bonusChest") ? dedicatedserverproperties.getWorldGenSettings(iregistrycustom_dimension).withBonusChest() : dedicatedserverproperties.getWorldGenSettings(iregistrycustom_dimension);
                }

                worlddata = new PrimaryLevelData(worldsettings, generatorsettings, Lifecycle.stable());
            }
            worlddata.checkName(name); // CraftBukkit - Migration did not rewrite the level.dat; This forces 1.8 to take the last loaded world as respawn (in this case the end)
            if (this.options.has("forceUpgrade")) {
                net.minecraft.server.Main.forceUpgrade(worldSession, DataFixers.getDataFixer(), this.options.has("eraseCache"), () -> {
                    return true;
                }, worlddata.worldGenSettings());
            }

            PrimaryLevelData iworlddataserver = worlddata;
            WorldGenSettings generatorsettings = worlddata.worldGenSettings();
            boolean flag = generatorsettings.isDebug();
            long i = generatorsettings.seed();
            long j = BiomeManager.obfuscateSeed(i);
            List<CustomSpawner> list = ImmutableList.of(new PhantomSpawner(), new PatrolSpawner(), new CatSpawner(), new VillageSiege(), new WanderingTraderSpawner(iworlddataserver));
            LevelStem worlddimension = (LevelStem) iregistry.get(dimensionKey);

            org.bukkit.generator.WorldInfo worldInfo = new org.bukkit.craftbukkit.generator.CraftWorldInfo(iworlddataserver, worldSession, org.bukkit.World.Environment.getEnvironment(dimension), worlddimension.typeHolder().value());
            if (biomeProvider == null && gen != null) {
                biomeProvider = gen.getDefaultBiomeProvider(worldInfo);
            }

            ResourceKey<Level> worldKey = ResourceKey.create(Registry.DIMENSION_REGISTRY, dimensionKey.location());

            if (dimensionKey == LevelStem.OVERWORLD) {
                this.worldData = worlddata;
                this.worldData.setGameType(((DedicatedServer) this).getProperties().gamemode); // From DedicatedServer.init

                ChunkProgressListener worldloadlistener = this.progressListenerFactory.create(11);

                world = new ServerLevel(this, this.executor, worldSession, iworlddataserver, worldKey, worlddimension, worldloadlistener, flag, j, list, true, org.bukkit.World.Environment.getEnvironment(dimension), gen, biomeProvider);
                DimensionDataStorage worldpersistentdata = world.getDataStorage();
                this.readScoreboard(worldpersistentdata);
                this.server.scoreboardManager = new org.bukkit.craftbukkit.scoreboard.CraftScoreboardManager(this, world.getScoreboard());
                this.commandStorage = new CommandStorage(worldpersistentdata);
            } else {
                ChunkProgressListener worldloadlistener = this.progressListenerFactory.create(11);
                world = new ServerLevel(this, this.executor, worldSession, iworlddataserver, worldKey, worlddimension, worldloadlistener, flag, j, ImmutableList.of(), true, org.bukkit.World.Environment.getEnvironment(dimension), gen, biomeProvider);
            }

            worlddata.setModdedInfo(this.getServerModName(), this.getModdedStatus().shouldReportAsModified());
            this.initWorld(world, worlddata, worldData, worlddata.worldGenSettings());

            this.addLevel(world);
            this.getPlayerList().addWorldborderListener(world);

            if (worlddata.getCustomBossEvents() != null) {
                this.getCustomBossEvents().load(worlddata.getCustomBossEvents());
            }
        }
        this.forceDifficulty();
        for (ServerLevel worldserver : this.getAllLevels()) {
            this.prepareLevels(worldserver.getChunkSource().chunkMap.progressListener, worldserver);
            worldserver.entityManager.tick(); // SPIGOT-6526: Load pending entities so they are available to the API
            this.server.getPluginManager().callEvent(new org.bukkit.event.world.WorldLoadEvent(worldserver.getWorld()));
        }

        this.server.enablePlugins(org.bukkit.plugin.PluginLoadOrder.POSTWORLD);
        this.server.getPluginManager().callEvent(new ServerLoadEvent(ServerLoadEvent.LoadType.STARTUP));
        this.connection.acceptConnections();
    }
    // CraftBukkit end

    protected void forceDifficulty() {}

    // CraftBukkit start
    public void initWorld(ServerLevel worldserver, ServerLevelData iworlddataserver, WorldData saveData, WorldGenSettings generatorsettings) {
        boolean flag = generatorsettings.isDebug();
        // CraftBukkit start
        if (worldserver.generator != null) {
            worldserver.getWorld().getPopulators().addAll(worldserver.generator.getDefaultPopulators(worldserver.getWorld()));
        }
        WorldBorder worldborder = worldserver.getWorldBorder();
        worldborder.applySettings(iworlddataserver.getWorldBorder()); // CraftBukkit - move up so that WorldBorder is set during WorldInitEvent
        this.server.getPluginManager().callEvent(new org.bukkit.event.world.WorldInitEvent(worldserver.getWorld())); // CraftBukkit - SPIGOT-5569: Call WorldInitEvent before any chunks are generated

        if (!iworlddataserver.isInitialized()) {
            try {
                MinecraftServer.setInitialSpawn(worldserver, iworlddataserver, generatorsettings.generateBonusChest(), flag);
                iworlddataserver.setInitialized(true);
                if (flag) {
                    this.setupDebugLevel(this.worldData);
                }
            } catch (Throwable throwable) {
                CrashReport crashreport = CrashReport.forThrowable(throwable, "Exception initializing level");

                try {
                    worldserver.fillReportDetails(crashreport);
                } catch (Throwable throwable1) {
                    ;
                }

                throw new ReportedException(crashreport);
            }

            iworlddataserver.setInitialized(true);
        }

    }
    // CraftBukkit end

    private static void setInitialSpawn(ServerLevel world, ServerLevelData worldProperties, boolean bonusChest, boolean debugWorld) {
        if (debugWorld) {
            worldProperties.setSpawn(BlockPos.ZERO.above(80), 0.0F);
        } else {
            ServerChunkCache chunkproviderserver = world.getChunkSource();
            ChunkPos chunkcoordintpair = new ChunkPos(chunkproviderserver.randomState().sampler().findSpawnPosition());
            // CraftBukkit start
            if (world.generator != null) {
                Random rand = new Random(world.getSeed());
                org.bukkit.Location spawn = world.generator.getFixedSpawnLocation(world.getWorld(), rand);

                if (spawn != null) {
                    if (spawn.getWorld() != world.getWorld()) {
                        throw new IllegalStateException("Cannot set spawn point for " + worldProperties.getLevelName() + " to be in another world (" + spawn.getWorld().getName() + ")");
                    } else {
                        worldProperties.setSpawn(new BlockPos(spawn.getBlockX(), spawn.getBlockY(), spawn.getBlockZ()), spawn.getYaw());
                        return;
                    }
                }
            }
            // CraftBukkit end
            int i = chunkproviderserver.getGenerator().getSpawnHeight(world);

            if (i < world.getMinBuildHeight()) {
                BlockPos blockposition = chunkcoordintpair.getWorldPosition();

                i = world.getHeight(Heightmap.Types.WORLD_SURFACE, blockposition.getX() + 8, blockposition.getZ() + 8);
            }

            worldProperties.setSpawn(chunkcoordintpair.getWorldPosition().offset(8, i, 8), 0.0F);
            int j = 0;
            int k = 0;
            int l = 0;
            int i1 = -1;
            boolean flag2 = true;

            for (int j1 = 0; j1 < Mth.square(11); ++j1) {
                if (j >= -5 && j <= 5 && k >= -5 && k <= 5) {
                    BlockPos blockposition1 = PlayerRespawnLogic.getSpawnPosInChunk(world, new ChunkPos(chunkcoordintpair.x + j, chunkcoordintpair.z + k));

                    if (blockposition1 != null) {
                        worldProperties.setSpawn(blockposition1, 0.0F);
                        break;
                    }
                }

                if (j == k || j < 0 && j == -k || j > 0 && j == 1 - k) {
                    int k1 = l;

                    l = -i1;
                    i1 = k1;
                }

                j += l;
                k += i1;
            }

            if (bonusChest) {
                ConfiguredFeature<?, ?> worldgenfeatureconfigured = (ConfiguredFeature) MiscOverworldFeatures.BONUS_CHEST.value();

                worldgenfeatureconfigured.place(world, chunkproviderserver.getGenerator(), world.random, new BlockPos(worldProperties.getXSpawn(), worldProperties.getYSpawn(), worldProperties.getZSpawn()));
            }

        }
    }

    private void setupDebugLevel(WorldData properties) {
        properties.setDifficulty(Difficulty.PEACEFUL);
        properties.setDifficultyLocked(true);
        ServerLevelData iworlddataserver = properties.overworldData();

        iworlddataserver.setRaining(false);
        iworlddataserver.setThundering(false);
        iworlddataserver.setClearWeatherTime(1000000000);
        iworlddataserver.setDayTime(6000L);
        iworlddataserver.setGameType(GameType.SPECTATOR);
    }

    // CraftBukkit start
    public void prepareLevels(ChunkProgressListener worldloadlistener, ServerLevel worldserver) {
        // WorldServer worldserver = this.overworld();
        this.forceTicks = true;
        // CraftBukkit end

        MinecraftServer.LOGGER.info("Preparing start region for dimension {}", worldserver.dimension().location());
        BlockPos blockposition = worldserver.getSharedSpawnPos();

        worldloadlistener.updateSpawnPos(new ChunkPos(blockposition));
        ServerChunkCache chunkproviderserver = worldserver.getChunkSource();

        chunkproviderserver.getLightEngine().setTaskPerBatch(500);
        this.nextTickTime = Util.getMillis();
        // CraftBukkit start
        if (worldserver.getWorld().getKeepSpawnInMemory()) {
            chunkproviderserver.addRegionTicket(TicketType.START, new ChunkPos(blockposition), 11, Unit.INSTANCE);

            while (chunkproviderserver.getTickingGenerated() != 441) {
                // this.nextTickTime = SystemUtils.getMillis() + 10L;
                this.executeModerately();
            }
        }

        // this.nextTickTime = SystemUtils.getMillis() + 10L;
        this.executeModerately();
        // Iterator iterator = this.levels.values().iterator();

        if (true) {
            ServerLevel worldserver1 = worldserver;
            // CraftBukkit end
            ForcedChunksSavedData forcedchunk = (ForcedChunksSavedData) worldserver1.getDataStorage().get(ForcedChunksSavedData::load, "chunks");

            if (forcedchunk != null) {
                LongIterator longiterator = forcedchunk.getChunks().iterator();

                while (longiterator.hasNext()) {
                    long i = longiterator.nextLong();
                    ChunkPos chunkcoordintpair = new ChunkPos(i);

                    worldserver1.getChunkSource().updateChunkForced(chunkcoordintpair, true);
                }
            }
        }

        // CraftBukkit start
        // this.nextTickTime = SystemUtils.getMillis() + 10L;
        this.executeModerately();
        // CraftBukkit end
        worldloadlistener.stop();
        chunkproviderserver.getLightEngine().setTaskPerBatch(5);
        // CraftBukkit start
        // this.updateMobSpawningFlags();
        worldserver.setSpawnSettings(this.isSpawningMonsters(), this.isSpawningAnimals());

        this.forceTicks = false;
        // CraftBukkit end
    }

    public GameType getDefaultGameType() {
        return this.worldData.getGameType();
    }

    public boolean isHardcore() {
        return this.worldData.isHardcore();
    }

    public abstract int getOperatorUserPermissionLevel();

    public abstract int getFunctionCompilationLevel();

    public abstract boolean shouldRconBroadcast();

    public boolean saveAllChunks(boolean suppressLogs, boolean flush, boolean force) {
        boolean flag3 = false;

        for (Iterator iterator = this.getAllLevels().iterator(); iterator.hasNext(); flag3 = true) {
            ServerLevel worldserver = (ServerLevel) iterator.next();

            if (!suppressLogs) {
                MinecraftServer.LOGGER.info("Saving chunks for level '{}'/{}", worldserver, worldserver.dimension().location());
            }

            worldserver.save((ProgressListener) null, flush, worldserver.noSave && !force);
        }

        // CraftBukkit start - moved to WorldServer.save
        /*
        WorldServer worldserver1 = this.overworld();
        IWorldDataServer iworlddataserver = this.worldData.overworldData();

        iworlddataserver.setWorldBorder(worldserver1.getWorldBorder().createSettings());
        this.worldData.setCustomBossEvents(this.getCustomBossEvents().save());
        this.storageSource.saveDataTag(this.registryAccess(), this.worldData, this.getPlayerList().getSingleplayerData());
        */
        // CraftBukkit end
        if (flush) {
            Iterator iterator1 = this.getAllLevels().iterator();

            while (iterator1.hasNext()) {
                ServerLevel worldserver2 = (ServerLevel) iterator1.next();

                MinecraftServer.LOGGER.info("ThreadedAnvilChunkStorage ({}): All chunks are saved", worldserver2.getChunkSource().chunkMap.getStorageName());
            }

            MinecraftServer.LOGGER.info("ThreadedAnvilChunkStorage: All dimensions are saved");
        }

        return flag3;
    }

    public boolean saveEverything(boolean suppressLogs, boolean flush, boolean force) {
        boolean flag3;

        try {
            this.isSaving = true;
            this.getPlayerList().saveAll();
            flag3 = this.saveAllChunks(suppressLogs, flush, force);
        } finally {
            this.isSaving = false;
        }

        return flag3;
    }

    @Override
    public void close() {
        this.stopServer();
    }

    // CraftBukkit start
    private boolean hasStopped = false;
    private final Object stopLock = new Object();
    public final boolean hasStopped() {
        synchronized (this.stopLock) {
            return this.hasStopped;
        }
    }
    // CraftBukkit end

    public void stopServer() {
        // CraftBukkit start - prevent double stopping on multiple threads
        synchronized(this.stopLock) {
            if (this.hasStopped) return;
            this.hasStopped = true;
        }
        // CraftBukkit end
        if (this.metricsRecorder.isRecording()) {
            this.cancelRecordingMetrics();
        }

        MinecraftServer.LOGGER.info("Stopping server");
        // CraftBukkit start
        if (this.server != null) {
            this.server.disablePlugins();
        }
        // CraftBukkit end
        if (this.getConnection() != null) {
            this.getConnection().stop();
        }

        this.isSaving = true;
        if (this.playerList != null) {
            MinecraftServer.LOGGER.info("Saving players");
            this.playerList.saveAll();
            this.playerList.removeAll();
            try { Thread.sleep(100); } catch (InterruptedException ex) {} // CraftBukkit - SPIGOT-625 - give server at least a chance to send packets
        }

        MinecraftServer.LOGGER.info("Saving worlds");
        Iterator iterator = this.getAllLevels().iterator();

        ServerLevel worldserver;

        while (iterator.hasNext()) {
            worldserver = (ServerLevel) iterator.next();
            if (worldserver != null) {
                worldserver.noSave = false;
            }
        }

        while (this.levels.values().stream().anyMatch((worldserver1) -> {
            return worldserver1.getChunkSource().chunkMap.hasWork();
        })) {
            this.nextTickTime = Util.getMillis() + 1L;
            iterator = this.getAllLevels().iterator();

            while (iterator.hasNext()) {
                worldserver = (ServerLevel) iterator.next();
                worldserver.getChunkSource().removeTicketsOnClosing();
                worldserver.getChunkSource().tick(() -> {
                    return true;
                }, false);
            }

            this.waitUntilNextTick();
        }

        this.saveAllChunks(false, true, false);
        iterator = this.getAllLevels().iterator();

        while (iterator.hasNext()) {
            worldserver = (ServerLevel) iterator.next();
            if (worldserver != null) {
                try {
                    worldserver.close();
                } catch (IOException ioexception) {
                    MinecraftServer.LOGGER.error("Exception closing the level", ioexception);
                }
            }
        }

        this.isSaving = false;
        this.resources.close();

        try {
            this.storageSource.close();
        } catch (IOException ioexception1) {
            MinecraftServer.LOGGER.error("Failed to unlock level {}", this.storageSource.getLevelId(), ioexception1);
        }
        // Spigot start
        MCUtil.asyncExecutor.shutdown(); // Paper
        try { MCUtil.asyncExecutor.awaitTermination(30, java.util.concurrent.TimeUnit.SECONDS); // Paper
        } catch (java.lang.InterruptedException ignored) {} // Paper
        if (org.spigotmc.SpigotConfig.saveUserCacheOnStopOnly) {
            MinecraftServer.LOGGER.info("Saving usercache.json");
            this.getProfileCache().save();
        }
        // Spigot end

    }

    public String getLocalIp() {
        return this.localIp;
    }

    public void setLocalIp(String serverIp) {
        this.localIp = serverIp;
    }

    public boolean isRunning() {
        return this.running;
    }

    public void halt(boolean flag) {
        this.running = false;
        if (flag) {
            try {
                this.serverThread.join();
            } catch (InterruptedException interruptedexception) {
                MinecraftServer.LOGGER.error("Error while shutting down", interruptedexception);
            }
        }

    }

    // Spigot Start
    private static double calcTps(double avg, double exp, double tps)
    {
        return ( avg * exp ) + ( tps * ( 1 - exp ) );
    }
    // Spigot End

    protected void runServer() {
        try {
            if (!this.initServer()) {
                throw new IllegalStateException("Failed to initialize server");
            }

            this.nextTickTime = Util.getMillis();
            this.status.setDescription(Component.literal(this.motd));
            this.status.setVersion(new ServerStatus.Version(SharedConstants.getCurrentVersion().getName(), SharedConstants.getCurrentVersion().getProtocolVersion()));
            this.status.setPreviewsChat(this.previewsChat());
            this.status.setEnforcesSecureChat(this.enforceSecureProfile());
            this.updateStatusIcon(this.status);

            // Spigot start
            Arrays.fill( recentTps, 20 );
            long curTime, tickSection = Util.getMillis(), tickCount = 1;
            while (this.running) {
                long i = (curTime = Util.getMillis()) - this.nextTickTime;

                if (i > 5000L && this.nextTickTime - this.lastOverloadWarning >= 30000L) { // CraftBukkit
                    long j = i / 50L;

                    if (this.server.getWarnOnOverload()) // CraftBukkit
                    MinecraftServer.LOGGER.warn("Can't keep up! Is the server overloaded? Running {}ms or {} ticks behind", i, j);
                    this.nextTickTime += j * 50L;
                    this.lastOverloadWarning = this.nextTickTime;
                }

                ++MinecraftServer.currentTickLong; // Paper
                if ( tickCount++ % MinecraftServer.SAMPLE_INTERVAL == 0 )
                {
                    double currentTps = 1E3 / ( curTime - tickSection ) * MinecraftServer.SAMPLE_INTERVAL;
                    this.recentTps[0] = MinecraftServer.calcTps( this.recentTps[0], 0.92, currentTps ); // 1/exp(5sec/1min)
                    this.recentTps[1] = MinecraftServer.calcTps( this.recentTps[1], 0.9835, currentTps ); // 1/exp(5sec/5min)
                    this.recentTps[2] = MinecraftServer.calcTps( this.recentTps[2], 0.9945, currentTps ); // 1/exp(5sec/15min)
                    tickSection = curTime;
                }
                // Spigot end

                if (this.debugCommandProfilerDelayStart) {
                    this.debugCommandProfilerDelayStart = false;
                    this.debugCommandProfiler = new MinecraftServer.TimeProfiler(Util.getNanos(), this.tickCount);
                }

                MinecraftServer.currentTick = (int) (System.currentTimeMillis() / 50); // CraftBukkit
                this.nextTickTime += 50L;
                this.startMetricsRecordingTick();
                this.profiler.push("tick");
                this.tickServer(this::haveTime);
                this.profiler.popPush("nextTickWait");
                this.mayHaveDelayedTasks = true;
                this.delayedTasksMaxNextTickTime = Math.max(Util.getMillis() + 50L, this.nextTickTime);
                this.waitUntilNextTick();
                this.profiler.pop();
                this.endMetricsRecordingTick();
                this.isReady = true;
                JvmProfiler.INSTANCE.onServerTick(this.averageTickTime);
            }
        } catch (Throwable throwable) {
            MinecraftServer.LOGGER.error("Encountered an unexpected exception", throwable);
            // Spigot Start
            if ( throwable.getCause() != null )
            {
                MinecraftServer.LOGGER.error( "\tCause of unexpected exception was", throwable.getCause() );
            }
            // Spigot End
            CrashReport crashreport = MinecraftServer.constructOrExtractCrashReport(throwable);

            this.fillSystemReport(crashreport.getSystemReport());
            File file = new File(new File(this.getServerDirectory(), "crash-reports"), "crash-" + Util.getFilenameFormattedDateTime() + "-server.txt");

            if (crashreport.saveToFile(file)) {
                MinecraftServer.LOGGER.error("This crash report has been saved to: {}", file.getAbsolutePath());
            } else {
                MinecraftServer.LOGGER.error("We were unable to save this crash report to disk.");
            }

            this.onServerCrash(crashreport);
        } finally {
            try {
                this.stopped = true;
                this.stopServer();
            } catch (Throwable throwable1) {
                MinecraftServer.LOGGER.error("Exception stopping the server", throwable1);
            } finally {
                if (this.services.profileCache() != null) {
                    this.services.profileCache().clearExecutor();
                }

                org.spigotmc.WatchdogThread.doStop(); // Spigot
                // CraftBukkit start - Restore terminal to original settings
                try {
                    this.reader.getTerminal().restore();
                } catch (Exception ignored) {
                }
                // CraftBukkit end
                this.onServerExit();
            }

        }

    }

    private static CrashReport constructOrExtractCrashReport(Throwable throwable) {
        ReportedException reportedexception = null;

        for (Throwable throwable1 = throwable; throwable1 != null; throwable1 = throwable1.getCause()) {
            if (throwable1 instanceof ReportedException) {
                ReportedException reportedexception1 = (ReportedException) throwable1;

                reportedexception = reportedexception1;
            }
        }

        CrashReport crashreport;

        if (reportedexception != null) {
            crashreport = reportedexception.getReport();
            if (reportedexception != throwable) {
                crashreport.addCategory("Wrapped in").setDetailError("Wrapping exception", throwable);
            }
        } else {
            crashreport = new CrashReport("Exception in server tick loop", throwable);
        }

        return crashreport;
    }

    private boolean haveTime() {
        // CraftBukkit start
        return this.forceTicks || this.runningTask() || Util.getMillis() < (this.mayHaveDelayedTasks ? this.delayedTasksMaxNextTickTime : this.nextTickTime);
    }

    private void executeModerately() {
        this.runAllTasks();
        java.util.concurrent.locks.LockSupport.parkNanos("executing tasks", 1000L);
    }
    // CraftBukkit end

    protected void waitUntilNextTick() {
        this.runAllTasks();
        this.managedBlock(() -> {
            return !this.haveTime();
        });
    }

    @Override
    public TickTask wrapRunnable(Runnable runnable) {
        return new TickTask(this.tickCount, runnable);
    }

    protected boolean shouldRun(TickTask ticktask) {
        return ticktask.getTick() + 3 < this.tickCount || this.haveTime();
    }

    @Override
    public boolean pollTask() {
        boolean flag = this.pollTaskInternal();

        this.mayHaveDelayedTasks = flag;
        return flag;
    }

    private boolean pollTaskInternal() {
        if (super.pollTask()) {
            return true;
        } else {
            if (this.haveTime()) {
                Iterator iterator = this.getAllLevels().iterator();

                while (iterator.hasNext()) {
                    ServerLevel worldserver = (ServerLevel) iterator.next();

                    if (worldserver.getChunkSource().pollTask()) {
                        return true;
                    }
                }
            }

            return false;
        }
    }

    public void doRunTask(TickTask ticktask) { // CraftBukkit - decompile error
        this.getProfiler().incrementCounter("runTask");
        super.doRunTask(ticktask);
    }

    private void updateStatusIcon(ServerStatus metadata) {
        Optional<File> optional = Optional.of(this.getFile("server-icon.png")).filter(File::isFile);

        if (!optional.isPresent()) {
            optional = this.storageSource.getIconFile().map(Path::toFile).filter(File::isFile);
        }

        optional.ifPresent((file) -> {
            try {
                BufferedImage bufferedimage = ImageIO.read(file);

                Validate.validState(bufferedimage.getWidth() == 64, "Must be 64 pixels wide", new Object[0]);
                Validate.validState(bufferedimage.getHeight() == 64, "Must be 64 pixels high", new Object[0]);
                ByteArrayOutputStream bytearrayoutputstream = new ByteArrayOutputStream();

                ImageIO.write(bufferedimage, "PNG", bytearrayoutputstream);
                byte[] abyte = Base64.getEncoder().encode(bytearrayoutputstream.toByteArray());
                String s = new String(abyte, StandardCharsets.UTF_8);

                metadata.setFavicon("data:image/png;base64," + s);
            } catch (Exception exception) {
                MinecraftServer.LOGGER.error("Couldn't load server icon", exception);
            }

        });
    }

    public Optional<Path> getWorldScreenshotFile() {
        return this.storageSource.getIconFile();
    }

    public File getServerDirectory() {
        return new File(".");
    }

    public void onServerCrash(CrashReport report) {}

    public void onServerExit() {}

    public void tickServer(BooleanSupplier shouldKeepTicking) {
        SpigotTimings.serverTickTimer.startTiming(); // Spigot
        long i = Util.getNanos();

        ++this.tickCount;
        this.tickChildren(shouldKeepTicking);
        if (i - this.lastServerStatus >= 5000000000L) {
            this.lastServerStatus = i;
            this.status.setPlayers(new ServerStatus.Players(this.getMaxPlayers(), this.getPlayerCount()));
            if (!this.hidesOnlinePlayers()) {
                GameProfile[] agameprofile = new GameProfile[Math.min(this.getPlayerCount(), 12)];
                int j = Mth.nextInt(this.random, 0, this.getPlayerCount() - agameprofile.length);

                for (int k = 0; k < agameprofile.length; ++k) {
                    ServerPlayer entityplayer = (ServerPlayer) this.playerList.getPlayers().get(j + k);

                    if (entityplayer.allowsListing()) {
                        agameprofile[k] = entityplayer.getGameProfile();
                    } else {
                        agameprofile[k] = MinecraftServer.ANONYMOUS_PLAYER_PROFILE;
                    }
                }

                Collections.shuffle(Arrays.asList(agameprofile));
                this.status.getPlayers().setSample(agameprofile);
            }
        }

        if (this.autosavePeriod > 0 && this.tickCount % this.autosavePeriod == 0) { // CraftBukkit
            SpigotTimings.worldSaveTimer.startTiming(); // Spigot
            MinecraftServer.LOGGER.debug("Autosave started");
            this.profiler.push("save");
            this.saveEverything(true, false, false);
            this.profiler.pop();
            MinecraftServer.LOGGER.debug("Autosave finished");
            SpigotTimings.worldSaveTimer.stopTiming(); // Spigot
        }
        io.papermc.paper.util.CachedLists.reset(); // Paper
        this.profiler.push("tallying");
        long l = this.tickTimes[this.tickCount % 100] = Util.getNanos() - i;

        this.averageTickTime = this.averageTickTime * 0.8F + (float) l / 1000000.0F * 0.19999999F;
        long i1 = Util.getNanos();

        this.frameTimer.logFrameDuration(i1 - i);
        this.profiler.pop();
        org.spigotmc.WatchdogThread.tick(); // Spigot
        SpigotTimings.serverTickTimer.stopTiming(); // Spigot
        org.spigotmc.CustomTimingsHandler.tick(); // Spigot
    }

    public void tickChildren(BooleanSupplier shouldKeepTicking) {
        SpigotTimings.schedulerTimer.startTiming(); // Spigot
        this.server.getScheduler().mainThreadHeartbeat(this.tickCount); // CraftBukkit
        SpigotTimings.schedulerTimer.stopTiming(); // Spigot
        this.profiler.push("commandFunctions");
        SpigotTimings.commandFunctionsTimer.startTiming(); // Spigot
        this.getFunctions().tick();
        SpigotTimings.commandFunctionsTimer.stopTiming(); // Spigot
        this.profiler.popPush("levels");
        Iterator iterator = this.getAllLevels().iterator();

        // CraftBukkit start
        // Run tasks that are waiting on processing
        SpigotTimings.processQueueTimer.startTiming(); // Spigot
        while (!this.processQueue.isEmpty()) {
            this.processQueue.remove().run();
        }
        SpigotTimings.processQueueTimer.stopTiming(); // Spigot

        SpigotTimings.timeUpdateTimer.startTiming(); // Spigot
        // Send time updates to everyone, it will get the right time from the world the player is in.
        if (this.tickCount % 20 == 0) {
            for (int i = 0; i < this.getPlayerList().players.size(); ++i) {
                ServerPlayer entityplayer = (ServerPlayer) this.getPlayerList().players.get(i);
                entityplayer.connection.send(new ClientboundSetTimePacket(entityplayer.level.getGameTime(), entityplayer.getPlayerTime(), entityplayer.level.getGameRules().getBoolean(GameRules.RULE_DAYLIGHT))); // Add support for per player time
            }
        }
        SpigotTimings.timeUpdateTimer.stopTiming(); // Spigot

        while (iterator.hasNext()) {
            ServerLevel worldserver = (ServerLevel) iterator.next();

            this.profiler.push(() -> {
                return worldserver + " " + worldserver.dimension().location();
            });
            /* Drop global time updates
            if (this.tickCount % 20 == 0) {
                this.profiler.push("timeSync");
                this.playerList.broadcastAll(new PacketPlayOutUpdateTime(worldserver.getGameTime(), worldserver.getDayTime(), worldserver.getGameRules().getBoolean(GameRules.RULE_DAYLIGHT)), worldserver.dimension());
                this.profiler.pop();
            }
            // CraftBukkit end */

            this.profiler.push("tick");

            try {
                worldserver.timings.doTick.startTiming(); // Spigot
                worldserver.tick(shouldKeepTicking);
                // Paper start
                for (final io.papermc.paper.chunk.SingleThreadChunkRegionManager regionManager : worldserver.getChunkSource().chunkMap.regionManagers) {
                    regionManager.recalculateRegions();
                }
                // Paper end
                worldserver.timings.doTick.stopTiming(); // Spigot
            } catch (Throwable throwable) {
                // Spigot Start
                CrashReport crashreport;
                try {
                    crashreport = CrashReport.forThrowable(throwable, "Exception ticking world");
                } catch (Throwable t) {
                    throw new RuntimeException("Error generating crash report", t);
                }
                // Spigot End

                worldserver.fillReportDetails(crashreport);
                throw new ReportedException(crashreport);
            }

            this.profiler.pop();
            this.profiler.pop();
        }

        this.profiler.popPush("connection");
        SpigotTimings.connectionTimer.startTiming(); // Spigot
        this.getConnection().tick();
        SpigotTimings.connectionTimer.stopTiming(); // Spigot
        this.profiler.popPush("players");
        SpigotTimings.playerListTimer.startTiming(); // Spigot
        this.playerList.tick();
        SpigotTimings.playerListTimer.stopTiming(); // Spigot
        if (SharedConstants.IS_RUNNING_IN_IDE) {
            GameTestTicker.SINGLETON.tick();
        }

        this.profiler.popPush("server gui refresh");

        SpigotTimings.tickablesTimer.startTiming(); // Spigot
        for (int i = 0; i < this.tickables.size(); ++i) {
            ((Runnable) this.tickables.get(i)).run();
        }
        SpigotTimings.tickablesTimer.stopTiming(); // Spigot

        this.profiler.pop();
    }

    public boolean isNetherEnabled() {
        return true;
    }

    public void addTickable(Runnable tickable) {
        this.tickables.add(tickable);
    }

    protected void setId(String serverId) {
        this.serverId = serverId;
    }

    public boolean isShutdown() {
        return !this.serverThread.isAlive();
    }

    public File getFile(String path) {
        return new File(this.getServerDirectory(), path);
    }

    public final ServerLevel overworld() {
        return (ServerLevel) this.levels.get(Level.OVERWORLD);
    }

    @Nullable
    public ServerLevel getLevel(ResourceKey<Level> key) {
        return (ServerLevel) this.levels.get(key);
    }

    // CraftBukkit start
    public void addLevel(ServerLevel level) {
        Map<ResourceKey<Level>, ServerLevel> oldLevels = this.levels;
        Map<ResourceKey<Level>, ServerLevel> newLevels = Maps.newLinkedHashMap(oldLevels);
        newLevels.put(level.dimension(), level);
        this.levels = Collections.unmodifiableMap(newLevels);
    }

    public void removeLevel(ServerLevel level) {
        Map<ResourceKey<Level>, ServerLevel> oldLevels = this.levels;
        Map<ResourceKey<Level>, ServerLevel> newLevels = Maps.newLinkedHashMap(oldLevels);
        newLevels.remove(level.dimension());
        this.levels = Collections.unmodifiableMap(newLevels);
    }
    // CraftBukkit end

    public Set<ResourceKey<Level>> levelKeys() {
        return this.levels.keySet();
    }

    public Iterable<ServerLevel> getAllLevels() {
        return this.levels.values();
    }

    public String getServerVersion() {
        return SharedConstants.getCurrentVersion().getName();
    }

    public int getPlayerCount() {
        return this.playerList.getPlayerCount();
    }

    public int getMaxPlayers() {
        return this.playerList.getMaxPlayers();
    }

    public String[] getPlayerNames() {
        return this.playerList.getPlayerNamesArray();
    }

    @DontObfuscate
    public String getServerModName() {
        return "Spigot"; // Spigot - Spigot > // CraftBukkit - cb > vanilla!
    }

    public SystemReport fillSystemReport(SystemReport details) {
        details.setDetail("Server Running", () -> {
            return Boolean.toString(this.running);
        });
        if (this.playerList != null) {
            details.setDetail("Player Count", () -> {
                int i = this.playerList.getPlayerCount();

                return i + " / " + this.playerList.getMaxPlayers() + "; " + this.playerList.getPlayers();
            });
        }

        details.setDetail("Data Packs", () -> {
            StringBuilder stringbuilder = new StringBuilder();
            Iterator iterator = this.packRepository.getSelectedPacks().iterator();

            while (iterator.hasNext()) {
                Pack resourcepackloader = (Pack) iterator.next();

                if (stringbuilder.length() > 0) {
                    stringbuilder.append(", ");
                }

                stringbuilder.append(resourcepackloader.getId());
                if (!resourcepackloader.getCompatibility().isCompatible()) {
                    stringbuilder.append(" (incompatible)");
                }
            }

            return stringbuilder.toString();
        });
        details.setDetail("World Generation", () -> {
            return this.worldData.worldGenSettingsLifecycle().toString();
        });
        if (this.serverId != null) {
            details.setDetail("Server Id", () -> {
                return this.serverId;
            });
        }

        return this.fillServerSystemReport(details);
    }

    public abstract SystemReport fillServerSystemReport(SystemReport details);

    public ModCheck getModdedStatus() {
        return ModCheck.identify("vanilla", this::getServerModName, "Server", MinecraftServer.class);
    }

    @Override
    public void sendSystemMessage(Component message) {
        MinecraftServer.LOGGER.info(message.getString());
    }

    public KeyPair getKeyPair() {
        return this.keyPair;
    }

    public int getPort() {
        return this.port;
    }

    public void setPort(int serverPort) {
        this.port = serverPort;
    }

    @Nullable
    public GameProfile getSingleplayerProfile() {
        return this.singleplayerProfile;
    }

    public void setSingleplayerProfile(@Nullable GameProfile hostProfile) {
        this.singleplayerProfile = hostProfile;
    }

    public boolean isSingleplayer() {
        return this.singleplayerProfile != null;
    }

    protected void initializeKeyPair() {
        MinecraftServer.LOGGER.info("Generating keypair");

        try {
            this.keyPair = Crypt.generateKeyPair();
        } catch (CryptException cryptographyexception) {
            throw new IllegalStateException("Failed to generate key pair", cryptographyexception);
        }
    }

    public void setDifficulty(Difficulty difficulty, boolean forceUpdate) {
        if (forceUpdate || !this.worldData.isDifficultyLocked()) {
            this.worldData.setDifficulty(this.worldData.isHardcore() ? Difficulty.HARD : difficulty);
            this.updateMobSpawningFlags();
            this.getPlayerList().getPlayers().forEach(this::sendDifficultyUpdate);
        }
    }

    public int getScaledTrackingDistance(int initialDistance) {
        return initialDistance;
    }

    private void updateMobSpawningFlags() {
        Iterator iterator = this.getAllLevels().iterator();

        while (iterator.hasNext()) {
            ServerLevel worldserver = (ServerLevel) iterator.next();

            worldserver.setSpawnSettings(this.isSpawningMonsters(), this.isSpawningAnimals());
        }

    }

    public void setDifficultyLocked(boolean locked) {
        this.worldData.setDifficultyLocked(locked);
        this.getPlayerList().getPlayers().forEach(this::sendDifficultyUpdate);
    }

    private void sendDifficultyUpdate(ServerPlayer player) {
        LevelData worlddata = player.getLevel().getLevelData();

        player.connection.send(new ClientboundChangeDifficultyPacket(worlddata.getDifficulty(), worlddata.isDifficultyLocked()));
    }

    public boolean isSpawningMonsters() {
        return this.worldData.getDifficulty() != Difficulty.PEACEFUL;
    }

    public boolean isDemo() {
        return this.isDemo;
    }

    public void setDemo(boolean demo) {
        this.isDemo = demo;
    }

    public Optional<MinecraftServer.ServerResourcePackInfo> getServerResourcePack() {
        return Optional.empty();
    }

    public boolean isResourcePackRequired() {
        return this.getServerResourcePack().filter(MinecraftServer.ServerResourcePackInfo::isRequired).isPresent();
    }

    public abstract boolean isDedicatedServer();

    public abstract int getRateLimitPacketsPerSecond();

    public boolean usesAuthentication() {
        return this.onlineMode;
    }

    public void setUsesAuthentication(boolean onlineMode) {
        this.onlineMode = onlineMode;
    }

    public boolean getPreventProxyConnections() {
        return this.preventProxyConnections;
    }

    public void setPreventProxyConnections(boolean preventProxyConnections) {
        this.preventProxyConnections = preventProxyConnections;
    }

    public boolean isSpawningAnimals() {
        return true;
    }

    public boolean areNpcsEnabled() {
        return true;
    }

    public abstract boolean isEpollEnabled();

    public boolean isPvpAllowed() {
        return this.pvp;
    }

    public void setPvpAllowed(boolean pvpEnabled) {
        this.pvp = pvpEnabled;
    }

    public boolean isFlightAllowed() {
        return this.allowFlight;
    }

    public void setFlightAllowed(boolean flightEnabled) {
        this.allowFlight = flightEnabled;
    }

    public abstract boolean isCommandBlockEnabled();

    public String getMotd() {
        return this.motd;
    }

    public void setMotd(String motd) {
        this.motd = motd;
    }

    public boolean previewsChat() {
        return false;
    }

    public boolean isStopped() {
        return this.stopped;
    }

    public PlayerList getPlayerList() {
        return this.playerList;
    }

    public void setPlayerList(PlayerList playerManager) {
        this.playerList = playerManager;
    }

    public abstract boolean isPublished();

    public void setDefaultGameType(GameType gameMode) {
        this.worldData.setGameType(gameMode);
    }

    @Nullable
    public ServerConnectionListener getConnection() {
        return this.connection == null ? this.connection = new ServerConnectionListener(this) : this.connection; // Spigot
    }

    public boolean isReady() {
        return this.isReady;
    }

    public boolean hasGui() {
        return false;
    }

    public boolean publishServer(@Nullable GameType gameMode, boolean cheatsAllowed, int port) {
        return false;
    }

    public int getTickCount() {
        return this.tickCount;
    }

    public int getSpawnProtectionRadius() {
        return 16;
    }

    public boolean isUnderSpawnProtection(ServerLevel world, BlockPos pos, Player player) {
        return false;
    }

    public boolean repliesToStatus() {
        return true;
    }

    public boolean hidesOnlinePlayers() {
        return false;
    }

    public Proxy getProxy() {
        return this.proxy;
    }

    public int getPlayerIdleTimeout() {
        return this.playerIdleTimeout;
    }

    public void setPlayerIdleTimeout(int playerIdleTimeout) {
        this.playerIdleTimeout = playerIdleTimeout;
    }

    public MinecraftSessionService getSessionService() {
        return this.services.sessionService();
    }

    public SignatureValidator getServiceSignatureValidator() {
        return this.services.serviceSignatureValidator();
    }

    public GameProfileRepository getProfileRepository() {
        return this.services.profileRepository();
    }

    public GameProfileCache getProfileCache() {
        return this.services.profileCache();
    }

    public ServerStatus getStatus() {
        return this.status;
    }

    public void invalidateStatus() {
        this.lastServerStatus = 0L;
    }

    public int getAbsoluteMaxWorldSize() {
        return 29999984;
    }

    @Override
    public boolean scheduleExecutables() {
        return super.scheduleExecutables() && !this.isStopped();
    }

    @Override
    public void executeIfPossible(Runnable runnable) {
        if (this.isStopped()) {
            throw new RejectedExecutionException("Server already shutting down");
        } else {
            super.executeIfPossible(runnable);
        }
    }

    @Override
    public Thread getRunningThread() {
        return this.serverThread;
    }

    public int getCompressionThreshold() {
        return 256;
    }

    public boolean enforceSecureProfile() {
        return false;
    }

    public long getNextTickTime() {
        return this.nextTickTime;
    }

    public DataFixer getFixerUpper() {
        return this.fixerUpper;
    }

    public int getSpawnRadius(@Nullable ServerLevel world) {
        return world != null ? world.getGameRules().getInt(GameRules.RULE_SPAWN_RADIUS) : 10;
    }

    public ServerAdvancementManager getAdvancements() {
        return this.resources.managers.getAdvancements();
    }

    public ServerFunctionManager getFunctions() {
        return this.functionManager;
    }

    public CompletableFuture<Void> reloadResources(Collection<String> dataPacks) {
        RegistryAccess.Frozen iregistrycustom_dimension = this.registryAccess();
        CompletableFuture<Void> completablefuture = CompletableFuture.supplyAsync(() -> {
            Stream<String> stream = dataPacks.stream(); // CraftBukkit - decompile error
            PackRepository resourcepackrepository = this.packRepository;

            Objects.requireNonNull(this.packRepository);
            return stream.<Pack>map(resourcepackrepository::getPack).filter(Objects::nonNull).map(Pack::open).collect(ImmutableList.toImmutableList()); // CraftBukkit - decompile error // Paper - decompile error // todo: is this needed anymore?
        }, this).thenCompose((immutablelist) -> {
            MultiPackResourceManager resourcemanager = new MultiPackResourceManager(PackType.SERVER_DATA, immutablelist);

            return ReloadableServerResources.loadResources(resourcemanager, iregistrycustom_dimension, this.isDedicatedServer() ? Commands.CommandSelection.DEDICATED : Commands.CommandSelection.INTEGRATED, this.getFunctionCompilationLevel(), this.executor, this).whenComplete((datapackresources, throwable) -> {
                if (throwable != null) {
                    resourcemanager.close();
                }

            }).thenApply((datapackresources) -> {
                return new MinecraftServer.ReloadableResources(resourcemanager, datapackresources);
            });
        }).thenAcceptAsync((minecraftserver_reloadableresources) -> {
            this.resources.close();
            this.resources = minecraftserver_reloadableresources;
            this.server.syncCommands(); // SPIGOT-5884: Lost on reload
            this.packRepository.setSelected(dataPacks);
            this.worldData.setDataPackConfig(MinecraftServer.getSelectedPacks(this.packRepository));
            this.resources.managers.updateRegistryTags(this.registryAccess());
            this.getPlayerList().saveAll();
            this.getPlayerList().reloadResources();
            this.functionManager.replaceLibrary(this.resources.managers.getFunctionLibrary());
            this.structureTemplateManager.onResourceManagerReload(this.resources.resourceManager);
        }, this);

        if (this.isSameThread()) {
            Objects.requireNonNull(completablefuture);
            this.managedBlock(completablefuture::isDone);
        }

        return completablefuture;
    }

    public static DataPackConfig configurePackRepository(PackRepository resourcePackManager, DataPackConfig dataPackSettings, boolean safeMode) {
        resourcePackManager.reload();
        if (safeMode) {
            resourcePackManager.setSelected(Collections.singleton("vanilla"));
            return DataPackConfig.DEFAULT;
        } else {
            Set<String> set = Sets.newLinkedHashSet();
            Iterator iterator = dataPackSettings.getEnabled().iterator();

            while (iterator.hasNext()) {
                String s = (String) iterator.next();

                if (resourcePackManager.isAvailable(s)) {
                    set.add(s);
                } else {
                    MinecraftServer.LOGGER.warn("Missing data pack {}", s);
                }
            }

            iterator = resourcePackManager.getAvailablePacks().iterator();

            while (iterator.hasNext()) {
                Pack resourcepackloader = (Pack) iterator.next();
                String s1 = resourcepackloader.getId();

                if (!dataPackSettings.getDisabled().contains(s1) && !set.contains(s1)) {
                    MinecraftServer.LOGGER.info("Found new data pack {}, loading it automatically", s1);
                    set.add(s1);
                }
            }

            if (set.isEmpty()) {
                MinecraftServer.LOGGER.info("No datapacks selected, forcing vanilla");
                set.add("vanilla");
            }

            resourcePackManager.setSelected(set);
            return MinecraftServer.getSelectedPacks(resourcePackManager);
        }
    }

    private static DataPackConfig getSelectedPacks(PackRepository dataPackManager) {
        Collection<String> collection = dataPackManager.getSelectedIds();
        List<String> list = ImmutableList.copyOf(collection);
        List<String> list1 = (List) dataPackManager.getAvailableIds().stream().filter((s) -> {
            return !collection.contains(s);
        }).collect(ImmutableList.toImmutableList());

        return new DataPackConfig(list, list1);
    }

    public void kickUnlistedPlayers(CommandSourceStack source) {
        if (this.isEnforceWhitelist()) {
            PlayerList playerlist = source.getServer().getPlayerList();
            UserWhiteList whitelist = playerlist.getWhiteList();
            List<ServerPlayer> list = Lists.newArrayList(playerlist.getPlayers());
            Iterator iterator = list.iterator();

            while (iterator.hasNext()) {
                ServerPlayer entityplayer = (ServerPlayer) iterator.next();

                if (!whitelist.isWhiteListed(entityplayer.getGameProfile())) {
                    entityplayer.connection.disconnect(Component.translatable("multiplayer.disconnect.not_whitelisted"));
                }
            }

        }
    }

    public PackRepository getPackRepository() {
        return this.packRepository;
    }

    public Commands getCommands() {
        return this.resources.managers.getCommands();
    }

    public CommandSourceStack createCommandSourceStack() {
        ServerLevel worldserver = this.overworld();

        return new CommandSourceStack(this, worldserver == null ? Vec3.ZERO : Vec3.atLowerCornerOf(worldserver.getSharedSpawnPos()), Vec2.ZERO, worldserver, 4, "Server", Component.literal("Server"), this, (Entity) null);
    }

    @Override
    public boolean acceptsSuccess() {
        return true;
    }

    @Override
    public boolean acceptsFailure() {
        return true;
    }

    @Override
    public abstract boolean shouldInformAdmins();

    public RecipeManager getRecipeManager() {
        return this.resources.managers.getRecipeManager();
    }

    public ServerScoreboard getScoreboard() {
        return this.scoreboard;
    }

    public CommandStorage getCommandStorage() {
        if (this.commandStorage == null) {
            throw new NullPointerException("Called before server init");
        } else {
            return this.commandStorage;
        }
    }

    public LootTables getLootTables() {
        return this.resources.managers.getLootTables();
    }

    public PredicateManager getPredicateManager() {
        return this.resources.managers.getPredicateManager();
    }

    public ItemModifierManager getItemModifierManager() {
        return this.resources.managers.getItemModifierManager();
    }

    public GameRules getGameRules() {
        return this.overworld().getGameRules();
    }

    public CustomBossEvents getCustomBossEvents() {
        return this.customBossEvents;
    }

    public boolean isEnforceWhitelist() {
        return this.enforceWhitelist;
    }

    public void setEnforceWhitelist(boolean enforceWhitelist) {
        this.enforceWhitelist = enforceWhitelist;
    }

    public float getAverageTickTime() {
        return this.averageTickTime;
    }

    public int getProfilePermissions(GameProfile profile) {
        if (this.getPlayerList().isOp(profile)) {
            ServerOpListEntry oplistentry = (ServerOpListEntry) this.getPlayerList().getOps().get(profile);

            return oplistentry != null ? oplistentry.getLevel() : (this.isSingleplayerOwner(profile) ? 4 : (this.isSingleplayer() ? (this.getPlayerList().isAllowCheatsForAllPlayers() ? 4 : 0) : this.getOperatorUserPermissionLevel()));
        } else {
            return 0;
        }
    }

    public FrameTimer getFrameTimer() {
        return this.frameTimer;
    }

    public ProfilerFiller getProfiler() {
        return this.profiler;
    }

    public abstract boolean isSingleplayerOwner(GameProfile profile);

    public void dumpServerProperties(Path file) throws IOException {}

    private void saveDebugReport(Path path) {
        Path path1 = path.resolve("levels");

        try {
            Iterator iterator = this.levels.entrySet().iterator();

            while (iterator.hasNext()) {
                Entry<ResourceKey<Level>, ServerLevel> entry = (Entry) iterator.next();
                ResourceLocation minecraftkey = ((ResourceKey) entry.getKey()).location();
                Path path2 = path1.resolve(minecraftkey.getNamespace()).resolve(minecraftkey.getPath());

                Files.createDirectories(path2);
                ((ServerLevel) entry.getValue()).saveDebugReport(path2);
            }

            this.dumpGameRules(path.resolve("gamerules.txt"));
            this.dumpClasspath(path.resolve("classpath.txt"));
            this.dumpMiscStats(path.resolve("stats.txt"));
            this.dumpThreads(path.resolve("threads.txt"));
            this.dumpServerProperties(path.resolve("server.properties.txt"));
            this.dumpNativeModules(path.resolve("modules.txt"));
        } catch (IOException ioexception) {
            MinecraftServer.LOGGER.warn("Failed to save debug report", ioexception);
        }

    }

    private void dumpMiscStats(Path path) throws IOException {
        BufferedWriter bufferedwriter = Files.newBufferedWriter(path);

        try {
            bufferedwriter.write(String.format(Locale.ROOT, "pending_tasks: %d\n", this.getPendingTasksCount()));
            bufferedwriter.write(String.format(Locale.ROOT, "average_tick_time: %f\n", this.getAverageTickTime()));
            bufferedwriter.write(String.format(Locale.ROOT, "tick_times: %s\n", Arrays.toString(this.tickTimes)));
            bufferedwriter.write(String.format(Locale.ROOT, "queue: %s\n", Util.backgroundExecutor()));
        } catch (Throwable throwable) {
            if (bufferedwriter != null) {
                try {
                    bufferedwriter.close();
                } catch (Throwable throwable1) {
                    throwable.addSuppressed(throwable1);
                }
            }

            throw throwable;
        }

        if (bufferedwriter != null) {
            bufferedwriter.close();
        }

    }

    private void dumpGameRules(Path path) throws IOException {
        BufferedWriter bufferedwriter = Files.newBufferedWriter(path);

        try {
            final List<String> list = Lists.newArrayList();
            final GameRules gamerules = this.getGameRules();

            GameRules.visitGameRuleTypes(new GameRules.GameRuleTypeVisitor() {
                @Override
                public <T extends GameRules.Value<T>> void visit(GameRules.Key<T> key, GameRules.Type<T> type) {
                    list.add(String.format(Locale.ROOT, "%s=%s\n", key.getId(), gamerules.getRule(key)));
                }
            });
            Iterator iterator = list.iterator();

            while (iterator.hasNext()) {
                String s = (String) iterator.next();

                bufferedwriter.write(s);
            }
        } catch (Throwable throwable) {
            if (bufferedwriter != null) {
                try {
                    bufferedwriter.close();
                } catch (Throwable throwable1) {
                    throwable.addSuppressed(throwable1);
                }
            }

            throw throwable;
        }

        if (bufferedwriter != null) {
            bufferedwriter.close();
        }

    }

    private void dumpClasspath(Path path) throws IOException {
        BufferedWriter bufferedwriter = Files.newBufferedWriter(path);

        try {
            String s = System.getProperty("java.class.path");
            String s1 = System.getProperty("path.separator");
            Iterator iterator = Splitter.on(s1).split(s).iterator();

            while (iterator.hasNext()) {
                String s2 = (String) iterator.next();

                bufferedwriter.write(s2);
                bufferedwriter.write("\n");
            }
        } catch (Throwable throwable) {
            if (bufferedwriter != null) {
                try {
                    bufferedwriter.close();
                } catch (Throwable throwable1) {
                    throwable.addSuppressed(throwable1);
                }
            }

            throw throwable;
        }

        if (bufferedwriter != null) {
            bufferedwriter.close();
        }

    }

    private void dumpThreads(Path path) throws IOException {
        ThreadMXBean threadmxbean = ManagementFactory.getThreadMXBean();
        ThreadInfo[] athreadinfo = threadmxbean.dumpAllThreads(true, true);

        Arrays.sort(athreadinfo, Comparator.comparing(ThreadInfo::getThreadName));
        BufferedWriter bufferedwriter = Files.newBufferedWriter(path);

        try {
            ThreadInfo[] athreadinfo1 = athreadinfo;
            int i = athreadinfo.length;

            for (int j = 0; j < i; ++j) {
                ThreadInfo threadinfo = athreadinfo1[j];

                bufferedwriter.write(threadinfo.toString());
                bufferedwriter.write(10);
            }
        } catch (Throwable throwable) {
            if (bufferedwriter != null) {
                try {
                    bufferedwriter.close();
                } catch (Throwable throwable1) {
                    throwable.addSuppressed(throwable1);
                }
            }

            throw throwable;
        }

        if (bufferedwriter != null) {
            bufferedwriter.close();
        }

    }

    private void dumpNativeModules(Path path) throws IOException {
        BufferedWriter bufferedwriter = Files.newBufferedWriter(path);

        label50:
        {
            try {
                label51:
                {
                    ArrayList<NativeModuleLister.NativeModuleInfo> arraylist; // CraftBukkit - decompile error

                    try {
                        arraylist = Lists.newArrayList(NativeModuleLister.listModules());
                    } catch (Throwable throwable) {
                        MinecraftServer.LOGGER.warn("Failed to list native modules", throwable);
                        break label51;
                    }

                    arraylist.sort(Comparator.comparing((nativemodulelister_a) -> {
                        return nativemodulelister_a.name;
                    }));
                    Iterator iterator = arraylist.iterator();

                    while (true) {
                        if (!iterator.hasNext()) {
                            break label50;
                        }

                        NativeModuleLister.NativeModuleInfo nativemodulelister_a = (NativeModuleLister.NativeModuleInfo) iterator.next();

                        bufferedwriter.write(nativemodulelister_a.toString());
                        bufferedwriter.write(10);
                    }
                }
            } catch (Throwable throwable1) {
                if (bufferedwriter != null) {
                    try {
                        bufferedwriter.close();
                    } catch (Throwable throwable2) {
                        throwable1.addSuppressed(throwable2);
                    }
                }

                throw throwable1;
            }

            if (bufferedwriter != null) {
                bufferedwriter.close();
            }

            return;
        }

        if (bufferedwriter != null) {
            bufferedwriter.close();
        }

    }

    // CraftBukkit start
    @Override
    public boolean isSameThread() {
        return super.isSameThread() || this.isStopped(); // CraftBukkit - MC-142590
    }

    public boolean isDebugging() {
        return false;
    }

    @Deprecated
    public static MinecraftServer getServer() {
        return (Bukkit.getServer() instanceof CraftServer) ? ((CraftServer) Bukkit.getServer()).getServer() : null;
    }
    // CraftBukkit end

    private void startMetricsRecordingTick() {
        if (this.willStartRecordingMetrics) {
            this.metricsRecorder = ActiveMetricsRecorder.createStarted(new ServerMetricsSamplersProvider(Util.timeSource, this.isDedicatedServer()), Util.timeSource, Util.ioPool(), new MetricsPersister("server"), this.onMetricsRecordingStopped, (path) -> {
                this.executeBlocking(() -> {
                    this.saveDebugReport(path.resolve("server"));
                });
                this.onMetricsRecordingFinished.accept(path);
            });
            this.willStartRecordingMetrics = false;
        }

        this.profiler = SingleTickProfiler.decorateFiller(this.metricsRecorder.getProfiler(), SingleTickProfiler.createTickProfiler("Server"));
        this.metricsRecorder.startTick();
        this.profiler.startTick();
    }

    private void endMetricsRecordingTick() {
        this.profiler.endTick();
        this.metricsRecorder.endTick();
    }

    public boolean isRecordingMetrics() {
        return this.metricsRecorder.isRecording();
    }

    public void startRecordingMetrics(Consumer<ProfileResults> resultConsumer, Consumer<Path> dumpConsumer) {
        this.onMetricsRecordingStopped = (methodprofilerresults) -> {
            this.stopRecordingMetrics();
            resultConsumer.accept(methodprofilerresults);
        };
        this.onMetricsRecordingFinished = dumpConsumer;
        this.willStartRecordingMetrics = true;
    }

    public void stopRecordingMetrics() {
        this.metricsRecorder = InactiveMetricsRecorder.INSTANCE;
    }

    public void finishRecordingMetrics() {
        this.metricsRecorder.end();
    }

    public void cancelRecordingMetrics() {
        this.metricsRecorder.cancel();
        this.profiler = this.metricsRecorder.getProfiler();
    }

    public Path getWorldPath(LevelResource worldSavePath) {
        return this.storageSource.getLevelPath(worldSavePath);
    }

    public boolean forceSynchronousWrites() {
        return true;
    }

    public StructureTemplateManager getStructureManager() {
        return this.structureTemplateManager;
    }

    public WorldData getWorldData() {
        return this.worldData;
    }

    public RegistryAccess.Frozen registryAccess() {
        return this.registryHolder;
    }

    public TextFilter createTextFilterForPlayer(ServerPlayer player) {
        return TextFilter.DUMMY;
    }

    public ServerPlayerGameMode createGameModeForPlayer(ServerPlayer player) {
        return (ServerPlayerGameMode) (this.isDemo() ? new DemoMode(player) : new ServerPlayerGameMode(player));
    }

    @Nullable
    public GameType getForcedGameType() {
        return null;
    }

    public ResourceManager getResourceManager() {
        return this.resources.resourceManager;
    }

    public boolean isCurrentlySaving() {
        return this.isSaving;
    }

    public boolean isTimeProfilerRunning() {
        return this.debugCommandProfilerDelayStart || this.debugCommandProfiler != null;
    }

    public void startTimeProfiler() {
        this.debugCommandProfilerDelayStart = true;
    }

    public ProfileResults stopTimeProfiler() {
        if (this.debugCommandProfiler == null) {
            return EmptyProfileResults.EMPTY;
        } else {
            ProfileResults methodprofilerresults = this.debugCommandProfiler.stop(Util.getNanos(), this.tickCount);

            this.debugCommandProfiler = null;
            return methodprofilerresults;
        }
    }

    public int getMaxChainedNeighborUpdates() {
        return 1000000;
    }

    public void logChatMessage(Component message, ChatType.Bound params, @Nullable String prefix) {
        String s1 = params.decorate(message).getString();

        if (prefix != null) {
            MinecraftServer.LOGGER.info("[{}] {}", prefix, s1);
        } else {
            MinecraftServer.LOGGER.info("{}", s1);
        }

    }

    // CraftBukkit start
    public final java.util.concurrent.ExecutorService chatExecutor = java.util.concurrent.Executors.newCachedThreadPool(
            new com.google.common.util.concurrent.ThreadFactoryBuilder().setDaemon(true).setNameFormat("Async Chat Thread - #%d").build());

    public ChatDecorator getChatDecorator() {
        return (entityplayer, ichatbasecomponent) -> {
            // SPIGOT-7127: Console /say and similar
            if (entityplayer == null) {
                return CompletableFuture.completedFuture(ichatbasecomponent);
            }

            return CompletableFuture.supplyAsync(() -> {
                AsyncPlayerChatPreviewEvent event = new AsyncPlayerChatPreviewEvent(true, entityplayer.getBukkitEntity(), CraftChatMessage.fromComponent(ichatbasecomponent), new LazyPlayerSet(this));
                String originalFormat = event.getFormat(), originalMessage = event.getMessage();
                this.server.getPluginManager().callEvent(event);

                if (originalFormat.equals(event.getFormat()) && originalMessage.equals(event.getMessage()) && event.getPlayer().getName().equalsIgnoreCase(event.getPlayer().getDisplayName())) {
                    return ichatbasecomponent;
                }

                return CraftChatMessage.fromStringOrNull(String.format(event.getFormat(), event.getPlayer().getDisplayName(), event.getMessage()));
            }, chatExecutor);
        };
        // CraftBukkit end
    }

    public static record ReloadableResources(CloseableResourceManager resourceManager, ReloadableServerResources managers) implements AutoCloseable {

        public void close() {
            this.resourceManager.close();
        }
    }

    private static class TimeProfiler {

        final long startNanos;
        final int startTick;

        TimeProfiler(long time, int tick) {
            this.startNanos = time;
            this.startTick = tick;
        }

        ProfileResults stop(final long endTime, final int endTick) {
            return new ProfileResults() {
                @Override
                public List<ResultField> getTimes(String parentPath) {
                    return Collections.emptyList();
                }

                @Override
                public boolean saveResults(Path path) {
                    return false;
                }

                @Override
                public long getStartTimeNano() {
                    return TimeProfiler.this.startNanos;
                }

                @Override
                public int getStartTimeTicks() {
                    return TimeProfiler.this.startTick;
                }

                @Override
                public long getEndTimeNano() {
                    return endTime;
                }

                @Override
                public int getEndTimeTicks() {
                    return endTick;
                }

                @Override
                public String getProfilerResults() {
                    return "";
                }
            };
        }
    }

    public static record ServerResourcePackInfo(String url, String hash, boolean isRequired, @Nullable Component prompt) {

    }
}