# Aksara Addons

🚧 **Still in Development** - This addon is actively being developed and will continue to receive updates!

## Current Status

-   ✅ Available for Minecraft 1.21.4
-   🔄 Actively maintained and updated
-   📈 More features coming soon

## List of Aksara Modules

-   🤖 [Auto Reply](#auto-reply) - Automatically responds to chat messages with custom triggers

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


### Using Commands
Open chat and type commands to manage your auto-reply settings:

```
.autoreply add "hello" "Hi there!"
.autoreply list
```
