package mcpimod.utils;

import net.minecraft.server.network.ServerPlayerEntity;

public class ChatEvent {

  public ServerPlayerEntity entity;
  public String message;

  public ChatEvent(ServerPlayerEntity entity, String message) {
    this.entity = entity;
    this.message = message;
  }

  public String serialize() {
    return entity.getId() +
        ", " +
        message;
  }

}
