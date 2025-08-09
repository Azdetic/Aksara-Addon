package com.example.addon.modules;

import com.example.addon.AddonTemplate;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.friends.Friends;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.player.ChatUtils;
import meteordevelopment.meteorclient.utils.player.PlayerUtils;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.Tameable;
import net.minecraft.entity.passive.AnimalEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.SwordItem;
import net.minecraft.util.Hand;
import net.minecraft.world.GameMode;

import java.util.Set;

public class AutoTriggerPlus extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgTiming = settings.createGroup("Timing");
    private final SettingGroup sgSword = settings.createGroup("Auto Sword");

    // === GENERAL SETTINGS ===
    private final Setting<Set<EntityType<?>>> entities = sgGeneral.add(new EntityTypeListSetting.Builder()
        .name("Entity Whitelist")
        .description("Entities to automatically attack when cursor is on them")
        .onlyAttackable()
        .build()
    );

    private final Setting<Boolean> babies = sgGeneral.add(new BoolSetting.Builder()
        .name("Attack Babies")
        .description("Whether to attack baby variants of entities")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> targetPlayers = sgGeneral.add(new BoolSetting.Builder()
        .name("Target Players")
        .description("Whether to target other players")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> ignoreFriends = sgGeneral.add(new BoolSetting.Builder()
        .name("Ignore Friends")
        .description("Don't attack players in friends list")
        .defaultValue(true)
        .visible(() -> targetPlayers.get())
        .build()
    );

    private final Setting<Boolean> ignoreCreativePlayers = sgGeneral.add(new BoolSetting.Builder()
        .name("Ignore Creative Players")
        .description("Don't attack players in creative mode")
        .defaultValue(true)
        .visible(() -> targetPlayers.get())
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
        .description("Delay between attacks (in ticks)")
        .defaultValue(0)
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
        .defaultValue(4)
        .min(0)
        .max(20)
        .sliderMax(20)
        .visible(() -> randomDelayEnabled.get() && !smartDelay.get())
        .build()
    );

    private final Setting<Boolean> onlyCrits = sgTiming.add(new BoolSetting.Builder()
        .name("Only Critical Hits")
        .description("Only attack when can perform critical hits")
        .defaultValue(false)
        .build()
    );

    // === AUTO SWORD SETTINGS ===
    private final Setting<Boolean> enableAutoSword = sgSword.add(new BoolSetting.Builder()
        .name("Enable Auto Sword")
        .description("Automatically switch to best sword when attacking")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> preferNetheriteSword = sgSword.add(new BoolSetting.Builder()
        .name("Prefer Netherite")
        .description("Prioritize netherite sword over diamond")
        .defaultValue(true)
        .visible(() -> enableAutoSword.get())
        .build()
    );

    private final Setting<Boolean> considerEnchantments = sgSword.add(new BoolSetting.Builder()
        .name("Consider Enchantments")
        .description("Factor in sword enchantments when selecting best sword")
        .defaultValue(true)
        .visible(() -> enableAutoSword.get())
        .build()
    );

    // Internal state
    private int hitDelayTimer = 0;
    private Entity lastTargetedEntity = null;
    private int originalSlot = -1;
    private int noTargetTimer = 0;

    public AutoTriggerPlus() {
        super(AddonTemplate.CATEGORY, "auto-trigger+", "Advanced trigger bot with auto sword switching and entity targeting");
    }

    @Override
    public void onActivate() {
        hitDelayTimer = 0;
        lastTargetedEntity = null;
        originalSlot = -1;
        noTargetTimer = 0;

        ChatUtils.info("AutoTrigger+ activated");
        if (enableAutoSword.get()) {
            ChatUtils.info("Auto Sword enabled - will switch to best sword during combat");
        }
    }

    @Override
    public void onDeactivate() {
        hitDelayTimer = 0;
        lastTargetedEntity = null;
        originalSlot = -1;
        noTargetTimer = 0;
        ChatUtils.info("AutoTrigger+ deactivated");
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null || mc.world == null) return;

        // Check if we're in valid game mode
        if (PlayerUtils.getGameMode() == GameMode.SPECTATOR || !mc.player.isAlive()) {
            return;
        }

        Entity targetedEntity = mc.targetedEntity;

        // Check if we have a valid target
        if (targetedEntity != null && entityCheck(targetedEntity)) {
            lastTargetedEntity = targetedEntity;
            noTargetTimer = 0;

            // Auto sword switching logic - check every tick when there's a target
            if (enableAutoSword.get()) {
                int bestSwordSlot = findBestSword();
                if (bestSwordSlot != -1 && bestSwordSlot != mc.player.getInventory().selectedSlot) {
                    // Always switch to sword if not holding the best one
                    originalSlot = mc.player.getInventory().selectedSlot;
                    mc.player.getInventory().selectedSlot = bestSwordSlot;
                    ChatUtils.info("Switched to sword in slot " + (bestSwordSlot + 1));
                }
            }

            // Attack logic
            if (delayCheck()) {
                hitEntity(targetedEntity);
            }
        }
        // No more switch back logic - sword stays switched for simplicity
    }

    private boolean entityCheck(Entity entity) {
        if (entity == null) return false;
        if (entity.equals(mc.player) || entity.equals(mc.cameraEntity)) return false;

        // Check if entity is alive
        if (entity instanceof LivingEntity livingEntity) {
            if (livingEntity.isDead() || !livingEntity.isAlive()) return false;
        } else if (!entity.isAlive()) {
            return false;
        }

        // Check entity whitelist
        if (!entities.get().contains(entity.getType())) return false;

        // Check baby animals
        if (entity instanceof AnimalEntity animalEntity) {
            if (!babies.get() && animalEntity.isBaby()) return false;
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
            if (ignoreCreativePlayers.get() && playerEntity.isCreative()) return false;

            // Friends check
            if (ignoreFriends.get() && !Friends.get().shouldAttack(playerEntity)) return false;
        }

        return true;
    }

    private boolean delayCheck() {
        // Critical hit check
        if (onlyCrits.get()) {
            if (!canCriticalHit()) return false;
        }

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

    private boolean canCriticalHit() {
        // Basic critical hit conditions
        return mc.player.fallDistance > 0.0f &&
               !mc.player.isOnGround() &&
               !mc.player.isClimbing() &&
               !mc.player.isTouchingWater() &&
               !mc.player.hasVehicle();
    }

    private int findBestSword() {
        int bestSlot = -1;
        double bestDamage = 0;

        for (int i = 0; i < 9; i++) {
            ItemStack stack = mc.player.getInventory().getStack(i);
            if (stack.isEmpty()) continue;

            Item item = stack.getItem();
            if (!(item instanceof SwordItem)) continue;

            double damage = calculateSwordDamage(stack);

            if (damage > bestDamage) {
                bestDamage = damage;
                bestSlot = i;
            }
        }

        return bestSlot;
    }

    private double calculateSwordDamage(ItemStack stack) {
        if (!(stack.getItem() instanceof SwordItem swordItem)) return 0;

        // Get base attack damage from item attributes
        double baseDamage = 4.0; // Default sword damage

        // Try to get actual damage from sword item
        String itemName = stack.getItem().toString().toLowerCase();
        if (itemName.contains("netherite")) baseDamage = 8.0;
        else if (itemName.contains("diamond")) baseDamage = 7.0;
        else if (itemName.contains("iron")) baseDamage = 6.0;
        else if (itemName.contains("stone")) baseDamage = 5.0;
        else if (itemName.contains("golden")) baseDamage = 4.0;
        else if (itemName.contains("wooden")) baseDamage = 4.0;

        if (considerEnchantments.get()) {
            // Simplified enchantment checking
            try {
                // Check for sharpness enchantment (simplified)
                var enchantments = stack.getEnchantments();
                if (!enchantments.isEmpty()) {
                    baseDamage += 1.0; // Add bonus for any enchantments
                }
            } catch (Exception e) {
                // Ignore enchantment errors
            }
        }

        // Prioritize netherite if setting is enabled
        if (preferNetheriteSword.get() && itemName.contains("netherite")) {
            baseDamage += 1.0; // Bonus for netherite
        }

        return baseDamage;
    }    private void hitEntity(Entity target) {
        mc.interactionManager.attackEntity(mc.player, target);
        mc.player.swingHand(Hand.MAIN_HAND);

        String targetName = target.getType().getTranslationKey();
        ChatUtils.info("→ Attacked: " + targetName);
    }

    public String getInfo() {
        String weaponInfo = enableAutoSword.get() ? "Auto Sword" : "Manual";
        String targetInfo = lastTargetedEntity != null ? lastTargetedEntity.getType().getTranslationKey() : "No Target";

        return weaponInfo + " | Target: " + targetInfo;
    }
}
