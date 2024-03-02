/* Copyright 2024 UserNameBlank
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
 */

package mcpimod;

import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.gamerule.v1.GameRuleFactory;
import net.fabricmc.fabric.api.gamerule.v1.GameRuleRegistry;
import net.minecraft.world.GameRules;
import net.minecraft.world.GameRules.Category;

public class McpiMod implements ModInitializer {

  public static final Logger LOGGER = LoggerFactory.getLogger("mcpimod");

  public static final GameRules.Key<GameRules.IntRule> MAX_COMMANDS_PER_TICK = GameRuleRegistry
      .register("mcpiMaxCommandsPerTick", Category.UPDATES,
          GameRuleFactory.createIntRule(9000, 0, Integer.MAX_VALUE, RemoteSession::setMaxCommandsPerTick));

  public ServerListenerThread serverThread;

  public List<RemoteSession> sessions;

  @Override
  public void onInitialize() {
    LOGGER.info("Initializing McpiMod");

    sessions = new ArrayList<>();

    try {
      serverThread = new ServerListenerThread(this, new InetSocketAddress(4711));
      new Thread(serverThread).start();
      LOGGER.info("ThreadListener started");
    } catch (Exception e) {
      e.printStackTrace();
      LOGGER.warn("*ERROR* failed to start ThreadListener");
      LOGGER.warn("McpiMod Initialization failed.");
      return;
    }

    // sync the static variable with the game rule
    ServerLifecycleEvents.SERVER_STARTED.register(server -> {
      RemoteSession.MAX_COMMANDS_PER_TICK = server.getGameRules().getInt(MAX_COMMANDS_PER_TICK);
    });

    // Registering a tick event to execute the commands from the remote sessions
    ServerTickEvents.START_SERVER_TICK.register(server -> {
      Iterator<RemoteSession> iter = sessions.iterator();
      while (iter.hasNext()) {
        RemoteSession s = iter.next();
        if (s.pendingRemoval) {
          s.close();
          iter.remove();
        } else {
          s.tick(server.getOverworld());
        }
      }
    });
  }

  public void handleConnection(RemoteSession session) {
    synchronized (sessions) {
      sessions.add(session);
    }
  }
}
