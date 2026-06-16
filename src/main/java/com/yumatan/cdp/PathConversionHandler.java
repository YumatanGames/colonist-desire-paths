package com.yumatan.cdp;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.FarmBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.event.tick.EntityTickEvent;

/**
 * Listens to entity ticks and probabilistically converts grass-type blocks
 * beneath MineColonies colonists into worn desire paths:
 *
 *   Grass / Podzol / Mycelium  →  Dirt  →  Coarse Dirt  →  Dirt Path
 *
 * Conversion is purely probabilistic — no per-block counters are stored.
 * Each qualifying colonist has a 1/RATE chance per second of triggering a
 * conversion on the block they are standing on.  Multiple colonists sharing
 * the same route simply multiply the effective wear rate.
 *
 * Rates are intentionally slow so that paths emerge only on genuinely
 * high-traffic corridors (Warehouse routes, hut approaches, etc.).
 *
 * ─── Tuning ───────────────────────────────────────────────────────────────
 *  GRASS_RATE  — 1/N chance per colonist-second for grass → dirt
 *  DIRT_RATE   — 1/N chance per colonist-second for dirt → coarse dirt
 *  COARSE_RATE — 1/N chance per colonist-second for coarse dirt → path
 *
 *  With 10 colonists continuously crossing the same block:
 *    expected time for grass → dirt   ≈  GRASS_RATE / 10  seconds
 *    expected time for dirt → coarse  ≈  DIRT_RATE  / 10  seconds
 *    expected time for coarse → path  ≈  COARSE_RATE / 10 seconds
 *
 *  At the defaults (500 / 750 / 1000), a corridor used by 10 colonists
 *  will reach full path in roughly 22 minutes of continuous traffic.
 *  Routes used by 3–4 colonists will take over an hour, which is realistic
 *  for lightly-travelled hut connections.
 * ──────────────────────────────────────────────────────────────────────────
 */
public class PathConversionHandler {

    // ── Entity filter ──────────────────────────────────────────────────────
    // Only entity types in this tag will produce wear.
    // Default contents: minecolonies:citizen
    // Other types (guards, custom colonists) can be added via a datapack by
    // adding entries to data/colonist_desire_paths/tags/entity_type/path_makers.json
    private static final TagKey<EntityType<?>> PATH_MAKERS = TagKey.create(
            Registries.ENTITY_TYPE,
            ResourceLocation.fromNamespaceAndPath(ColonistDesirePaths.MODID, "path_makers")
    );

    // ── Block filter ───────────────────────────────────────────────────────
    // Blocks in this tag convert to DIRT when first worn.
    // Default: grass_block, podzol, mycelium
    // Modded grass-type blocks can be added via datapack:
    //   data/colonist_desire_paths/tags/block/grass_types.json
    private static final TagKey<Block> GRASS_TYPES = TagKey.create(
            Registries.BLOCK,
            ResourceLocation.fromNamespaceAndPath(ColonistDesirePaths.MODID, "grass_types")
    );

    // ── Conversion rates (1/N chance per colonist-second) ─────────────────
    private static final int GRASS_RATE  = 500;
    private static final int DIRT_RATE   = 750;
    private static final int COARSE_RATE = 1000;

    // ── Tick stride ────────────────────────────────────────────────────────
    // Check is performed every STRIDE ticks per entity.
    // 20 ticks = 1 real second.  Raising this reduces server overhead at the
    // cost of granularity; lowering it does the opposite.
    private static final int STRIDE = 20;

    public static void onEntityTick(EntityTickEvent.Post event) {

        // 1. Server side only — block state changes must happen on the server.
        if (event.getEntity().level().isClientSide()) return;

        // 2. Stride — spread the check across ticks so not every colonist
        //    fires on the same tick.  tickCount is unique per entity so the
        //    phase is naturally staggered.
        if (event.getEntity().tickCount % STRIDE != 0) return;

        // 3. Must be a living entity standing on the ground.
        if (!(event.getEntity() instanceof LivingEntity entity)) return;
        if (!entity.onGround()) return;

        // 4. Entity type filter — only path_makers tag members.
        if (!entity.getType().is(PATH_MAKERS)) return;

        // 5. Resolve block under the entity's feet.
        //    BlockPos.containing at Y-0.2 mirrors Entity.getOnPos() internals
        //    and reliably targets the block the entity is standing on rather
        //    than the one their feet are clipping into.
        Level level = entity.level();
        BlockPos pos = BlockPos.containing(entity.getX(), entity.getY() - 0.2, entity.getZ());
        BlockState state = level.getBlockState(pos);
        Block block = state.getBlock();

        // 6. Hard skip: farmland of any kind.
        //    FarmBlock covers vanilla farmland.  Modded farmlands that extend
        //    FarmBlock are caught automatically.  For modded farmlands that
        //    do not extend FarmBlock, add them to the grass_types exclusion
        //    by simply not including them in the grass_types tag.
        if (block instanceof FarmBlock) return;

        // 7. Determine the conversion target and the chance denominator.
        final Block target;
        final int rate;

        if (state.is(GRASS_TYPES)) {
            // Grass-type block (grass, podzol, mycelium, or modded equivalent)
            target = Blocks.DIRT;
            rate   = GRASS_RATE;
        } else if (block == Blocks.DIRT) {
            target = Blocks.COARSE_DIRT;
            rate   = DIRT_RATE;
        } else if (block == Blocks.COARSE_DIRT) {
            target = Blocks.DIRT_PATH;
            rate   = COARSE_RATE;
        } else {
            // Block is not in the erosion chain — nothing to do.
            return;
        }

        // 8. Roll the dice.  level.random is safe on the server thread.
        if (level.random.nextInt(rate) != 0) return;

        // 9. Convert.  setBlockAndUpdate notifies neighbors so vanilla block
        //    physics fire normally (e.g. short grass on top of coarse dirt
        //    will break when the block below becomes a path block, because
        //    ShortGrassBlock.canSurvive() returns false for path blocks).
        level.setBlockAndUpdate(pos, target.defaultBlockState());
    }
}
