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

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketAddress;

public class ServerListenerThread implements Runnable {

  public ServerSocket serverSocket;

  public SocketAddress bindAddress;

  public boolean running = true;

  private McpiMod mod;

  public ServerListenerThread(McpiMod mod, SocketAddress bindAddress) throws IOException {
    this.mod = mod;
    this.bindAddress = bindAddress;
    serverSocket = new ServerSocket();
    serverSocket.setReuseAddress(true);
    serverSocket.bind(bindAddress);
  }

  @Override
  public void run() {
    while (running) {
      try {
        Socket newConnection = serverSocket.accept();
        if (!running)
          return;
        mod.handleConnection(new RemoteSession(newConnection));
      } catch (Exception e) {
        if (running) {
          McpiMod.LOGGER.warn("*ERROR* creating new connection");
          e.printStackTrace();
        }
      }
    }
    try {
      serverSocket.close();
    } catch (Exception e) {
      McpiMod.LOGGER.warn("*ERROR* closing server socket");
      e.printStackTrace();
    }
  }

}
