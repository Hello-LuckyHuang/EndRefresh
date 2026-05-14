package com.helloluckyhuang.endrefresh;

import com.helloluckyhuang.endrefresh.mixin.EntityStorageAccessor;
import com.helloluckyhuang.endrefresh.mixin.PersistentEntitySectionManagerAccessor;
import com.helloluckyhuang.endrefresh.mixin.SectionStorageAccessor;
import com.helloluckyhuang.endrefresh.mixin.ServerLevelAccessor;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import net.minecraft.Util;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.SectionPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.storage.EntityStorage;
import net.minecraft.world.level.chunk.storage.SimpleRegionStorage;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.entity.EntityPersistentStorage;
import net.minecraft.world.level.entity.PersistentEntitySectionManager;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

public final class EndChunkCleaner {
    private static final Pattern REGION_FILE_PATTERN = Pattern.compile("r\\.(-?\\d+)\\.(-?\\d+)\\.mca");
    private static final int REGION_CHUNKS = 32;
    private static CleanupRun activeRun;
    private static long nextRunAtMillis = -1L;

    private EndChunkCleaner() {
    }

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        if (!Config.enabled && activeRun == null) {
            nextRunAtMillis = -1L;
            return;
        }

        long now = System.currentTimeMillis();
        if (Config.enabled && nextRunAtMillis < 0L) {
            nextRunAtMillis = now + intervalMillis();
            return;
        }

        if (activeRun != null) {
            if (activeRun.tick(event)) {
                activeRun = null;
                nextRunAtMillis = Config.enabled ? System.currentTimeMillis() + intervalMillis() : -1L;
            }
            return;
        }

        if (Config.enabled && now >= nextRunAtMillis) {
            activeRun = CleanupRun.start(event.getServer());
            if (activeRun == null) {
                nextRunAtMillis = now + intervalMillis();
            }
        }
    }

    public static int startManualCleanup(CommandSourceStack source) {
        if (activeRun != null) {
            source.sendFailure(Component.literal("[EndRefresh] End cleanup is already running."));
            return 0;
        }

        activeRun = CleanupRun.start(source.getServer());
        if (activeRun == null) {
            source.sendFailure(Component.literal("[EndRefresh] Failed to start End cleanup. Check the server log for details."));
            return 0;
        }

        source.sendSuccess(() -> Component.literal("[EndRefresh] End cleanup started."), true);
        return 1;
    }

    private static long intervalMillis() {
        return Math.max(1L, Config.cleanupIntervalDays) * 24L * 60L * 60L * 1000L;
    }

    private static boolean isInKeptCenter(ChunkPos pos, int radius) {
        return pos.x >= -radius && pos.x <= radius && pos.z >= -radius && pos.z <= radius;
    }

    private static boolean isProtectedByPlayer(ServerLevel level, ChunkPos pos, int radius) {
        if (radius < 0) {
            return false;
        }

        for (ServerPlayer player : level.getServer().getPlayerList().getPlayers()) {
            if (player.level().dimension() != Level.END) {
                continue;
            }

            ChunkPos playerChunk = player.chunkPosition();
            if (Math.abs(pos.x - playerChunk.x) <= radius && Math.abs(pos.z - playerChunk.z) <= radius) {
                return true;
            }
        }

        return false;
    }

    private static boolean isLoaded(ServerLevel level, ChunkPos pos) {
        long packedPos = pos.toLong();
        return level.getChunkSource().chunkMap.getVisibleChunkIfPresent(packedPos) != null || level.areEntitiesLoaded(packedPos);
    }

    @SuppressWarnings("unchecked")
    private static SimpleRegionStorage getEntityStorage(ServerLevel level) {
        PersistentEntitySectionManager<Entity> entityManager = ((ServerLevelAccessor)level).endrefresh$getEntityManager();
        EntityPersistentStorage<Entity> persistentStorage = ((PersistentEntitySectionManagerAccessor<Entity>)entityManager).endrefresh$getPermanentStorage();
        if (!(persistentStorage instanceof EntityStorage entityStorage)) {
            throw new IllegalStateException("Unexpected End entity storage implementation: " + persistentStorage.getClass().getName());
        }

        return ((EntityStorageAccessor)entityStorage).endrefresh$getSimpleRegionStorage();
    }

    private static SimpleRegionStorage getPoiStorage(ServerLevel level) {
        return ((SectionStorageAccessor)level.getPoiManager()).endrefresh$getSimpleRegionStorage();
    }

    private record ScanResult(ArrayDeque<ChunkPos> candidates, int keptCenterChunks, int protectedPlayerChunks, int regionFiles, int scanFailures) {
    }

    private static final class CleanupRun {
        private final ServerLevel endLevel;
        private final SimpleRegionStorage entityStorage;
        private final SimpleRegionStorage poiStorage;
        private final CompletableFuture<ScanResult> scanFuture;
        private final List<CompletableFuture<Void>> pendingDeletes = new ArrayList<>();
        private final AtomicInteger deletedChunks = new AtomicInteger();
        private final AtomicInteger failedChunks = new AtomicInteger();
        private ArrayDeque<ChunkPos> candidates = new ArrayDeque<>();
        private int keptCenterChunks;
        private int protectedPlayerChunks;
        private int skippedLoadedChunks;
        private int regionFiles;
        private int scanFailures;
        private boolean scanConsumed;

        private CleanupRun(ServerLevel endLevel, SimpleRegionStorage entityStorage, SimpleRegionStorage poiStorage, CompletableFuture<ScanResult> scanFuture) {
            this.endLevel = endLevel;
            this.entityStorage = entityStorage;
            this.poiStorage = poiStorage;
            this.scanFuture = scanFuture;
        }

        static CleanupRun start(MinecraftServer server) {
            ServerLevel endLevel = server.getLevel(Level.END);
            if (endLevel == null) {
                EndRefresh.LOGGER.warn("Cannot run End cleanup because The End level is not loaded");
                return null;
            }

            SimpleRegionStorage entityStorage;
            SimpleRegionStorage poiStorage;
            try {
                entityStorage = getEntityStorage(endLevel);
                poiStorage = getPoiStorage(endLevel);
            } catch (RuntimeException exception) {
                EndRefresh.LOGGER.error("Cannot access End storage for cleanup", exception);
                return null;
            }

            Path worldPath = server.getWorldPath(LevelResource.ROOT);
            Path endDimensionPath = DimensionType.getStorageFolder(Level.END, worldPath);
            Path regionPath = endDimensionPath.resolve("region");
            List<ChunkPos> protectedPlayers = endLevel.getServer()
                .getPlayerList()
                .getPlayers()
                .stream()
                .filter(player -> player.level().dimension() == Level.END)
                .map(ServerPlayer::chunkPosition)
                .toList();
            int keptCenterRadius = Config.keptCenterRadiusChunks;
            int playerProtectionRadius = Config.playerProtectionRadiusChunks;

            CompletableFuture<ScanResult> scanFuture = CompletableFuture.supplyAsync(
                () -> scanRegionFiles(regionPath, keptCenterRadius, playerProtectionRadius, protectedPlayers),
                Util.ioPool()
            );

            EndRefresh.LOGGER.info("Started End chunk cleanup scan in {}", regionPath);
            return new CleanupRun(endLevel, entityStorage, poiStorage, scanFuture);
        }

        boolean tick(ServerTickEvent.Post event) {
            this.pendingDeletes.removeIf(CompletableFuture::isDone);

            if (!this.scanConsumed) {
                if (!this.scanFuture.isDone()) {
                    return false;
                }

                try {
                    ScanResult result = this.scanFuture.join();
                    this.candidates = result.candidates();
                    this.keptCenterChunks = result.keptCenterChunks();
                    this.protectedPlayerChunks = result.protectedPlayerChunks();
                    this.regionFiles = result.regionFiles();
                    this.scanFailures = result.scanFailures();
                    this.scanConsumed = true;
                    EndRefresh.LOGGER.info("End cleanup scan found {} candidate chunks in {} region files", this.candidates.size(), this.regionFiles);
                } catch (RuntimeException exception) {
                    EndRefresh.LOGGER.error("End cleanup scan failed", exception);
                    this.failedChunks.incrementAndGet();
                    this.scanConsumed = true;
                }
            }

            int scheduled = 0;
            int limit = Math.max(1, Config.chunksPerTick);
            while (scheduled < limit && !this.candidates.isEmpty() && event.hasTime()) {
                ChunkPos pos = this.candidates.removeFirst();
                if (isInKeptCenter(pos, Config.keptCenterRadiusChunks) || isProtectedByPlayer(this.endLevel, pos, Config.playerProtectionRadiusChunks)) {
                    this.protectedPlayerChunks++;
                    continue;
                }

                if (isLoaded(this.endLevel, pos)) {
                    this.skippedLoadedChunks++;
                    continue;
                }

                this.scheduleDelete(pos);
                scheduled++;
            }

            this.pendingDeletes.removeIf(CompletableFuture::isDone);
            if (this.scanConsumed && this.candidates.isEmpty() && this.pendingDeletes.isEmpty()) {
                this.finish();
                return true;
            }

            return false;
        }

        private void scheduleDelete(ChunkPos pos) {
            this.endLevel.getPoiManager().flush(pos);
            for (int sectionY = this.endLevel.getMinSection(); sectionY < this.endLevel.getMaxSection(); sectionY++) {
                this.endLevel.getPoiManager().remove(SectionPos.asLong(pos.x, sectionY, pos.z));
            }

            CompletableFuture<Void> chunkDelete = this.endLevel.getChunkSource().chunkMap.write(pos, null);
            CompletableFuture<Void> entityDelete = this.entityStorage.write(pos, null);
            CompletableFuture<Void> poiDelete = this.poiStorage.write(pos, null);
            CompletableFuture<Void> deleteFuture = CompletableFuture.allOf(chunkDelete, entityDelete, poiDelete).whenComplete((ignored, throwable) -> {
                if (throwable == null) {
                    this.deletedChunks.incrementAndGet();
                } else {
                    this.failedChunks.incrementAndGet();
                    EndRefresh.LOGGER.error("Failed to delete End chunk {}", pos, throwable);
                }
            });
            this.pendingDeletes.add(deleteFuture);
        }

        private void finish() {
            try {
                this.endLevel.getChunkSource().chunkMap.flushWorker();
                this.entityStorage.synchronize(true).join();
                this.poiStorage.synchronize(true).join();
            } catch (RuntimeException exception) {
                this.failedChunks.incrementAndGet();
                EndRefresh.LOGGER.error("Failed to flush End cleanup storage", exception);
            }

            int deleted = this.deletedChunks.get();
            int failed = this.failedChunks.get();
            Component message = Component.literal(
                "[EndRefresh] End cleanup complete: deleted "
                    + deleted
                    + " chunks, kept "
                    + this.keptCenterChunks
                    + " center chunks, protected "
                    + this.protectedPlayerChunks
                    + " chunks near players, skipped "
                    + this.skippedLoadedChunks
                    + " loaded chunks"
                    + (failed > 0 ? ", failed " + failed + " chunks" : "")
                    + (this.scanFailures > 0 ? ", failed to scan " + this.scanFailures + " region files" : "")
                    + "."
            );
            this.endLevel.getServer().getPlayerList().broadcastSystemMessage(message, false);
            EndRefresh.LOGGER.info(
                "Finished End cleanup: deleted={}, keptCenter={}, protectedPlayers={}, skippedLoaded={}, failed={}, scanFailures={}",
                deleted,
                this.keptCenterChunks,
                this.protectedPlayerChunks,
                this.skippedLoadedChunks,
                failed,
                this.scanFailures
            );
        }

        private static ScanResult scanRegionFiles(Path regionPath, int keptCenterRadius, int playerProtectionRadius, List<ChunkPos> protectedPlayers) {
            ArrayDeque<ChunkPos> candidates = new ArrayDeque<>();
            int[] keptCenter = new int[1];
            int[] protectedByPlayers = new int[1];
            int[] regionFiles = new int[1];
            int[] scanFailures = new int[1];

            if (!Files.isDirectory(regionPath)) {
                return new ScanResult(candidates, 0, 0, 0, 0);
            }

            try (Stream<Path> paths = Files.list(regionPath)) {
                paths.forEach(path -> {
                    Matcher matcher = REGION_FILE_PATTERN.matcher(path.getFileName().toString());
                    if (!matcher.matches()) {
                        return;
                    }

                    regionFiles[0]++;
                    int regionX = Integer.parseInt(matcher.group(1));
                    int regionZ = Integer.parseInt(matcher.group(2));
                    try {
                        scanRegionFile(path, regionX, regionZ, keptCenterRadius, playerProtectionRadius, protectedPlayers, candidates, keptCenter, protectedByPlayers);
                    } catch (IOException exception) {
                        scanFailures[0]++;
                        EndRefresh.LOGGER.warn("Failed to scan End region file {}", path, exception);
                    }
                });
            } catch (IOException exception) {
                scanFailures[0]++;
                EndRefresh.LOGGER.warn("Failed to list End region folder {}", regionPath, exception);
            }

            return new ScanResult(candidates, keptCenter[0], protectedByPlayers[0], regionFiles[0], scanFailures[0]);
        }

        private static void scanRegionFile(
            Path path,
            int regionX,
            int regionZ,
            int keptCenterRadius,
            int playerProtectionRadius,
            List<ChunkPos> protectedPlayers,
            ArrayDeque<ChunkPos> candidates,
            int[] keptCenter,
            int[] protectedByPlayers
        ) throws IOException {
            ByteBuffer header = ByteBuffer.allocate(4096).order(ByteOrder.BIG_ENDIAN);
            try (SeekableByteChannel channel = Files.newByteChannel(path, StandardOpenOption.READ)) {
                while (header.hasRemaining() && channel.read(header) > 0) {
                }
            }

            if (header.position() < 4096) {
                return;
            }

            header.flip();
            for (int index = 0; index < REGION_CHUNKS * REGION_CHUNKS; index++) {
                if (header.getInt(index * Integer.BYTES) == 0) {
                    continue;
                }

                int localX = index % REGION_CHUNKS;
                int localZ = index / REGION_CHUNKS;
                ChunkPos pos = new ChunkPos(regionX * REGION_CHUNKS + localX, regionZ * REGION_CHUNKS + localZ);
                if (isInKeptCenter(pos, keptCenterRadius)) {
                    keptCenter[0]++;
                } else if (isProtectedBySnapshot(pos, protectedPlayers, playerProtectionRadius)) {
                    protectedByPlayers[0]++;
                } else {
                    candidates.add(pos);
                }
            }
        }

        private static boolean isProtectedBySnapshot(ChunkPos pos, List<ChunkPos> protectedPlayers, int radius) {
            for (ChunkPos playerChunk : protectedPlayers) {
                if (Math.abs(pos.x - playerChunk.x) <= radius && Math.abs(pos.z - playerChunk.z) <= radius) {
                    return true;
                }
            }

            return false;
        }
    }
}
