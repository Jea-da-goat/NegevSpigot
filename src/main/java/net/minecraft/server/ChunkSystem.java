package net.minecraft.server;

import ca.spottedleaf.concurrentutil.executor.standard.PrioritisedExecutor;
import com.destroystokyo.paper.util.SneakyThrow;
import com.mojang.datafixers.util.Either;
import com.mojang.logging.LogUtils;
import io.papermc.paper.util.CoordinateUtils;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkStatus;
import net.minecraft.world.level.chunk.LevelChunk;
import org.bukkit.Bukkit;
import org.slf4j.Logger;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

public final class ChunkSystem {

    private static final Logger LOGGER = LogUtils.getLogger();

    public static void scheduleChunkTask(final ServerLevel level, final int chunkX, final int chunkZ, final Runnable run) {
        scheduleChunkTask(level, chunkX, chunkZ, run, PrioritisedExecutor.Priority.NORMAL);
    }

    public static void scheduleChunkTask(final ServerLevel level, final int chunkX, final int chunkZ, final Runnable run, final PrioritisedExecutor.Priority priority) {
        level.chunkSource.mainThreadProcessor.execute(run);
    }

    public static void scheduleChunkLoad(final ServerLevel level, final int chunkX, final int chunkZ, final boolean gen,
                                         final ChunkStatus toStatus, final boolean addTicket, final PrioritisedExecutor.Priority priority,
                                         final Consumer<ChunkAccess> onComplete) {
        if (gen) {
            scheduleChunkLoad(level, chunkX, chunkZ, toStatus, addTicket, priority, onComplete);
            return;
        }
        scheduleChunkLoad(level, chunkX, chunkZ, ChunkStatus.EMPTY, addTicket, priority, (final ChunkAccess chunk) -> {
            if (chunk == null) {
                onComplete.accept(null);
            } else {
                if (chunk.getStatus().isOrAfter(toStatus)) {
                    scheduleChunkLoad(level, chunkX, chunkZ, toStatus, addTicket, priority, onComplete);
                } else {
                    onComplete.accept(null);
                }
            }
        });
    }

    static final TicketType<Long> CHUNK_LOAD = TicketType.create("chunk_load", Long::compareTo);

    // Paper start - priority
    private static int getPriorityBoost(final PrioritisedExecutor.Priority priority) {
        if (priority.isLowerOrEqualPriority(PrioritisedExecutor.Priority.NORMAL)) {
            return 0;
        }

        int dist = PrioritisedExecutor.Priority.BLOCKING.ordinal() - PrioritisedExecutor.Priority.NORMAL.ordinal();


        return (net.minecraft.server.level.DistanceManager.URGENT_PRIORITY * (priority.ordinal() - PrioritisedExecutor.Priority.NORMAL.ordinal())) / dist;
    }
    // Paper end - priority

    private static long chunkLoadCounter = 0L;
    public static void scheduleChunkLoad(final ServerLevel level, final int chunkX, final int chunkZ, final ChunkStatus toStatus,
                                         final boolean addTicket, final PrioritisedExecutor.Priority priority, final Consumer<ChunkAccess> onComplete) {
        if (!Bukkit.isPrimaryThread()) {
            scheduleChunkTask(level, chunkX, chunkZ, () -> {
                scheduleChunkLoad(level, chunkX, chunkZ, toStatus, addTicket, priority, onComplete);
            }, priority);
            return;
        }

        final int minLevel = 33 + ChunkStatus.getDistance(toStatus);
        final Long chunkReference = addTicket ? Long.valueOf(++chunkLoadCounter) : null;
        final ChunkPos chunkPos = new ChunkPos(chunkX, chunkZ);
        final int priorityBoost = getPriorityBoost(priority);

        if (addTicket) {
            level.chunkSource.addTicketAtLevel(CHUNK_LOAD, chunkPos, minLevel, chunkReference);
        }
        level.chunkSource.runDistanceManagerUpdates();

        if (priorityBoost == net.minecraft.server.level.DistanceManager.URGENT_PRIORITY) {
            level.chunkSource.markUrgent(chunkPos);
        } else if (priorityBoost != 0) {
            level.chunkSource.markHighPriority(chunkPos, priorityBoost);
        }

        final Consumer<ChunkAccess> loadCallback = (final ChunkAccess chunk) -> {
            try {
                if (onComplete != null) {
                    onComplete.accept(chunk);
                }
            } catch (final ThreadDeath death) {
                throw death;
            } catch (final Throwable thr) {
                LOGGER.error("Exception handling chunk load callback", thr);
                SneakyThrow.sneaky(thr);
            } finally {
                if (addTicket) {
                    level.chunkSource.addTicketAtLevel(TicketType.UNKNOWN, chunkPos, minLevel, chunkPos);
                    level.chunkSource.removeTicketAtLevel(CHUNK_LOAD, chunkPos, minLevel, chunkReference);
                }
                if (priorityBoost == net.minecraft.server.level.DistanceManager.URGENT_PRIORITY) {
                    level.chunkSource.clearUrgent(chunkPos);
                } else if (priorityBoost != 0) {
                    level.chunkSource.clearPriorityTickets(chunkPos);
                }
            }
        };

        final ChunkHolder holder = level.chunkSource.chunkMap.getUpdatingChunkIfPresent(CoordinateUtils.getChunkKey(chunkX, chunkZ));

        if (holder == null || holder.getTicketLevel() > minLevel) {
            loadCallback.accept(null);
            return;
        }

        final CompletableFuture<Either<ChunkAccess, ChunkHolder.ChunkLoadingFailure>> loadFuture = holder.getOrScheduleFuture(toStatus, level.chunkSource.chunkMap);

        if (loadFuture.isDone()) {
            loadCallback.accept(loadFuture.join().left().orElse(null));
            return;
        }

        loadFuture.whenCompleteAsync((final Either<ChunkAccess, ChunkHolder.ChunkLoadingFailure> either, final Throwable thr) -> {
            if (thr != null) {
                loadCallback.accept(null);
                return;
            }
            loadCallback.accept(either.left().orElse(null));
        }, (final Runnable r) -> {
            scheduleChunkTask(level, chunkX, chunkZ, r, PrioritisedExecutor.Priority.HIGHEST);
        });
    }

    public static void scheduleTickingState(final ServerLevel level, final int chunkX, final int chunkZ,
                                            final ChunkHolder.FullChunkStatus toStatus, final boolean addTicket,
                                            final PrioritisedExecutor.Priority priority, final Consumer<LevelChunk> onComplete) {
        if (toStatus == ChunkHolder.FullChunkStatus.INACCESSIBLE) {
            throw new IllegalArgumentException("Cannot wait for INACCESSIBLE status");
        }

        if (!Bukkit.isPrimaryThread()) {
            scheduleChunkTask(level, chunkX, chunkZ, () -> {
                scheduleTickingState(level, chunkX, chunkZ, toStatus, addTicket, priority, onComplete);
            }, priority);
            return;
        }

        final int minLevel = 33 - (toStatus.ordinal() - 1);
        final int radius = toStatus.ordinal() - 1;
        final Long chunkReference = addTicket ? Long.valueOf(++chunkLoadCounter) : null;
        final ChunkPos chunkPos = new ChunkPos(chunkX, chunkZ);
        final int priorityBoost = getPriorityBoost(priority);

        if (addTicket) {
            level.chunkSource.addTicketAtLevel(CHUNK_LOAD, chunkPos, minLevel, chunkReference);
        }
        level.chunkSource.runDistanceManagerUpdates();

        if (priorityBoost != 0) {
            level.chunkSource.markAreaHighPriority(chunkPos, priorityBoost, radius);
        }

        final Consumer<LevelChunk> loadCallback = (final LevelChunk chunk) -> {
            try {
                if (onComplete != null) {
                    onComplete.accept(chunk);
                }
            } catch (final ThreadDeath death) {
                throw death;
            } catch (final Throwable thr) {
                LOGGER.error("Exception handling chunk load callback", thr);
                SneakyThrow.sneaky(thr);
            } finally {
                if (addTicket) {
                    level.chunkSource.addTicketAtLevel(TicketType.UNKNOWN, chunkPos, minLevel, chunkPos);
                    level.chunkSource.removeTicketAtLevel(CHUNK_LOAD, chunkPos, minLevel, chunkReference);
                }
                if (priorityBoost != 0) {
                    level.chunkSource.clearAreaPriorityTickets(chunkPos, radius);
                }
            }
        };

        final ChunkHolder holder = level.chunkSource.chunkMap.getUpdatingChunkIfPresent(CoordinateUtils.getChunkKey(chunkX, chunkZ));

        if (holder == null || holder.getTicketLevel() > minLevel) {
            loadCallback.accept(null);
            return;
        }

        final CompletableFuture<Either<LevelChunk, ChunkHolder.ChunkLoadingFailure>> tickingState;
        switch (toStatus) {
            case BORDER: {
                tickingState = holder.getFullChunkFuture();
                break;
            }
            case TICKING: {
                tickingState = holder.getTickingChunkFuture();
                break;
            }
            case ENTITY_TICKING: {
                tickingState = holder.getEntityTickingChunkFuture();
                break;
            }
            default: {
                throw new IllegalStateException("Cannot reach here");
            }
        }

        if (tickingState.isDone()) {
            loadCallback.accept(tickingState.join().left().orElse(null));
            return;
        }

        tickingState.whenCompleteAsync((final Either<LevelChunk, ChunkHolder.ChunkLoadingFailure> either, final Throwable thr) -> {
            if (thr != null) {
                loadCallback.accept(null);
                return;
            }
            loadCallback.accept(either.left().orElse(null));
        }, (final Runnable r) -> {
            scheduleChunkTask(level, chunkX, chunkZ, r, PrioritisedExecutor.Priority.HIGHEST);
        });
    }

    public static List<ChunkHolder> getVisibleChunkHolders(final ServerLevel level) {
        if (Bukkit.isPrimaryThread()) {
            return level.chunkSource.chunkMap.updatingChunks.getVisibleValuesCopy();
        }
        synchronized (level.chunkSource.chunkMap.updatingChunks) {
            return level.chunkSource.chunkMap.updatingChunks.getVisibleValuesCopy();
        }
    }

    public static List<ChunkHolder> getUpdatingChunkHolders(final ServerLevel level) {
        return level.chunkSource.chunkMap.updatingChunks.getUpdatingValuesCopy();
    }

    public static int getVisibleChunkHolderCount(final ServerLevel level) {
        return level.chunkSource.chunkMap.updatingChunks.getVisibleMap().size();
    }

    public static int getUpdatingChunkHolderCount(final ServerLevel level) {
        return level.chunkSource.chunkMap.updatingChunks.getUpdatingMap().size();
    }

    public static boolean hasAnyChunkHolders(final ServerLevel level) {
        return getUpdatingChunkHolderCount(level) != 0;
    }

    public static void onEntityPreAdd(final ServerLevel level, final Entity entity) {
        if (net.minecraft.server.level.ChunkMap.checkDupeUUID(level, entity)) {
            return;
        }
    }

    public static void onChunkHolderCreate(final ServerLevel level, final ChunkHolder holder) {
        final ChunkMap chunkMap = level.chunkSource.chunkMap;
        for (int index = 0, len = chunkMap.regionManagers.size(); index < len; ++index) {
            chunkMap.regionManagers.get(index).addChunk(holder.pos.x, holder.pos.z);
        }
    }

    public static void onChunkHolderDelete(final ServerLevel level, final ChunkHolder holder) {
        final ChunkMap chunkMap = level.chunkSource.chunkMap;
        for (int index = 0, len = chunkMap.regionManagers.size(); index < len; ++index) {
            chunkMap.regionManagers.get(index).removeChunk(holder.pos.x, holder.pos.z);
        }
    }

    public static void onChunkBorder(LevelChunk chunk, ChunkHolder holder) {
        chunk.playerChunk = holder;
    }

    public static void onChunkNotBorder(LevelChunk chunk, ChunkHolder holder) {

    }

    public static void onChunkTicking(LevelChunk chunk, ChunkHolder holder) {
        chunk.level.getChunkSource().tickingChunks.add(chunk);
    }

    public static void onChunkNotTicking(LevelChunk chunk, ChunkHolder holder) {
        chunk.level.getChunkSource().tickingChunks.remove(chunk);
    }

    public static void onChunkEntityTicking(LevelChunk chunk, ChunkHolder holder) {
        chunk.level.getChunkSource().entityTickingChunks.add(chunk);
    }

    public static void onChunkNotEntityTicking(LevelChunk chunk, ChunkHolder holder) {
        chunk.level.getChunkSource().entityTickingChunks.remove(chunk);
    }

    private ChunkSystem() {
        throw new RuntimeException();
    }
}
