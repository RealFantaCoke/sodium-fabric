package me.jellysquid.mods.sodium.client.render.chunk;

import com.google.common.collect.Lists;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectArrayFIFOQueue;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ObjectList;
import me.jellysquid.mods.sodium.client.SodiumClientMod;
import me.jellysquid.mods.sodium.client.gui.SodiumGameOptions;
import me.jellysquid.mods.sodium.client.render.chunk.compile.ChunkRender;
import me.jellysquid.mods.sodium.common.util.DirectionUtil;
import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.Camera;
import net.minecraft.client.render.Frustum;
import net.minecraft.client.render.chunk.ChunkOcclusionDataBuilder;
import net.minecraft.client.util.math.Vector3f;
import net.minecraft.util.math.*;
import net.minecraft.world.World;
import net.minecraft.world.chunk.ChunkSection;
import net.minecraft.world.chunk.WorldChunk;

import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

public class ChunkGraph<T extends ChunkRenderData> {
    private final Long2ObjectOpenHashMap<ChunkRender<T>> nodes = new Long2ObjectOpenHashMap<>();

    private final ObjectList<ChunkRender<T>> visibleNodes = new ObjectArrayList<>();
    private final ObjectList<ChunkRender<T>> drawableNodes = new ObjectArrayList<>();

    private final ObjectArrayFIFOQueue<ChunkRender<T>> iterationQueue = new ObjectArrayFIFOQueue<>();

    private final ChunkRenderManager<T> renderManager;
    private final World world;

    private int minX, minZ, maxX, maxZ;
    private int renderDistance;

    public ChunkGraph(ChunkRenderManager<T> renderManager, World world, int renderDistance) {
        this.renderManager = renderManager;
        this.world = world;
        this.renderDistance = renderDistance;
    }

    public void calculateVisible(Camera camera, Vec3d cameraPos, BlockPos blockPos, int frame, Frustum frustum, boolean spectator) {
        BlockPos center = new BlockPos(MathHelper.floor(cameraPos.x / 16.0D) * 16,
                MathHelper.floor(cameraPos.y / 16.0D) * 16,
                MathHelper.floor(cameraPos.z / 16.0D) * 16);

        this.minX = center.getX() - (this.renderDistance * 16);
        this.minZ = center.getZ() - (this.renderDistance * 16);

        this.maxX = center.getX() + (this.renderDistance * 16);
        this.maxZ = center.getZ() + (this.renderDistance * 16);

        this.visibleNodes.clear();
        this.drawableNodes.clear();

        boolean cull = this.init(blockPos, camera, cameraPos, frustum, frame, spectator);

        SodiumGameOptions options = SodiumClientMod.options();

        boolean fogCulling = cull && this.renderDistance > 4 && options.quality.enableFog && options.performance.useFogChunkCulling;
        int maxChunkDistance = (this.renderDistance * 16) + 16;

        while (!this.iterationQueue.isEmpty()) {
            ChunkRender<T> render = this.iterationQueue.dequeue();

            this.visibleNodes.add(render);

            if (!render.isEmpty()) {
                this.drawableNodes.add(render);
            }

            if (fogCulling && !render.getOrigin().isWithinDistance(cameraPos, maxChunkDistance)) {
                continue;
            }

            this.addNeighbors(render, cull, frustum, frame);
        }
    }

    private void addNeighbors(ChunkRender<T> render, boolean cull, Frustum frustum, int frame) {
        for (Direction adjDir : DirectionUtil.ALL_DIRECTIONS) {
            ChunkRender<T> adjRender = this.getAdjacentRender(render, adjDir);

            if (adjRender == null || adjRender.getRebuildFrame() == frame) {
                continue;
            }

            if (cull && !this.isVisible(render, adjRender, adjDir, frustum)) {
                continue;
            }

            if (!adjRender.hasNeighbors()) {
                continue;
            }

            adjRender.setDirection(adjDir);
            adjRender.setRebuildFrame(frame);
            adjRender.updateCullingState(render.cullingState, adjDir);

            this.iterationQueue.enqueue(adjRender);
        }
    }

    private boolean isVisible(ChunkRender<T> render, ChunkRender<T> adjRender, Direction dir, Frustum frustum) {
        if (render.canCull(dir.getOpposite())) {
            return false;
        }

        if (render.direction != null && !render.isVisibleThrough(render.direction.getOpposite(), dir)) {
            return false;
        }

        return frustum.isVisible(adjRender.getBoundingBox());
    }

    private boolean init(BlockPos blockPos, Camera camera, Vec3d cameraPos, Frustum frustum, int frame, boolean spectator) {
        MinecraftClient client = MinecraftClient.getInstance();
        ObjectArrayFIFOQueue<ChunkRender<T>> queue = this.iterationQueue;

        boolean cull = client.chunkCullingEnabled;

        ChunkRender<T> node = this.getOrCreateRender(blockPos);

        if (node != null) {
            node.reset();

            // Player is within bounds and inside a node
            Set<Direction> openFaces = this.getOpenChunkFaces(blockPos);

            if (openFaces.size() == 1) {
                Vector3f vector3f = camera.getHorizontalPlane();
                Direction direction = Direction.getFacing(vector3f.getX(), vector3f.getY(), vector3f.getZ()).getOpposite();

                openFaces.remove(direction);
            }

            if (openFaces.isEmpty() && !spectator) {
                this.visibleNodes.add(node);
            } else {
                if (spectator && this.world.getBlockState(blockPos).isFullOpaque(this.world, blockPos)) {
                    cull = false;
                }

                node.setRebuildFrame(frame);
                queue.enqueue(node);
            }
        } else {
            // Player is out-of-bounds
            int y = blockPos.getY() > 0 ? 248 : 8;

            int x = MathHelper.floor(cameraPos.x / 16.0D) * 16;
            int z = MathHelper.floor(cameraPos.z / 16.0D) * 16;

            List<ChunkRender<T>> list = Lists.newArrayList();

            for (int x2 = -this.renderDistance; x2 <= this.renderDistance; ++x2) {
                for (int z2 = -this.renderDistance; z2 <= this.renderDistance; ++z2) {
                    ChunkRender<T> chunk = this.getOrCreateRender(new BlockPos(x + (x2 << 4) + 8, y, z + (z2 << 4) + 8));

                    if (chunk == null) {
                        continue;
                    }

                    if (frustum.isVisible(chunk.getBoundingBox())) {
                        chunk.setRebuildFrame(frame);
                        chunk.reset();

                        list.add(chunk);
                    }
                }
            }

            list.sort(Comparator.comparingDouble(o -> blockPos.getSquaredDistance(o.getOrigin().add(8, 8, 8))));

            for (ChunkRender<T> n : list) {
                queue.enqueue(n);
            }
        }

        return cull;
    }

    public ChunkRender<T> getOrCreateRender(BlockPos pos) {
        return this.getOrCreateRender(pos.getX() >> 4, pos.getY() >> 4, pos.getZ() >> 4);
    }

    public ChunkRender<T> getOrCreateRender(int x, int y, int z) {
        if (y < 0 || y >= 16) {
            return null;
        }

        return this.nodes.computeIfAbsent(ChunkSectionPos.asLong(x, y, z), this::createRender);
    }

    public ChunkRender<T> getRender(int x, int y, int z) {
        return this.nodes.get(ChunkSectionPos.asLong(x >> 4, y >> 4, z >> 4));
    }

    private ChunkRender<T> createRender(long pos) {
        return this.renderManager.createChunkRender(ChunkSectionPos.getX(pos) << 4, ChunkSectionPos.getY(pos) << 4, ChunkSectionPos.getZ(pos) << 4);
    }

    private ChunkRender<T> getAdjacentRender(ChunkRender<T> render, Direction direction) {
        int x = render.getChunkX() + direction.getOffsetX();
        int y = render.getChunkY() + direction.getOffsetY();
        int z = render.getChunkZ() + direction.getOffsetZ();

        if (!this.isWithinRenderBounds(x, y, z)) {
            return null;
        }

        return render.getAdjacent(direction);
    }

    private boolean isWithinRenderBounds(int x, int y, int z) {
        return y >= 0 && y < 256 && x >= this.minX && x <= this.maxX && z >= this.minZ && z <= this.maxZ;
    }

    private Set<Direction> getOpenChunkFaces(BlockPos pos) {
        WorldChunk chunk = this.world.getChunk(pos.getX() >> 4, pos.getZ() >> 4);

        ChunkSection section = chunk.getSectionArray()[pos.getY() >> 4];

        if (section == null || section.isEmpty()) {
            return EnumSet.allOf(Direction.class);
        }

        ChunkOcclusionDataBuilder occlusionBuilder = new ChunkOcclusionDataBuilder();

        BlockPos.Mutable mpos = new BlockPos.Mutable();

        for (int x = 0; x < 16; x++) {
            for (int y = 0; y < 16; y++) {
                for (int z = 0; z < 16; z++) {
                    BlockState state = section.getBlockState(x, y, z);
                    mpos.set(x, y, z);

                    if (state.isFullOpaque(this.world, mpos)) {
                        occlusionBuilder.markClosed(mpos);
                    }
                }
            }
        }

        return occlusionBuilder.getOpenFaces(pos);
    }

    public void reset() {
        for (ChunkRender<?> node : this.nodes.values()) {
            node.delete();
        }

        this.nodes.clear();
        this.visibleNodes.clear();
    }

    public ObjectList<ChunkRender<T>> getVisibleChunks() {
        return this.visibleNodes;
    }

    public ObjectList<ChunkRender<T>> getDrawableChunks() {
        return this.drawableNodes;
    }
}
