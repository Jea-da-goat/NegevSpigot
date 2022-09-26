package net.minecraft.world.level.biome;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.mojang.logging.LogUtils;
import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import javax.annotation.Nullable;
import net.minecraft.Util;
import net.minecraft.core.Registry;
import net.minecraft.util.StringRepresentable;
import net.minecraft.util.random.Weight;
import net.minecraft.util.random.WeightedEntry;
import net.minecraft.util.random.WeightedRandomList;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import org.slf4j.Logger;

public class MobSpawnSettings {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final float DEFAULT_CREATURE_SPAWN_PROBABILITY = 0.1F;
    public static final WeightedRandomList<MobSpawnSettings.SpawnerData> EMPTY_MOB_LIST = WeightedRandomList.create();
    public static final MobSpawnSettings EMPTY = (new MobSpawnSettings.Builder()).build();
    public static final MapCodec<MobSpawnSettings> CODEC = RecordCodecBuilder.mapCodec((instance) -> {
        return instance.group(Codec.floatRange(0.0F, 0.9999999F).optionalFieldOf("creature_spawn_probability", 0.1F).forGetter((mobSpawnSettings) -> {
            return mobSpawnSettings.creatureGenerationProbability;
        }), Codec.simpleMap(MobCategory.CODEC, WeightedRandomList.codec(MobSpawnSettings.SpawnerData.CODEC).promotePartial(Util.prefix("Spawn data: ", LOGGER::error)), StringRepresentable.keys(MobCategory.values())).fieldOf("spawners").forGetter((mobSpawnSettings) -> {
            return mobSpawnSettings.spawners;
        }), Codec.simpleMap(Registry.ENTITY_TYPE.byNameCodec(), MobSpawnSettings.MobSpawnCost.CODEC, Registry.ENTITY_TYPE).fieldOf("spawn_costs").forGetter((mobSpawnSettings) -> {
            return mobSpawnSettings.mobSpawnCosts;
        })).apply(instance, MobSpawnSettings::new);
    });
    private final float creatureGenerationProbability;
    private final Map<MobCategory, WeightedRandomList<MobSpawnSettings.SpawnerData>> spawners;
    private final Map<EntityType<?>, MobSpawnSettings.MobSpawnCost> mobSpawnCosts;

    MobSpawnSettings(float creatureSpawnProbability, Map<MobCategory, WeightedRandomList<MobSpawnSettings.SpawnerData>> spawners, Map<EntityType<?>, MobSpawnSettings.MobSpawnCost> spawnCosts) {
        this.creatureGenerationProbability = creatureSpawnProbability;
        this.spawners = ImmutableMap.copyOf(spawners);
        this.mobSpawnCosts = ImmutableMap.copyOf(spawnCosts);
    }

    public WeightedRandomList<MobSpawnSettings.SpawnerData> getMobs(MobCategory spawnGroup) {
        return this.spawners.getOrDefault(spawnGroup, EMPTY_MOB_LIST);
    }

    @Nullable
    public MobSpawnSettings.MobSpawnCost getMobSpawnCost(EntityType<?> entityType) {
        return this.mobSpawnCosts.get(entityType);
    }

    public float getCreatureProbability() {
        return this.creatureGenerationProbability;
    }

    public static class Builder {
        private final Map<MobCategory, List<MobSpawnSettings.SpawnerData>> spawners = Stream.of(MobCategory.values()).collect(ImmutableMap.toImmutableMap((mobCategory) -> {
            return mobCategory;
        }, (mobCategory) -> {
            return Lists.newArrayList();
        }));
        private final Map<EntityType<?>, MobSpawnSettings.MobSpawnCost> mobSpawnCosts = Maps.newLinkedHashMap();
        private float creatureGenerationProbability = 0.1F;

        public MobSpawnSettings.Builder addSpawn(MobCategory spawnGroup, MobSpawnSettings.SpawnerData spawnEntry) {
            this.spawners.get(spawnGroup).add(spawnEntry);
            return this;
        }

        public MobSpawnSettings.Builder addMobCharge(EntityType<?> entityType, double mass, double gravityLimit) {
            this.mobSpawnCosts.put(entityType, new MobSpawnSettings.MobSpawnCost(gravityLimit, mass));
            return this;
        }

        public MobSpawnSettings.Builder creatureGenerationProbability(float probability) {
            this.creatureGenerationProbability = probability;
            return this;
        }

        public MobSpawnSettings build() {
            return new MobSpawnSettings(this.creatureGenerationProbability, this.spawners.entrySet().stream().collect(ImmutableMap.toImmutableMap(Map.Entry::getKey, (entry) -> {
                return WeightedRandomList.create(entry.getValue());
            })), ImmutableMap.copyOf(this.mobSpawnCosts));
        }
    }

    public static class MobSpawnCost {
        public static final Codec<MobSpawnSettings.MobSpawnCost> CODEC = RecordCodecBuilder.create((instance) -> {
            return instance.group(Codec.DOUBLE.fieldOf("energy_budget").forGetter((mobSpawnCost) -> {
                return mobSpawnCost.energyBudget;
            }), Codec.DOUBLE.fieldOf("charge").forGetter((mobSpawnCost) -> {
                return mobSpawnCost.charge;
            })).apply(instance, MobSpawnSettings.MobSpawnCost::new);
        });
        private final double energyBudget;
        private final double charge;

        MobSpawnCost(double gravityLimit, double mass) {
            this.energyBudget = gravityLimit;
            this.charge = mass;
        }

        public double getEnergyBudget() {
            return this.energyBudget;
        }

        public double getCharge() {
            return this.charge;
        }
    }

    public static class SpawnerData extends WeightedEntry.IntrusiveBase {
        public static final Codec<MobSpawnSettings.SpawnerData> CODEC = RecordCodecBuilder.create((instance) -> {
            return instance.group(Registry.ENTITY_TYPE.byNameCodec().fieldOf("type").forGetter((spawnerData) -> {
                return spawnerData.type;
            }), Weight.CODEC.fieldOf("weight").forGetter(WeightedEntry.IntrusiveBase::getWeight), Codec.INT.fieldOf("minCount").forGetter((spawnerData) -> {
                return spawnerData.minCount;
            }), Codec.INT.fieldOf("maxCount").forGetter((spawnerData) -> {
                return spawnerData.maxCount;
            })).apply(instance, MobSpawnSettings.SpawnerData::new);
        });
        public final EntityType<?> type;
        public final int minCount;
        public final int maxCount;

        public SpawnerData(EntityType<?> type, int weight, int minGroupSize, int maxGroupSize) {
            this(type, Weight.of(weight), minGroupSize, maxGroupSize);
        }

        public SpawnerData(EntityType<?> type, Weight weight, int minGroupSize, int maxGroupSize) {
            super(weight);
            this.type = type.getCategory() == MobCategory.MISC ? EntityType.PIG : type;
            this.minCount = minGroupSize;
            this.maxCount = maxGroupSize;
        }

        @Override
        public String toString() {
            return EntityType.getKey(this.type) + "*(" + this.minCount + "-" + this.maxCount + "):" + this.getWeight();
        }
    }
}
