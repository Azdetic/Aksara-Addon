package com.example.addon.modules;

import com.example.addon.AddonTemplate;
import meteordevelopment.meteorclient.events.game.ReceiveMessageEvent;
import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.events.game.GameLeftEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.player.ChatUtils;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.PlayerListEntry;
import net.minecraft.network.packet.c2s.play.RequestCommandCompletionsC2SPacket;
import net.minecraft.network.packet.s2c.play.CommandSuggestionsS2CPacket;
import net.minecraft.world.GameMode;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class AntiStaff extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgDetection = settings.createGroup("Detection");
    private final SettingGroup sgActions = settings.createGroup("Actions");
    private final SettingGroup sgPlayers = settings.createGroup("Players");

    // General Settings
    private final Setting<Boolean> enableVanishDetection = sgGeneral.add(new BoolSetting.Builder()
        .name("vanish-detection")
        .description("Detect when monitored players go into vanish mode.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> enableGamemodeDetection = sgGeneral.add(new BoolSetting.Builder()
        .name("gamemode-detection")
        .description("Detect when monitored players change gamemode.")
        .defaultValue(true)
        .build()
    );

    // Gamemode Detection Settings
    private final Setting<Boolean> detectSurvival = sgGeneral.add(new BoolSetting.Builder()
        .name("detect-survival")
        .description("Detect changes to Survival mode.")
        .defaultValue(true)
        .visible(() -> enableGamemodeDetection.get())
        .build()
    );

    private final Setting<Boolean> detectCreative = sgGeneral.add(new BoolSetting.Builder()
        .name("detect-creative")
        .description("Detect changes to Creative mode.")
        .defaultValue(true)
        .visible(() -> enableGamemodeDetection.get())
        .build()
    );

    private final Setting<Boolean> detectAdventure = sgGeneral.add(new BoolSetting.Builder()
        .name("detect-adventure")
        .description("Detect changes to Adventure mode.")
        .defaultValue(true)
        .visible(() -> enableGamemodeDetection.get())
        .build()
    );

    private final Setting<Boolean> detectSpectator = sgGeneral.add(new BoolSetting.Builder()
        .name("detect-spectator")
        .description("Detect changes to Spectator mode.")
        .defaultValue(true)
        .visible(() -> enableGamemodeDetection.get())
        .build()
    );

    private final Setting<Boolean> enableStaffAutoDetect = sgGeneral.add(new BoolSetting.Builder()
        .name("auto-detect-staff")
        .description("Automatically detect and monitor staff members.")
        .defaultValue(true)
        .build()
    );

    // Detection Settings
    private final Setting<String> vanishCommand = sgDetection.add(new StringSetting.Builder()
        .name("vanish-command")
        .description("Command for vanish detection (most accurate method).")
        .defaultValue("minecraft:msg")
        .visible(() -> enableVanishDetection.get())
        .build()
    );

    private final Setting<Integer> vanishCheckInterval = sgDetection.add(new IntSetting.Builder()
        .name("vanish-interval")
        .description("Interval between vanish checks in ticks (20 = 1 second).")
        .defaultValue(100)
        .min(20)
        .max(600)
        .sliderMax(200)
        .visible(() -> enableVanishDetection.get())
        .build()
    );

    private final Setting<Integer> detectionCooldown = sgDetection.add(new IntSetting.Builder()
        .name("detection-cooldown")
        .description("Cooldown between detections in seconds (0 = no cooldown, 30 = 30 seconds).")
        .defaultValue(10)
        .min(0)
        .max(300)
        .sliderMax(60)
        .build()
    );

    // Staff Prefix System
    private final Setting<Boolean> enableCustomPrefixes = sgDetection.add(new BoolSetting.Builder()
        .name("enable-custom-prefixes")
        .description("Enable custom staff prefix detection.")
        .defaultValue(true)
        .visible(() -> enableStaffAutoDetect.get())
        .build()
    );

    private final Setting<List<String>> customPrefixes = sgDetection.add(new StringListSetting.Builder()
        .name("custom-prefixes")
        .description("Custom staff prefixes to detect. Examples: '[ADMIN]', '[MOD]', 'Owner', etc.")
        .defaultValue(List.of("[ADMIN]", "[MOD]", "[HELPER]", "[STAFF]", "[OWNER]", "Admin", "Mod", "Helper", "Owner"))
        .visible(() -> enableStaffAutoDetect.get() && enableCustomPrefixes.get())
        .build()
    );

    // Action Settings
    public enum ActionType {
        DISCONNECT("Disconnect from server"),
        COMMAND("Execute command"),
        NOTIFICATION_ONLY("Show notification only");

        private final String title;

        ActionType(String title) {
            this.title = title;
        }

        @Override
        public String toString() {
            return title;
        }
    }

    private final Setting<ActionType> actionType = sgActions.add(new EnumSetting.Builder<ActionType>()
        .name("action-type")
        .description("Action to take when staff is detected.")
        .defaultValue(ActionType.DISCONNECT)
        .build()
    );

    private final Setting<Integer> disconnectDelay = sgActions.add(new IntSetting.Builder()
        .name("disconnect-delay")
        .description("Delay before disconnect in seconds.")
        .defaultValue(2)
        .min(0)
        .max(30)
        .sliderMax(10)
        .visible(() -> actionType.get() == ActionType.DISCONNECT)
        .build()
    );

    private final Setting<String> customCommand = sgActions.add(new StringSetting.Builder()
        .name("custom-command")
        .description("Command to execute when staff detected (without /).")
        .defaultValue("disconnect")
        .visible(() -> actionType.get() == ActionType.COMMAND)
        .build()
    );

    private final Setting<String> disconnectReason = sgActions.add(new StringSetting.Builder()
        .name("disconnect-reason")
        .description("Reason shown when disconnecting.")
        .defaultValue("Staff vanish/gamemode change")
        .visible(() -> actionType.get() == ActionType.DISCONNECT)
        .build()
    );

    private final Setting<Boolean> autoDisableAfterDisconnect = sgActions.add(new BoolSetting.Builder()
        .name("auto-disable-after-disconnect")
        .description("Automatically disable Anti-Staff after disconnecting to avoid reconnection issues")
        .defaultValue(true)
        .visible(() -> actionType.get() == ActionType.DISCONNECT)
        .build()
    );

    private final Setting<Boolean> showNotifications = sgActions.add(new BoolSetting.Builder()
        .name("show-notifications")
        .description("Show notifications when staff is detected.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> playSoundAlert = sgActions.add(new BoolSetting.Builder()
        .name("sound-alert")
        .description("Play sound when staff is detected.")
        .defaultValue(true)
        .build()
    );

    // Player Management (Combined)
    private final Setting<Boolean> monitorAllPlayers = sgPlayers.add(new BoolSetting.Builder()
        .name("monitor-all-players")
        .description("Monitor ALL players on the server (ignores player list).")
        .defaultValue(false)
        .build()
    );

    private final Setting<List<String>> playerList = sgPlayers.add(new StringListSetting.Builder()
        .name("player-list")
        .description("Specific players to monitor. Add usernames here to monitor for staff activity.")
        .defaultValue(List.of())
        .visible(() -> !monitorAllPlayers.get())
        .build()
    );

    // Internal tracking
    private final Map<String, PlayerState> playerStates = new ConcurrentHashMap<>();
    private final Map<String, Long> lastDetectionTime = new ConcurrentHashMap<>();
    private final Set<String> detectedStaff = new HashSet<>();
    private ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    private boolean disconnectedByAntiStaff = false; // Track if we triggered the disconnect

    // Command completion vanish detection (most accurate)
    private final List<Integer> completionIDs = new ArrayList<>();
    private List<String> completionPlayerCache = new ArrayList<>();
    private final Random random = new Random();
    private long lastTickTime = 0;
    private int vanishTimer = 0;    // Player state tracking (simplified for command completion detection)
    private static class PlayerState {
        GameMode lastGameMode = null;
        boolean isStaff = false;

        PlayerState(GameMode gameMode, boolean staff) {
            this.lastGameMode = gameMode;
            this.isStaff = staff;
        }
    }

    public AntiStaff() {
        super(AddonTemplate.CATEGORY, "anti-staff", "Monitors staff members for vanish and gamemode changes with auto-disconnect.");
    }

    @Override
    public void onActivate() {
        playerStates.clear();
        lastDetectionTime.clear();
        detectedStaff.clear();
        disconnectedByAntiStaff = false; // Reset disconnect flag

        if (scheduler == null || scheduler.isShutdown()) {
            scheduler = Executors.newSingleThreadScheduledExecutor();
        }

        if (showNotifications.get()) {
            ChatUtils.info("Anti-Staff activated - Monitoring staff activity...");
            if (autoDisableAfterDisconnect.get()) {
                ChatUtils.info("Auto-disable after disconnect: ENABLED - Module will turn off after disconnecting");
            }
        }
    }

    @Override
    public void onDeactivate() {
        if (scheduler != null && !scheduler.isShutdown()) {
            scheduler.shutdown();
        }

        playerStates.clear();
        lastDetectionTime.clear();
        detectedStaff.clear();
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null || mc.world == null) return;

        // Check if we disconnected and should auto-disable
        if (disconnectedByAntiStaff && autoDisableAfterDisconnect.get()) {
            // Reset flag and disable module
            disconnectedByAntiStaff = false;
            if (isActive()) {
                ChatUtils.info("Anti-Staff: Auto-disabling after disconnect to prevent reconnection issues");
                toggle();
                return;
            }
        }

        // Limit tick frequency to avoid performance issues
        long currentTime = System.currentTimeMillis();
        if (currentTime - lastTickTime < 1000) return; // Check every second
        lastTickTime = currentTime;

        // Enhanced vanish detection timer - always use command completion
        vanishTimer++;
        if (vanishTimer >= vanishCheckInterval.get()) {
            performCommandCompletionCheck();
            vanishTimer = 0;
        }

        updatePlayerStates();
    }

    @EventHandler
    private void onReceiveMessage(ReceiveMessageEvent event) {
        if (!isActive()) return;

        String message = event.getMessage().getString();

        // Check for join/leave messages to detect staff
        if (enableStaffAutoDetect.get()) {
            checkJoinLeaveMessages(message);
        }
    }

    @EventHandler
    private void onGameLeft(GameLeftEvent event) {
        if (autoDisableAfterDisconnect.get() && disconnectedByAntiStaff) {
            // Wait a short moment then disable the module
            new Thread(() -> {
                try {
                    Thread.sleep(500); // Give the client time to properly disconnect
                    if (isActive()) {
                        ChatUtils.info("Anti-Staff: Auto-disabling after disconnect to allow reconnection");
                        toggle();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }).start();
        }
    }

    @EventHandler
    private void onPacketReceive(PacketEvent.Receive event) {
        if (!isActive()) return;

        // Command completion vanish detection (most accurate)
        if (enableVanishDetection.get() &&
            event.packet instanceof CommandSuggestionsS2CPacket) {

            CommandSuggestionsS2CPacket packet = (CommandSuggestionsS2CPacket) event.packet;
            if (completionIDs.contains(packet.id())) {
                processCommandCompletions(packet);
                event.cancel();
            }
        }
    }

    private void updatePlayerStates() {
        if (mc.getNetworkHandler() == null) return;

        Set<String> currentPlayers = new HashSet<>();

        // Get current player list
        for (PlayerListEntry entry : mc.getNetworkHandler().getPlayerList()) {
            if (entry.getProfile() == null || entry.getProfile().getName() == null) continue;

            String playerName = entry.getProfile().getName();
            currentPlayers.add(playerName);

            // Check if this player should be monitored
            if (shouldMonitorPlayer(playerName)) {
                boolean isStaff = isStaffMember(playerName);
                GameMode currentGameMode = entry.getGameMode();

                PlayerState previousState = playerStates.get(playerName);

                if (previousState == null) {
                    // New player detected
                    playerStates.put(playerName, new PlayerState(currentGameMode, isStaff));

                    if (isStaff) {
                        onStaffDetected(playerName, "joined server", currentGameMode);
                    }
                } else {
                    // Check for gamemode changes
                    if (enableGamemodeDetection.get() &&
                        previousState.lastGameMode != null &&
                        !previousState.lastGameMode.equals(currentGameMode)) {

                        onGamemodeChanged(playerName, previousState.lastGameMode, currentGameMode);
                    }

                    // Update state
                    previousState.lastGameMode = currentGameMode;
                    previousState.isStaff = isStaff;
                }
            }
        }

        // Check for vanished players - not needed with command completion
        // Command completion method is more accurate
    }

    private void performCommandCompletionCheck() {
        if (mc.getNetworkHandler() == null) return;

        int id = random.nextInt(200);
        completionIDs.add(id);
        mc.getNetworkHandler().sendPacket(new RequestCommandCompletionsC2SPacket(id, vanishCommand.get() + " "));
    }

    private void processCommandCompletions(CommandSuggestionsS2CPacket packet) {
        List<String> lastUsernames = new ArrayList<>(completionPlayerCache);

        completionPlayerCache.clear();
        for (var suggestion : packet.getSuggestions().getList()) {
            completionPlayerCache.add(suggestion.getText());
        }

        if (lastUsernames.isEmpty()) return;

        // Check for players who joined or left
        for (String playerName : completionPlayerCache) {
            if (playerName.equals(mc.player.getName().getString())) continue;
            if (playerName.contains(" ") || playerName.length() < 3 || playerName.length() > 16) continue;

            if (!lastUsernames.contains(playerName) && shouldMonitorPlayer(playerName)) {
                ChatUtils.info("Player joined: " + playerName);
            }
        }

        for (String playerName : lastUsernames) {
            if (playerName.equals(mc.player.getName().getString())) continue;
            if (playerName.contains(" ") || playerName.length() < 3 || playerName.length() > 16) continue;

            if (!completionPlayerCache.contains(playerName) && shouldMonitorPlayer(playerName)) {
                onStaffDetected(playerName, "vanished (detected via command completion)", null);
            }
        }

        completionIDs.remove(Integer.valueOf(packet.id()));
    }    private void checkJoinLeaveMessages(String message) {
        // Common join message patterns
        if (message.contains(" joined the game") || message.contains(" joined the server")) {
            String playerName = extractPlayerFromJoinMessage(message);
            if (playerName != null && isStaffMember(playerName)) {
                onStaffDetected(playerName, "joined via message", null);
            }
        }
    }

    private String extractPlayerFromJoinMessage(String message) {
        // Extract player name from join message
        if (message.contains(" joined the game")) {
            return message.substring(0, message.indexOf(" joined the game")).trim();
        } else if (message.contains(" joined the server")) {
            return message.substring(0, message.indexOf(" joined the server")).trim();
        }
        return null;
    }

    private boolean shouldMonitorPlayer(String playerName) {
        // If monitoring all players is enabled, monitor everyone
        if (monitorAllPlayers.get()) {
            return true;
        }

        // Check if player is in our manual monitoring list
        return playerList.get().contains(playerName) ||
               (enableStaffAutoDetect.get() && isStaffMember(playerName));
    }

    private boolean isStaffMember(String playerName) {
        if (!enableStaffAutoDetect.get() || !enableCustomPrefixes.get()) return false;

        String lowerName = playerName.toLowerCase();

        // Check against custom prefixes list
        for (String prefix : customPrefixes.get()) {
            if (lowerName.contains(prefix.toLowerCase())) {
                return true;
            }
        }

        return false;
    }

    private void onStaffDetected(String playerName, String reason, GameMode gameMode) {
        if (!canTriggerDetection(playerName)) return;

        detectedStaff.add(playerName);
        lastDetectionTime.put(playerName, System.currentTimeMillis());

        String gameModeText = gameMode != null ? " (Gamemode: " + gameMode.getName() + ")" : "";
        String alertMessage = "§c[Staff Alert] §f" + playerName + " detected - " + reason + gameModeText;

        if (showNotifications.get()) {
            ChatUtils.info(alertMessage);
        }

        if (playSoundAlert.get()) {
            // Sound alert disabled temporarily due to compatibility issues
            AddonTemplate.LOG.info("Sound alert triggered for: " + playerName);
        }

        AddonTemplate.LOG.info("Staff detected: " + playerName + " - " + reason);

        if (actionType.get() == ActionType.DISCONNECT) {
            scheduleDisconnect(playerName, reason);
        } else if (actionType.get() == ActionType.COMMAND) {
            executeCustomCommand();
        }
    }

    private void onGamemodeChanged(String playerName, GameMode oldMode, GameMode newMode) {
        // Check if we should detect this specific gamemode change
        boolean shouldDetect = false;

        if (newMode == GameMode.SURVIVAL && detectSurvival.get()) shouldDetect = true;
        if (newMode == GameMode.CREATIVE && detectCreative.get()) shouldDetect = true;
        if (newMode == GameMode.ADVENTURE && detectAdventure.get()) shouldDetect = true;
        if (newMode == GameMode.SPECTATOR && detectSpectator.get()) shouldDetect = true;

        if (!shouldDetect) return;

        String reason = "gamemode changed from " + oldMode.getName() + " to " + newMode.getName();
        onStaffDetected(playerName, reason, newMode);
    }

    private boolean canTriggerDetection(String playerName) {
        long currentTime = System.currentTimeMillis();
        Long lastTime = lastDetectionTime.get(playerName);

        if (lastTime == null) return true;

        long cooldownMs = detectionCooldown.get() * 1000L;
        return (currentTime - lastTime) >= cooldownMs;
    }

    private void scheduleDisconnect(String playerName, String reason) {
        int delay = disconnectDelay.get();

        if (scheduler == null || scheduler.isShutdown()) {
            scheduler = Executors.newSingleThreadScheduledExecutor();
        }

        scheduler.schedule(() -> {
            if (mc.world != null && isActive()) {
                ChatUtils.info("§c[Anti-Staff] Disconnecting due to: " + playerName + " (" + reason + ")");

                if (mc.world != null) {
                    mc.world.disconnect();
                }
            }
        }, delay, TimeUnit.SECONDS);

        if (showNotifications.get()) {
            ChatUtils.info("§e[Anti-Staff] Disconnecting in " + delay + " seconds...");
        }
    }

    private void executeCustomCommand() {
        String command = customCommand.get();
        if (!command.isEmpty()) {
            if (mc.player != null) {
                ChatUtils.sendPlayerMsg("/" + command);
            }
        }
    }

    // Public methods for external access
    public Set<String> getDetectedStaff() {
        return new HashSet<>(detectedStaff);
    }

    public void addPlayerToList(String playerName) {
        List<String> current = new ArrayList<>(playerList.get());
        if (!current.contains(playerName)) {
            current.add(playerName);
            playerList.set(current);
        }
    }

    public void removePlayerFromList(String playerName) {
        List<String> current = new ArrayList<>(playerList.get());
        current.remove(playerName);
        playerList.set(current);
    }

    public void addCustomPrefix(String prefix) {
        List<String> current = new ArrayList<>(customPrefixes.get());
        if (!current.contains(prefix)) {
            current.add(prefix);
            customPrefixes.set(current);
        }
    }

    public Map<String, PlayerState> getPlayerStates() {
        return new HashMap<>(playerStates);
    }
}
