package mcpimod.utils;

import net.minecraft.entity.Entity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

public class BlockEvent {

  public Entity entity;
  public BlockPos position;
  public Direction direction;

  public BlockEvent(Entity entity, BlockPos position, Direction direction) {
    this.entity = entity;
    this.position = position;
    this.direction = direction;
  }

  public String serialize() {
    return serializeBlockPos(position) +
        "," +
        direction.getId() +
        "," +
        entity.getId();
  }

  private static String serializeBlockPos(BlockPos pos) {
    return pos.getX() + "," + pos.getY() + "," + pos.getZ();
  }

}
