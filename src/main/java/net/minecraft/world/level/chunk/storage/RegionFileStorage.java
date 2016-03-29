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
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.StreamTagVisitor;
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

    private RegionFile getRegionFile(ChunkPos chunkcoordintpair, boolean existingOnly) throws IOException { // CraftBukkit
        long i = ChunkPos.asLong(chunkcoordintpair.getRegionX(), chunkcoordintpair.getRegionZ());
        RegionFile regionfile = (RegionFile) this.regionCache.getAndMoveToFirst(i);

        if (regionfile != null) {
            return regionfile;
        } else {
            if (this.regionCache.size() >= 256) {
                ((RegionFile) this.regionCache.removeLast()).close();
            }

            Files.createDirectories(this.folder);
            Path path = this.folder;
            int j = chunkcoordintpair.getRegionX();
            Path path1 = path.resolve("r." + j + "." + chunkcoordintpair.getRegionZ() + ".mca");
            if (existingOnly && !Files.exists(path1)) return null; // CraftBukkit
            RegionFile regionfile1 = new RegionFile(path1, this.folder, this.sync);

            this.regionCache.putAndMoveToFirst(i, regionfile1);
            return regionfile1;
        }
    }

    @Nullable
    public CompoundTag read(ChunkPos pos) throws IOException {
        // CraftBukkit start - SPIGOT-5680: There's no good reason to preemptively create files on read, save that for writing
        RegionFile regionfile = this.getRegionFile(pos, true);
        if (regionfile == null) {
            return null;
        }
        // CraftBukkit end
        DataInputStream datainputstream = regionfile.getChunkDataInputStream(pos);

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
        RegionFile regionfile = this.getRegionFile(pos, false); // CraftBukkit

        if (nbt == null) {
            regionfile.clear(pos);
        } else {
            DataOutputStream dataoutputstream = regionfile.getChunkDataOutputStream(pos);

            try {
                NbtIo.write(nbt, (DataOutput) dataoutputstream);
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

    }

    public void close() throws IOException {
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

    public void flush() throws IOException {
        ObjectIterator objectiterator = this.regionCache.values().iterator();

        while (objectiterator.hasNext()) {
            RegionFile regionfile = (RegionFile) objectiterator.next();

            regionfile.flush();
        }

    }
}