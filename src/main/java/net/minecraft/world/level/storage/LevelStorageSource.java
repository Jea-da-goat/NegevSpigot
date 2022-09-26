package net.minecraft.world.level.storage;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.Maps;
import com.google.common.collect.UnmodifiableIterator;
import com.mojang.datafixers.DataFixer;
import com.mojang.datafixers.util.Pair;
import com.mojang.logging.LogUtils;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.Dynamic;
import com.mojang.serialization.DynamicOps;
import com.mojang.serialization.Lifecycle;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.format.SignStyle;
import java.time.temporal.ChronoField;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiFunction;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import javax.annotation.Nullable;
import net.minecraft.FileUtil;
import net.minecraft.SharedConstants;
import net.minecraft.Util;
import net.minecraft.core.RegistryAccess;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.nbt.visitors.FieldSelector;
import net.minecraft.nbt.visitors.SkipFields;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.util.DirectoryLock;
import net.minecraft.util.MemoryReserve;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.util.datafix.DataFixers;
import net.minecraft.util.datafix.fixes.References;
import net.minecraft.world.level.DataPackConfig;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.dimension.LevelStem;
import net.minecraft.world.level.levelgen.WorldGenSettings;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import org.slf4j.Logger;

public class LevelStorageSource {

    static final Logger LOGGER = LogUtils.getLogger();
    static final DateTimeFormatter FORMATTER = (new DateTimeFormatterBuilder()).appendValue(ChronoField.YEAR, 4, 10, SignStyle.EXCEEDS_PAD).appendLiteral('-').appendValue(ChronoField.MONTH_OF_YEAR, 2).appendLiteral('-').appendValue(ChronoField.DAY_OF_MONTH, 2).appendLiteral('_').appendValue(ChronoField.HOUR_OF_DAY, 2).appendLiteral('-').appendValue(ChronoField.MINUTE_OF_HOUR, 2).appendLiteral('-').appendValue(ChronoField.SECOND_OF_MINUTE, 2).toFormatter();
    private static final ImmutableList<String> OLD_SETTINGS_KEYS = ImmutableList.of("RandomSeed", "generatorName", "generatorOptions", "generatorVersion", "legacy_custom_options", "MapFeatures", "BonusChest");
    private static final String TAG_DATA = "Data";
    public final Path baseDir;
    private final Path backupDir;
    final DataFixer fixerUpper;

    public LevelStorageSource(Path savesDirectory, Path backupsDirectory, DataFixer dataFixer) {
        this.fixerUpper = dataFixer;

        try {
            Files.createDirectories(Files.exists(savesDirectory, new LinkOption[0]) ? savesDirectory.toRealPath() : savesDirectory);
        } catch (IOException ioexception) {
            throw new RuntimeException(ioexception);
        }

        this.baseDir = savesDirectory;
        this.backupDir = backupsDirectory;
    }

    public static LevelStorageSource createDefault(Path path) {
        return new LevelStorageSource(path, path.resolve("../backups"), DataFixers.getDataFixer());
    }

    private static <T> Pair<WorldGenSettings, Lifecycle> readWorldGenSettings(Dynamic<T> levelData, DataFixer dataFixer, int version) {
        Dynamic<T> dynamic1 = levelData.get("WorldGenSettings").orElseEmptyMap();
        UnmodifiableIterator unmodifiableiterator = LevelStorageSource.OLD_SETTINGS_KEYS.iterator();

        while (unmodifiableiterator.hasNext()) {
            String s = (String) unmodifiableiterator.next();
            Optional<? extends Dynamic<?>> optional = levelData.get(s).result();

            if (optional.isPresent()) {
                dynamic1 = dynamic1.set(s, (Dynamic) optional.get());
            }
        }

        Dynamic<T> dynamic2 = dataFixer.update(References.WORLD_GEN_SETTINGS, dynamic1, version, SharedConstants.getCurrentVersion().getWorldVersion());
        DataResult<WorldGenSettings> dataresult = WorldGenSettings.CODEC.parse(dynamic2);
        Logger logger = LevelStorageSource.LOGGER;

        Objects.requireNonNull(logger);
        return Pair.of((WorldGenSettings) dataresult.resultOrPartial(Util.prefix("WorldGenSettings: ", logger::error)).orElseGet(() -> {
            RegistryAccess iregistrycustom = RegistryAccess.readFromDisk(dynamic2);

            return WorldPresets.createNormalWorldFromPreset(iregistrycustom);
        }), dataresult.lifecycle());
    }

    private static DataPackConfig readDataPackConfig(Dynamic<?> dynamic) {
        DataResult<DataPackConfig> dataresult = DataPackConfig.CODEC.parse(dynamic); // CraftBukkit - decompile error
        Logger logger = LevelStorageSource.LOGGER;

        Objects.requireNonNull(logger);
        return (DataPackConfig) dataresult.resultOrPartial(logger::error).orElse(DataPackConfig.DEFAULT);
    }

    public String getName() {
        return "Anvil";
    }

    public LevelStorageSource.LevelCandidates findLevelCandidates() throws LevelStorageException {
        if (!Files.isDirectory(this.baseDir, new LinkOption[0])) {
            throw new LevelStorageException(Component.translatable("selectWorld.load_folder_access"));
        } else {
            try {
                List<LevelStorageSource.LevelDirectory> list = Files.list(this.baseDir).filter((path) -> {
                    return Files.isDirectory(path, new LinkOption[0]);
                }).map(LevelStorageSource.LevelDirectory::new).filter((convertable_b) -> {
                    return Files.isRegularFile(convertable_b.dataFile(), new LinkOption[0]) || Files.isRegularFile(convertable_b.oldDataFile(), new LinkOption[0]);
                }).toList();

                return new LevelStorageSource.LevelCandidates(list);
            } catch (IOException ioexception) {
                throw new LevelStorageException(Component.translatable("selectWorld.load_folder_access"));
            }
        }
    }

    public CompletableFuture<List<LevelSummary>> loadLevelSummaries(LevelStorageSource.LevelCandidates levels) {
        List<CompletableFuture<LevelSummary>> list = new ArrayList(levels.levels.size());
        Iterator iterator = levels.levels.iterator();

        while (iterator.hasNext()) {
            LevelStorageSource.LevelDirectory convertable_b = (LevelStorageSource.LevelDirectory) iterator.next();

            list.add(CompletableFuture.supplyAsync(() -> {
                boolean flag;

                try {
                    flag = DirectoryLock.isLocked(convertable_b.path());
                } catch (Exception exception) {
                    LevelStorageSource.LOGGER.warn("Failed to read {} lock", convertable_b.path(), exception);
                    return null;
                }

                try {
                    LevelSummary worldinfo = (LevelSummary) this.readLevelData(convertable_b, this.levelSummaryReader(convertable_b, flag));

                    return worldinfo != null ? worldinfo : null;
                } catch (OutOfMemoryError outofmemoryerror) {
                    MemoryReserve.release();
                    System.gc();
                    LevelStorageSource.LOGGER.error(LogUtils.FATAL_MARKER, "Ran out of memory trying to read summary of {}", convertable_b.directoryName());
                    throw outofmemoryerror;
                } catch (StackOverflowError stackoverflowerror) {
                    LevelStorageSource.LOGGER.error(LogUtils.FATAL_MARKER, "Ran out of stack trying to read summary of {}. Assuming corruption; attempting to restore from from level.dat_old.", convertable_b.directoryName());
                    Util.safeReplaceOrMoveFile(convertable_b.dataFile(), convertable_b.oldDataFile(), convertable_b.corruptedDataFile(LocalDateTime.now()), true);
                    throw stackoverflowerror;
                }
            }, Util.backgroundExecutor()));
        }

        return Util.sequenceFailFastAndCancel(list).thenApply((list1) -> {
            return list1.stream().filter(Objects::nonNull).sorted().toList();
        });
    }

    private int getStorageVersion() {
        return 19133;
    }

    @Nullable
    <T> T readLevelData(LevelStorageSource.LevelDirectory levelSave, BiFunction<Path, DataFixer, T> levelDataParser) {
        if (!Files.exists(levelSave.path(), new LinkOption[0])) {
            return null;
        } else {
            Path path = levelSave.dataFile();

            if (Files.exists(path, new LinkOption[0])) {
                T t0 = levelDataParser.apply(path, this.fixerUpper);

                if (t0 != null) {
                    return t0;
                }
            }

            path = levelSave.oldDataFile();
            return Files.exists(path, new LinkOption[0]) ? levelDataParser.apply(path, this.fixerUpper) : null;
        }
    }

    @Nullable
    private static DataPackConfig getDataPacks(Path path, DataFixer dataFixer) {
        try {
            Tag nbtbase = LevelStorageSource.readLightweightData(path);

            if (nbtbase instanceof CompoundTag) {
                CompoundTag nbttagcompound = (CompoundTag) nbtbase;
                CompoundTag nbttagcompound1 = nbttagcompound.getCompound("Data");
                int i = nbttagcompound1.contains("DataVersion", 99) ? nbttagcompound1.getInt("DataVersion") : -1;
                Dynamic<Tag> dynamic = dataFixer.update(DataFixTypes.LEVEL.getType(), new Dynamic(NbtOps.INSTANCE, nbttagcompound1), i, SharedConstants.getCurrentVersion().getWorldVersion());

                return (DataPackConfig) dynamic.get("DataPacks").result().map(LevelStorageSource::readDataPackConfig).orElse(DataPackConfig.DEFAULT);
            }
        } catch (Exception exception) {
            LevelStorageSource.LOGGER.error("Exception reading {}", path, exception);
        }

        return null;
    }

    static BiFunction<Path, DataFixer, PrimaryLevelData> getLevelData(DynamicOps<Tag> ops, DataPackConfig dataPackSettings, Lifecycle lifecycle) {
        return (path, datafixer) -> {
            try {
                CompoundTag nbttagcompound = NbtIo.readCompressed(path.toFile());
                CompoundTag nbttagcompound1 = nbttagcompound.getCompound("Data");
                CompoundTag nbttagcompound2 = nbttagcompound1.contains("Player", 10) ? nbttagcompound1.getCompound("Player") : null;

                nbttagcompound1.remove("Player");
                int i = nbttagcompound1.contains("DataVersion", 99) ? nbttagcompound1.getInt("DataVersion") : -1;
                Dynamic<Tag> dynamic = datafixer.update(DataFixTypes.LEVEL.getType(), new Dynamic(ops, nbttagcompound1), i, SharedConstants.getCurrentVersion().getWorldVersion());
                Pair<WorldGenSettings, Lifecycle> pair = LevelStorageSource.readWorldGenSettings(dynamic, datafixer, i);
                LevelVersion levelversion = LevelVersion.parse(dynamic);
                LevelSettings worldsettings = LevelSettings.parse(dynamic, dataPackSettings);
                Lifecycle lifecycle1 = ((Lifecycle) pair.getSecond()).add(lifecycle);

                // CraftBukkit start - Add PDC to world
                PrimaryLevelData worldDataServer = PrimaryLevelData.parse(dynamic, datafixer, i, nbttagcompound2, worldsettings, levelversion, (WorldGenSettings) pair.getFirst(), lifecycle1);
                worldDataServer.pdc = nbttagcompound1.get("BukkitValues");
                return worldDataServer;
                // CraftBukkit end
            } catch (Exception exception) {
                LevelStorageSource.LOGGER.error("Exception reading {}", path, exception);
                return null;
            }
        };
    }

    BiFunction<Path, DataFixer, LevelSummary> levelSummaryReader(LevelStorageSource.LevelDirectory levelSave, boolean locked) {
        return (path, datafixer) -> {
            try {
                Tag nbtbase = LevelStorageSource.readLightweightData(path);

                if (nbtbase instanceof CompoundTag) {
                    CompoundTag nbttagcompound = (CompoundTag) nbtbase;
                    CompoundTag nbttagcompound1 = nbttagcompound.getCompound("Data");
                    int i = nbttagcompound1.contains("DataVersion", 99) ? nbttagcompound1.getInt("DataVersion") : -1;
                    Dynamic<Tag> dynamic = datafixer.update(DataFixTypes.LEVEL.getType(), new Dynamic(NbtOps.INSTANCE, nbttagcompound1), i, SharedConstants.getCurrentVersion().getWorldVersion());
                    LevelVersion levelversion = LevelVersion.parse(dynamic);
                    int j = levelversion.levelDataVersion();

                    if (j == 19132 || j == 19133) {
                        boolean flag1 = j != this.getStorageVersion();
                        Path path1 = levelSave.iconFile();
                        DataPackConfig datapackconfiguration = (DataPackConfig) dynamic.get("DataPacks").result().map(LevelStorageSource::readDataPackConfig).orElse(DataPackConfig.DEFAULT);
                        LevelSettings worldsettings = LevelSettings.parse(dynamic, datapackconfiguration);

                        return new LevelSummary(worldsettings, levelversion, levelSave.directoryName(), flag1, locked, path1);
                    }
                } else {
                    LevelStorageSource.LOGGER.warn("Invalid root tag in {}", path);
                }

                return null;
            } catch (Exception exception) {
                LevelStorageSource.LOGGER.error("Exception reading {}", path, exception);
                return null;
            }
        };
    }

    @Nullable
    private static Tag readLightweightData(Path path) throws IOException {
        SkipFields skipfields = new SkipFields(new FieldSelector[]{new FieldSelector("Data", CompoundTag.TYPE, "Player"), new FieldSelector("Data", CompoundTag.TYPE, "WorldGenSettings")});

        NbtIo.parseCompressed(path.toFile(), skipfields);
        return skipfields.getResult();
    }

    public boolean isNewLevelIdAcceptable(String name) {
        try {
            Path path = this.baseDir.resolve(name);

            Files.createDirectory(path);
            Files.deleteIfExists(path);
            return true;
        } catch (IOException ioexception) {
            return false;
        }
    }

    public boolean levelExists(String name) {
        return Files.isDirectory(this.baseDir.resolve(name), new LinkOption[0]);
    }

    public Path getBaseDir() {
        return this.baseDir;
    }

    public Path getBackupPath() {
        return this.backupDir;
    }

    // CraftBukkit start
    public LevelStorageSource.LevelStorageAccess createAccess(String s, ResourceKey<LevelStem> dimensionType) throws IOException {
        return new LevelStorageSource.LevelStorageAccess(s, dimensionType);
    }

    public static Path getStorageFolder(Path path, ResourceKey<LevelStem> dimensionType) {
        if (dimensionType == LevelStem.OVERWORLD) {
            return path;
        } else if (dimensionType == LevelStem.NETHER) {
            return path.resolve("DIM-1");
        } else if (dimensionType == LevelStem.END) {
            return path.resolve("DIM1");
        } else {
            return path.resolve("dimensions").resolve(dimensionType.location().getNamespace()).resolve(dimensionType.location().getPath());
        }
    }
    // CraftBukkit end

    public static record LevelCandidates(List<LevelStorageSource.LevelDirectory> levels) implements Iterable<LevelStorageSource.LevelDirectory> {

        public boolean isEmpty() {
            return this.levels.isEmpty();
        }

        public Iterator<LevelStorageSource.LevelDirectory> iterator() {
            return this.levels.iterator();
        }
    }

    public static record LevelDirectory(Path path) {

        public String directoryName() {
            return this.path.getFileName().toString();
        }

        public Path dataFile() {
            return this.resourcePath(LevelResource.LEVEL_DATA_FILE);
        }

        public Path oldDataFile() {
            return this.resourcePath(LevelResource.OLD_LEVEL_DATA_FILE);
        }

        public Path corruptedDataFile(LocalDateTime dateTime) {
            Path path = this.path;
            String s = LevelResource.LEVEL_DATA_FILE.getId();

            return path.resolve(s + "_corrupted_" + dateTime.format(LevelStorageSource.FORMATTER));
        }

        public Path iconFile() {
            return this.resourcePath(LevelResource.ICON_FILE);
        }

        public Path lockFile() {
            return this.resourcePath(LevelResource.LOCK_FILE);
        }

        public Path resourcePath(LevelResource savePath) {
            return this.path.resolve(savePath.getId());
        }
    }

    public class LevelStorageAccess implements AutoCloseable {

        final DirectoryLock lock;
        public final LevelStorageSource.LevelDirectory levelDirectory;
        private final String levelId;
        private final Map<LevelResource, Path> resources = Maps.newHashMap();
        // CraftBukkit start
        public final ResourceKey<LevelStem> dimensionType;

        public LevelStorageAccess(String s, ResourceKey<LevelStem> dimensionType) throws IOException {
            this.dimensionType = dimensionType;
            // CraftBukkit end
            this.levelId = s;
            this.levelDirectory = new LevelStorageSource.LevelDirectory(LevelStorageSource.this.baseDir.resolve(s));
            this.lock = DirectoryLock.create(this.levelDirectory.path());
        }

        public String getLevelId() {
            return this.levelId;
        }

        public Path getLevelPath(LevelResource savePath) {
            Map<LevelResource, Path> map = this.resources; // CraftBukkit - decompile error
            LevelStorageSource.LevelDirectory convertable_b = this.levelDirectory;

            Objects.requireNonNull(this.levelDirectory);
            return (Path) map.computeIfAbsent(savePath, convertable_b::resourcePath);
        }

        public Path getDimensionPath(ResourceKey<Level> key) {
            return LevelStorageSource.getStorageFolder(this.levelDirectory.path(), this.dimensionType); // CraftBukkit
        }

        private void checkLock() {
            if (!this.lock.isValid()) {
                throw new IllegalStateException("Lock is no longer valid");
            }
        }

        public PlayerDataStorage createPlayerStorage() {
            this.checkLock();
            return new PlayerDataStorage(this, LevelStorageSource.this.fixerUpper);
        }

        @Nullable
        public LevelSummary getSummary() {
            this.checkLock();
            return (LevelSummary) LevelStorageSource.this.readLevelData(this.levelDirectory, LevelStorageSource.this.levelSummaryReader(this.levelDirectory, false));
        }

        @Nullable
        public WorldData getDataTag(DynamicOps<Tag> ops, DataPackConfig dataPackSettings, Lifecycle lifecycle) {
            this.checkLock();
            return (WorldData) LevelStorageSource.this.readLevelData(this.levelDirectory, LevelStorageSource.getLevelData(ops, dataPackSettings, lifecycle));
        }

        @Nullable
        public DataPackConfig getDataPacks() {
            this.checkLock();
            return (DataPackConfig) LevelStorageSource.this.readLevelData(this.levelDirectory, LevelStorageSource::getDataPacks);
        }

        public void saveDataTag(RegistryAccess registryManager, WorldData saveProperties) {
            this.saveDataTag(registryManager, saveProperties, (CompoundTag) null);
        }

        public void saveDataTag(RegistryAccess registryManager, WorldData saveProperties, @Nullable CompoundTag nbt) {
            File file = this.levelDirectory.path().toFile();
            CompoundTag nbttagcompound1 = saveProperties.createTag(registryManager, nbt);
            CompoundTag nbttagcompound2 = new CompoundTag();

            nbttagcompound2.put("Data", nbttagcompound1);

            try {
                File file1 = File.createTempFile("level", ".dat", file);

                NbtIo.writeCompressed(nbttagcompound2, file1);
                File file2 = this.levelDirectory.oldDataFile().toFile();
                File file3 = this.levelDirectory.dataFile().toFile();

                Util.safeReplaceFile(file3, file1, file2);
            } catch (Exception exception) {
                LevelStorageSource.LOGGER.error("Failed to save level {}", file, exception);
            }

        }

        public Optional<Path> getIconFile() {
            return !this.lock.isValid() ? Optional.empty() : Optional.of(this.levelDirectory.iconFile());
        }

        public void deleteLevel() throws IOException {
            this.checkLock();
            final Path path = this.levelDirectory.lockFile();

            LevelStorageSource.LOGGER.info("Deleting level {}", this.levelId);
            int i = 1;

            while (i <= 5) {
                LevelStorageSource.LOGGER.info("Attempt {}...", i);

                try {
                    Files.walkFileTree(this.levelDirectory.path(), new SimpleFileVisitor<Path>() {
                        public FileVisitResult visitFile(Path path1, BasicFileAttributes basicfileattributes) throws IOException {
                            if (!path1.equals(path)) {
                                LevelStorageSource.LOGGER.debug("Deleting {}", path1);
                                Files.delete(path1);
                            }

                            return FileVisitResult.CONTINUE;
                        }

                        public FileVisitResult postVisitDirectory(Path path1, IOException ioexception) throws IOException {
                            if (ioexception != null) {
                                throw ioexception;
                            } else {
                                if (path1.equals(LevelStorageAccess.this.levelDirectory.path())) {
                                    LevelStorageAccess.this.lock.close();
                                    Files.deleteIfExists(path);
                                }

                                Files.delete(path1);
                                return FileVisitResult.CONTINUE;
                            }
                        }
                    });
                    break;
                } catch (IOException ioexception) {
                    if (i >= 5) {
                        throw ioexception;
                    }

                    LevelStorageSource.LOGGER.warn("Failed to delete {}", this.levelDirectory.path(), ioexception);

                    try {
                        Thread.sleep(500L);
                    } catch (InterruptedException interruptedexception) {
                        ;
                    }

                    ++i;
                }
            }

        }

        public void renameLevel(String name) throws IOException {
            this.checkLock();
            Path path = this.levelDirectory.dataFile();

            if (Files.exists(path, new LinkOption[0])) {
                CompoundTag nbttagcompound = NbtIo.readCompressed(path.toFile());
                CompoundTag nbttagcompound1 = nbttagcompound.getCompound("Data");

                nbttagcompound1.putString("LevelName", name);
                NbtIo.writeCompressed(nbttagcompound, path.toFile());
            }

        }

        public long makeWorldBackup() throws IOException {
            this.checkLock();
            String s = LocalDateTime.now().format(LevelStorageSource.FORMATTER);
            String s1 = s + "_" + this.levelId;
            Path path = LevelStorageSource.this.getBackupPath();

            try {
                Files.createDirectories(Files.exists(path, new LinkOption[0]) ? path.toRealPath() : path);
            } catch (IOException ioexception) {
                throw new RuntimeException(ioexception);
            }

            Path path1 = path.resolve(FileUtil.findAvailableName(path, s1, ".zip"));
            final ZipOutputStream zipoutputstream = new ZipOutputStream(new BufferedOutputStream(Files.newOutputStream(path1)));

            try {
                final Path path2 = Paths.get(this.levelId);

                Files.walkFileTree(this.levelDirectory.path(), new SimpleFileVisitor<Path>() {
                    public FileVisitResult visitFile(Path path3, BasicFileAttributes basicfileattributes) throws IOException {
                        if (path3.endsWith("session.lock")) {
                            return FileVisitResult.CONTINUE;
                        } else {
                            String s2 = path2.resolve(LevelStorageAccess.this.levelDirectory.path().relativize(path3)).toString().replace('\\', '/');
                            ZipEntry zipentry = new ZipEntry(s2);

                            zipoutputstream.putNextEntry(zipentry);
                            com.google.common.io.Files.asByteSource(path3.toFile()).copyTo(zipoutputstream);
                            zipoutputstream.closeEntry();
                            return FileVisitResult.CONTINUE;
                        }
                    }
                });
            } catch (Throwable throwable) {
                try {
                    zipoutputstream.close();
                } catch (Throwable throwable1) {
                    throwable.addSuppressed(throwable1);
                }

                throw throwable;
            }

            zipoutputstream.close();
            return Files.size(path1);
        }

        public void close() throws IOException {
            this.lock.close();
        }
    }
}
