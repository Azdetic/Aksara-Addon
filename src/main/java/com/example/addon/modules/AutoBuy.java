package com.example.addon.modules;

import com.example.addon.AddonTemplate;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.events.game.OpenScreenEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.player.ChatUtils;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ingame.GenericContainerScreen;
import net.minecraft.screen.slot.SlotActionType;

import java.util.*;

public class AutoBuy extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgSlots = settings.createGroup("Slot Management");
    private final SettingGroup sgTiming = settings.createGroup("Timing");

    // General Settings
    private final Setting<String> shopCommand = sgGeneral.add(new StringSetting.Builder()
        .name("Shop Command")
        .description("Command to open shop GUI (e.g., /shop, /warp shop)")
        .defaultValue("/shop")
        .build()
    );

    // Dynamic Slot Configuration System
    private final Setting<List<String>> slotList = sgSlots.add(new StringListSetting.Builder()
        .name("Slot Configuration")
        .description("Slot numbers to click. Auto-detects chest type: Single Chest (0-26), Double Chest (0-53). Click + to add slots.")
        .defaultValue(List.of("28", "37", "38"))
        .build()
    );

    // Timing Settings
    private final Setting<Integer> openDelay = sgTiming.add(new IntSetting.Builder()
        .name("Open Delay")
        .description("Delay after opening shop before clicking slots (ticks)")
        .defaultValue(20)
        .min(1)
        .max(100)
        .sliderMax(100)
        .build()
    );

    private final Setting<Integer> clickDelay = sgTiming.add(new IntSetting.Builder()
        .name("Click Delay")
        .description("Delay between slot clicks (ticks)")
        .defaultValue(5)
        .min(1)
        .max(40)
        .sliderMax(40)
        .build()
    );

    private final Setting<Boolean> bulkMode = sgTiming.add(new BoolSetting.Builder()
        .name("Bulk Mode")
        .description("Click all slots simultaneously without delays (faster)")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> repeatMode = sgTiming.add(new BoolSetting.Builder()
        .name("Repeat Mode")
        .description("Keep repeating the buying process instead of running only once")
        .defaultValue(false)
        .build()
    );

    private final Setting<Integer> repeatDelay = sgTiming.add(new IntSetting.Builder()
        .name("Repeat Delay")
        .description("Delay between repeat cycles (in seconds)")
        .defaultValue(10)
        .min(1)
        .max(300)
        .sliderMax(60)
        .visible(() -> repeatMode.get())
        .build()
    );

    // Internal state and slot storage
    private boolean waitingForGui = false;
    private boolean isProcessing = false;
    private int currentSlotIndex = 0;
    private int tickCounter = 0;
    private int currentPhase = 0;
    private int maxSafeSlot = 26; // Default to single chest, will be updated on GUI detection
    private long lastRepeatTime = 0;
    private boolean completedOneCycle = false;

    public AutoBuy() {
        super(AddonTemplate.CATEGORY, "auto-buy", "Automatically buy items from shop with dynamic slot configuration");
    }

    @Override
    public void onActivate() {
        List<Integer> validSlots = getValidSlots();
        if (validSlots.isEmpty()) {
            ChatUtils.error("No valid slots configured! Add slot numbers like '10', '25', '36' using + button.");
            toggle();
            return;
        }

        currentSlotIndex = 0;
        currentPhase = 0;
        tickCounter = 0;
        waitingForGui = false;
        isProcessing = false;
        lastRepeatTime = 0;
        completedOneCycle = false;

        // Execute shop command
        executeShopCommand();
        ChatUtils.info("AutoBuy started - executing: " + shopCommand.get());
        ChatUtils.info("Will click " + validSlots.size() + " slots: " + getSlotListString());

        if (repeatMode.get()) {
            ChatUtils.info("Repeat Mode enabled - will repeat every " + repeatDelay.get() + " seconds");
        } else {
            ChatUtils.info("Single Mode - will run once and disable");
        }
    }    @Override
    public void onDeactivate() {
        waitingForGui = false;
        isProcessing = false;
        currentSlotIndex = 0;
        currentPhase = 0;
        tickCounter = 0;
        lastRepeatTime = 0;
        completedOneCycle = false;
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null || mc.world == null) return;

        // === REPEAT MODE LOGIC ===
        // Check if we completed one cycle and should repeat or disable
        if (completedOneCycle) {
            if (repeatMode.get()) {
                // Check if enough time has passed for next repeat
                long currentTime = System.currentTimeMillis();
                if (currentTime - lastRepeatTime >= repeatDelay.get() * 1000) {
                    // Reset for next cycle
                    completedOneCycle = false;
                    isProcessing = false;
                    waitingForGui = false;
                    currentPhase = 0;
                    tickCounter = 0;
                    currentSlotIndex = 0;

                    // Start new cycle
                    executeShopCommand();
                    ChatUtils.info("Repeat Mode: Starting new cycle");
                    return; // Important: return here to prevent processing in same tick
                }
            } else {
                // Single mode - disable after completion
                ChatUtils.info("Single Mode: Completed one cycle, disabling AutoBuy");
                toggle();
                return;
            }
        }        // === MAIN AUTO BUY LOGIC ===
        if (isActive() && isProcessing) {
            tickCounter++;
            handleAutoBuyProcess(mc);
        }
    }

    @EventHandler
    private void onOpenScreen(OpenScreenEvent event) {
        if (!isActive() || !waitingForGui) return;

        if (event.screen instanceof GenericContainerScreen) {
            GenericContainerScreen containerScreen = (GenericContainerScreen) event.screen;
            String title = containerScreen.getTitle().getString().toLowerCase();

            ChatUtils.info("DEBUG: Detected GUI with title: '" + containerScreen.getTitle().getString() + "'");

            // Check if this looks like a shop GUI - MUCH MORE PERMISSIVE
            if (title.contains("shop") || title.contains("store") || title.contains("buy") ||
                title.contains("market") || title.contains("chest") || title.contains("gui") ||
                title.contains("trading") || title.contains("sell") || title.length() == 0 ||
                title.contains("container") || !title.isEmpty()) {

                // Auto-detect chest type for information purposes only
                int totalSlots = containerScreen.getScreenHandler().slots.size();
                if (totalSlots <= 54) {
                    // Single chest: 27 chest slots + 27 inventory = 54 total
                    maxSafeSlot = 26; // Slots 0-26 are chest area
                    ChatUtils.info("Detected Single Chest - " + totalSlots + " total slots (0-26 = chest area)");
                } else {
                    // Double chest: 54 chest slots + 27 inventory = 81 total
                    maxSafeSlot = 53; // Slots 0-53 are chest area
                    ChatUtils.info("Detected Double Chest - " + totalSlots + " total slots (0-53 = chest area)");
                }

                waitingForGui = false;
                isProcessing = true;
                currentPhase = 1;
                tickCounter = 0;

                List<Integer> validSlots = getValidSlots();
                ChatUtils.info("Shop GUI detected: " + containerScreen.getTitle().getString());
                ChatUtils.info("GUI has " + totalSlots + " slots total");
                ChatUtils.info("Will click " + validSlots.size() + " configured slots: " + getSlotListString());
            } else {
                ChatUtils.warning("GUI detected but not recognized as shop: '" + title + "' - add keywords if needed");
            }
        }
    }

    private void executeShopCommand() {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player != null) {
            String command = shopCommand.get().trim();
            if (!command.startsWith("/")) {
                command = "/" + command;
            }

            mc.player.networkHandler.sendChatMessage(command);
            waitingForGui = true;
            tickCounter = 0;
        }
    }

    private void handleAutoBuyProcess(MinecraftClient mc) {
        List<Integer> validSlots = getValidSlots();

        switch (currentPhase) {
            case 1: // Waiting for initial delay after GUI opens
                if (tickCounter >= openDelay.get()) {
                    currentPhase = 2;
                    tickCounter = 0;
                    currentSlotIndex = 0;

                    if (bulkMode.get()) {
                        ChatUtils.info("Starting BULK clicking " + validSlots.size() + " slots simultaneously...");
                        processBulkClicks(mc, validSlots);
                        finishProcess();
                    } else {
                        ChatUtils.info("Starting sequential clicking " + validSlots.size() + " slots...");
                    }
                }
                break;

            case 2: // Sequential clicking slots (only if bulk mode is disabled)
                if (!bulkMode.get() && tickCounter >= clickDelay.get()) {
                    if (currentSlotIndex < validSlots.size()) {
                        clickSlot(mc, validSlots.get(currentSlotIndex));
                        currentSlotIndex++;
                        tickCounter = 0;
                    } else {
                        finishProcess();
                    }
                }
                break;
        }
    }

    private void clickSlot(MinecraftClient mc, int slotNumber) {
        if (mc.currentScreen instanceof GenericContainerScreen && mc.interactionManager != null) {
            GenericContainerScreen screen = (GenericContainerScreen) mc.currentScreen;

            if (slotNumber >= 0 && slotNumber < screen.getScreenHandler().slots.size()) {
                mc.interactionManager.clickSlot(
                    screen.getScreenHandler().syncId,
                    slotNumber,
                    0,
                    SlotActionType.PICKUP,
                    mc.player
                );

                List<Integer> validSlots = getValidSlots();
                ChatUtils.info("Clicked slot #" + slotNumber + " (" + (currentSlotIndex + 1) + "/" + validSlots.size() + ")");
            } else {
                ChatUtils.error("Invalid slot #" + slotNumber + " (GUI has " + screen.getScreenHandler().slots.size() + " slots)");
            }
        }
    }

    private void processBulkClicks(MinecraftClient mc, List<Integer> validSlots) {
        if (mc.currentScreen instanceof GenericContainerScreen && mc.interactionManager != null) {
            GenericContainerScreen screen = (GenericContainerScreen) mc.currentScreen;

            ChatUtils.info("Starting BULK operation - clicking " + validSlots.size() + " slots simultaneously...");

            int successfulClicks = 0;

            // Click all slots without delays (bulk operation)
            for (int slotNumber : validSlots) {
                try {
                    if (slotNumber >= 0 && slotNumber < screen.getScreenHandler().slots.size()) {
                        mc.interactionManager.clickSlot(
                            screen.getScreenHandler().syncId,
                            slotNumber,
                            0,
                            SlotActionType.PICKUP,
                            mc.player
                        );

                        successfulClicks++;
                        ChatUtils.info("→ Bulk clicked slot #" + slotNumber);
                    } else {
                        ChatUtils.error("Skipped invalid slot #" + slotNumber + " (GUI has " + screen.getScreenHandler().slots.size() + " slots)");
                    }
                } catch (Exception e) {
                    ChatUtils.error("Error clicking slot #" + slotNumber + ": " + e.getMessage());
                }
            }

            ChatUtils.info("✓ BULK COMPLETE: Successfully clicked " + successfulClicks + "/" + validSlots.size() + " slots in one operation!");
        } else {
            ChatUtils.error("Cannot perform bulk clicks - no valid screen or interaction manager!");
        }
    }

    private void finishProcess() {
        isProcessing = false;
        waitingForGui = false;
        currentPhase = 0;
        tickCounter = 0;
        currentSlotIndex = 0;
        completedOneCycle = true; // Mark that one cycle is completed
        lastRepeatTime = System.currentTimeMillis(); // Record completion time

        List<Integer> validSlots = getValidSlots();
        ChatUtils.info("AutoBuy cycle completed! Clicked " + validSlots.size() + " slots");

        // Don't toggle here - let onTick handle repeat logic
        if (!repeatMode.get()) {
            ChatUtils.info("Single Mode: One cycle completed, will disable in next tick");
        } else {
            ChatUtils.info("Repeat Mode: Waiting " + repeatDelay.get() + " seconds for next cycle");
        }
    }

    private List<Integer> getValidSlots() {
        List<Integer> validSlots = new ArrayList<>();
        for (String slotStr : slotList.get()) {
            try {
                int slot = Integer.parseInt(slotStr.trim());
                // Accept any reasonable slot number (0-80 to cover double chest + inventory)
                if (slot >= 0 && slot <= 80) {
                    validSlots.add(slot);
                }
            } catch (NumberFormatException e) {
                // Ignore invalid entries
            }
        }
        return validSlots;
    }

    private String getSlotListString() {
        List<Integer> validSlots = getValidSlots();
        if (validSlots.isEmpty()) {
            return "None";
        }
        return validSlots.toString().replace("[", "").replace("]", "");
    }

    public String getInfo() {
        List<Integer> validSlots = getValidSlots();
        return "Slots: " + validSlots.size() + " configured";
    }
}
