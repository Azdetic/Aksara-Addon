# Aksara Addons

🚧 **Still in Development** - This add![Auto Reply Commands](images/command-examples.png)
_Example of using Auto Reply commands in chat_

### Auto ExpBottle

Auto ExpBottle is an intelligent armor repair system that automatically throws experience bottles when your armor durability drops below a specified threshold.

#### Features

-   **Smart Durability Monitoring**: Continuously monitors all armor pieces for durability levels
-   **Configurable Threshold**: Set custom durability percentage (1-99%) to trigger auto repair
-   **Selective Armor Checking**: Choose which armor pieces to monitor (helmet, chestplate, leggings, boots)
-   **Flexible Repair Options**: Continue until 100% durability or stop after reaching threshold
-   **Auto Item Management**: Automatically finds and switches to exp bottles, then switches back
-   **Customizable Timing**: Adjust delay between throws to avoid spam

#### Settings

-   **Durability Threshold**: Percentage below which to start throwing exp bottles (default: 50%)
-   **Throw Delay**: Delay between throws in ticks (default: 10 ticks = 0.5 seconds)
-   **Continue Until Full**: Keep throwing until all armor reaches 100% durability
-   **Switch Back**: Automatically return to previous item after throwing
-   **Armor Selection**: Individual toggles for helmet, chestplate, leggings, and boots

#### Usage

1. Enable the Auto ExpBottle module in Meteor Client GUI under "Aksara" category
2. Configure your durability threshold and timing preferences
3. Select which armor pieces to monitor
4. The module will automatically throw exp bottles when armor durability drops below threshold

#### How It Works

The module continuously scans your equipped armor pieces and calculates their durability percentages. When any monitored armor piece falls below your configured threshold, it automatically:

1. Locates experience bottles in your inventory
2. Switches to the exp bottle (temporarily if needed)
3. Throws the bottle to repair armor via experience
4. Returns to your previous item (if enabled)
5. Repeats until armor is sufficiently repaired is actively being developed and will continue to receive updates!

<p align="center">
    <img src="src/main/resources/assets/template/icon.png" alt="Aksara Addons GUI" width="120" style="max-width:120px; border-radius:8px;" />
    <br />
</p>

## Current Status

-   ✅ Available for Minecraft 1.21.4
-   🔄 Actively maintained and updated
-   📈 More features coming soon

## List of Aksara Modules

-   🤖 [Auto Reply](#auto-reply) - Automatically responds to chat messages with custom triggers
-   🧪 [Auto ExpBottle](#auto-expbottle) - Automatically throws experience bottles to repair armor when durability is low

## Modules

### Auto Reply

Auto Reply is a powerful chat automation module that monitors incoming chat messages and responds automatically based on your configured triggers.

#### Features

-   **Dynamic Trigger System**: Add unlimited trigger-reply pairs with easy management
-   **Advanced Delay Control**: Configurable base delay and random delay with min/max settings
-   **Flexible Delay Options**: Toggle between base delay, random delay, or both
-   **Case Sensitivity**: Option to match triggers with exact case
-   **Chat Commands**: Manage triggers via in-game commands
-   **Easy Configuration**: Simple GUI settings for managing triggers and replies

#### Usage

1. Enable the Auto Reply module in Meteor Client GUI under "Aksara" category
2. Configure your trigger-reply pairs using GUI settings or chat commands
3. The module will automatically respond when someone types your triggers in chat

#### Commands

-   `.autoreply list` - Show all configured trigger-reply pairs
-   `.autoreply add "trigger" "reply"` - Add new trigger-reply pair
-   `.autoreply remove <index>` - Remove trigger-reply pair by number
-   `.autoreply clear` - Clear all trigger-reply pairs
-   `.autoreply status` - Show module status and pair count

#### Example

```
.autoreply add "hello" "Hi there!"
.autoreply add "how are you" "I'm doing great, thanks!"
.autoreply list
```

![Auto Reply Commands](images/command-examples.png)
_Example of using Auto Reply commands in chat_

## Installation

### Quick Download

1. Download the latest JAR file from the releases section
2. Place the JAR file in your Minecraft `mods` folder alongside Meteor Client
3. Launch Minecraft 1.21.4 with Fabric Loader

### Build from Source

Want to try the latest development version? Clone and build it yourself:

```bash
git clone https://github.com/Azdetic/Aksara-Addon.git
cd aksara-addons
./gradlew build
```

The compiled JAR file will be available in `build/libs/` folder.
