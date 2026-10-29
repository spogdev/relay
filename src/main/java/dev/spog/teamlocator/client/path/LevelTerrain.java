package dev.spog.teamlocator.client.path;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.registry.tag.FluidTags;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.world.World;

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
    private final World level;
    private final Map<BlockState, Cell> cells = new IdentityHashMap<>();
    private final BlockPos.Mutable pos = new BlockPos.Mutable();

    public LevelTerrain(World level) {
        this.level = level;
    }

    @Override
    public boolean loaded(int x, int z) {
        return level.isChunkLoaded(x >> 4, z >> 4);
    }

    @Override
    public Cell cell(int x, int y, int z) {
        if (y < level.getBottomY() || y > level.getTopYInclusive() || !loaded(x, z)) {
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
        List<Box> parts = shape.getBoundingBoxes();
        double[] boxes = new double[parts.size() * 6];
        for (int i = 0; i < parts.size(); i++) {
            Box b = parts.get(i);
            boxes[i * 6] = b.minX;
            boxes[i * 6 + 1] = b.minY;
            boxes[i * 6 + 2] = b.minZ;
            boxes[i * 6 + 3] = b.maxX;
            boxes[i * 6 + 4] = b.maxY;
            boxes[i * 6 + 5] = b.maxZ;
        }
        boolean water = state.getFluidState().isIn(FluidTags.WATER);
        boolean hazard = state.getFluidState().isIn(FluidTags.LAVA)
                || state.isIn(BlockTags.FIRE)
                || state.isIn(BlockTags.CAMPFIRES)
                || state.isOf(Blocks.MAGMA_BLOCK)
                || state.isOf(Blocks.CACTUS)
                || state.isOf(Blocks.COBWEB)
                || state.isOf(Blocks.SWEET_BERRY_BUSH)
                || state.isOf(Blocks.POWDER_SNOW)
                || state.isOf(Blocks.WITHER_ROSE);
        boolean door = state.isIn(BlockTags.WOODEN_DOORS) || state.isIn(BlockTags.FENCE_GATES);
        return new Cell(boxes, water, state.isIn(BlockTags.CLIMBABLE), hazard, door);
    }
}
