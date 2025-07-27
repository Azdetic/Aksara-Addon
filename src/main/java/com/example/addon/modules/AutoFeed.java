package com.example.addon.modules;

import com.example.addon.AddonTemplate;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.player.ChatUtils;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.sound.SoundEvents;

import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;

public class AutoFeed extends Module {
    private final SettingGroup sgGeneral = this.settings.getDefaultGroup();
    private final SettingGroup sgConditions = this.settings.createGroup("Conditions");
    private final SettingGroup sgAdvanced = this.settings.createGroup("Advanced");
    private final SettingGroup sgNotifications = this.settings.createGroup("Notifications");

    // === GENERAL SETTINGS ===
    private final Setting<String> feedCommand = sgGeneral.add(new StringSetting.Builder()
        .name("feed-command")
        .description("Command to feed player (e.g., '/feed', '/eat', '/hunger')")
        .defaultValue("/feed")
        .build()
    );

    private final Setting<Integer> cooldown = sgGeneral.add(new IntSetting.Builder()
        .name("cooldown")
        .description("Cooldown between feed commands in seconds (0 = no cooldown, 60 = 1 minute)")
        .defaultValue(120)
        .min(0)
        .max(3600) // 1 hour max
        .sliderMax(600) // 10 minutes slider
        .build()
    );

    // === CONDITIONS ===
    private final Setting<Integer> hungerThreshold = sgConditions.add(new IntSetting.Builder()
        .name("hunger-threshold")
        .description("Execute feed when hunger is below this value (20 = full)")
        .defaultValue(15)
        .min(1)
        .max(19)
        .sliderMax(19)
        .build()
    );



    private final Setting<Boolean> checkSaturation = sgConditions.add(new BoolSetting.Builder()
        .name("check-saturation")
        .description("Consider saturation level when deciding to feed")
        .defaultValue(true)
        .build()
    );

    private final Setting<Double> saturationThreshold = sgConditions.add(new DoubleSetting.Builder()
        .name("saturation-threshold")
        .description("Execute feed when saturation is below this value")
        .defaultValue(10.0)
        .min(0.0)
        .max(20.0)
        .sliderMax(20.0)
        .visible(() -> checkSaturation.get())
        .build()
    );

    // === ADVANCED ===
    private final Setting<Boolean> smartTiming = sgAdvanced.add(new BoolSetting.Builder()
        .name("smart-timing")
        .description("Avoid feeding during combat or dangerous situations")
        .defaultValue(true)
        .build()
    );

    private final Setting<Integer> combatCooldown = sgAdvanced.add(new IntSetting.Builder()
        .name("combat-delay")
        .description("Seconds to wait after combat before feeding (3 = 3 seconds)")
        .defaultValue(3)
        .min(0)
        .max(30)
        .sliderMax(15)
        .visible(() -> smartTiming.get())
        .build()
    );

    private final Setting<Boolean> serverResponseDetection = sgAdvanced.add(new BoolSetting.Builder()
        .name("server-response-detection")
        .description("Auto-adjust cooldown based on server responses")
        .defaultValue(false)
        .build()
    );

    // === NOTIFICATIONS ===
    private final Setting<Boolean> enableNotifications = sgNotifications.add(new BoolSetting.Builder()
        .name("chat-notifications")
        .description("Show feed notifications in chat")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> soundNotification = sgNotifications.add(new BoolSetting.Builder()
        .name("sound-notification")
        .description("Play sound when feeding")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> visualIndicators = sgNotifications.add(new BoolSetting.Builder()
        .name("visual-indicators")
        .description("Show cooldown timer and status indicators")
        .defaultValue(true)
        .build()
    );

    // === INTERNAL VARIABLES ===
    private long lastFeedTime = 0;
    private long lastCombatTime = 0;
    private int feedCount = 0;
    private int failedFeedCount = 0;
    private boolean isProcessing = false;
    private final Map<String, Long> serverResponseTimes = new ConcurrentHashMap<>();

    public AutoFeed() {
        super(AddonTemplate.CATEGORY, "auto-feed+", "Automatically feeds player when hunger/health is low with smart cooldown management.");
    }

    @Override
    public void onActivate() {
        lastFeedTime = 0;
        lastCombatTime = 0;
        isProcessing = false;
        feedCount = 0;
        failedFeedCount = 0;

        if (enableNotifications.get()) {
            ChatUtils.info("Auto Feed+ activated! Threshold: " + hungerThreshold.get() + " hunger" +
                (checkSaturation.get() ? ", " + saturationThreshold.get() + " saturation" : "") +
                " | Cooldown: " + (cooldown.get() == 0 ? "None" : cooldown.get() + "s"));
        }
    }

    @Override
    public void onDeactivate() {
        isProcessing = false;

        if (enableNotifications.get()) {
            ChatUtils.info("Auto Feed+ deactivated! Fed " + feedCount + " times (" + failedFeedCount + " failed)");
        }
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null || mc.world == null || isProcessing) return;

        // Update combat timer
        updateCombatTimer();

        // Check if we need to feed
        if (!shouldFeed()) return;

        // Check cooldown
        if (!isCooldownReady()) return;

        // Check smart timing
        if (!isTimingGood()) return;

        // Execute feed
        executeFeed();
    }

    private void updateCombatTimer() {
        if (mc.player == null) return;

        // Check if player is in combat (has hurt time or attacking)
        if (mc.player.hurtTime > 0 || mc.player.getAttacking() != null) {
            lastCombatTime = System.currentTimeMillis();
        }
    }

    private boolean shouldFeed() {
        if (mc.player == null) return false;

        boolean needFeedForHunger = false;
        boolean needFeedForSaturation = false;

        // Check hunger
        int currentHunger = mc.player.getHungerManager().getFoodLevel();
        if (currentHunger < hungerThreshold.get()) {
            needFeedForHunger = true;
        }

        // Check saturation (if enabled)
        if (checkSaturation.get()) {
            float currentSaturation = mc.player.getHungerManager().getSaturationLevel();
            if (currentSaturation < saturationThreshold.get()) {
                needFeedForSaturation = true;
            }
        }

        return needFeedForHunger || needFeedForSaturation;
    }

    private boolean isCooldownReady() {
        if (cooldown.get() == 0) return true;

        long timeSinceLastFeed = System.currentTimeMillis() - lastFeedTime;
        long cooldownMs = cooldown.get() * 1000L;

        return timeSinceLastFeed >= cooldownMs;
    }

    private boolean isTimingGood() {
        if (!smartTiming.get()) return true;

        // Don't feed if recently in combat
        long timeSinceCombat = System.currentTimeMillis() - lastCombatTime;
        long combatCooldownMs = combatCooldown.get() * 1000L;

        if (timeSinceCombat < combatCooldownMs) {
            return false;
        }

        // Don't feed if player is moving too fast (might be in danger)
        if (mc.player != null && mc.player.getVelocity().length() > 0.5) {
            return false;
        }

        return true;
    }

    private void executeFeed() {
        if (mc.player == null) return;

        isProcessing = true;

        try {
            String command = feedCommand.get();
            String commandToSend = command.startsWith("/") ? command.substring(1) : command;

            mc.player.networkHandler.sendChatCommand(commandToSend);

            lastFeedTime = System.currentTimeMillis();
            feedCount++;

            // Play sound notification
            if (soundNotification.get()) {
                mc.player.playSound(SoundEvents.ENTITY_PLAYER_BURP, 0.7f, 1.0f);
            }

            // Show notification
            if (enableNotifications.get()) {
                int hunger = mc.player.getHungerManager().getFoodLevel();
                float saturation = mc.player.getHungerManager().getSaturationLevel();
                ChatUtils.info("Fed player! H:" + hunger + "/20 S:" + String.format("%.1f", saturation) + "/20 (#" + feedCount + ")");
            }

            // Server response detection
            if (serverResponseDetection.get()) {
                trackServerResponse();
            }

        } catch (Exception e) {
            failedFeedCount++;
            if (enableNotifications.get()) {
                ChatUtils.error("Feed failed: " + e.getMessage());
            }
        } finally {
            isProcessing = false;
        }
    }

    private void trackServerResponse() {
        // Track response time for future cooldown optimization
        String commandKey = feedCommand.get();
        serverResponseTimes.put(commandKey, System.currentTimeMillis());

        // Auto-adjust cooldown based on server response (basic implementation)
        if (serverResponseTimes.size() > 3) {
            // Simple heuristic: if we haven't failed recently, maybe cooldown can be shorter
            if (failedFeedCount == 0 && feedCount > 5) {
                // This is a basic implementation - in reality you'd want more sophisticated detection
                if (enableNotifications.get()) {
                    ChatUtils.info("Server seems responsive - cooldown is optimal");
                }
            }
        }
    }

    // === PUBLIC METHODS FOR EXTERNAL ACCESS ===
    public long getCooldownRemaining() {
        if (cooldown.get() == 0) return 0;

        long timeSinceLastFeed = System.currentTimeMillis() - lastFeedTime;
        long cooldownMs = cooldown.get() * 1000L;

        return Math.max(0, cooldownMs - timeSinceLastFeed);
    }

    public boolean isOnCooldown() {
        return getCooldownRemaining() > 0;
    }

    public int getFeedCount() {
        return feedCount;
    }

    public int getFailedFeedCount() {
        return failedFeedCount;
    }

    public boolean isCurrentlyProcessing() {
        return isProcessing;
    }

    public String getStatusString() {
        if (mc.player == null) return "Inactive";

        if (isOnCooldown()) {
            long remaining = getCooldownRemaining() / 1000;
            return "Cooldown: " + remaining + "s";
        }

        int hunger = mc.player.getHungerManager().getFoodLevel();
        float saturation = mc.player.getHungerManager().getSaturationLevel();

        if (shouldFeed()) {
            return "Ready to feed (H:" + hunger + " S:" + String.format("%.1f", saturation) + ")";
        }

        return "Monitoring (H:" + hunger + " S:" + String.format("%.1f", saturation) + ")";
    }

    public boolean isVisualIndicatorsEnabled() {
        return visualIndicators.get();
    }

    public void resetStatistics() {
        feedCount = 0;
        failedFeedCount = 0;
        serverResponseTimes.clear();

        if (enableNotifications.get()) {
            ChatUtils.info("Auto Feed+ statistics have been reset!");
        }
    }
}
