package com.example.addon.modules;

import com.example.addon.AddonTemplate;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.friends.Friends;
import meteordevelopment.meteorclient.utils.player.ChatUtils;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.meteorclient.utils.player.PlayerUtils;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.Tameable;
import net.minecraft.entity.passive.AnimalEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ArrowItem;
import net.minecraft.item.Items;
import net.minecraft.world.GameMode;

import java.util.Set;

public class BowSpamPlus extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgTiming = settings.createGroup("Timing");
    private final SettingGroup sgTargeting = settings.createGroup("Targeting");

    // === GENERAL SETTINGS ===
    private final Setting<Integer> charge = sgGeneral.add(new IntSetting.Builder()
        .name("Charge Time")
        .description("How long to charge the bow before releasing (in ticks)")
        .defaultValue(5)
        .min(3)
        .max(20)
        .sliderMax(20)
        .build()
    );

    private final Setting<Boolean> onlyWhenHoldingRightClick = sgGeneral.add(new BoolSetting.Builder()
        .name("Manual Mode")
        .description("Only works when holding right click (manual bow spam)")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> requireArrows = sgGeneral.add(new BoolSetting.Builder()
        .name("Require Arrows")
        .description("Only work when arrows are available in inventory")
        .defaultValue(true)
        .build()
    );

    // === TARGETING SETTINGS ===
    private final Setting<Boolean> enableAutoTargeting = sgTargeting.add(new BoolSetting.Builder()
        .name("Enable Auto Targeting")
        .description("Automatically target entities when cursor is near them")
        .defaultValue(true)
        .build()
    );

    private final Setting<Set<EntityType<?>>> entityWhitelist = sgTargeting.add(new EntityTypeListSetting.Builder()
        .name("Entity Whitelist")
        .description("Entities to automatically target and attack")
        .onlyAttackable()
        .build()
    );

    private final Setting<Boolean> targetBabies = sgTargeting.add(new BoolSetting.Builder()
        .name("Target Babies")
        .description("Whether to target baby variants of entities")
        .defaultValue(true)
        .visible(() -> enableAutoTargeting.get())
        .build()
    );

    private final Setting<Boolean> targetPlayers = sgTargeting.add(new BoolSetting.Builder()
        .name("Target Players")
        .description("Whether to target other players")
        .defaultValue(false)
        .visible(() -> enableAutoTargeting.get())
        .build()
    );

    private final Setting<Boolean> ignoreFriends = sgTargeting.add(new BoolSetting.Builder()
        .name("Ignore Friends")
        .description("Don't target players in friends list")
        .defaultValue(true)
        .visible(() -> enableAutoTargeting.get() && targetPlayers.get())
        .build()
    );

    private final Setting<Boolean> ignoreTeamates = sgTargeting.add(new BoolSetting.Builder()
        .name("Ignore Teammates")
        .description("Don't target players with same team color")
        .defaultValue(true)
        .visible(() -> enableAutoTargeting.get() && targetPlayers.get())
        .build()
    );

    // === TIMING SETTINGS ===
    private final Setting<Boolean> smartDelay = sgTiming.add(new BoolSetting.Builder()
        .name("Smart Delay")
        .description("Use vanilla attack cooldown timing")
        .defaultValue(true)
        .build()
    );

    private final Setting<Integer> hitDelay = sgTiming.add(new IntSetting.Builder()
        .name("Hit Delay")
        .description("Delay between shots (in ticks)")
        .defaultValue(10)
        .min(0)
        .max(60)
        .sliderMax(60)
        .visible(() -> !smartDelay.get())
        .build()
    );

    private final Setting<Boolean> randomDelayEnabled = sgTiming.add(new BoolSetting.Builder()
        .name("Random Delay")
        .description("Add random delay to bypass anti-cheat")
        .defaultValue(false)
        .visible(() -> !smartDelay.get())
        .build()
    );

    private final Setting<Integer> randomDelayMax = sgTiming.add(new IntSetting.Builder()
        .name("Random Delay Max")
        .description("Maximum random delay to add (in ticks)")
        .defaultValue(5)
        .min(0)
        .max(20)
        .sliderMax(20)
        .visible(() -> randomDelayEnabled.get() && !smartDelay.get())
        .build()
    );

    // Internal state
    private boolean wasBow = false;
    private boolean wasHoldingRightClick = false;
    private int hitDelayTimer = 0;
    private Entity lastTargetedEntity = null;

    public BowSpamPlus() {
        super(AddonTemplate.CATEGORY, "bow-spam-plus", "Advanced bow spam with auto-targeting and entity detection");
    }

    @Override
    public void onActivate() {
        wasBow = false;
        wasHoldingRightClick = false;
        hitDelayTimer = 0;
        lastTargetedEntity = null;

        ChatUtils.info("BowSpam+ activated");
        if (enableAutoTargeting.get()) {
            ChatUtils.info("Auto-targeting enabled for " + entityWhitelist.get().size() + " entity types");
        }
    }

    @Override
    public void onDeactivate() {
        setPressed(false);
        wasBow = false;
        wasHoldingRightClick = false;
        hitDelayTimer = 0;
        lastTargetedEntity = null;

        ChatUtils.info("BowSpam+ deactivated");
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null || mc.world == null) return;

        // Check if we're in valid game mode
        if (PlayerUtils.getGameMode() == GameMode.SPECTATOR || !mc.player.isAlive()) {
            setPressed(false);
            return;
        }

        // Check if we have arrows (unless in creative mode)
        if (requireArrows.get() && !mc.player.getAbilities().creativeMode) {
            if (!InvUtils.find(itemStack -> itemStack.getItem() instanceof ArrowItem).found()) {
                setPressed(false);
                return;
            }
        }

        // Check if we're holding a bow
        boolean isBow = mc.player.getMainHandStack().getItem() == Items.BOW;
        if (!isBow && wasBow) {
            setPressed(false);
        }
        wasBow = isBow;
        if (!isBow) return;

        // Determine if we should activate bow spam
        boolean shouldActivate = false;

        // Manual mode check
        if (onlyWhenHoldingRightClick.get()) {
            if (mc.options.useKey.isPressed()) {
                shouldActivate = true;
                wasHoldingRightClick = true;
            } else {
                if (wasHoldingRightClick) {
                    setPressed(false);
                    wasHoldingRightClick = false;
                }
                return;
            }
        } else {
            // Auto-targeting mode
            if (enableAutoTargeting.get()) {
                Entity targetedEntity = mc.targetedEntity;
                if (targetedEntity != null && isValidTarget(targetedEntity)) {
                    shouldActivate = true;
                    lastTargetedEntity = targetedEntity;
                } else {
                    // No valid target found
                    setPressed(false);
                    return;
                }
            } else {
                // Always active mode (when not in manual mode and auto-targeting is off)
                shouldActivate = true;
            }
        }

        // Execute bow spam logic if we should activate
        if (shouldActivate && delayCheck()) {
            if (mc.player.getItemUseTime() >= charge.get()) {
                // Release the bow
                mc.interactionManager.stopUsingItem(mc.player);
                ChatUtils.info("→ Shot fired! " + (lastTargetedEntity != null ? "Target: " + lastTargetedEntity.getType().getTranslationKey() : ""));
            } else {
                // Start charging the bow
                setPressed(true);
            }
        }
    }

    private boolean isValidTarget(Entity entity) {
        if (entity == null) return false;
        if (entity.equals(mc.player) || entity.equals(mc.cameraEntity)) return false;

        // Check if entity is alive
        if (entity instanceof LivingEntity livingEntity) {
            if (livingEntity.isDead() || !livingEntity.isAlive()) return false;
        } else if (!entity.isAlive()) {
            return false;
        }

        // Check entity whitelist
        if (!entityWhitelist.get().contains(entity.getType())) return false;

        // Check baby animals
        if (entity instanceof AnimalEntity animalEntity) {
            if (!targetBabies.get() && animalEntity.isBaby()) return false;
        }

        // Check tamed entities
        if (entity instanceof Tameable tameable) {
            if (tameable.getOwner() != null && tameable.getOwner().getUuid() != null) {
                if (tameable.getOwner().getUuid().equals(mc.player.getUuid())) {
                    return false; // Don't target our own pets
                }
            }
        }

        // Player-specific checks
        if (entity instanceof PlayerEntity playerEntity) {
            if (!targetPlayers.get()) return false;
            if (playerEntity.isCreative()) return false;

            // Friends check
            if (ignoreFriends.get() && !Friends.get().shouldAttack(playerEntity)) return false;

            // Team check (basic implementation)
            if (ignoreTeamates.get() && isSameTeam(playerEntity)) return false;
        }

        return true;
    }

    private boolean isSameTeam(PlayerEntity player) {
        // Basic team check - you can enhance this based on your server's team system
        if (mc.player.getScoreboardTeam() != null && player.getScoreboardTeam() != null) {
            return mc.player.getScoreboardTeam().equals(player.getScoreboardTeam());
        }
        return false;
    }

    private boolean delayCheck() {
        if (smartDelay.get()) {
            return mc.player.getAttackCooldownProgress(0.5f) >= 1.0f;
        }

        if (hitDelayTimer > 0) {
            hitDelayTimer--;
            return false;
        } else {
            hitDelayTimer = hitDelay.get();
            if (randomDelayEnabled.get()) {
                hitDelayTimer += (int) (Math.random() * randomDelayMax.get());
            }
            return true;
        }
    }

    private void setPressed(boolean pressed) {
        mc.options.useKey.setPressed(pressed);
    }

    public String getInfo() {
        if (enableAutoTargeting.get()) {
            int targetCount = entityWhitelist.get().size();
            String mode = onlyWhenHoldingRightClick.get() ? "Manual" : "Auto";
            return mode + " | Targets: " + targetCount + " types" +
                   (lastTargetedEntity != null ? " | Current: " + lastTargetedEntity.getType().getTranslationKey() : "");
        } else {
            return onlyWhenHoldingRightClick.get() ? "Manual Mode" : "Always Active";
        }
    }
}
