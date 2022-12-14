package net.minecraft.server.commands;

import com.mojang.brigadier.CommandDispatcher;
import java.util.Collection;
import java.util.Collections;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.AngleArgument;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;

public class SetSpawnCommand {
    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("spawnpoint").requires((source) -> {
            return source.hasPermission(2);
        }).executes((context) -> {
            return setSpawn(context.getSource(), Collections.singleton(context.getSource().getPlayerOrException()), new BlockPos(context.getSource().getPosition()), 0.0F);
        }).then(Commands.argument("targets", EntityArgument.players()).executes((context) -> {
            return setSpawn(context.getSource(), EntityArgument.getPlayers(context, "targets"), new BlockPos(context.getSource().getPosition()), 0.0F);
        }).then(Commands.argument("pos", BlockPosArgument.blockPos()).executes((context) -> {
            return setSpawn(context.getSource(), EntityArgument.getPlayers(context, "targets"), BlockPosArgument.getSpawnablePos(context, "pos"), 0.0F);
        }).then(Commands.argument("angle", AngleArgument.angle()).executes((context) -> {
            return setSpawn(context.getSource(), EntityArgument.getPlayers(context, "targets"), BlockPosArgument.getSpawnablePos(context, "pos"), AngleArgument.getAngle(context, "angle"));
        })))));
    }

    private static int setSpawn(CommandSourceStack source, Collection<ServerPlayer> targets, BlockPos pos, float angle) {
        ResourceKey<Level> resourceKey = source.getLevel().dimension();

        final Collection<ServerPlayer> actualTargets = new java.util.ArrayList<>(); // Paper
        for(ServerPlayer serverPlayer : targets) {
            // Paper start - PlayerSetSpawnEvent
            if (serverPlayer.setRespawnPosition(resourceKey, pos, angle, true, false, com.destroystokyo.paper.event.player.PlayerSetSpawnEvent.Cause.COMMAND)) {
                actualTargets.add(serverPlayer);
            }
            // Paper end
        }
        // Paper start
        if (actualTargets.isEmpty()) {
            return 0;
        } else {
            targets = actualTargets;
        }
        // Paper end

        String string = resourceKey.location().toString();
        if (targets.size() == 1) {
            source.sendSuccess(Component.translatable("commands.spawnpoint.success.single", pos.getX(), pos.getY(), pos.getZ(), angle, string, targets.iterator().next().getDisplayName()), true);
        } else {
            source.sendSuccess(Component.translatable("commands.spawnpoint.success.multiple", pos.getX(), pos.getY(), pos.getZ(), angle, string, targets.size()), true);
        }

        return targets.size();
    }
}
