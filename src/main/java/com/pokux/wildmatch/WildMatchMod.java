package com.pokux.wildmatch;

import com.mojang.brigadier.CommandDispatcher;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.block.BlockState;
import net.minecraft.command.CommandRegistryAccess;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.random.Random;
import net.minecraft.world.Heightmap;
import net.minecraft.world.World;
import net.minecraft.world.border.WorldBorder;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.UUID;

public class WildMatchMod implements ModInitializer {
    public static final String MOD_ID = "wildmatch";

    private static final int WAIT_TICKS = 8 * 20;
    private static final int INVULN_TICKS = 20;
    private static final double CANCEL_MOVE_DISTANCE_SQ = 0.0064D;
    private static final int PLAYER_GAP_BLOCKS = 4;
    private static final int BORDER_MARGIN = 64;
    private static final int SAFE_POS_ATTEMPTS = 80;

    private static final Queue<UUID> waitingQueue = new ArrayDeque<>();
    private static final Set<UUID> waitingSet = new HashSet<>();
    private static final Map<UUID, MatchState> activeMatches = new HashMap<>();
    private static final Map<UUID, Integer> invulnerableTicks = new HashMap<>();

    @Override
    public void onInitialize() {
        CommandRegistrationCallback.EVENT.register(WildMatchMod::registerCommands);
        ServerTickEvents.END_SERVER_TICK.register(WildMatchMod::tick);
        ServerLivingEntityEvents.ALLOW_DAMAGE.register(WildMatchMod::allowDamage);
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            ServerPlayerEntity player = handler.getPlayer();
            cancelFor(player, CancelReason.DISCONNECT);
            removeFromWaiting(player.getUuid());
            invulnerableTicks.remove(player.getUuid());
        });
    }

    private static void registerCommands(CommandDispatcher<ServerCommandSource> dispatcher,
                                         CommandRegistryAccess registryAccess,
                                         CommandManager.RegistrationEnvironment environment) {
        dispatcher.register(CommandManager.literal("match").executes(ctx -> {
            ServerPlayerEntity player = ctx.getSource().getPlayer();
            joinMatch(player);
            return 1;
        }));

        dispatcher.register(CommandManager.literal("매칭").executes(ctx -> {
            ServerPlayerEntity player = ctx.getSource().getPlayer();
            joinMatch(player);
            return 1;
        }));

        dispatcher.register(CommandManager.literal("matchcancel").executes(ctx -> {
            ServerPlayerEntity player = ctx.getSource().getPlayer();
            boolean cancelled = cancelFor(player, CancelReason.MANUAL);
            boolean removed = removeFromWaiting(player.getUuid());
            if (!cancelled && !removed) {
                msg(player, "[매칭] 현재 매칭 대기 중이 아닙니다.");
            } else if (removed) {
                msg(player, "[매칭] 매칭 대기열에서 나갔습니다.");
            }
            return 1;
        }));
    }

    private static void joinMatch(ServerPlayerEntity player) {
        UUID uuid = player.getUuid();
        if (waitingSet.contains(uuid) || activeMatches.containsKey(uuid)) {
            msg(player, "[매칭] 이미 매칭 중입니다.");
            return;
        }

        UUID opponentId = pollValidWaiting(player.getServer(), uuid);
        if (opponentId == null) {
            waitingQueue.add(uuid);
            waitingSet.add(uuid);
            msg(player, "[매칭] 매칭 대기열에 참가했습니다. 상대를 기다리는 중입니다.");
            broadcast(player.getServer(), "[매칭] 누군가 매칭 대기열에 참가했습니다. (/match 또는 /매칭)");
            return;
        }

        waitingSet.remove(opponentId);
        ServerPlayerEntity opponent = player.getServer().getPlayerManager().getPlayer(opponentId);
        if (opponent == null) {
            joinMatch(player);
            return;
        }

        MatchState state = new MatchState(player.getUuid(), opponent.getUuid(), WAIT_TICKS,
                player.getX(), player.getY(), player.getZ(),
                opponent.getX(), opponent.getY(), opponent.getZ());
        activeMatches.put(player.getUuid(), state);
        activeMatches.put(opponent.getUuid(), state);

        msg(player, "[매칭] 상대를 찾았습니다. 8초 후 이동합니다. 움직이거나 피해를 입으면 취소됩니다.");
        msg(opponent, "[매칭] 상대를 찾았습니다. 8초 후 이동합니다. 움직이거나 피해를 입으면 취소됩니다.");
    }

    private static UUID pollValidWaiting(MinecraftServer server, UUID joiningPlayer) {
        while (!waitingQueue.isEmpty()) {
            UUID candidate = waitingQueue.poll();
            if (candidate.equals(joiningPlayer)) {
                waitingSet.remove(candidate);
                continue;
            }
            if (!waitingSet.contains(candidate)) continue;
            if (server.getPlayerManager().getPlayer(candidate) == null) {
                waitingSet.remove(candidate);
                continue;
            }
            return candidate;
        }
        return null;
    }

    private static void tick(MinecraftServer server) {
        tickInvulnerable();

        Set<MatchState> uniqueMatches = new HashSet<>(activeMatches.values());
        for (MatchState state : uniqueMatches) {
            ServerPlayerEntity a = server.getPlayerManager().getPlayer(state.playerA);
            ServerPlayerEntity b = server.getPlayerManager().getPlayer(state.playerB);
            if (a == null || b == null) {
                clearMatch(state);
                continue;
            }

            if (hasMoved(a, state.aStartX, state.aStartY, state.aStartZ)) {
                cancelState(state, a, CancelReason.MOVE);
                continue;
            }
            if (hasMoved(b, state.bStartX, state.bStartY, state.bStartZ)) {
                cancelState(state, b, CancelReason.MOVE);
                continue;
            }

            int seconds = Math.max(1, (state.ticksLeft + 19) / 20);
            actionbar(a, "[매칭] " + seconds + "초 후 이동합니다.");
            actionbar(b, "[매칭] " + seconds + "초 후 이동합니다.");

            state.ticksLeft--;
            if (state.ticksLeft <= 0) {
                clearMatch(state);
                teleportPair(server, a, b);
            }
        }
    }

    private static void tickInvulnerable() {
        invulnerableTicks.entrySet().removeIf(entry -> {
            int left = entry.getValue() - 1;
            if (left <= 0) return true;
            entry.setValue(left);
            return false;
        });
    }

    private static boolean allowDamage(LivingEntity entity, DamageSource source, float amount) {
        if (!(entity instanceof ServerPlayerEntity player)) return true;

        if (invulnerableTicks.containsKey(player.getUuid())) {
            return false;
        }

        MatchState state = activeMatches.get(player.getUuid());
        if (state != null) {
            cancelState(state, player, CancelReason.DAMAGE);
        }
        return true;
    }

    private static boolean hasMoved(ServerPlayerEntity player, double x, double y, double z) {
        double dx = player.getX() - x;
        double dy = player.getY() - y;
        double dz = player.getZ() - z;
        return (dx * dx + dy * dy + dz * dz) > CANCEL_MOVE_DISTANCE_SQ;
    }

    private static boolean cancelFor(ServerPlayerEntity player, CancelReason reason) {
        MatchState state = activeMatches.get(player.getUuid());
        if (state == null) return false;
        cancelState(state, player, reason);
        return true;
    }

    private static void cancelState(MatchState state, ServerPlayerEntity cancelledBy, CancelReason reason) {
        ServerPlayerEntity a = cancelledBy.getServer().getPlayerManager().getPlayer(state.playerA);
        ServerPlayerEntity b = cancelledBy.getServer().getPlayerManager().getPlayer(state.playerB);
        clearMatch(state);

        if (a != null && a.getUuid().equals(cancelledBy.getUuid())) {
            msg(a, selfCancelMessage(reason));
            if (b != null) msg(b, opponentCancelMessage(reason));
        } else if (b != null && b.getUuid().equals(cancelledBy.getUuid())) {
            msg(b, selfCancelMessage(reason));
            if (a != null) msg(a, opponentCancelMessage(reason));
        } else {
            if (a != null) msg(a, "[매칭] 매칭이 취소되었습니다.");
            if (b != null) msg(b, "[매칭] 매칭이 취소되었습니다.");
        }
    }

    private static String selfCancelMessage(CancelReason reason) {
        return switch (reason) {
            case MOVE -> "[매칭] 이동하여 매칭이 취소되었습니다.";
            case DAMAGE -> "[매칭] 피해를 받아 매칭이 취소되었습니다.";
            case DISCONNECT -> "[매칭] 접속 종료로 매칭이 취소되었습니다.";
            case MANUAL -> "[매칭] 매칭을 취소했습니다.";
        };
    }

    private static String opponentCancelMessage(CancelReason reason) {
        return switch (reason) {
            case MOVE -> "[매칭] 상대방이 이동하여 매칭이 취소되었습니다.";
            case DAMAGE -> "[매칭] 상대방이 피해를 받아 매칭이 취소되었습니다.";
            case DISCONNECT -> "[매칭] 상대방이 서버를 나가 매칭이 취소되었습니다.";
            case MANUAL -> "[매칭] 상대방이 매칭을 취소했습니다.";
        };
    }

    private static void clearMatch(MatchState state) {
        activeMatches.remove(state.playerA);
        activeMatches.remove(state.playerB);
    }

    private static boolean removeFromWaiting(UUID uuid) {
        boolean removed = waitingSet.remove(uuid);
        if (removed) waitingQueue.remove(uuid);
        return removed;
    }

    private static void teleportPair(MinecraftServer server, ServerPlayerEntity a, ServerPlayerEntity b) {
        SafeSpot spot = findSafeSpot(server);
        if (spot == null) {
            msg(a, "[매칭] 안전한 전장을 찾지 못해 매칭이 취소되었습니다.");
            msg(b, "[매칭] 안전한 전장을 찾지 못해 매칭이 취소되었습니다.");
            return;
        }

        double ax = spot.x + 0.5D;
        double ay = spot.y;
        double az = spot.z - (PLAYER_GAP_BLOCKS / 2.0D);
        double bx = spot.x + 0.5D;
        double by = spot.y;
        double bz = spot.z + (PLAYER_GAP_BLOCKS / 2.0D);

        runServerCommand(server, String.format(Locale.ROOT,
                "execute in minecraft:overworld run tp %s %.2f %.2f %.2f 0 0", a.getGameProfile().getName(), ax, ay, az));
        runServerCommand(server, String.format(Locale.ROOT,
                "execute in minecraft:overworld run tp %s %.2f %.2f %.2f 180 0", b.getGameProfile().getName(), bx, by, bz));

        invulnerableTicks.put(a.getUuid(), INVULN_TICKS);
        invulnerableTicks.put(b.getUuid(), INVULN_TICKS);

        msg(a, "[매칭] 전장으로 이동했습니다. 1초 동안 무적입니다. 행운을 빕니다!");
        msg(b, "[매칭] 전장으로 이동했습니다. 1초 동안 무적입니다. 행운을 빕니다!");
    }

    private static SafeSpot findSafeSpot(MinecraftServer server) {
        ServerWorld world = server.getOverworld();
        WorldBorder border = world.getWorldBorder();
        Random random = world.getRandom();

        double half = Math.max(1, border.getSize() / 2.0D - BORDER_MARGIN);
        double centerX = border.getCenterX();
        double centerZ = border.getCenterZ();

        for (int i = 0; i < SAFE_POS_ATTEMPTS; i++) {
            int x = (int) Math.floor(centerX - half + random.nextDouble() * half * 2.0D);
            int z = (int) Math.floor(centerZ - half + random.nextDouble() * half * 2.0D);
            int y = world.getTopY(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, x, z);

            if (y <= world.getBottomY() + 2 || y >= world.getTopY() - 2) continue;
            BlockPos ground = new BlockPos(x, y - 1, z);
            BlockPos feet = new BlockPos(x, y, z);
            BlockPos head = new BlockPos(x, y + 1, z);
            BlockPos p2Feet = new BlockPos(x, y, z + PLAYER_GAP_BLOCKS);
            BlockPos p2Head = new BlockPos(x, y + 1, z + PLAYER_GAP_BLOCKS);
            BlockPos p2Ground = new BlockPos(x, y - 1, z + PLAYER_GAP_BLOCKS);

            if (isSafe(world, ground, feet, head) && isSafe(world, p2Ground, p2Feet, p2Head)) {
                return new SafeSpot(x, y, z + PLAYER_GAP_BLOCKS / 2);
            }
        }
        return null;
    }

    private static boolean isSafe(ServerWorld world, BlockPos ground, BlockPos feet, BlockPos head) {
        BlockState groundState = world.getBlockState(ground);
        BlockState feetState = world.getBlockState(feet);
        BlockState headState = world.getBlockState(head);
        return !groundState.isAir()
                && feetState.isAir()
                && headState.isAir()
                && world.getFluidState(feet).isEmpty()
                && world.getFluidState(head).isEmpty();
    }

    private static void runServerCommand(MinecraftServer server, String command) {
        server.getCommandManager().executeWithPrefix(server.getCommandSource(), command);
    }

    private static void msg(ServerPlayerEntity player, String message) {
        player.sendMessage(Text.literal(message), false);
    }

    private static void actionbar(ServerPlayerEntity player, String message) {
        player.sendMessage(Text.literal(message), true);
    }

    private static void broadcast(MinecraftServer server, String message) {
        for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
            msg(player, message);
        }
    }

    private enum CancelReason {
        MOVE,
        DAMAGE,
        DISCONNECT,
        MANUAL
    }

    private static final class MatchState {
        final UUID playerA;
        final UUID playerB;
        int ticksLeft;
        final double aStartX;
        final double aStartY;
        final double aStartZ;
        final double bStartX;
        final double bStartY;
        final double bStartZ;

        MatchState(UUID playerA, UUID playerB, int ticksLeft,
                   double aStartX, double aStartY, double aStartZ,
                   double bStartX, double bStartY, double bStartZ) {
            this.playerA = playerA;
            this.playerB = playerB;
            this.ticksLeft = ticksLeft;
            this.aStartX = aStartX;
            this.aStartY = aStartY;
            this.aStartZ = aStartZ;
            this.bStartX = bStartX;
            this.bStartY = bStartY;
            this.bStartZ = bStartZ;
        }
    }

    private record SafeSpot(int x, int y, int z) {}
}
