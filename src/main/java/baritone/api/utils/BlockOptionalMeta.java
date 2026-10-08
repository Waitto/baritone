/*
 * This file is part of Baritone.
 *
 * Baritone is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Baritone is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with Baritone.  If not, see <https://www.gnu.org/licenses/>.
 */

package baritone.api.utils;

import baritone.api.utils.accessor.IItemStack;
import baritone.api.utils.accessor.ILootTable;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.loot.LootTable;
import net.minecraft.loot.context.LootContext;
import net.minecraft.loot.context.LootContextParameters;
import net.minecraft.loot.context.LootContextTypes;
import net.minecraft.loot.context.LootWorldContext;
import net.minecraft.registry.CombinedDynamicRegistries;
import net.minecraft.registry.DynamicRegistryManager;
import net.minecraft.registry.Registry;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryLoader;
import net.minecraft.registry.RegistryWrapper;
import net.minecraft.registry.ReloadableRegistries;
import net.minecraft.registry.ServerDynamicRegistryType;
import net.minecraft.registry.tag.TagGroupLoader;
import net.minecraft.resource.DefaultResourcePack;
import net.minecraft.resource.LifecycledResourceManager;
import net.minecraft.resource.LifecycledResourceManagerImpl;
import net.minecraft.resource.ResourceType;
import net.minecraft.resource.VanillaDataPackProvider;
import net.minecraft.resource.featuretoggle.FeatureSet;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.WorldGenerationProgressListener;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.state.property.Property;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.math.random.RandomSequencesState;
import net.minecraft.world.World;
import net.minecraft.world.dimension.DimensionOptions;
import net.minecraft.world.level.ServerWorldProperties;
import net.minecraft.world.level.storage.LevelStorage;
import net.minecraft.world.spawner.SpecialSpawner;
import sun.misc.Unsafe;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public final class BlockOptionalMeta {
    // id or id[] or id[properties] where id and properties are any text with at least one character
    private static final Pattern PATTERN = Pattern.compile("^(?<id>.+?)(?:\\[(?<properties>.+?)?\\])?$");

    private final Block block;
    private final String propertiesDescription; // exists so toString() can return something more useful than a list of all blockstates
    private final Set<BlockState> blockstates;
    private final ImmutableSet<Integer> stateHashes;
    private final ImmutableSet<Integer> stackHashes;
    private static Map<Block, List<Item>> drops = new HashMap<>();

    public BlockOptionalMeta(@Nonnull Block block) {
        this.block = block;
        this.propertiesDescription = "{}";
        this.blockstates = getStates(block, Collections.emptyMap());
        this.stateHashes = getStateHashes(blockstates);
        this.stackHashes = getStackHashes(blockstates);
    }

    public BlockOptionalMeta(@Nonnull String selector) {
        Matcher matcher = PATTERN.matcher(selector);

        if (!matcher.find()) {
            throw new IllegalArgumentException("invalid block selector");
        }

        block = BlockUtils.stringToBlockRequired(matcher.group("id"));

        String props = matcher.group("properties");
        Map<Property<?>, ?> properties = props == null || props.equals("") ? Collections.emptyMap() : parseProperties(block, props);

        propertiesDescription = props == null ? "{}" : "{" + props.replace("=", ":") + "}";
        blockstates = getStates(block, properties);
        stateHashes = getStateHashes(blockstates);
        stackHashes = getStackHashes(blockstates);
    }

    private static <C extends Comparable<C>, P extends Property<C>> P castToIProperty(Object value) {
        //noinspection unchecked
        return (P) value;
    }

    private static Map<Property<?>, ?> parseProperties(Block block, String raw) {
        ImmutableMap.Builder<Property<?>, Object> builder = ImmutableMap.builder();
        for (String pair : raw.split(",")) {
            String[] parts = pair.split("=");
            if (parts.length != 2) {
                throw new IllegalArgumentException(String.format("\"%s\" is not a valid property-value pair", pair));
            }
            String rawKey = parts[0];
            String rawValue = parts[1];
            Property<?> key = block.getStateManager().getProperty(rawKey);
            Comparable<?> value = castToIProperty(key).parse(rawValue)
                    .orElseThrow(() -> new IllegalArgumentException(String.format(
                            "\"%s\" is not a valid value for %s on %s",
                            rawValue, key, block
                    )));
            builder.put(key, value);
        }
        return builder.build();
    }

    private static Set<BlockState> getStates(@Nonnull Block block, @Nonnull Map<Property<?>, ?> properties) {
        return block.getStateManager().getStates().stream()
                .filter(blockstate -> properties.entrySet().stream().allMatch(entry ->
                        blockstate.get(entry.getKey()) == entry.getValue()
                ))
                .collect(Collectors.toSet());
    }

    private static ImmutableSet<Integer> getStateHashes(Set<BlockState> blockstates) {
        return ImmutableSet.copyOf(
                blockstates.stream()
                        .map(BlockState::hashCode)
                        .toArray(Integer[]::new)
        );
    }

    private static ImmutableSet<Integer> getStackHashes(Set<BlockState> blockstates) {
        //noinspection ConstantConditions
        return ImmutableSet.copyOf(
                blockstates.stream()
                        .flatMap(state -> drops(state.getBlock())
                                .stream()
                                .map(item -> new ItemStack(item, 1))
                        )
                        .map(stack -> ((IItemStack) (Object) stack).getBaritoneHash())
                        .toArray(Integer[]::new)
        );
    }

    public Block getBlock() {
        return block;
    }

    public boolean matches(@Nonnull Block block) {
        return block == this.block;
    }

    public boolean matches(@Nonnull BlockState blockstate) {
        Block block = blockstate.getBlock();
        return block == this.block && stateHashes.contains(blockstate.hashCode());
    }

    public boolean matches(ItemStack stack) {
        //noinspection ConstantConditions
        int hash = ((IItemStack) (Object) stack).getBaritoneHash();

        hash -= stack.getDamage();

        return stackHashes.contains(hash);
    }

    @Override
    public String toString() {
        return String.format("BlockOptionalMeta{block=%s,properties=%s}", block, propertiesDescription);
    }

    public BlockState getAnyBlockState() {
        if (blockstates.size() > 0) {
            return blockstates.iterator().next();
        }

        return null;
    }

    public Set<BlockState> getAllBlockStates() {
        return blockstates;
    }

    public Set<Integer> stackHashes() {
        return stackHashes;
    }

    private static Method getVanillaServerPack;

    private static DefaultResourcePack getVanillaServerPack() {
        if (getVanillaServerPack == null) {
            getVanillaServerPack = Arrays.stream(VanillaDataPackProvider.class.getDeclaredMethods()).filter(field -> field.getReturnType() == DefaultResourcePack.class).findFirst().orElseThrow();
            getVanillaServerPack.setAccessible(true);
        }

        try {
            return (DefaultResourcePack) getVanillaServerPack.invoke(null);
        } catch (Exception e) {
            e.printStackTrace();
        }

        return null;
    }

    private static synchronized List<Item> drops(Block b) {
        return drops.computeIfAbsent(b, block -> {
            Optional<RegistryKey<LootTable>> optionalLootTableKey = block.getLootTableKey();
            if (optionalLootTableKey.isEmpty()) {
                return Collections.emptyList();
            } else {
                List<Item> items = new ArrayList<>();
                try {
                    ServerWorld lv2 = ServerLevelStub.fastCreate();

                    LootWorldContext.Builder lv5 = new LootWorldContext.Builder(lv2)
                        .add(LootContextParameters.ORIGIN, Vec3d.ZERO)
                        .add(LootContextParameters.BLOCK_STATE, b.getDefaultState())
                        .add(LootContextParameters.TOOL, new ItemStack(Items.NETHERITE_PICKAXE, 1));
                    getDrops(block, lv5).stream().map(ItemStack::getItem).forEach(items::add);
                } catch (Throwable e) {
                    e.printStackTrace();
                }
                return items;
            }
        });
    }

    private static List<ItemStack> getDrops(Block state, LootWorldContext.Builder params) {
        Optional<RegistryKey<LootTable>> lv = state.getLootTableKey();
        if (lv.isEmpty()) {
            return Collections.emptyList();
        } else {
            LootWorldContext lv2 = params.add(LootContextParameters.BLOCK_STATE, state.getDefaultState()).build(LootContextTypes.BLOCK);
            ServerLevelStub lv3 = (ServerLevelStub) lv2.getWorld();
            LootTable lv4 = lv3.holder().getLootTable(lv.get());
            return((ILootTable) lv4).invokeGetRandomItems(new LootContext.Builder(lv2).random(1).build(null));
        }
    }

    public static class ServerLevelStub extends ServerWorld {
        private static MinecraftClient client = MinecraftClient.getInstance();
        private static Unsafe unsafe = getUnsafe();
        private static CompletableFuture<DynamicRegistryManager> registryAccess = load();

        public ServerLevelStub(MinecraftServer $$0, Executor $$1, LevelStorage.Session $$2, ServerWorldProperties $$3, RegistryKey<World> $$4, DimensionOptions $$5, WorldGenerationProgressListener $$6, boolean $$7, long $$8, List<SpecialSpawner> $$9, boolean $$10, @Nullable RandomSequencesState $$11) {
            super($$0, $$1, $$2, $$3, $$4, $$5, $$6, $$7, $$8, $$9, $$10, $$11);
        }

        @Override
        public FeatureSet getEnabledFeatures() {
            assert client.world != null;
            return client.world.getEnabledFeatures();
        }

        public static ServerLevelStub fastCreate() {
            try {
                return (ServerLevelStub) unsafe.allocateInstance(ServerLevelStub.class);
            } catch (InstantiationException e) {
                throw new RuntimeException(e);
            }
        }

        @Override
        public DynamicRegistryManager getRegistryManager() {
            // Loot tables are populated only by the reloaded server registries built
            // in load(). A client RegistryAccess never receives them, so sourcing the
            // holder from client.level made every block resolve to zero drops, which
            // silently broke #mine item pickup (regression from PR #5058).
            return registryAccess.join();
        }

        public ReloadableRegistries.Lookup holder() {
            return new ReloadableRegistries.Lookup(getRegistryManager().toImmutable());
        }

        public static Unsafe getUnsafe() {
            try {
                Field theUnsafe = Unsafe.class.getDeclaredField("theUnsafe");
                theUnsafe.setAccessible(true);
                return (Unsafe) theUnsafe.get(null);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }

        public static CompletableFuture<DynamicRegistryManager> load() {
            // Simplified from {@link net.minecraft.server.WorldLoader#load()}
            LifecycledResourceManager closeableResourceManager = new LifecycledResourceManagerImpl(
                ResourceType.SERVER_DATA,
                List.of(VanillaDataPackProvider.createDefaultPack())
            );
            CombinedDynamicRegistries<ServerDynamicRegistryType> baseLayeredRegistry = ServerDynamicRegistryType.createCombinedDynamicRegistries();
            List<Registry.PendingTagLoad<?>> pendingTags = TagGroupLoader.startReload(
                closeableResourceManager, baseLayeredRegistry.get(ServerDynamicRegistryType.STATIC)
            );
            List<RegistryWrapper.Impl<?>> worldGenRegistryLookupList = TagGroupLoader.collectRegistries(
                baseLayeredRegistry.getPrecedingRegistryManagers(ServerDynamicRegistryType.WORLDGEN),
                pendingTags
            );
            CombinedDynamicRegistries<ServerDynamicRegistryType> layeredRegistryAccess = baseLayeredRegistry.with(
                ServerDynamicRegistryType.WORLDGEN,
                RegistryLoader.loadFromResource(
                    closeableResourceManager,
                    worldGenRegistryLookupList,
                    RegistryLoader.DYNAMIC_REGISTRIES
                )
            );
            return ReloadableRegistries.reload(
                layeredRegistryAccess,
                pendingTags,
                closeableResourceManager,
                MinecraftClient.getInstance()
            ).thenApply(r -> r.layers().getCombinedRegistryManager());
        }
    }
}
