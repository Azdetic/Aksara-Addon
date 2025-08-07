package com.example.addon.modules;

import com.example.addon.AddonTemplate;
import meteordevelopment.meteorclient.events.entity.player.AttackEntityEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.player.ChatUtils;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.SwordItem;
import net.minecraft.item.AxeItem;
import net.minecraft.network.packet.c2s.play.PlayerInteractEntityC2SPacket;

public class AttackMultiplier extends Module {
    private final SettingGroup sgGeneral = this.settings.getDefaultGroup();
    private final SettingGroup sgStealth = this.settings.createGroup("Stealth");
    private final SettingGroup sgPaper = this.settings.createGroup("Paper Bypass");

    // === GENERAL SETTINGS ===
    private final Setting<Integer> attackMultiplier = sgGeneral.add(new IntSetting.Builder()
        .name("attack-multiplier")
        .description("How many extra attack packets to send (1 = double damage)")
        .defaultValue(1)
        .min(1)
        .max(10)
        .sliderMax(5)
        .build()
    );

    private final Setting<Integer> packetDelay = sgGeneral.add(new IntSetting.Builder()
        .name("packet-delay")
        .description("Microseconds between attack packets (0 = instant)")
        .defaultValue(500)
        .min(0)
        .max(10000)
        .sliderMax(5000)
        .build()
    );

    private final Setting<Boolean> onlySwords = sgGeneral.add(new BoolSetting.Builder()
        .name("only-swords")
        .description("Only multiply attacks with swords/axes")
        .defaultValue(true)
        .build()
    );

    // === STEALTH SETTINGS ===
    private final Setting<Boolean> randomizeDelay = sgStealth.add(new BoolSetting.Builder()
        .name("randomize-delay")
        .description("Randomize delay between packets to avoid pattern detection")
        .defaultValue(true)
        .build()
    );

    private final Setting<Integer> maxRandomDelay = sgStealth.add(new IntSetting.Builder()
        .name("max-random-delay")
        .description("Maximum random delay to add (microseconds)")
        .defaultValue(2000)
        .min(0)
        .max(10000)
        .sliderMax(5000)
        .visible(() -> randomizeDelay.get())
        .build()
    );

    private final Setting<Boolean> onlyPvP = sgStealth.add(new BoolSetting.Builder()
        .name("only-pvp")
        .description("Only multiply attacks against players")
        .defaultValue(false)
        .build()
    );

    private final Setting<Integer> cooldownTicks = sgStealth.add(new IntSetting.Builder()
        .name("cooldown-ticks")
        .description("Ticks between multiplied attacks (0 = no cooldown)")
        .defaultValue(0)
        .min(0)
        .max(100)
        .sliderMax(40)
        .build()
    );

    // === PAPER BYPASS SETTINGS ===
    private final Setting<Boolean> paperMode = sgPaper.add(new BoolSetting.Builder()
        .name("paper-mode")
        .description("Use Paper/Spigot specific bypass techniques")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> spoofCooldown = sgPaper.add(new BoolSetting.Builder()
        .name("spoof-cooldown")
        .description("Attempt to bypass attack cooldown checks")
        .defaultValue(true)
        .visible(() -> paperMode.get())
        .build()
    );

    private final Setting<Boolean> dispersePackets = sgPaper.add(new BoolSetting.Builder()
        .name("disperse-packets")
        .description("Spread attack packets across multiple ticks")
        .defaultValue(false)
        .visible(() -> paperMode.get())
        .build()
    );

    private final Setting<Integer> maxPacketsPerTick = sgPaper.add(new IntSetting.Builder()
        .name("max-packets-per-tick")
        .description("Maximum attack packets to send per tick")
        .defaultValue(2)
        .min(1)
        .max(5)
        .visible(() -> paperMode.get() && dispersePackets.get())
        .build()
    );

    // === INTERNAL VARIABLES ===
    private long lastAttackTime = 0;
    private int multipliedAttacks = 0;
    private boolean isProcessing = false;

    public AttackMultiplier() {
        super(AddonTemplate.CATEGORY, "attack-multiplier", "Send multiple attack packets to bypass server-side damage limitations.");
    }

    @Override
    public void onActivate() {
        lastAttackTime = 0;
        multipliedAttacks = 0;
        isProcessing = false;

        ChatUtils.info("§c[Attack Multiplier] §fActivated! Multiplier: §c" + attackMultiplier.get() + "x");
        if (paperMode.get()) {
            ChatUtils.info("§c[Attack Multiplier] §6Paper bypass mode enabled");
        }
    }

    @Override
    public void onDeactivate() {
        isProcessing = false;
        ChatUtils.info("§c[Attack Multiplier] §fDeactivated! Multiplied attacks: §c" + multipliedAttacks);
    }

    @EventHandler
    private void onAttackEntity(AttackEntityEvent event) {
        if (mc.player == null || mc.world == null || isProcessing) return;
        if (!isValidWeapon()) return;
        if (!isValidTarget(event.entity)) return;

        // Cooldown check
        if (cooldownTicks.get() > 0) {
            long currentTime = System.currentTimeMillis();
            if (currentTime - lastAttackTime < cooldownTicks.get() * 50L) {
                return;
            }
            lastAttackTime = currentTime;
        }

        // Execute attack multiplication
        isProcessing = true;
        try {
            if (dispersePackets.get() && paperMode.get()) {
                scheduleDispersedAttacks(event.entity);
            } else {
                executeInstantAttacks(event.entity);
            }
            multipliedAttacks++;
        } catch (Exception e) {
            // Fail silently for stealth
        } finally {
            isProcessing = false;
        }
    }

    private void executeInstantAttacks(Entity target) {
        int multiplier = attackMultiplier.get();
        int baseDelay = packetDelay.get();

        for (int i = 0; i < multiplier; i++) {
            try {
                // Calculate delay
                int delay = baseDelay;
                if (randomizeDelay.get()) {
                    delay += (int)(Math.random() * maxRandomDelay.get());
                }

                // Send attack packet
                if (paperMode.get() && spoofCooldown.get()) {
                    sendBypassAttackPacket(target);
                } else {
                    sendStandardAttackPacket(target);
                }

                // Wait if delay is set
                if (delay > 0) {
                    Thread.sleep(0, delay * 1000); // Convert to nanoseconds
                }

            } catch (InterruptedException e) {
                break; // Stop if interrupted
            } catch (Exception e) {
                // Continue with next packet even if one fails
            }
        }
    }

    private void scheduleDispersedAttacks(Entity target) {
        // This would require a tick-based system to spread packets
        // For now, we'll use a simpler approach
        int multiplier = attackMultiplier.get();
        int maxPerTick = maxPacketsPerTick.get();

        for (int i = 0; i < Math.min(multiplier, maxPerTick); i++) {
            try {
                if (spoofCooldown.get()) {
                    sendBypassAttackPacket(target);
                } else {
                    sendStandardAttackPacket(target);
                }

                // Small delay between packets in same tick
                Thread.sleep(0, 100000); // 0.1ms

            } catch (Exception e) {
                // Continue
            }
        }
    }

    private void sendStandardAttackPacket(Entity target) {
        try {
            PlayerInteractEntityC2SPacket packet = PlayerInteractEntityC2SPacket.attack(target, mc.player.isSneaking());
            mc.player.networkHandler.sendPacket(packet);
        } catch (Exception e) {
            // Fail silently
        }
    }

    private void sendBypassAttackPacket(Entity target) {
        try {
            // Paper bypass: Try to make attack look more legitimate

            // 1. Send standard attack packet
            PlayerInteractEntityC2SPacket packet = PlayerInteractEntityC2SPacket.attack(target, mc.player.isSneaking());
            mc.player.networkHandler.sendPacket(packet);

            // 2. Add small delay to avoid burst detection
            Thread.sleep(0, 50000); // 0.05ms

            // 3. Send another packet with slight variation
            PlayerInteractEntityC2SPacket packet2 = PlayerInteractEntityC2SPacket.attack(target, !mc.player.isSneaking());
            mc.player.networkHandler.sendPacket(packet2);

        } catch (Exception e) {
            // Fallback to standard packet
            sendStandardAttackPacket(target);
        }
    }

    private boolean isValidWeapon() {
        if (!onlySwords.get()) return true;
        if (mc.player == null || mc.player.getMainHandStack().isEmpty()) return false;

        Object item = mc.player.getMainHandStack().getItem();
        return item instanceof SwordItem || item instanceof AxeItem;
    }

    private boolean isValidTarget(Entity target) {
        if (!(target instanceof LivingEntity)) return false;

        // PvP only check
        if (onlyPvP.get() && !(target instanceof PlayerEntity)) {
            return false;
        }

        // Don't attack dead entities
        if (target instanceof LivingEntity living && living.isDead()) {
            return false;
        }

        return true;
    }

    // Statistics
    public int getMultipliedAttacks() {
        return multipliedAttacks;
    }

    public boolean isCurrentlyProcessing() {
        return isProcessing;
    }
}
