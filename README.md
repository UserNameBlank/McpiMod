# MCPI Mod

A Fabric Mod which implements most of the Minecraft Pi Socket API. Currently only for 1.20.4. WIP

## Options

You can configure the mod with custom gamerules:

- `mcpiMaxCommandsPerTick` defines the maximum number of commands executed per game tick.

## Commands

### Commands supported

Minecraft Pi:

- `world.get/setBlock`
- `world.setBlocks`
- `world.getPlayerIds`
- `world.getBlocks`
- `chat.post`
- `player.getTile`
- `player.setTile`
- `player.getPos`
- `player.setPos`
- `world.getHeight`
- `entity.getTile`
- `entity.setTile`
- `entity.getPos`
- `entity.setPos`

RaspberryJuice:

- `getBlocks(x1,y1,z1,x2,y2,z2)`
- `getDirection, getRotation, getPitch`
- `setDirection, setRotation, setPitch`
- `getPlayerId(playerName)`
- `entity.getName(id)`

### Commands not supported (yet)

- `world.saveCheckpoint`
- `world.restoreCheckpoint`
- `world.getBlockWithData`
- setting (will probably be never implemented)
- camera api
- event api

# License

Copyright 2012-2024 RaspberryJuice project

Copyright 2024 UserNameBlank

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.

Parts of this code were originally created by the RaspberryJuice project, which you can find here:
https://github.com/zhuowei/RaspberryJuice

All copyright to the original code belongs to the creators of said project.
