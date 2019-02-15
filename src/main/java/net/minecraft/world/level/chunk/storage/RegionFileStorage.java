package net.minecraft.world.level.chunk.storage;

import it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectIterator;
import java.io.DataInput;
import java.io.DataInputStream;
import java.io.DataOutput;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.annotation.Nullable;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.StreamTagVisitor;
import net.minecraft.nbt.Tag;
import net.minecraft.util.ExceptionCollector;
import net.minecraft.world.level.ChunkPos;

public class RegionFileStorage implements AutoCloseable {

    public static final String ANVIL_EXTENSION = ".mca";
    private static final int MAX_CACHE_SIZE = 256;
    public final Long2ObjectLinkedOpenHashMap<RegionFile> regionCache = new Long2ObjectLinkedOpenHashMap();
    private final Path folder;
    private final boolean sync;

    RegionFileStorage(Path directory, boolean dsync) {
        this.folder = directory;
        this.sync = dsync;
    }

    // Paper start
    public synchronized RegionFile getRegionFileIfLoaded(ChunkPos chunkcoordintpair) {
        return this.regionCache.getAndMoveToFirst(ChunkPos.asLong(chunkcoordintpair.getRegionX(), chunkcoordintpair.getRegionZ()));
    }

    public synchronized boolean chunkExists(ChunkPos pos) throws IOException {
        RegionFile regionfile = getRegionFile(pos, true);

        return regionfile != null ? regionfile.hasChunk(pos) : false;
    }

    public synchronized RegionFile getRegionFile(ChunkPos chunkcoordintpair, boolean existingOnly) throws IOException { // CraftBukkit
        return this.getRegionFile(chunkcoordintpair, existingOnly, false);
    }
    public synchronized RegionFile getRegionFile(ChunkPos chunkcoordintpair, boolean existingOnly, boolean lock) throws IOException {
        // Paper end
        long i = ChunkPos.asLong(chunkcoordintpair.getRegionX(), chunkcoordintpair.getRegionZ());
        RegionFile regionfile = (RegionFile) this.regionCache.getAndMoveToFirst(i);

        if (regionfile != null) {
            // Paper start
            if (lock) {
                // must be in this synchronized block
                regionfile.fileLock.lock();
            }
            // Paper end
            return regionfile;
        } else {
            if (this.regionCache.size() >= io.papermc.paper.configuration.GlobalConfiguration.get().misc.regionFileCacheSize) { // Paper - configurable
                ((RegionFile) this.regionCache.removeLast()).close();
            }

            Files.createDirectories(this.folder);
            Path path = this.folder;
            int j = chunkcoordintpair.getRegionX();
            Path path1 = path.resolve("r." + j + "." + chunkcoordintpair.getRegionZ() + ".mca");
            if (existingOnly && !Files.exists(path1)) return null; // CraftBukkit
            RegionFile regionfile1 = new RegionFile(path1, this.folder, this.sync);

            this.regionCache.putAndMoveToFirst(i, regionfile1);
            // Paper start
            if (lock) {
                // must be in this synchronized block
                regionfile1.fileLock.lock();
            }
            // Paper end
            return regionfile1;
        }
    }

    // Paper start
    private static void printOversizedLog(String msg, Path file, int x, int z) {
        org.apache.logging.log4j.LogManager.getLogger().fatal(msg + " (" + file.toString().replaceAll(".+[\\\\/]", "") + " - " + x + "," + z + ") Go clean it up to remove this message. /minecraft:tp " + (x<<4)+" 128 "+(z<<4) + " - DO NOT REPORT THIS TO PAPER - You may ask for help on Discord, but do not file an issue. These error messages can not be removed.");
    }

    private static final int DEFAULT_SIZE_THRESHOLD = 1024 * 8;
    private static final int OVERZEALOUS_TOTAL_THRESHOLD = 1024 * 64;
    private static final int OVERZEALOUS_THRESHOLD = 1024;
    private static int SIZE_THRESHOLD = DEFAULT_SIZE_THRESHOLD;
    private static void resetFilterThresholds() {
        SIZE_THRESHOLD = Math.max(1024 * 4, Integer.getInteger("Paper.FilterThreshhold", DEFAULT_SIZE_THRESHOLD));
    }
    static {
        resetFilterThresholds();
    }

    static boolean isOverzealous() {
        return SIZE_THRESHOLD == OVERZEALOUS_THRESHOLD;
    }


    private static CompoundTag readOversizedChunk(RegionFile regionfile, ChunkPos chunkCoordinate) throws IOException {
        synchronized (regionfile) {
            try (DataInputStream datainputstream = regionfile.getChunkDataInputStream(chunkCoordinate)) {
                CompoundTag oversizedData = regionfile.getOversizedData(chunkCoordinate.x, chunkCoordinate.z);
                CompoundTag chunk = NbtIo.read((DataInput) datainputstream);
                if (oversizedData == null) {
                    return chunk;
                }
                CompoundTag oversizedLevel = oversizedData.getCompound("Level");

                mergeChunkList(chunk.getCompound("Level"), oversizedLevel, "Entities", "Entities");
                mergeChunkList(chunk.getCompound("Level"), oversizedLevel, "TileEntities", "TileEntities");

                return chunk;
            } catch (Throwable throwable) {
                throwable.printStackTrace();
                throw throwable;
            }
        }
    }

    private static void mergeChunkList(CompoundTag level, CompoundTag oversizedLevel, String key, String oversizedKey) {
        ListTag levelList = level.getList(key, 10);
        ListTag oversizedList = oversizedLevel.getList(oversizedKey, 10);

        if (!oversizedList.isEmpty()) {
            levelList.addAll(oversizedList);
            level.put(key, levelList);
        }
    }

    private static int getNBTSize(Tag nbtBase) {
        DataOutputStream test = new DataOutputStream(new org.apache.commons.io.output.NullOutputStream());
        try {
            nbtBase.write(test);
            return test.size();
        } catch (IOException e) {
            e.printStackTrace();
            return 0;
        }
    }

    // Paper End

    @Nullable
    public CompoundTag read(ChunkPos pos) throws IOException {
        // CraftBukkit start - SPIGOT-5680: There's no good reason to preemptively create files on read, save that for writing
        RegionFile regionfile = this.getRegionFile(pos, true, true); // Paper
        if (regionfile == null) {
            return null;
        }
        // CraftBukkit end
        try { // Paper
        DataInputStream datainputstream = regionfile.getChunkDataInputStream(pos);

        // Paper start
        if (regionfile.isOversized(pos.x, pos.z)) {
            printOversizedLog("Loading Oversized Chunk!", regionfile.regionFile, pos.x, pos.z);
            return readOversizedChunk(regionfile, pos);
        }
        // Paper end
        CompoundTag nbttagcompound;
        label43:
        {
            try {
                if (datainputstream != null) {
                    nbttagcompound = NbtIo.read((DataInput) datainputstream);
                    break label43;
                }

                nbttagcompound = null;
            } catch (Throwable throwable) {
                if (datainputstream != null) {
                    try {
                        datainputstream.close();
                    } catch (Throwable throwable1) {
                        throwable.addSuppressed(throwable1);
                    }
                }

                throw throwable;
            }

            if (datainputstream != null) {
                datainputstream.close();
            }

            return nbttagcompound;
        }

        if (datainputstream != null) {
            datainputstream.close();
        }

        return nbttagcompound;
        } finally { // Paper start
            regionfile.fileLock.unlock();
        } // Paper end
    }

    public void scanChunk(ChunkPos chunkcoordintpair, StreamTagVisitor streamtagvisitor) throws IOException {
        // CraftBukkit start - SPIGOT-5680: There's no good reason to preemptively create files on read, save that for writing
        RegionFile regionfile = this.getRegionFile(chunkcoordintpair, true);
        if (regionfile == null) {
            return;
        }
        // CraftBukkit end
        DataInputStream datainputstream = regionfile.getChunkDataInputStream(chunkcoordintpair);

        try {
            if (datainputstream != null) {
                NbtIo.parse(datainputstream, streamtagvisitor);
            }
        } catch (Throwable throwable) {
            if (datainputstream != null) {
                try {
                    datainputstream.close();
                } catch (Throwable throwable1) {
                    throwable.addSuppressed(throwable1);
                }
            }

            throw throwable;
        }

        if (datainputstream != null) {
            datainputstream.close();
        }

    }

    protected void write(ChunkPos pos, @Nullable CompoundTag nbt) throws IOException {
        RegionFile regionfile = this.getRegionFile(pos, false, true); // CraftBukkit // Paper
        try { // Paper
        int attempts = 0; Exception laste = null; while (attempts++ < 5) { try { // Paper

        if (nbt == null) {
            regionfile.clear(pos);
        } else {
            DataOutputStream dataoutputstream = regionfile.getChunkDataOutputStream(pos);

            try {
                NbtIo.write(nbt, (DataOutput) dataoutputstream);
                regionfile.setOversized(pos.x, pos.z, false); // Paper - We don't do this anymore, mojang stores differently, but clear old meta flag if it exists to get rid of our own meta file once last oversized is gone
            } catch (Throwable throwable) {
                if (dataoutputstream != null) {
                    try {
                        dataoutputstream.close();
                    } catch (Throwable throwable1) {
                        throwable.addSuppressed(throwable1);
                    }
                }

                throw throwable;
            }

            if (dataoutputstream != null) {
                dataoutputstream.close();
            }
        }
        // Paper start
        return;
        } catch (Exception ex)  {
            laste = ex;
        }
        }

        if (laste != null) {
            com.destroystokyo.paper.exception.ServerInternalException.reportInternalException(laste);
            net.minecraft.server.MinecraftServer.LOGGER.error("Failed to save chunk " + pos, laste);
        }
        // Paper end
        } finally { // Paper start
            regionfile.fileLock.unlock();
        } // Paper end
    }

    public synchronized void close() throws IOException { // Paper -> synchronized
        ExceptionCollector<IOException> exceptionsuppressor = new ExceptionCollector<>();
        ObjectIterator objectiterator = this.regionCache.values().iterator();

        while (objectiterator.hasNext()) {
            RegionFile regionfile = (RegionFile) objectiterator.next();

            try {
                regionfile.close();
            } catch (IOException ioexception) {
                exceptionsuppressor.add(ioexception);
            }
        }

        exceptionsuppressor.throwIfPresent();
    }

    public synchronized void flush() throws IOException { // Paper - synchronize
        ObjectIterator objectiterator = this.regionCache.values().iterator();

        while (objectiterator.hasNext()) {
            RegionFile regionfile = (RegionFile) objectiterator.next();

            regionfile.flush();
        }

    }
}
