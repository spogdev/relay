package dev.spog.teamlocator.client.path;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * The client's loaded world, as the pathfinder sees it.
 *
 * <p>Read on the client thread only, so it never races the chunk updates arriving from the server.
 * Block states are turned into {@link Cell}s once and cached by state, which is what keeps a search
 * over tens of thousands of positions cheap: a world has far fewer distinct states than blocks.
 */
@Environment(EnvType.CLIENT)
public final class LevelTerrain implements Terrain {
    private final Level level;
    private final Map<BlockState, Cell> cells = new IdentityHashMap<>();
    private final BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();

    public LevelTerrain(Level level) {
        this.level = level;
    }

    @Override
    public boolean loaded(int x, int z) {
        return level.hasChunk(x >> 4, z >> 4);
    }

    @Override
    public Cell cell(int x, int y, int z) {
        if (y < level.getMinY() || y > level.getMaxY() || !loaded(x, z)) {
            return Cell.VOID;
        }
        pos.set(x, y, z);
        BlockState state = level.getBlockState(pos);
        Cell cell = cells.get(state);
        if (cell == null) {
            cell = describe(state);
            cells.put(state, cell);
        }
        return cell;
    }

    private Cell describe(BlockState state) {
        if (state.isAir()) {
            return Cell.AIR;
        }
        VoxelShape shape = state.getCollisionShape(level, pos);
        List<AABB> parts = shape.toAabbs();
        double[] boxes = new double[parts.size() * 6];
        for (int i = 0; i < parts.size(); i++) {
            AABB b = parts.get(i);
            boxes[i * 6] = b.minX;
            boxes[i * 6 + 1] = b.minY;
            boxes[i * 6 + 2] = b.minZ;
            boxes[i * 6 + 3] = b.maxX;
            boxes[i * 6 + 4] = b.maxY;
            boxes[i * 6 + 5] = b.maxZ;
        }
        boolean water = state.getFluidState().is(FluidTags.WATER);
        boolean hazard = state.getFluidState().is(FluidTags.LAVA)
                || state.is(BlockTags.FIRE)
                || state.is(BlockTags.CAMPFIRES)
                || state.is(Blocks.MAGMA_BLOCK)
                || state.is(Blocks.CACTUS)
                || state.is(Blocks.COBWEB)
                || state.is(Blocks.SWEET_BERRY_BUSH)
                || state.is(Blocks.POWDER_SNOW)
                || state.is(Blocks.WITHER_ROSE);
        boolean door = state.is(BlockTags.WOODEN_DOORS) || state.is(BlockTags.FENCE_GATES);
        return new Cell(boxes, water, state.is(BlockTags.CLIMBABLE), hazard, door);
    }
}
