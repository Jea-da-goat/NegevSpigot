package net.minecraft.server.level;

import com.mojang.datafixers.util.Pair;
import com.mojang.logging.LogUtils;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ObjectList;
import it.unimi.dsi.fastutil.objects.ObjectListIterator;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.IntSupplier;
import javax.annotation.Nullable;
import net.minecraft.Util;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.util.thread.ProcessorHandle;
import net.minecraft.util.thread.ProcessorMailbox;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.LightChunkGetter;
import net.minecraft.world.level.lighting.LevelLightEngine;
import org.slf4j.Logger;

public class ThreadedLevelLightEngine extends LevelLightEngine implements AutoCloseable {
    private static final Logger LOGGER = LogUtils.getLogger();
    private final ProcessorMailbox<Runnable> taskMailbox;
    // Paper start
    private static final int MAX_PRIORITIES = ChunkMap.MAX_CHUNK_DISTANCE + 2;

    static class ChunkLightQueue {
        public boolean shouldFastUpdate;
        java.util.ArrayDeque<Runnable> pre = new java.util.ArrayDeque<Runnable>();
        java.util.ArrayDeque<Runnable> post = new java.util.ArrayDeque<Runnable>();

        ChunkLightQueue(long chunk) {}
    }

    static class PendingLightTask {
        long chunkId;
        IntSupplier priority;
        Runnable pre;
        Runnable post;
        boolean fastUpdate;

        public PendingLightTask(long chunkId, IntSupplier priority, Runnable pre, Runnable post, boolean fastUpdate) {
            this.chunkId = chunkId;
            this.priority = priority;
            this.pre = pre;
            this.post = post;
            this.fastUpdate = fastUpdate;
        }
    }


    // Retain the chunks priority level for queued light tasks
    class LightQueue {
        private int size = 0;
        private final it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap<ChunkLightQueue>[] buckets = new it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap[MAX_PRIORITIES];
        private final java.util.concurrent.ConcurrentLinkedQueue<PendingLightTask> pendingTasks = new java.util.concurrent.ConcurrentLinkedQueue<>();
        private final java.util.concurrent.ConcurrentLinkedQueue<Runnable> priorityChanges = new java.util.concurrent.ConcurrentLinkedQueue<>();

        private LightQueue() {
            for (int i = 0; i < buckets.length; i++) {
                buckets[i] = new it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap<>();
            }
        }

        public void changePriority(long pair, int currentPriority, int priority) {
            this.priorityChanges.add(() -> {
                ChunkLightQueue remove = this.buckets[currentPriority].remove(pair);
                if (remove != null) {
                    ChunkLightQueue existing = this.buckets[Math.max(1, priority)].put(pair, remove);
                    if (existing != null) {
                        remove.pre.addAll(existing.pre);
                        remove.post.addAll(existing.post);
                    }
                }
            });
        }

        public final void addChunk(long chunkId, IntSupplier priority, Runnable pre, Runnable post) {
            pendingTasks.add(new PendingLightTask(chunkId, priority, pre, post, true));
            tryScheduleUpdate();
        }

        public final void add(long chunkId, IntSupplier priority, ThreadedLevelLightEngine.TaskType type, Runnable run) {
            pendingTasks.add(new PendingLightTask(chunkId, priority, type == TaskType.PRE_UPDATE ? run : null, type == TaskType.POST_UPDATE ? run : null, false));
        }
        public final void add(PendingLightTask update) {
            int priority = update.priority.getAsInt();
            ChunkLightQueue lightQueue = this.buckets[priority].computeIfAbsent(update.chunkId, ChunkLightQueue::new);

            if (update.pre != null) {
                this.size++;
                lightQueue.pre.add(update.pre);
            }
            if (update.post != null) {
                this.size++;
                lightQueue.post.add(update.post);
            }
            if (update.fastUpdate) {
                lightQueue.shouldFastUpdate = true;
            }
        }

        public final boolean isEmpty() {
            return this.size == 0 && this.pendingTasks.isEmpty();
        }

        public final int size() {
            return this.size;
        }

        public boolean poll(java.util.List<Runnable> pre, java.util.List<Runnable> post) {
            PendingLightTask pending;
            while ((pending = pendingTasks.poll()) != null) {
                add(pending);
            }
            Runnable run;
            while ((run = priorityChanges.poll()) != null) {
                run.run();
            }
            boolean hasWork = false;
            it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap<ChunkLightQueue>[] buckets = this.buckets;
            int priority = 0;
            while (priority < MAX_PRIORITIES && !isEmpty()) {
                it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap<ChunkLightQueue> bucket = buckets[priority];
                if (bucket.isEmpty()) {
                    priority++;
                    if (hasWork) {
                        return true;
                    } else {
                        continue;
                    }
                }
                ChunkLightQueue queue = bucket.removeFirst();
                this.size -= queue.pre.size() + queue.post.size();
                pre.addAll(queue.pre);
                post.addAll(queue.post);
                queue.pre.clear();
                queue.post.clear();
                hasWork = true;
                if (queue.shouldFastUpdate) {
                    return true;
                }
            }
            return hasWork;
        }
    }

    final LightQueue queue = new LightQueue();
    // Paper end
    private final ChunkMap chunkMap; private final ChunkMap playerChunkMap; // Paper
    private final ProcessorHandle<ChunkTaskPriorityQueueSorter.Message<Runnable>> sorterMailbox;
    private volatile int taskPerBatch = 5;
    private final AtomicBoolean scheduled = new AtomicBoolean();

    public ThreadedLevelLightEngine(LightChunkGetter chunkProvider, ChunkMap chunkStorage, boolean hasBlockLight, ProcessorMailbox<Runnable> processor, ProcessorHandle<ChunkTaskPriorityQueueSorter.Message<Runnable>> executor) {
        super(chunkProvider, true, hasBlockLight);
        this.chunkMap = chunkStorage; this.playerChunkMap = chunkMap; // Paper
        this.sorterMailbox = executor;
        this.taskMailbox = processor;
    }

    @Override
    public void close() {
    }

    @Override
    public int runUpdates(int i, boolean doSkylight, boolean skipEdgeLightPropagation) {
        throw (UnsupportedOperationException)Util.pauseInIde(new UnsupportedOperationException("Ran automatically on a different thread!"));
    }

    @Override
    public void onBlockEmissionIncrease(BlockPos pos, int level) {
        throw (UnsupportedOperationException)Util.pauseInIde(new UnsupportedOperationException("Ran automatically on a different thread!"));
    }

    @Override
    public void checkBlock(BlockPos pos) {
        BlockPos blockPos = pos.immutable();
        this.addTask(SectionPos.blockToSectionCoord(pos.getX()), SectionPos.blockToSectionCoord(pos.getZ()), ThreadedLevelLightEngine.TaskType.POST_UPDATE, Util.name(() -> {
            super.checkBlock(blockPos);
        }, () -> {
            return "checkBlock " + blockPos;
        }));
    }

    protected void updateChunkStatus(ChunkPos pos) {
        this.addTask(pos.x, pos.z, () -> {
            return 0;
        }, ThreadedLevelLightEngine.TaskType.PRE_UPDATE, Util.name(() -> {
            super.retainData(pos, false);
            super.enableLightSources(pos, false);

            for(int i = this.getMinLightSection(); i < this.getMaxLightSection(); ++i) {
                super.queueSectionData(LightLayer.BLOCK, SectionPos.of(pos, i), (DataLayer)null, true);
                super.queueSectionData(LightLayer.SKY, SectionPos.of(pos, i), (DataLayer)null, true);
            }

            for(int j = this.levelHeightAccessor.getMinSection(); j < this.levelHeightAccessor.getMaxSection(); ++j) {
                super.updateSectionStatus(SectionPos.of(pos, j), true);
            }

        }, () -> {
            return "updateChunkStatus " + pos + " true";
        }));
    }

    @Override
    public void updateSectionStatus(SectionPos pos, boolean notReady) {
        this.addTask(pos.x(), pos.z(), () -> {
            return 0;
        }, ThreadedLevelLightEngine.TaskType.PRE_UPDATE, Util.name(() -> {
            super.updateSectionStatus(pos, notReady);
        }, () -> {
            return "updateSectionStatus " + pos + " " + notReady;
        }));
    }

    @Override
    public void enableLightSources(ChunkPos pos, boolean retainData) {
        this.addTask(pos.x, pos.z, ThreadedLevelLightEngine.TaskType.PRE_UPDATE, Util.name(() -> {
            super.enableLightSources(pos, retainData);
        }, () -> {
            return "enableLight " + pos + " " + retainData;
        }));
    }

    @Override
    public void queueSectionData(LightLayer lightType, SectionPos pos, @Nullable DataLayer nibbles, boolean nonEdge) {
        this.addTask(pos.x(), pos.z(), () -> {
            return 0;
        }, ThreadedLevelLightEngine.TaskType.PRE_UPDATE, Util.name(() -> {
            super.queueSectionData(lightType, pos, nibbles, nonEdge);
        }, () -> {
            return "queueData " + pos;
        }));
    }

    private void addTask(int x, int z, ThreadedLevelLightEngine.TaskType stage, Runnable task) {
        this.addTask(x, z, this.chunkMap.getChunkQueueLevel(ChunkPos.asLong(x, z)), stage, task);
    }

    private void addTask(int x, int z, IntSupplier completedLevelSupplier, ThreadedLevelLightEngine.TaskType stage, Runnable task) {
        // Paper start - replace method
        this.queue.add(ChunkPos.asLong(x, z), completedLevelSupplier, stage, task);
        // Paper end
    }

    @Override
    public void retainData(ChunkPos pos, boolean retainData) {
        this.addTask(pos.x, pos.z, () -> {
            return 0;
        }, ThreadedLevelLightEngine.TaskType.PRE_UPDATE, Util.name(() -> {
            super.retainData(pos, retainData);
        }, () -> {
            return "retainData " + pos;
        }));
    }

    public CompletableFuture<ChunkAccess> retainData(ChunkAccess chunk) {
        ChunkPos chunkPos = chunk.getPos();
        return CompletableFuture.supplyAsync(Util.name(() -> {
            super.retainData(chunkPos, true);
            return chunk;
        }, () -> {
            return "retainData: " + chunkPos;
        }), (task) -> {
            this.addTask(chunkPos.x, chunkPos.z, ThreadedLevelLightEngine.TaskType.PRE_UPDATE, task);
        });
    }

    public CompletableFuture<ChunkAccess> lightChunk(ChunkAccess chunk, boolean excludeBlocks) {
        ChunkPos chunkPos = chunk.getPos();
        // Paper start
        //ichunkaccess.b(false); // Don't need to disable this
        long pair = chunkPos.toLong();
        CompletableFuture<ChunkAccess> future = new CompletableFuture<>();
        IntSupplier prioritySupplier = playerChunkMap.getChunkQueueLevel(pair);
        boolean[] skippedPre = {false};
        this.queue.addChunk(pair, prioritySupplier, Util.name(() -> {
            // Paper end
            LevelChunkSection[] levelChunkSections = chunk.getSections();

            for(int i = 0; i < chunk.getSectionsCount(); ++i) {
                LevelChunkSection levelChunkSection = levelChunkSections[i];
                if (!levelChunkSection.hasOnlyAir()) {
                    int j = this.levelHeightAccessor.getSectionYFromSectionIndex(i);
                    super.updateSectionStatus(SectionPos.of(chunkPos, j), false);
                }
            }

            super.enableLightSources(chunkPos, true);
            if (!excludeBlocks) {
                chunk.getLights().forEach((pos) -> {
                    super.onBlockEmissionIncrease(pos, chunk.getLightEmission(pos));
                });
            }

        }, () -> {
            return "lightChunk " + chunkPos + " " + excludeBlocks;
            // Paper start  - merge the 2 together
        }), () -> {
            this.chunkMap.releaseLightTicket(chunkPos); // Paper - moved from below, we want to call this even when returning early
            if (skippedPre[0]) return; // Paper - future's already complete
            chunk.setLightCorrect(true);
            super.retainData(chunkPos, false);
            //this.chunkMap.releaseLightTicket(chunkPos); // Paper - moved up
            future.complete(chunk);
        });
        return future;
        // Paper end
    }

    public void tryScheduleUpdate() {
        if ((!this.queue.isEmpty() || super.hasLightWork()) && this.scheduled.compareAndSet(false, true)) { // Paper
            this.taskMailbox.tell(() -> {
                this.runUpdate();
                this.scheduled.set(false);
                tryScheduleUpdate(); // Paper - if we still have work to do, do it!
            });
        }

    }

    // Paper start - replace impl
    private final java.util.List<Runnable> pre = new java.util.ArrayList<>();
    private final java.util.List<Runnable> post = new java.util.ArrayList<>();
    private void runUpdate() {
        if (queue.poll(pre, post)) {
            pre.forEach(Runnable::run);
            pre.clear();
            super.runUpdates(Integer.MAX_VALUE, true, true);
            post.forEach(Runnable::run);
            post.clear();
        } else {
            // might have level updates to go still
            super.runUpdates(Integer.MAX_VALUE, true, true);
        }
        // Paper end
    }

    public void setTaskPerBatch(int taskBatchSize) {
        this.taskPerBatch = taskBatchSize;
    }

    static enum TaskType {
        PRE_UPDATE,
        POST_UPDATE;
    }
}
