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

public class AutoHeal extends Module {
    private final SettingGroup sgGeneral = this.settings.getDefaultGroup();
    private final SettingGroup sgConditions = this.settings.createGroup("Conditions");
    private final SettingGroup sgAdvanced = this.settings.createGroup("Advanced");
    private final SettingGroup sgNotifications = this.settings.createGroup("Notifications");

    // === GENERAL SETTINGS ===
    private final Setting<String> healCommand = sgGeneral.add(new StringSetting.Builder()
        .name("heal-command")
        .description("Command to heal player (e.g., '/heal', '/hp', '/health')")
        .defaultValue("/heal")
        .build()
    );

    private final Setting<Integer> cooldown = sgGeneral.add(new IntSetting.Builder()
        .name("cooldown")
        .description("Cooldown between heal commands in seconds (0 = no cooldown, 300 = 5 minutes)")
        .defaultValue(300)
        .min(0)
        .max(3600) // 1 hour max
        .sliderMax(600) // 10 minutes slider
        .build()
    );

    // === CONDITIONS ===
    private final Setting<Double> healthPercentage = sgConditions.add(new DoubleSetting.Builder()
        .name("health-percentage")
        .description("Execute heal when health is below this percentage (0.5 = 50%, works with any max health)")
        .defaultValue(0.5)
        .min(0.1)
        .max(0.95)
        .sliderMax(0.95)
        .build()
    );

    // === ADVANCED ===
    private final Setting<Boolean> smartTiming = sgAdvanced.add(new BoolSetting.Builder()
        .name("smart-timing")
        .description("Avoid healing during combat or dangerous situations")
        .defaultValue(true)
        .build()
    );

    private final Setting<Integer> combatCooldown = sgAdvanced.add(new IntSetting.Builder()
        .name("combat-delay")
        .description("Seconds to wait after combat before healing (2 = 2 seconds)")
        .defaultValue(2)
        .min(0)
        .max(30)
        .sliderMax(15)
        .visible(() -> smartTiming.get())
        .build()
    );

    private final Setting<Boolean> onlyWhenDamaged = sgAdvanced.add(new BoolSetting.Builder()
        .name("only-when-damaged")
        .description("Only heal when recently damaged (prevents spam)")
        .defaultValue(true)
        .build()
    );

    private final Setting<Integer> damageTimeout = sgAdvanced.add(new IntSetting.Builder()
        .name("damage-timeout")
        .description("Consider damage recent for this many seconds (10 = 10 seconds)")
        .defaultValue(10)
        .min(1)
        .max(60)
        .sliderMax(30)
        .visible(() -> onlyWhenDamaged.get())
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
        .description("Show heal notifications in chat")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> soundNotification = sgNotifications.add(new BoolSetting.Builder()
        .name("sound-notification")
        .description("Play sound when healing")
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
    private long lastHealTime = 0;
    private long lastCombatTime = 0;
    private long lastDamageTime = 0;
    private float previousHealth = 20.0f;
    private int healCount = 0;
    private int failedHealCount = 0;
    private boolean isProcessing = false;
    private final Map<String, Long> serverResponseTimes = new ConcurrentHashMap<>();

    public AutoHeal() {
        super(AddonTemplate.CATEGORY, "auto-heal", "Automatically heals player when health is low with smart cooldown management.");
    }

    @Override
    public void onActivate() {
        lastHealTime = 0;
        lastCombatTime = 0;
        lastDamageTime = 0;
        isProcessing = false;
        healCount = 0;
        failedHealCount = 0;

        if (mc.player != null) {
            previousHealth = mc.player.getHealth();
        }

        if (enableNotifications.get()) {
            double percentage = healthPercentage.get() * 100;
            ChatUtils.info("Auto Heal activated! Health threshold: " + String.format("%.0f", percentage) + "%");
        }
    }

    @Override
    public void onDeactivate() {
        isProcessing = false;

        if (enableNotifications.get()) {
            ChatUtils.info("Auto Heal deactivated! Healed " + healCount + " times.");
        }
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null || mc.world == null || isProcessing) return;

        // Update combat and damage tracking
        updatePlayerStatus();

        // Check if healing is needed
        if (!shouldHeal()) return;

        // Execute heal
        executeHeal();
    }

    private void updatePlayerStatus() {
        if (mc.player == null) return;

        float currentHealth = mc.player.getHealth();

        // Detect damage
        if (currentHealth < previousHealth) {
            lastDamageTime = System.currentTimeMillis();
        }

        // Detect combat (simplified - you might want more sophisticated detection)
        if (mc.player.getAttackCooldownProgress(0.0f) < 1.0f) {
            lastCombatTime = System.currentTimeMillis();
        }

        previousHealth = currentHealth;
    }

    private boolean shouldHeal() {
        if (mc.player == null) return false;

        float currentHealth = mc.player.getHealth();
        float maxHealth = mc.player.getMaxHealth();
        long currentTime = System.currentTimeMillis();

        // Check if health percentage is above threshold
        double currentPercentage = currentHealth / maxHealth;
        if (currentPercentage >= healthPercentage.get()) return false;

        // Check if only heal when damaged
        if (onlyWhenDamaged.get()) {
            if (currentTime - lastDamageTime > damageTimeout.get() * 1000L) {
                return false;
            }
        }

        // Check cooldown (no more emergency mode)
        long requiredCooldown = cooldown.get() * 1000L;
        if (currentTime - lastHealTime < requiredCooldown) return false;

        // Check smart timing (combat)
        if (smartTiming.get()) {
            if (currentTime - lastCombatTime < combatCooldown.get() * 1000L) {
                return false;
            }
        }

        return true;
    }

    private void executeHeal() {
        if (mc.player == null) return;

        isProcessing = true;

        try {
            // Send heal command
            String command = healCommand.get();
            mc.player.networkHandler.sendChatCommand(command.startsWith("/") ? command.substring(1) : command);

            // Update statistics
            healCount++;
            lastHealTime = System.currentTimeMillis();

            // Store server response time for detection
            if (serverResponseDetection.get()) {
                serverResponseTimes.put("last_heal", System.currentTimeMillis());
            }

            // Play sound notification
            if (soundNotification.get()) {
                mc.player.playSound(SoundEvents.ENTITY_PLAYER_LEVELUP, 0.3f, 1.5f);
            }

            // Show notification
            if (enableNotifications.get()) {
                float currentHealth = mc.player.getHealth();
                float maxHealth = mc.player.getMaxHealth();
                double percentage = (currentHealth / maxHealth) * 100;
                ChatUtils.info("§c[AUTO HEAL] §fHealed! Health: §c" + String.format("%.1f", currentHealth) + "§f/" + String.format("%.1f", maxHealth) + " (" + String.format("%.0f", percentage) + "%)");
            }

        } catch (Exception e) {
            failedHealCount++;
            if (enableNotifications.get()) {
                ChatUtils.error("Failed to heal: " + e.getMessage());
            }
        } finally {
            isProcessing = false;
        }
    }

    // === UTILITY METHODS ===
    public boolean isCurrentlyProcessing() {
        return isProcessing;
    }

    public int getHealCount() {
        return healCount;
    }

    public int getFailedHealCount() {
        return failedHealCount;
    }

    public long getTimeSinceLastHeal() {
        return System.currentTimeMillis() - lastHealTime;
    }

    public long getTimeUntilNextHeal() {
        if (mc.player == null) return 0;

        // No more emergency mode, use normal cooldown
        long requiredCooldown = cooldown.get() * 1000L;

        long timeRemaining = requiredCooldown - (System.currentTimeMillis() - lastHealTime);
        return Math.max(0, timeRemaining);
    }

    public String getStatusText() {
        if (mc.player == null) return "No Player";

        float health = mc.player.getHealth();
        float maxHealth = mc.player.getMaxHealth();
        double percentage = (health / maxHealth) * 100;
        long timeUntilHeal = getTimeUntilNextHeal();

        if (percentage >= (healthPercentage.get() * 100)) {
            return "§aHealthy (" + String.format("%.0f", percentage) + "%)";
        } else if (timeUntilHeal > 0) {
            return "§eCooldown (" + (timeUntilHeal / 1000) + "s)";
        } else if (shouldHeal()) {
            return "§cReady to Heal";
        } else {
            return "§6Waiting...";
        }
    }

    // For HUD integration
    public boolean isVisualIndicatorsEnabled() {
        return visualIndicators.get();
    }
}
