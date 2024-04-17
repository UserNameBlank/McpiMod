/*
 * Copyright 2012-2024 RaspberryJuice project
 * Copyright 2024 UserNameBlank
 * 
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 * 
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * 
 * Parts of this code were originally created by the RaspberryJuice project, which you can find here: 
 * https://github.com/zhuowei/RaspberryJuice
 * 
 * 
 * All copyright to the original code belongs to the creators of said project.
 */

package mcpimod;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.Socket;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import mcpimod.utils.BlockEvent;
import mcpimod.utils.ChatEvent;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.ChestBlock;
import net.minecraft.block.FurnaceBlock;
import net.minecraft.block.LadderBlock;
import net.minecraft.block.RedstoneOreBlock;
import net.minecraft.block.SlabBlock;
import net.minecraft.block.StairsBlock;
import net.minecraft.block.TntBlock;
import net.minecraft.block.WallTorchBlock;
import net.minecraft.block.enums.BlockHalf;
import net.minecraft.block.enums.SlabType;
import net.minecraft.entity.Entity;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.GameRules;

public class RemoteSession {

  private Socket socket;

  private BufferedReader in;
  private BufferedWriter out;

  private Thread inThread;
  private Thread outThread;

  private ArrayDeque<String> inQueue = new ArrayDeque<>();
  private ArrayDeque<String> outQueue = new ArrayDeque<>();

  private boolean running = true;
  private boolean closed = false;

  public boolean pendingRemoval = false;

  public static LinkedList<BlockEvent> BLOCK_EVENTS = new LinkedList<>();
  public static LinkedList<ChatEvent> CHAT_EVENTS = new LinkedList<>();

  public static int MAX_COMMANDS_PER_TICK = 9000;

  public static void setMaxCommandsPerTick(MinecraftServer _server, GameRules.IntRule rule) {
    MAX_COMMANDS_PER_TICK = rule.get();
  }

  public RemoteSession(Socket socket) throws IOException {
    this.socket = socket;
    init();
  }

  private void init() throws IOException {
    socket.setTcpNoDelay(true);
    socket.setKeepAlive(true);
    socket.setTrafficClass(0x10);
    this.in = new BufferedReader(new InputStreamReader(socket.getInputStream(), "UTF-8"));
    this.out = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(), "UTF-8"));
    startThreads();
    McpiMod.LOGGER.info("Opened connection to " + socket.getRemoteSocketAddress());
  }

  private void startThreads() {
    this.inThread = new Thread(new InputThread());
    this.inThread.start();
    this.outThread = new Thread(new OutputThread());
    this.outThread.start();
  }

  public void tick(ServerWorld world) {
    int processedCount = 0;
    String message;
    while ((message = inQueue.poll()) != null) {
      try {
        handleLine(message, world);
      } catch (Exception e) {
        McpiMod.LOGGER.warn("*ERROR* at command: " + message + ", from " + socket.getInetAddress().toString());
        e.printStackTrace();
      }

      processedCount++;
      if (processedCount >= MAX_COMMANDS_PER_TICK) {
        McpiMod.LOGGER
            .warn("Over " + MAX_COMMANDS_PER_TICK + " commands were queued - deferring the other to next tick");
        break;
      }
    }

    if (!running && inQueue.size() <= 0) {
      pendingRemoval = true;
    }
  }

  private void handleLine(String line, ServerWorld world) {
    // System.out.println(line);
    String methodName = line.substring(0, line.indexOf("("));
    // split string into args, handles , inside " i.e. ","
    String[] args = line.substring(line.indexOf("(") + 1, line.length() - 1).split(",");
    // System.out.println(methodName + ":" + Arrays.toString(args));
    handleCommand(methodName, args, world);
  }

  private void handleCommand(String c, String[] args, ServerWorld world) {
    // McpiMod.LOGGER.info("Received Command: " + c);

    // WORLD commands

    if (c.startsWith("world.")) {
      if (c.equals("world.setBlock")) {
        BlockPos pos = parseBlockPos(args);

        int subId = args.length >= 5 ? Integer.parseInt(args[4]) : 0;
        BlockState state = blockIdToBlockState(Integer.parseInt(args[3]), subId);

        world.setBlockState(pos, state);

        if (state.isOf(Blocks.TNT) && subId > 0) {
          TntBlock.primeTnt(world, pos);
          world.removeBlock(pos, false);
        }
      }

      else if (c.equals("world.setBlocks")) {
        BlockPos pos1 = parseBlockPos(args);
        BlockPos pos2 = parseBlockPos(Arrays.copyOfRange(args, 3, 6));

        int subId = args.length >= 8 ? Integer.parseInt(args[7]) : 0;
        BlockState state = blockIdToBlockState(Integer.parseInt(args[6]), subId);

        setBlockStates(world, pos1, pos2, state);
      }

      else if (c.equals("world.getBlock")) {
        BlockPos pos = parseBlockPos(args);
        BlockState state = world.getBlockState(pos);

        int blockId = blockStateToBlockId(state);

        send(blockId);
      }

      // TODO: getBlockWithData

      else if (c.equals("world.getBlocks")) {
        BlockPos pos1 = parseBlockPos(args);
        BlockPos pos2 = parseBlockPos(Arrays.copyOfRange(args, 3, 6));

        List<Integer> blockIds = getBlockStatesAsIds(world, pos1, pos2);

        send(String.join(",", blockIds.stream().map(i -> i.toString()).toList()));
      }

      else if (c.equals("world.getHeight")) {
        int x = Integer.parseInt(args[0]);
        int z = Integer.parseInt(args[1]);

        int highestY = 0;
        for (int y = 0; y <= 255; y++) {
          BlockPos pos = new BlockPos(x, y, z);
          BlockState state = world.getBlockState(pos);
          if (!(state.isOf(Blocks.AIR) || state.isOf(Blocks.CAVE_AIR) || state.isOf(Blocks.VOID_AIR))) {
            highestY = y;
          }
        }

        send(highestY);
      }

      else if (c.equals("world.getPlayerIds")) {
        List<ServerPlayerEntity> players = getPlayerEntitys(world);
        List<String> playerIds = players.stream().map(player -> Integer.toString(player.getId())).toList();

        send(String.join(",", playerIds));
      }

      else if (c.equals("world.getPlayerId")) {
        ServerPlayerEntity player = world.getServer().getPlayerManager().getPlayer(args[0]);
        send(player.getId());
      }

      // TODO: saveCheckpoint
      // TODO: restoreCheckpoint
    }

    // PLAYER commands

    else if (c.startsWith("player.")) {
      ServerPlayerEntity player = getPlayer(world);

      if (player != null) {

        if (c.equals("player.getPos")) {
          Vec3d pos = player.getPos();
          send(serializePos(pos));
        }

        else if (c.equals("player.setPos")) {
          Vec3d pos = parsePos(args);
          player.teleport(pos.x, pos.y, pos.z);
        }

        else if (c.equals("player.getTile")) {
          Vec3d pos = player.getPos();
          int x = (int) pos.x;
          int y = (int) pos.y;
          int z = (int) pos.z;
          send(x + "," + y + "," + z);
        }

        else if (c.equals("player.setTile")) {
          Vec3d pos = parseBlockPos(args).toCenterPos();
          player.teleport(pos.x, pos.y, pos.z);
        }

        else if (c.equals("player.getDirection")) {
          double pitchRad = player.getPitch() * MathHelper.RADIANS_PER_DEGREE;
          double yawRad = player.getYaw() * MathHelper.RADIANS_PER_DEGREE;

          double x = Math.cos(pitchRad) * Math.cos(yawRad);
          double y = Math.cos(pitchRad) * Math.sin(yawRad);
          double z = Math.sin(pitchRad);

          Vec3d dVec = new Vec3d(x, y, z);

          send(serializePos(dVec.normalize())); // Normalize for safety
        }

        else if (c.equals("player.setDirection")) {
          Vec3d dVec = parsePos(args).normalize(); // Normalize for safety
          double pitch = Math.asin(-dVec.y) * MathHelper.DEGREES_PER_RADIAN;
          double yaw = Math.atan2(dVec.x, dVec.z) * MathHelper.DEGREES_PER_RADIAN;

          Vec3d pos = player.getPos();
          player.teleport(world, pos.x, pos.y, pos.z, (float) yaw, (float) pitch);
        }

        else if (c.equals("player.getRotation")) {
          send(player.getYaw());
        }

        else if (c.equals("player.setRotation")) {
          float yaw = Float.parseFloat(args[0]);

          Vec3d pos = player.getPos();
          player.teleport(world, pos.x, pos.y, pos.z, yaw, player.getPitch());
        }

        else if (c.equals("player.getPitch")) {
          send(player.getPitch());
        }

        else if (c.equals("player.setPitch")) {
          float pitch = Float.parseFloat(args[0]);

          Vec3d pos = player.getPos();
          player.teleport(world, pos.x, pos.y, pos.z, player.getYaw(), pitch);
        }

        else if (c.equals("player.events.block.hits")) {
          Iterator<BlockEvent> it = BLOCK_EVENTS.iterator();
          LinkedList<BlockEvent> events = new LinkedList<>();
          while (it.hasNext()) {
            BlockEvent event = it.next();
            if (event.entity.equals(player)) {
              events.add(event);
              it.remove();
            }
          }

          send(String.join("|", events.stream().map(event -> event.serialize()).toList()));
        }

        else if (c.equals("player.events.chat.posts")) {
          Iterator<ChatEvent> it = CHAT_EVENTS.iterator();
          LinkedList<ChatEvent> events = new LinkedList<>();
          while (it.hasNext()) {
            ChatEvent event = it.next();
            if (event.entity.equals(player)) {
              events.add(event);
              it.remove();
            }
          }

          send(String.join("|", events.stream().map(event -> event.serialize()).toList()));
        }

        // TODO: projectile events

        else if (c.equals("player.events.clear")) {
          BLOCK_EVENTS.removeIf(event -> event.entity.equals(player));
          CHAT_EVENTS.removeIf(event -> event.entity.equals(player));
        }

      }
    }

    // ENTITY commands

    else if (c.startsWith("entity.")) {
      Entity entity = getEntityFromId(world, Integer.parseInt(args[0]));

      if (entity != null) {
        String[] mArgs = Arrays.copyOfRange(args, 1, args.length);

        if (c.equals("entity.getPos")) {
          Vec3d pos = entity.getPos();
          send(serializePos(pos));
        }

        else if (c.equals("entity.setPos")) {
          Vec3d pos = parsePos(mArgs);
          entity.teleport(pos.x, pos.y, pos.z);
        }

        else if (c.equals("entity.getTile")) {
          Vec3d pos = entity.getPos();
          int x = (int) pos.x;
          int y = (int) pos.y;
          int z = (int) pos.z;
          send(x + "," + y + "," + z);
        }

        else if (c.equals("entity.setTile")) {
          Vec3d pos = parseBlockPos(mArgs).toCenterPos();
          entity.teleport(pos.x, pos.y, pos.z);
        }

        else if (c.equals("entity.getDirection")) {
          double pitchRad = entity.getPitch() * MathHelper.RADIANS_PER_DEGREE;
          double yawRad = entity.getYaw() * MathHelper.RADIANS_PER_DEGREE;

          double x = Math.cos(pitchRad) * Math.cos(yawRad);
          double y = Math.cos(pitchRad) * Math.sin(yawRad);
          double z = Math.sin(pitchRad);

          Vec3d dVec = new Vec3d(x, y, z);

          send(serializePos(dVec.normalize())); // Normalize for safety
        }

        else if (c.equals("entity.setDirection")) {
          Vec3d dVec = parsePos(mArgs).normalize(); // Normalize for safety
          double pitch = Math.asin(-dVec.y) * MathHelper.DEGREES_PER_RADIAN;
          double yaw = Math.atan2(dVec.x, dVec.z) * MathHelper.DEGREES_PER_RADIAN;

          Vec3d pos = entity.getPos();
          entity.teleport(world, pos.x, pos.y, pos.z, Set.of(), (float) yaw, (float) pitch);
        }

        else if (c.equals("entity.getRotation")) {
          send(entity.getYaw());
        }

        else if (c.equals("entity.setRotation")) {
          float yaw = Float.parseFloat(mArgs[0]);

          Vec3d pos = entity.getPos();
          entity.teleport(world, pos.x, pos.y, pos.z, Set.of(), yaw, entity.getPitch());
        }

        else if (c.equals("entity.getPitch")) {
          send(entity.getPitch());
        }

        else if (c.equals("entity.setPitch")) {
          float pitch = Float.parseFloat(mArgs[0]);

          Vec3d pos = entity.getPos();
          entity.teleport(world, pos.x, pos.y, pos.z, Set.of(), entity.getYaw(), pitch);
        }

        else if (c.equals("entity.getName")) {
          String name = entity.getName().getString();
          send(name);
        }

        else if (c.equals("entity.events.block.hits")) {
          Iterator<BlockEvent> it = BLOCK_EVENTS.iterator();
          LinkedList<BlockEvent> events = new LinkedList<>();
          while (it.hasNext()) {
            BlockEvent event = it.next();
            if (event.entity.equals(entity)) {
              events.add(event);
              it.remove();
            }
          }

          send(String.join("|", events.stream().map(event -> event.serialize()).toList()));
        }

        else if (c.equals("entity.events.chat.posts")) {
          Iterator<ChatEvent> it = CHAT_EVENTS.iterator();
          LinkedList<ChatEvent> events = new LinkedList<>();
          while (it.hasNext()) {
            ChatEvent event = it.next();
            if (event.entity.equals(entity)) {
              events.add(event);
              it.remove();
            }
          }

          send(String.join("|", events.stream().map(event -> event.serialize()).toList()));
        }

        // TODO: projectile events

        else if (c.equals("entity.events.clear")) {
          BLOCK_EVENTS.removeIf(event -> event.entity.equals(entity));
          CHAT_EVENTS.removeIf(event -> event.entity.equals(entity));
        }

      }
    }

    // CHAT commands

    else if (c.equals("chat.post")) {
      // Patch the message back together as it was split before
      String chatMessage = String.join(",", args);

      // for (PlayerEntity player : world.getPlayers()) {
      // player.sendMessage(Text.of(chatMessage));
      // }
      sendMessageToAll(world, chatMessage);
    }

    // EVENT commands

    else if (c.startsWith("events")) {

      if (c.equals("events.clear")) {
        BLOCK_EVENTS.clear();
        CHAT_EVENTS.clear();
      }

      if (c.equals("events.block.hits")) {
        Stream<String> events = BLOCK_EVENTS.stream().map(event -> event.serialize());
        send(String.join("|", events.toList()));
        BLOCK_EVENTS.clear();
      }

      else if (c.equals("events.chat.posts")) {
        Stream<String> events = CHAT_EVENTS.stream().map(event -> event.serialize());
        send(String.join("|", events.toList()));
        CHAT_EVENTS.clear();
      }

      // TODO: projectile events

    }

  }

  private void sendMessageToAll(ServerWorld world, String msg) {
    world.getServer().getPlayerManager().broadcast(Text.of(msg), false);
  }

  private ServerPlayerEntity getPlayer(ServerWorld world) {
    ServerPlayerEntity player = world.getServer().getPlayerManager().getPlayerList().get(0);
    if (player == null) {
      McpiMod.LOGGER.warn("*ERROR* no player found.");
    }
    return player;
  }

  private List<ServerPlayerEntity> getPlayerEntitys(ServerWorld world) {
    return world.getServer().getPlayerManager().getPlayerList();
  }

  private void setBlockStates(ServerWorld world, BlockPos pos1, BlockPos pos2, BlockState state) {
    int minX, maxX, minY, maxY, minZ, maxZ;
    minX = pos1.getX() < pos2.getX() ? pos1.getX() : pos2.getX();
    maxX = pos1.getX() >= pos2.getX() ? pos1.getX() : pos2.getX();
    minY = pos1.getY() < pos2.getY() ? pos1.getY() : pos2.getY();
    maxY = pos1.getY() >= pos2.getY() ? pos1.getY() : pos2.getY();
    minZ = pos1.getZ() < pos2.getZ() ? pos1.getZ() : pos2.getZ();
    maxZ = pos1.getZ() >= pos2.getZ() ? pos1.getZ() : pos2.getZ();

    for (int x = minX; x <= maxX; ++x) {
      for (int z = minZ; z <= maxZ; ++z) {
        for (int y = minY; y <= maxY; ++y) {
          world.setBlockState(new BlockPos(x, y, z), state);
        }
      }
    }
  }

  private List<Integer> getBlockStatesAsIds(ServerWorld world, BlockPos pos1, BlockPos pos2) {
    int minX, maxX, minY, maxY, minZ, maxZ;
    minX = pos1.getX() < pos2.getX() ? pos1.getX() : pos2.getX();
    maxX = pos1.getX() >= pos2.getX() ? pos1.getX() : pos2.getX();
    minY = pos1.getY() < pos2.getY() ? pos1.getY() : pos2.getY();
    maxY = pos1.getY() >= pos2.getY() ? pos1.getY() : pos2.getY();
    minZ = pos1.getZ() < pos2.getZ() ? pos1.getZ() : pos2.getZ();
    maxZ = pos1.getZ() >= pos2.getZ() ? pos1.getZ() : pos2.getZ();

    int w = maxX - minX;
    int h = maxY - minY;
    int d = maxZ - minZ;
    List<Integer> blockData = new ArrayList<>(w * h * d);

    int i = 0;
    for (int x = minX; x <= maxX; x++) {
      for (int z = minZ; z <= maxZ; z++) {
        for (int y = minY; y <= maxY; y++) {
          int blockId = blockStateToBlockId(world.getBlockState(new BlockPos(x, y, z)));
          blockData.add(i, blockId);
          i++;
        }
      }
    }

    return blockData;
  }

  // Maybe there are better ways at doing this.
  // An array won't work since some ids between are missing
  /// Turns blockIds from the api to actual ingame block states, see:
  /// https://pimylifeup.com/minecraft-pi-edition-api-reference/
  private BlockState blockIdToBlockState(int id, int subId) {
    switch (id) {
      case 0:
        return Blocks.AIR.getDefaultState();
      case 1:
        return Blocks.STONE.getDefaultState();
      case 2:
        return Blocks.GRASS_BLOCK.getDefaultState();
      case 3:
        return Blocks.DIRT.getDefaultState();
      case 4:
        return Blocks.COBBLESTONE.getDefaultState();
      case 5:
        switch (subId) {
          case 0:
            return Blocks.OAK_PLANKS.getDefaultState();
          case 1:
            return Blocks.SPRUCE_PLANKS.getDefaultState();
          case 2:
            return Blocks.BIRCH_PLANKS.getDefaultState();
          default:
            return Blocks.OAK_PLANKS.getDefaultState();
        }
      case 6:
        switch (subId) {
          case 0:
            return Blocks.OAK_SAPLING.getDefaultState();
          case 1:
            return Blocks.SPRUCE_SAPLING.getDefaultState();
          case 2:
            return Blocks.BIRCH_SAPLING.getDefaultState();
          default:
            return Blocks.OAK_SAPLING.getDefaultState();
        }
      case 7:
        return Blocks.BEDROCK.getDefaultState();
      case 8:
        return Blocks.WATER.getDefaultState();
      case 9:
        return Blocks.WATER.getDefaultState(); // TODO: figure out how to make stationary
      case 10:
        return Blocks.LAVA.getDefaultState();
      case 11:
        return Blocks.LAVA.getDefaultState(); // TODO: figure out how to make stationary
      case 12:
        return Blocks.SAND.getDefaultState();
      case 13:
        return Blocks.GRAVEL.getDefaultState();
      case 14:
        return Blocks.GOLD_ORE.getDefaultState();
      case 15:
        return Blocks.IRON_ORE.getDefaultState();
      case 16:
        return Blocks.COAL_ORE.getDefaultState();
      case 17:
        return Blocks.OAK_WOOD.getDefaultState();
      case 18:
        switch (subId) {
          case 1:
            return Blocks.OAK_LEAVES.getDefaultState();
          case 2:
            return Blocks.SPRUCE_LEAVES.getDefaultState();
          case 3:
            return Blocks.BIRCH_LEAVES.getDefaultState();
          default:
            return Blocks.OAK_LEAVES.getDefaultState();
        }
      case 20:
        return Blocks.GLASS.getDefaultState();
      case 21:
        return Blocks.LAPIS_ORE.getDefaultState();
      case 22:
        return Blocks.LAPIS_BLOCK.getDefaultState();
      case 24:
        switch (subId) {
          case 0:
            return Blocks.SANDSTONE.getDefaultState();
          case 1:
            return Blocks.CHISELED_SANDSTONE.getDefaultState();
          case 2:
            return Blocks.SMOOTH_SANDSTONE.getDefaultState();
          default:
            return Blocks.SANDSTONE.getDefaultState();
        }
      case 26:
        return Blocks.RED_BED.getDefaultState();
      case 30:
        return Blocks.COBWEB.getDefaultState();
      case 31:
        switch (subId) {
          case 0:
            return Blocks.DEAD_BUSH.getDefaultState();
          case 1:
            return Blocks.TALL_GRASS.getDefaultState();
          case 2:
            return Blocks.FERN.getDefaultState();
          default:
            return Blocks.DEAD_BUSH.getDefaultState();
        }
      case 35:
        switch (subId) {
          case 0:
            return Blocks.WHITE_WOOL.getDefaultState();
          case 1:
            return Blocks.ORANGE_WOOL.getDefaultState();
          case 2:
            return Blocks.MAGENTA_WOOL.getDefaultState();
          case 3:
            return Blocks.LIGHT_BLUE_WOOL.getDefaultState();
          case 4:
            return Blocks.YELLOW_WOOL.getDefaultState();
          case 5:
            return Blocks.LIME_WOOL.getDefaultState();
          case 6:
            return Blocks.PINK_WOOL.getDefaultState();
          case 7:
            return Blocks.GRAY_WOOL.getDefaultState();
          case 8:
            return Blocks.LIGHT_GRAY_WOOL.getDefaultState();
          case 9:
            return Blocks.CYAN_WOOL.getDefaultState();
          case 10:
            return Blocks.PURPLE_WOOL.getDefaultState();
          case 11:
            return Blocks.BLUE_WOOL.getDefaultState();
          case 12:
            return Blocks.BROWN_WOOL.getDefaultState();
          case 13:
            return Blocks.GREEN_WOOL.getDefaultState();
          case 14:
            return Blocks.RED_WOOL.getDefaultState();
          case 15:
            return Blocks.BLACK_WOOL.getDefaultState();
          default:
            return Blocks.WHITE_WOOL.getDefaultState();
        }
      case 37:
        return Blocks.DANDELION.getDefaultState();
      case 38:
        return Blocks.CORNFLOWER.getDefaultState();
      case 39:
        return Blocks.BROWN_MUSHROOM.getDefaultState();
      case 40:
        return Blocks.RED_MUSHROOM.getDefaultState();
      case 41:
        return Blocks.GOLD_BLOCK.getDefaultState();
      case 42:
        return Blocks.IRON_BLOCK.getDefaultState();
      case 43:
        switch (subId) {
          case 0:
            return Blocks.STONE_SLAB.getDefaultState().with(SlabBlock.TYPE, SlabType.DOUBLE);
          case 1:
            return Blocks.SANDSTONE_SLAB.getDefaultState().with(SlabBlock.TYPE, SlabType.DOUBLE);
          case 2:
            return Blocks.OAK_SLAB.getDefaultState().with(SlabBlock.TYPE, SlabType.DOUBLE);
          case 3:
            return Blocks.COBBLESTONE_SLAB.getDefaultState().with(SlabBlock.TYPE, SlabType.DOUBLE);
          case 4:
            return Blocks.BRICK_SLAB.getDefaultState().with(SlabBlock.TYPE, SlabType.DOUBLE);
          case 5:
            return Blocks.STONE_BRICK_SLAB.getDefaultState().with(SlabBlock.TYPE, SlabType.DOUBLE);
          default:
            return Blocks.STONE_SLAB.getDefaultState().with(SlabBlock.TYPE, SlabType.DOUBLE);
        }
      case 44:
        switch (subId) {
          case 0:
            return Blocks.STONE_SLAB.getDefaultState();
          case 1:
            return Blocks.SANDSTONE_SLAB.getDefaultState();
          case 2:
            return Blocks.OAK_SLAB.getDefaultState();
          case 3:
            return Blocks.COBBLESTONE_SLAB.getDefaultState();
          case 4:
            return Blocks.BRICK_SLAB.getDefaultState();
          case 5:
            return Blocks.STONE_BRICK_SLAB.getDefaultState();
          default:
            return Blocks.STONE_SLAB.getDefaultState();
        }
      case 45:
        return Blocks.BRICK_WALL.getDefaultState();
      case 46:
        return Blocks.TNT.getDefaultState();
      case 47:
        return Blocks.BOOKSHELF.getDefaultState();
      case 48:
        return Blocks.MOSSY_COBBLESTONE.getDefaultState();
      case 49:
        return Blocks.OBSIDIAN.getDefaultState();
      case 50:
        switch (subId) {
          case 1:
            return Blocks.WALL_TORCH.getDefaultState().with(WallTorchBlock.FACING, Direction.EAST);
          case 2:
            return Blocks.WALL_TORCH.getDefaultState().with(WallTorchBlock.FACING, Direction.WEST);
          case 3:
            return Blocks.WALL_TORCH.getDefaultState().with(WallTorchBlock.FACING, Direction.SOUTH);
          case 4:
            return Blocks.WALL_TORCH.getDefaultState().with(WallTorchBlock.FACING, Direction.NORTH);
          default:
            return Blocks.TORCH.getDefaultState();
        }
      case 51:
        return Blocks.FIRE.getDefaultState();
      case 53:
        switch (subId) {
          case 0:
            return Blocks.OAK_STAIRS.getDefaultState().with(StairsBlock.FACING, Direction.EAST);
          case 1:
            return Blocks.OAK_STAIRS.getDefaultState().with(StairsBlock.FACING, Direction.WEST);
          case 2:
            return Blocks.OAK_STAIRS.getDefaultState().with(StairsBlock.FACING, Direction.SOUTH);
          case 3:
            return Blocks.OAK_STAIRS.getDefaultState().with(StairsBlock.FACING, Direction.NORTH);
          case 4:
            return Blocks.OAK_STAIRS.getDefaultState().with(StairsBlock.FACING, Direction.EAST).with(StairsBlock.HALF,
                BlockHalf.TOP);
          case 5:
            return Blocks.OAK_STAIRS.getDefaultState().with(StairsBlock.FACING, Direction.WEST).with(StairsBlock.HALF,
                BlockHalf.TOP);
          case 6:
            return Blocks.OAK_STAIRS.getDefaultState().with(StairsBlock.FACING, Direction.SOUTH).with(StairsBlock.HALF,
                BlockHalf.TOP);
          case 7:
            return Blocks.OAK_STAIRS.getDefaultState().with(StairsBlock.FACING, Direction.NORTH).with(StairsBlock.HALF,
                BlockHalf.TOP);
          default:
            return Blocks.OAK_STAIRS.getDefaultState();
        }
      case 54:
        switch (subId) {
          case 1:
            return Blocks.CHEST.getDefaultState().with(ChestBlock.FACING, Direction.EAST);
          case 2:
            return Blocks.CHEST.getDefaultState().with(ChestBlock.FACING, Direction.WEST);
          case 3:
            return Blocks.CHEST.getDefaultState().with(ChestBlock.FACING, Direction.SOUTH);
          case 4:
            return Blocks.CHEST.getDefaultState().with(ChestBlock.FACING, Direction.NORTH);
          default:
            return Blocks.CHEST.getDefaultState();
        }
      case 56:
        return Blocks.DIAMOND_ORE.getDefaultState();
      case 57:
        return Blocks.DIAMOND_BLOCK.getDefaultState();
      case 58:
        return Blocks.CRAFTING_TABLE.getDefaultState();
      case 59:
        return Blocks.WHEAT.getDefaultState();
      case 60:
        return Blocks.FARMLAND.getDefaultState();
      case 61:
        return Blocks.FARMLAND.getDefaultState(); // TODO: make inactive
      case 62:
        switch (subId) {
          case 1:
            return Blocks.FURNACE.getDefaultState().with(FurnaceBlock.FACING, Direction.EAST).with(FurnaceBlock.LIT,
                true);
          case 2:
            return Blocks.FURNACE.getDefaultState().with(FurnaceBlock.FACING, Direction.WEST).with(FurnaceBlock.LIT,
                true);
          case 3:
            return Blocks.FURNACE.getDefaultState().with(FurnaceBlock.FACING, Direction.SOUTH).with(FurnaceBlock.LIT,
                true);
          case 4:
            return Blocks.FURNACE.getDefaultState().with(FurnaceBlock.FACING, Direction.NORTH).with(FurnaceBlock.LIT,
                true);
          default:
            return Blocks.FURNACE.getDefaultState().with(FurnaceBlock.LIT, true);
        }
      case 63:
        return Blocks.OAK_SIGN.getDefaultState();
      case 64:
        return Blocks.OAK_DOOR.getDefaultState();
      case 65:
        switch (subId) {
          case 1:
            return Blocks.LADDER.getDefaultState().with(LadderBlock.FACING, Direction.EAST);
          case 2:
            return Blocks.LADDER.getDefaultState().with(LadderBlock.FACING, Direction.WEST);
          case 3:
            return Blocks.LADDER.getDefaultState().with(LadderBlock.FACING, Direction.SOUTH);
          case 4:
            return Blocks.LADDER.getDefaultState().with(LadderBlock.FACING, Direction.NORTH);
          default:
            return Blocks.LADDER.getDefaultState();
        }
      case 67:
        switch (subId) {
          case 0:
            return Blocks.COBBLESTONE_STAIRS.getDefaultState().with(StairsBlock.FACING, Direction.EAST);
          case 1:
            return Blocks.COBBLESTONE_STAIRS.getDefaultState().with(StairsBlock.FACING, Direction.WEST);
          case 2:
            return Blocks.COBBLESTONE_STAIRS.getDefaultState().with(StairsBlock.FACING, Direction.SOUTH);
          case 3:
            return Blocks.COBBLESTONE_STAIRS.getDefaultState().with(StairsBlock.FACING, Direction.NORTH);
          case 4:
            return Blocks.COBBLESTONE_STAIRS.getDefaultState().with(StairsBlock.FACING, Direction.EAST).with(
                StairsBlock.HALF,
                BlockHalf.TOP);
          case 5:
            return Blocks.COBBLESTONE_STAIRS.getDefaultState().with(StairsBlock.FACING, Direction.WEST).with(
                StairsBlock.HALF,
                BlockHalf.TOP);
          case 6:
            return Blocks.COBBLESTONE_STAIRS.getDefaultState().with(StairsBlock.FACING, Direction.SOUTH).with(
                StairsBlock.HALF,
                BlockHalf.TOP);
          case 7:
            return Blocks.COBBLESTONE_STAIRS.getDefaultState().with(StairsBlock.FACING, Direction.NORTH).with(
                StairsBlock.HALF,
                BlockHalf.TOP);
          default:
            return Blocks.COBBLESTONE_STAIRS.getDefaultState();
        }
      case 71:
        return Blocks.IRON_DOOR.getDefaultState();
      case 73:
        return Blocks.REDSTONE_ORE.getDefaultState();
      case 74:
        return Blocks.REDSTONE_ORE.getDefaultState().with(RedstoneOreBlock.LIT, true);
      case 78:
        return Blocks.SNOW.getDefaultState();
      case 79:
        return Blocks.ICE.getDefaultState();
      case 80:
        return Blocks.SNOW_BLOCK.getDefaultState();
      case 81:
        return Blocks.CACTUS.getDefaultState();
      case 82:
        return Blocks.CLAY.getDefaultState();
      case 83:
        return Blocks.SUGAR_CANE.getDefaultState();
      case 85:
        return Blocks.OAK_FENCE.getDefaultState();
      case 87:
        return Blocks.NETHERRACK.getDefaultState();
      case 89:
        return Blocks.GLOWSTONE.getDefaultState();
      case 95:
        return Blocks.BARRIER.getDefaultState();
      case 96:
        return Blocks.OAK_TRAPDOOR.getDefaultState();
      case 98:
        switch (subId) {
          case 0:
            return Blocks.STONE_BRICKS.getDefaultState();
          case 1:
            return Blocks.MOSSY_STONE_BRICKS.getDefaultState();
          case 2:
            return Blocks.CRACKED_STONE_BRICKS.getDefaultState();
          case 3:
            return Blocks.CHISELED_STONE_BRICKS.getDefaultState();
          default:
            return Blocks.STONE_BRICKS.getDefaultState();
        }
      case 102:
        return Blocks.GLASS_PANE.getDefaultState();
      case 103:
        return Blocks.MELON.getDefaultState();
      case 105:
        return Blocks.MELON_STEM.getDefaultState();
      case 107:
        return Blocks.OAK_FENCE_GATE.getDefaultState(); // TODO: implement facing
      case 108:
        return Blocks.STONE_BRICK_STAIRS.getDefaultState();
      case 112:
        return Blocks.NETHER_BRICKS.getDefaultState();
      case 114:
        return Blocks.NETHER_BRICK_STAIRS.getDefaultState();
      case 128:
        return Blocks.SANDSTONE_STAIRS.getDefaultState();
      case 155:
        return Blocks.QUARTZ_BLOCK.getDefaultState();
      case 156:
        return Blocks.QUARTZ_STAIRS.getDefaultState();
      case 245:
        return Blocks.STONECUTTER.getDefaultState();
      case 246:
        return Blocks.CRYING_OBSIDIAN.getDefaultState();
      case 247:
        return Blocks.NETHER_PORTAL.getDefaultState(); // Java Editition doesn't have a nether reactor core
      default:
        return Blocks.AIR.getDefaultState();
    }
  }

  // Same as before. Is there a better way?
  /// Turns ingame block states to api block ids
  private int blockStateToBlockId(BlockState state) {
    if (state.isOf(Blocks.AIR)) {
      return 0;
    } else if (state.isOf(Blocks.STONE)) {
      return 1;
    } else if (state.isOf(Blocks.GRASS_BLOCK)) {
      return 2;
    } else if (state.isOf(Blocks.DIRT)) {
      return 3;
    } else if (state.isOf(Blocks.COBBLESTONE)) {
      return 4;
    } else if (state.isOf(Blocks.OAK_PLANKS)) {
      return 5;
    } else if (state.isOf(Blocks.SPRUCE_PLANKS)) {
      return 5;
    } else if (state.isOf(Blocks.BIRCH_PLANKS)) {
      return 5;
    } else if (state.isOf(Blocks.OAK_SAPLING)) {
      return 6;
    } else if (state.isOf(Blocks.SPRUCE_SAPLING)) {
      return 6;
    } else if (state.isOf(Blocks.BIRCH_SAPLING)) {
      return 6;
    } else if (state.isOf(Blocks.BEDROCK)) {
      return 7;
    } else if (state.isOf(Blocks.WATER)) {
      return 8;
    } else if (state.isOf(Blocks.LAVA)) {
      return 10;
    } else if (state.isOf(Blocks.SAND)) {
      return 12;
    } else if (state.isOf(Blocks.GRAVEL)) {
      return 13;
    } else if (state.isOf(Blocks.GOLD_ORE)) {
      return 14;
    } else if (state.isOf(Blocks.IRON_ORE)) {
      return 15;
    } else if (state.isOf(Blocks.COAL_ORE)) {
      return 16;
    } else if (state.isOf(Blocks.OAK_WOOD)) {
      return 17;
    } else if (state.isOf(Blocks.OAK_LEAVES)) {
      return 18;
    } else if (state.isOf(Blocks.SPRUCE_LEAVES)) {
      return 18;
    } else if (state.isOf(Blocks.BIRCH_LEAVES)) {
      return 18;
    } else if (state.isOf(Blocks.GLASS)) {
      return 20;
    } else if (state.isOf(Blocks.LAPIS_ORE)) {
      return 21;
    } else if (state.isOf(Blocks.LAPIS_BLOCK)) {
      return 22;
    } else if (state.isOf(Blocks.SANDSTONE)) {
      return 24;
    } else if (state.isOf(Blocks.CHISELED_SANDSTONE)) {
      return 24;
    } else if (state.isOf(Blocks.SMOOTH_SANDSTONE)) {
      return 24;
    } else if (state.isOf(Blocks.RED_BED)) {
      return 26;
    } else if (state.isOf(Blocks.COBWEB)) {
      return 30;
    } else if (state.isOf(Blocks.DEAD_BUSH)) {
      return 31;
    } else if (state.isOf(Blocks.TALL_GRASS)) {
      return 31;
    } else if (state.isOf(Blocks.FERN)) {
      return 31;
    } else if (state.isOf(Blocks.WHITE_WOOL)) {
      return 35;
    } else if (state.isOf(Blocks.ORANGE_WOOL)) {
      return 35;
    } else if (state.isOf(Blocks.MAGENTA_WOOL)) {
      return 35;
    } else if (state.isOf(Blocks.LIGHT_BLUE_WOOL)) {
      return 35;
    } else if (state.isOf(Blocks.YELLOW_WOOL)) {
      return 35;
    } else if (state.isOf(Blocks.LIME_WOOL)) {
      return 35;
    } else if (state.isOf(Blocks.PINK_WOOL)) {
      return 35;
    } else if (state.isOf(Blocks.GRAY_WOOL)) {
      return 35;
    } else if (state.isOf(Blocks.LIGHT_GRAY_WOOL)) {
      return 35;
    } else if (state.isOf(Blocks.CYAN_WOOL)) {
      return 35;
    } else if (state.isOf(Blocks.PURPLE_WOOL)) {
      return 35;
    } else if (state.isOf(Blocks.BLUE_WOOL)) {
      return 35;
    } else if (state.isOf(Blocks.BROWN_WOOL)) {
      return 35;
    } else if (state.isOf(Blocks.GREEN_WOOL)) {
      return 35;
    } else if (state.isOf(Blocks.RED_WOOL)) {
      return 35;
    } else if (state.isOf(Blocks.BLACK_WOOL)) {
      return 35;
    } else if (state.isOf(Blocks.DANDELION)) {
      return 37;
    } else if (state.isOf(Blocks.CORNFLOWER)) {
      return 38;
    } else if (state.isOf(Blocks.BROWN_MUSHROOM)) {
      return 39;
    } else if (state.isOf(Blocks.RED_MUSHROOM)) {
      return 40;
    } else if (state.isOf(Blocks.GOLD_BLOCK)) {
      return 41;
    } else if (state.isOf(Blocks.IRON_BLOCK)) {
      return 42;
    } else if (state.isOf(Blocks.STONE_SLAB)) {
      return 43;
    } else if (state.isOf(Blocks.SANDSTONE_SLAB)) {
      return 43;
    } else if (state.isOf(Blocks.OAK_SLAB)) {
      return 43;
    } else if (state.isOf(Blocks.COBBLESTONE_SLAB)) {
      return 43;
    } else if (state.isOf(Blocks.BRICK_SLAB)) {
      return 43;
    } else if (state.isOf(Blocks.STONE_BRICK_SLAB)) {
      return 43;
    } else if (state.isOf(Blocks.BRICK_WALL)) {
      return 45;
    } else if (state.isOf(Blocks.TNT)) {
      return 46;
    } else if (state.isOf(Blocks.BOOKSHELF)) {
      return 47;
    } else if (state.isOf(Blocks.MOSSY_COBBLESTONE)) {
      return 48;
    } else if (state.isOf(Blocks.OBSIDIAN)) {
      return 49;
    } else if (state.isOf(Blocks.WALL_TORCH)) {
      return 50;
    } else if (state.isOf(Blocks.TORCH)) {
      return 50;
    } else if (state.isOf(Blocks.FIRE)) {
      return 51;
    } else if (state.isOf(Blocks.OAK_STAIRS)) {
      return 53;
    } else if (state.isOf(Blocks.CHEST)) {
      return 54;
    } else if (state.isOf(Blocks.DIAMOND_ORE)) {
      return 56;
    } else if (state.isOf(Blocks.DIAMOND_BLOCK)) {
      return 57;
    } else if (state.isOf(Blocks.CRAFTING_TABLE)) {
      return 58;
    } else if (state.isOf(Blocks.FARMLAND)) {
      return 60;
    } else if (state.isOf(Blocks.FURNACE)) {
      return 62;
    } else if (state.isOf(Blocks.OAK_DOOR)) {
      return 64;
    } else if (state.isOf(Blocks.LADDER)) {
      return 65;
    } else if (state.isOf(Blocks.COBBLESTONE_STAIRS)) {
      return 67;
    } else if (state.isOf(Blocks.IRON_DOOR)) {
      return 71;
    } else if (state.isOf(Blocks.REDSTONE_ORE)) {
      return 73;
    } else if (state.isOf(Blocks.SNOW)) {
      return 78;
    } else if (state.isOf(Blocks.ICE)) {
      return 79;
    } else if (state.isOf(Blocks.SNOW_BLOCK)) {
      return 80;
    } else if (state.isOf(Blocks.CACTUS)) {
      return 81;
    } else if (state.isOf(Blocks.CLAY)) {
      return 82;
    } else if (state.isOf(Blocks.SUGAR_CANE)) {
      return 83;
    } else if (state.isOf(Blocks.OAK_FENCE)) {
      return 85;
    } else if (state.isOf(Blocks.GLOWSTONE)) {
      return 89;
    } else if (state.isOf(Blocks.BARRIER)) {
      return 95;
    } else if (state.isOf(Blocks.STONE_BRICKS)) {
      return 98;
    } else if (state.isOf(Blocks.MOSSY_STONE_BRICKS)) {
      return 98;
    } else if (state.isOf(Blocks.CRACKED_STONE_BRICKS)) {
      return 98;
    } else if (state.isOf(Blocks.CHISELED_STONE_BRICKS)) {
      return 98;
    } else if (state.isOf(Blocks.GLASS_PANE)) {
      return 102;
    } else if (state.isOf(Blocks.MELON)) {
      return 103;
    } else if (state.isOf(Blocks.OAK_FENCE_GATE)) {
      return 107;
    } else if (state.isOf(Blocks.CRYING_OBSIDIAN)) {
      return 246;
    } else if (state.isOf(Blocks.NETHER_PORTAL)) {
      return 247;
    } else if (state.isOf(Blocks.CAVE_AIR)) {
      return 0;
    } else if (state.isOf(Blocks.VOID_AIR)) {
      return 0;
    }
    return 1;
  }

  private Entity getEntityFromId(ServerWorld world, int id) {
    Entity entity = world.getEntityById(id);
    if (entity == null) {
      McpiMod.LOGGER.warn("*ERROR* remote session error: Entity not found");
    }
    return entity;
  }

  private BlockPos parseBlockPos(String[] args) {
    int x = (int) Double.parseDouble(args[0]);
    int y = (int) Double.parseDouble(args[1]);
    int z = (int) Double.parseDouble(args[2]);
    return new BlockPos(x, y, z);
  }

  private Vec3d parsePos(String[] args) {
    double x = Double.parseDouble(args[0]);
    double y = Double.parseDouble(args[1]);
    double z = Double.parseDouble(args[2]);
    return new Vec3d(x, y, z);
  }

  private String serializePos(Vec3d pos) {
    return pos.x + "," + pos.y + "," + pos.z;
  }

  private void send(Object a) {
    send(a.toString());
  }

  private void send(String a) {
    if (pendingRemoval)
      return;
    synchronized (outQueue) {
      outQueue.add(a);
    }
  }

  public void close() {
    if (closed)
      return;
    running = false;
    pendingRemoval = true;

    // wait for threads to stop
    try {
      inThread.join(2000);
      outThread.join(2000);
    } catch (InterruptedException e) {
      McpiMod.LOGGER.warn("Failed to stop in/out thread");
      e.printStackTrace();
    }

    try {
      socket.close();
    } catch (Exception e) {
      e.printStackTrace();
    }
    McpiMod.LOGGER.info("Closed connection to" + socket.getRemoteSocketAddress() + ".");
  }

  // Code from:
  // https://github.com/zhuowei/RaspberryJuice/blob/master/src/main/java/net/zhuoweizhang/raspberryjuice/RemoteSession.java
  /** Socket listening thread */
  private class InputThread implements Runnable {
    public void run() {
      McpiMod.LOGGER.info("Starting input thread");
      while (running) {
        try {
          String newLine = in.readLine();
          // System.out.println(newLine);
          if (newLine == null) {
            running = false;
          } else {
            inQueue.add(newLine);
            // System.out.println("Added to in queue");
          }
        } catch (Exception e) {
          // if its running raise an error
          if (running) {
            if (e.getMessage().equals("Connection reset")) {
              McpiMod.LOGGER.info("Connection reset");
            } else {
              e.printStackTrace();
            }
            running = false;
          }
        }
      }
      // close in buffer
      try {
        in.close();
      } catch (Exception e) {
        McpiMod.LOGGER.warn("Failed to close in buffer");
        e.printStackTrace();
      }
    }
  }

  private class OutputThread implements Runnable {
    public void run() {
      McpiMod.LOGGER.info("Starting output thread!");
      while (running) {
        try {
          String line;
          while ((line = outQueue.poll()) != null) {
            out.write(line);
            out.write('\n');
          }
          out.flush();
          Thread.yield();
          Thread.sleep(1L);
        } catch (Exception e) {
          // if its running raise an error
          if (running) {
            e.printStackTrace();
            running = false;
          }
        }
      }
      // close out buffer
      try {
        out.close();
      } catch (Exception e) {
        McpiMod.LOGGER.warn("Failed to close out buffer");
        e.printStackTrace();
      }
    }
  }

}
