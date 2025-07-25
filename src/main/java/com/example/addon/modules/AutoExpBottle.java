package com.example.addon.modules;

import com.example.addon.AddonTemplate;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.player.FindItemResult;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;

public class AutoExpBottle extends Module {
    private final SettingGroup sgGeneral = this.settings.getDefaultGroup();
    private final SettingGroup sgArmor = this.settings.createGroup("Armor Settings");

    // General Settings
    private final Setting<Integer> durabilityThreshold = sgGeneral.add(new IntSetting.Builder()
        .name("durability-threshold")
        .description("Durability percentage below which to start throwing exp bottles.")
        .defaultValue(50)
        .min(1)
        .max(99)
        .sliderMin(1)
        .sliderMax(99)
        .build()
    );

    private final Setting<Integer> throwDelay = sgGeneral.add(new IntSetting.Builder()
        .name("throw-delay")
        .description("Delay between throwing exp bottles in ticks (20 ticks = 1 second).")
        .defaultValue(10)
        .min(1)
        .max(100)
        .sliderMin(1)
        .sliderMax(60)
        .build()
    );

    private final Setting<Boolean> continueUntilFull = sgGeneral.add(new BoolSetting.Builder()
        .name("continue-until-full")
        .description("Continue throwing until all armor reaches 100% durability.")
        .defaultValue(true)
        .build()
    );

    // Armor Settings
    private final Setting<Boolean> checkHelmet = sgArmor.add(new BoolSetting.Builder()
        .name("check-helmet")
        .description("Check helmet durability.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> checkChestplate = sgArmor.add(new BoolSetting.Builder()
        .name("check-chestplate")
        .description("Check chestplate durability.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> checkLeggings = sgArmor.add(new BoolSetting.Builder()
        .name("check-leggings")
        .description("Check leggings durability.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> checkBoots = sgArmor.add(new BoolSetting.Builder()
        .name("check-boots")
        .description("Check boots durability.")
        .defaultValue(true)
        .build()
    );

    private int throwTimer = 0;
    private boolean noBottleWarningShown = false;
    private boolean repairMessageShown = false;

    public AutoExpBottle() {
        super(AddonTemplate.CATEGORY, "auto-exp-bottle", "Automatically throws experience bottles to repair armor when durability is low.");
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null || mc.world == null) return;

        // Update timer
        if (throwTimer > 0) {
            throwTimer--;
            return;
        }

        // Check if we need to repair armor
        if (!shouldRepairArmor()) {
            // Reset repair message flag when no repair needed
            repairMessageShown = false;
            return;
        }

        // Find exp bottle in inventory
        FindItemResult expBottle = InvUtils.find(Items.EXPERIENCE_BOTTLE);
        if (!expBottle.found()) {
            if (!noBottleWarningShown) {
                // Show warning only once and auto disable
                mc.player.sendMessage(net.minecraft.text.Text.literal("§c[AutoExpBottle] STOP - No experience bottles found! Module disabled."), false);
                info("Auto ExpBottle disabled - No experience bottles found");
                this.toggle(); // Auto disable module
                noBottleWarningShown = true;
            }
            return;
        }

        // Reset warning flag when bottles are available
        noBottleWarningShown = false;

        // Directly throw exp bottle without switching items
        throwExpBottle(expBottle);

        // Set delay before next throw
        throwTimer = throwDelay.get();
    }

    private boolean shouldRepairArmor() {
        PlayerInventory inventory = mc.player.getInventory();
        boolean needsRepair = false;

        // Check each armor piece based on settings
        if (checkHelmet.get()) {
            ItemStack helmet = inventory.getArmorStack(3); // Helmet slot
            if (shouldRepairItem(helmet)) needsRepair = true;
        }

        if (checkChestplate.get()) {
            ItemStack chestplate = inventory.getArmorStack(2); // Chestplate slot
            if (shouldRepairItem(chestplate)) needsRepair = true;
        }

        if (checkLeggings.get()) {
            ItemStack leggings = inventory.getArmorStack(1); // Leggings slot
            if (shouldRepairItem(leggings)) needsRepair = true;
        }

        if (checkBoots.get()) {
            ItemStack boots = inventory.getArmorStack(0); // Boots slot
            if (shouldRepairItem(boots)) needsRepair = true;
        }

        return needsRepair;
    }

    private boolean shouldRepairItem(ItemStack item) {
        if (item.isEmpty() || !item.isDamageable()) return false;

        int maxDamage = item.getMaxDamage();
        int currentDamage = item.getDamage();
        int durabilityLeft = maxDamage - currentDamage;

        double durabilityPercentage = (double) durabilityLeft / maxDamage * 100;

        if (continueUntilFull.get()) {
            // Continue until 100% durability
            return durabilityPercentage < 100.0 && durabilityPercentage <= durabilityThreshold.get();
        } else {
            // Only repair when below threshold
            return durabilityPercentage < durabilityThreshold.get();
        }
    }

    private void throwExpBottle(FindItemResult expBottle) {
        // Improved server-compatible approach
        int originalSlot = mc.player.getInventory().selectedSlot;

        // Save original pitch
        float originalPitch = mc.player.getPitch();

        try {
            // If exp bottle is in hotbar, switch to it briefly
            if (expBottle.isHotbar()) {
                // Switch to exp bottle slot
                InvUtils.swap(expBottle.slot(), true);

                // Small delay for server sync
                try { Thread.sleep(50); } catch (InterruptedException e) {}

                // Set pitch down and use item (server-compatible)
                mc.player.setPitch(90.0f);

                // Use item with proper hand detection
                net.minecraft.util.Hand hand = mc.player.getActiveHand();
                if (mc.player.getStackInHand(hand).getItem() == Items.EXPERIENCE_BOTTLE) {
                    mc.interactionManager.interactItem(mc.player, hand);
                    // Show message only once per repair session
                    if (!repairMessageShown) {
                        info("Auto ExpBottle: Started armor repair with experience bottles");
                        repairMessageShown = true;
                    }
                } else {
                    // Fallback: try main hand
                    if (mc.player.getMainHandStack().getItem() == Items.EXPERIENCE_BOTTLE) {
                        mc.interactionManager.interactItem(mc.player, net.minecraft.util.Hand.MAIN_HAND);
                        // Show message only once per repair session
                        if (!repairMessageShown) {
                            info("Auto ExpBottle: Started armor repair with experience bottles");
                            repairMessageShown = true;
                        }
                    }
                }

                // Restore pitch immediately
                mc.player.setPitch(originalPitch);

                // Small delay before switching back
                try { Thread.sleep(50); } catch (InterruptedException e) {}

                // Switch back to original item
                InvUtils.swap(originalSlot, true);
            } else {
                // Move to hotbar temporarily and use
                InvUtils.move().from(expBottle.slot()).to(originalSlot);

                // Small delay for server sync
                try { Thread.sleep(50); } catch (InterruptedException e) {}

                // Set pitch down and use
                mc.player.setPitch(90.0f);
                mc.interactionManager.interactItem(mc.player, mc.player.getActiveHand());
                mc.player.setPitch(originalPitch);

                // Show message only once per repair session
                if (!repairMessageShown) {
                    info("Auto ExpBottle: Started armor repair with experience bottles");
                    repairMessageShown = true;
                }
            }
        } catch (Exception e) {
            // Restore pitch in case of error
            mc.player.setPitch(originalPitch);
            info("Auto ExpBottle: Error during bottle throw - " + e.getMessage());
        }
    }

    @Override
    public void onActivate() {
        throwTimer = 0;
        noBottleWarningShown = false; // Reset warning when module is activated
        repairMessageShown = false; // Reset repair message when module is activated
    }

    @Override
    public void onDeactivate() {
        throwTimer = 0;
        noBottleWarningShown = false; // Reset warning when module is deactivated
        repairMessageShown = false; // Reset repair message when module is deactivated
    }
}
