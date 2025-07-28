package com.example.addon.modules;

import com.example.addon.AddonTemplate;
import meteordevelopment.meteorclient.events.entity.player.ItemUseCrosshairTargetEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.systems.modules.combat.KillAura;
import meteordevelopment.meteorclient.systems.modules.combat.CrystalAura;
import meteordevelopment.meteorclient.systems.modules.combat.AnchorAura;
import meteordevelopment.meteorclient.systems.modules.combat.BedAura;
import meteordevelopment.meteorclient.utils.Utils;
import meteordevelopment.meteorclient.utils.player.ChatUtils;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.meteorclient.utils.player.SlotUtils;
import meteordevelopment.orbit.EventHandler;
import meteordevelopment.orbit.EventPriority;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.FoodComponent;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.item.Item;
import net.minecraft.item.Items;
import net.minecraft.util.Hand;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiPredicate;

public class AutoEatPlus extends Module {
    @SuppressWarnings("unchecked")
    private static final Class<? extends Module>[] AURAS = new Class[]{ KillAura.class, CrystalAura.class, AnchorAura.class, BedAura.class };

    // Setting groups
    private final SettingGroup sgGeneral = this.settings.getDefaultGroup();
    private final SettingGroup sgThreshold = this.settings.createGroup("Threshold");
    private final SettingGroup sgAdvanced = this.settings.createGroup("Advanced Health");
    private final SettingGroup sgPerformance = this.settings.createGroup("Performance");
    private final SettingGroup sgIntegration = this.settings.createGroup("Integration");

    // === GENERAL SETTINGS ===
    private final Setting<List<Item>> blacklist = sgGeneral.add(new ItemListSetting.Builder()
        .name("blacklist")
        .description("Which items to not eat.")
        .defaultValue(
            Items.ENCHANTED_GOLDEN_APPLE,
            Items.GOLDEN_APPLE,
            Items.CHORUS_FRUIT,
            Items.POISONOUS_POTATO,
            Items.PUFFERFISH,
            Items.CHICKEN,
            Items.ROTTEN_FLESH,
            Items.SPIDER_EYE,
            Items.SUSPICIOUS_STEW
        )
        .filter(item -> item.getComponents().get(DataComponentTypes.FOOD) != null)
        .build()
    );

    // === THRESHOLD SETTINGS ===
    private final Setting<ThresholdMode> thresholdMode = sgThreshold.add(new EnumSetting.Builder<ThresholdMode>()
        .name("threshold-mode")
        .description("The threshold mode to trigger auto eat.")
        .defaultValue(ThresholdMode.Any)
        .build()
    );

    private final Setting<Double> healthThreshold = sgThreshold.add(new DoubleSetting.Builder()
        .name("health-threshold")
        .description("The level of health you eat at.")
        .defaultValue(10)
        .range(1, 19)
        .sliderRange(1, 19)
        .visible(() -> thresholdMode.get() != ThresholdMode.Hunger)
        .build()
    );

    private final Setting<Integer> hungerThreshold = sgThreshold.add(new IntSetting.Builder()
        .name("hunger-threshold")
        .description("The level of hunger you eat at.")
        .defaultValue(16)
        .range(1, 19)
        .sliderRange(1, 19)
        .visible(() -> thresholdMode.get() != ThresholdMode.Health)
        .build()
    );

    // === ADVANCED HEALTH SETTINGS ===
    private final Setting<Boolean> absorptionAwareness = sgAdvanced.add(new BoolSetting.Builder()
        .name("absorption-awareness")
        .description("Consider absorption hearts when calculating health threshold.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> regenerationDetection = sgAdvanced.add(new BoolSetting.Builder()
        .name("regeneration-detection")
        .description("Delay eating if regeneration effect is active.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> combatMode = sgAdvanced.add(new BoolSetting.Builder()
        .name("combat-mode")
        .description("Enhanced eating behavior during combat situations.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> damagePredictor = sgAdvanced.add(new BoolSetting.Builder()
        .name("damage-predictor")
        .description("Start eating earlier when predicting incoming damage.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Double> combatHealthThreshold = sgAdvanced.add(new DoubleSetting.Builder()
        .name("combat-health-threshold")
        .description("Health threshold when in combat mode.")
        .defaultValue(14)
        .range(1, 19)
        .sliderRange(1, 19)
        .visible(() -> combatMode.get())
        .build()
    );

    // === PERFORMANCE SETTINGS ===
    private final Setting<Boolean> fastEat = sgPerformance.add(new BoolSetting.Builder()
        .name("fast-eat")
        .description("Enables fast eating for quicker food consumption.")
        .defaultValue(false)
        .build()
    );

    // === INTEGRATION SETTINGS ===
    private final Setting<Boolean> autoGapIntegration = sgIntegration.add(new BoolSetting.Builder()
        .name("autogap-integration")
        .description("Seamless integration with AutoGap module.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> combatModuleSync = sgIntegration.add(new BoolSetting.Builder()
        .name("combat-module-sync")
        .description("Synchronize with combat modules for optimal performance.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> pauseAuras = sgIntegration.add(new BoolSetting.Builder()
        .name("pause-auras")
        .description("Pauses all auras when eating.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> pauseBaritone = sgIntegration.add(new BoolSetting.Builder()
        .name("pause-baritone")
        .description("Pause baritone when eating.")
        .defaultValue(true)
        .build()
    );

    // === MODULE STATE ===
    public boolean eating;
    private int slot, prevSlot;
    private long lastDamageTime = 0;
    private float lastHealth = 20.0f;
    private boolean inCombat = false;
    private int fastEatTicks = 0;

    private final List<Class<? extends Module>> wasAura = new ArrayList<>();
    private boolean wasBaritone = false;

    public AutoEatPlus() {
        super(AddonTemplate.CATEGORY, "AutoEat+", "Advanced automatic eating with enhanced features and integrations.");
    }

    @Override
    public void onActivate() {
        eating = false;
        slot = -1;
        prevSlot = -1;
        lastDamageTime = 0;
        lastHealth = mc.player != null ? mc.player.getHealth() : 20.0f;
        inCombat = false;
        fastEatTicks = 0;
        wasAura.clear();
        wasBaritone = false;

        if (mc.player != null) {
            ChatUtils.info("§6[AutoEat+] §fActivated with advanced features enabled!");
        }
    }

    @Override
    public void onDeactivate() {
        if (eating) stopEating();

        if (mc.player != null) {
            ChatUtils.info("§6[AutoEat+] §fDeactivated.");
        }
    }

    /**
     * Main tick handler for enhanced eating logic
     */
    @EventHandler(priority = EventPriority.LOW)
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null) return;

        // Update combat and damage tracking
        updateCombatState();
        updateDamageTracking();

        // Don't eat if AutoGap is eating and integration is enabled
        if (autoGapIntegration.get() && isAutoGapEating()) return;

        // Fast eat logic
        if (fastEat.get() && eating) {
            fastEatTicks++;
            if (fastEatTicks >= 3) { // Fast eat every 3 ticks
                mc.options.useKey.setPressed(false);
                mc.options.useKey.setPressed(true);
                fastEatTicks = 0;
            }
        }

        // Main eating logic
        if (eating) {
            handleEatingState();
        } else if (shouldEat()) {
            startEating();
        }
    }

    @EventHandler
    private void onItemUseCrosshairTarget(ItemUseCrosshairTargetEvent event) {
        if (eating) event.target = null;
    }

    private void updateCombatState() {
        if (!combatMode.get()) return;

        boolean newCombatState = false;

        // Check if any combat module is active and targeting
        if (combatModuleSync.get()) {
            for (Class<? extends Module> auraClass : AURAS) {
                Module aura = Modules.get().get(auraClass);
                if (aura.isActive()) {
                    newCombatState = true;
                    break;
                }
            }
        }

        // Additional combat detection (damage received recently)
        if (System.currentTimeMillis() - lastDamageTime < 5000) { // 5 seconds
            newCombatState = true;
        }

        inCombat = newCombatState;
    }

    private void updateDamageTracking() {
        if (!damagePredictor.get()) return;

        float currentHealth = mc.player.getHealth();
        if (currentHealth < lastHealth) {
            lastDamageTime = System.currentTimeMillis();
        }
        lastHealth = currentHealth;
    }

    private boolean isAutoGapEating() {
        // Check if AutoGap module exists and is eating
        try {
            // This would need to be adjusted based on your AutoGap implementation
            // For now, we'll return false as a placeholder
            return false;
        } catch (Exception e) {
            return false;
        }
    }

    private void handleEatingState() {
        // Stop eating if conditions are no longer met
        if (!shouldEat()) {
            stopEating();
            return;
        }

        // Check if current slot still has food
        if (mc.player.getInventory().getStack(slot).get(DataComponentTypes.FOOD) == null) {
            int newSlot = findSlot();
            if (newSlot == -1) {
                stopEating();
                return;
            }
            changeSlot(newSlot);
        }

        // Continue eating
        eat();
    }

    private void startEating() {
        prevSlot = mc.player.getInventory().selectedSlot;
        slot = findSlot();

        if (slot == -1) return;

        eat();

        // Pause auras if enabled
        if (pauseAuras.get()) {
            pauseAurasForEating();
        }

        // Pause baritone if enabled
        if (pauseBaritone.get()) {
            pauseBaritoneForEating();
        }
    }

    private void eat() {
        changeSlot(slot);
        setPressed(true);

        if (!mc.player.isUsingItem()) {
            Utils.rightClick();
        }

        eating = true;
        fastEatTicks = 0;
    }

    private void stopEating() {
        if (prevSlot != SlotUtils.OFFHAND) changeSlot(prevSlot);
        setPressed(false);
        eating = false;

        // Resume auras
        if (pauseAuras.get()) {
            resumeAuras();
        }

        // Resume baritone
        if (pauseBaritone.get()) {
            resumeBaritone();
        }
    }

    private void setPressed(boolean pressed) {
        mc.options.useKey.setPressed(pressed);
    }

    private void changeSlot(int slot) {
        InvUtils.swap(slot, false);
        this.slot = slot;
    }

    public boolean shouldEat() {
        if (mc.player == null) return false;

        // Calculate effective health (including absorption if enabled)
        float effectiveHealth = mc.player.getHealth();
        if (absorptionAwareness.get()) {
            effectiveHealth += mc.player.getAbsorptionAmount();
        }

        // Get appropriate health threshold based on combat state
        double currentHealthThreshold = healthThreshold.get();
        if (combatMode.get() && inCombat) {
            currentHealthThreshold = combatHealthThreshold.get();
        }

        // Damage prediction adjustment
        if (damagePredictor.get() && inCombat) {
            currentHealthThreshold += 2.0; // Eat 2 hearts earlier in combat
        }

        boolean healthLow = effectiveHealth <= currentHealthThreshold;
        boolean hungerLow = mc.player.getHungerManager().getFoodLevel() <= hungerThreshold.get();

        // Check regeneration effect
        if (regenerationDetection.get() && mc.player.hasStatusEffect(StatusEffects.REGENERATION)) {
            // Only eat if health is critically low even with regen
            if (effectiveHealth > currentHealthThreshold * 0.7) {
                return false;
            }
        }

        slot = findSlot();
        if (slot == -1) return false;

        FoodComponent food = mc.player.getInventory().getStack(slot).get(DataComponentTypes.FOOD);
        if (food == null) return false;

        return thresholdMode.get().test(healthLow, hungerLow)
            && (mc.player.getHungerManager().isNotFull() || food.canAlwaysEat());
    }

    private int findSlot() {
        int slot = -1;
        int bestHunger = -1;

        // Check hotbar slots
        for (int i = 0; i < 9; i++) {
            Item item = mc.player.getInventory().getStack(i).getItem();
            FoodComponent foodComponent = item.getComponents().get(DataComponentTypes.FOOD);
            if (foodComponent == null) continue;

            int hunger = foodComponent.nutrition();
            if (hunger > bestHunger && !blacklist.get().contains(item)) {
                slot = i;
                bestHunger = hunger;
            }
        }

        // Check offhand
        Item offHandItem = mc.player.getOffHandStack().getItem();
        FoodComponent offHandFood = offHandItem.getComponents().get(DataComponentTypes.FOOD);
        if (offHandFood != null && !blacklist.get().contains(offHandItem) && offHandFood.nutrition() > bestHunger) {
            slot = SlotUtils.OFFHAND;
        }

        return slot;
    }

    private void pauseAurasForEating() {
        wasAura.clear();
        for (Class<? extends Module> klass : AURAS) {
            Module module = Modules.get().get(klass);
            if (module.isActive()) {
                wasAura.add(klass);
                module.toggle();
            }
        }
    }

    private void resumeAuras() {
        for (Class<? extends Module> klass : AURAS) {
            Module module = Modules.get().get(klass);
            if (wasAura.contains(klass) && !module.isActive()) {
                module.toggle();
            }
        }
    }

    private void pauseBaritoneForEating() {
        // Placeholder for baritone integration
        // Implementation would depend on your baritone setup
        wasBaritone = true;
    }

    private void resumeBaritone() {
        if (wasBaritone) {
            // Placeholder for baritone resume
            wasBaritone = false;
        }
    }

    // === UTILITY METHODS ===
    public boolean isEating() {
        return eating;
    }

    public boolean isInCombat() {
        return inCombat;
    }

    @Override
    public String getInfoString() {
        if (mc.player != null) {
            float effectiveHealth = mc.player.getHealth();
            if (absorptionAwareness.get()) {
                effectiveHealth += mc.player.getAbsorptionAmount();
            }

            String combatIndicator = inCombat ? " [Combat]" : "";
            return String.format("%.1f/%.1f%s", effectiveHealth, mc.player.getMaxHealth(), combatIndicator);
        }
        return null;
    }

    // === ENUMS ===
    public enum ThresholdMode {
        Health((health, hunger) -> health),
        Hunger((health, hunger) -> hunger),
        Any((health, hunger) -> health || hunger),
        Both((health, hunger) -> health && hunger);

        private final BiPredicate<Boolean, Boolean> predicate;

        ThresholdMode(BiPredicate<Boolean, Boolean> predicate) {
            this.predicate = predicate;
        }

        public boolean test(boolean health, boolean hunger) {
            return predicate.test(health, hunger);
        }
    }
}
