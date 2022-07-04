package net.minecraft.world.level.gameevent;

import java.util.function.Consumer;
import javax.annotation.Nullable;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkStatus;

public class DynamicGameEventListener<T extends GameEventListener> {
    private T listener;
    @Nullable
    private SectionPos lastSection;

    public DynamicGameEventListener(T listener) {
        this.listener = listener;
    }

    public void add(ServerLevel world) {
        this.move(world);
    }

    public void updateListener(T listener, @Nullable Level world) {
        T gameEventListener = this.listener;
        if (gameEventListener != listener) {
            if (world instanceof ServerLevel) {
                ServerLevel serverLevel = (ServerLevel)world;
                ifChunkExists(serverLevel, this.lastSection, (dispatcher) -> {
                    dispatcher.unregister(gameEventListener);
                });
                ifChunkExists(serverLevel, this.lastSection, (dispatcher) -> {
                    dispatcher.register(listener);
                });
            }

            this.listener = listener;
        }
    }

    public T getListener() {
        return this.listener;
    }

    public void remove(ServerLevel world) {
        ifChunkExists(world, this.lastSection, (dispatcher) -> {
            dispatcher.unregister(this.listener);
        });
    }

    public void move(ServerLevel world) {
        this.listener.getListenerSource().getPosition(world).map(SectionPos::of).ifPresent((sectionPos) -> {
            if (this.lastSection == null || !this.lastSection.equals(sectionPos)) {
                ifChunkExists(world, this.lastSection, (dispatcher) -> {
                    dispatcher.unregister(this.listener);
                });
                this.lastSection = sectionPos;
                ifChunkExists(world, this.lastSection, (dispatcher) -> {
                    dispatcher.register(this.listener);
                });
            }

        });
    }

    private static void ifChunkExists(LevelReader world, @Nullable SectionPos sectionPos, Consumer<GameEventDispatcher> dispatcherConsumer) {
        if (sectionPos != null) {
            ChunkAccess chunkAccess = world.getChunkIfLoadedImmediately(sectionPos.getX(), sectionPos.getZ()); // Paper - can cause sync loads while completing a chunk, resulting in deadlock
            if (chunkAccess != null) {
                dispatcherConsumer.accept(chunkAccess.getEventDispatcher(sectionPos.y()));
            }

        }
    }
}
