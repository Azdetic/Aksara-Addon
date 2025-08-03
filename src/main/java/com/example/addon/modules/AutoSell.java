package com.example.addon.modules;

import com.example.addon.AddonTemplate;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.events.game.OpenScreenEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.player.ChatUtils;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.Identifier;
import net.minecraft.registry.Registries;
import net.minecraft.client.gui.screen.ingame.GenericContainerScreen;
import net.minecraft.screen.GenericContainerScreenHandler;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.screen.slot.Slot;
import net.minecraft.screen.ScreenHandler;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class AutoSell extends Module {
    private final SettingGroup sgGeneral = this.settings.getDefaultGroup();
    private final SettingGroup sgItems = this.settings.createGroup("Items");
    private final SettingGroup sgAdvanced = this.settings.createGroup("Advanced");
    private final SettingGroup sgStats = this.settings.createGroup("Statistics");

    // === GENERAL SETTINGS ===
    private final Setting<String> sellCommand = sgGeneral.add(new StringSetting.Builder()
        .name("sell-command")
        .description("Command to sell items (e.g., '/sell hand', '/sellall', '/shop sell')")
        .defaultValue("/sell hand")
        .build()
    );

    private final Setting<Integer> delay = sgGeneral.add(new IntSetting.Builder()
        .name("delay")
        .description("Delay between sell commands in milliseconds (1000ms = 1 second)")
        .defaultValue(1000)
        .min(100)
        .max(10000)
        .sliderMax(5000)
        .build()
    );

    private final Setting<Boolean> onlyWhenFull = sgGeneral.add(new BoolSetting.Builder()
        .name("only-when-inventory-full")
        .description("Only sell when inventory is full")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> soundNotification = sgGeneral.add(new BoolSetting.Builder()
        .name("sound-notification")
        .description("Play sound when selling items")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> chestAutoCollect = sgGeneral.add(new BoolSetting.Builder()
        .name("chest-auto-collect")
        .description("Automatically collect whitelisted items from opened chests (Optional - can be disabled)")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> bulkCollect = sgGeneral.add(new BoolSetting.Builder()
        .name("bulk-collect")
        .description("Collect all whitelisted items at once instead of one by one")
        .defaultValue(false)
        .visible(() -> chestAutoCollect.get())
        .build()
    );

    private final Setting<Integer> chestDelay = sgGeneral.add(new IntSetting.Builder()
        .name("chest-delay")
        .description("Delay between item movements from chest in milliseconds")
        .defaultValue(100)
        .min(50)
        .max(1000)
        .sliderMax(500)
        .visible(() -> chestAutoCollect.get() && !bulkCollect.get())
        .build()
    );

    // === ITEM SETTINGS ===
    private final Setting<List<Item>> whitelist = sgItems.add(new ItemListSetting.Builder()
        .name("item-whitelist")
        .description("Items to auto-sell. Click + to add items from list.")
        .defaultValue(List.of(Items.CACTUS, Items.SUGAR_CANE))
        .build()
    );

    private final Setting<SellStrategy> sellStrategy = sgItems.add(new EnumSetting.Builder<SellStrategy>()
        .name("sell-strategy")
        .description("How to sell items")
        .defaultValue(SellStrategy.SELL_ALL)
        .build()
    );

    private final Setting<Integer> sellAmount = sgItems.add(new IntSetting.Builder()
        .name("sell-amount")
        .description("Amount to sell (for Specific Amount strategy)")
        .defaultValue(64)
        .min(1)
        .max(2304) // Max inventory
        .sliderMax(320)
        .visible(() -> sellStrategy.get() == SellStrategy.SPECIFIC_AMOUNT)
        .build()
    );

    // === ADVANCED SETTINGS ===
    private final Setting<Integer> minThreshold = sgAdvanced.add(new IntSetting.Builder()
        .name("minimum-threshold")
        .description("Minimum item count before auto-selling")
        .defaultValue(32)
        .min(1)
        .max(2304)
        .sliderMax(320)
        .build()
    );

    private final Setting<Integer> keepAmount = sgAdvanced.add(new IntSetting.Builder()
        .name("keep-amount")
        .description("Amount of items to keep (never sell)")
        .defaultValue(0)
        .min(0)
        .max(320)
        .sliderMax(64)
        .build()
    );

    private final Setting<Boolean> priorityMode = sgAdvanced.add(new BoolSetting.Builder()
        .name("priority-mode")
        .description("Sell items in whitelist order (first = highest priority)")
        .defaultValue(true)
        .build()
    );

    // === STATISTICS ===
    private final Setting<Boolean> showStats = sgStats.add(new BoolSetting.Builder()
        .name("show-statistics")
        .description("Show selling statistics in chat")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> resetStats = sgStats.add(new BoolSetting.Builder()
        .name("reset-statistics")
        .description("Reset all statistics")
        .defaultValue(false)
        .build()
    );

    // === INTERNAL VARIABLES ===
    private long lastSellTime = 0;
    private long lastChestTime = 0;
    private final Map<Item, Integer> sellStats = new ConcurrentHashMap<>();
    private final Map<Item, Integer> currentInventoryCount = new ConcurrentHashMap<>();
    private boolean isProcessing = false;
    private boolean isProcessingChest = false;

    public enum SellStrategy {
        SELL_ALL("Sell All"),
        SELL_HAND("Move to Hand & Sell"),
        SPECIFIC_AMOUNT("Sell Specific Amount");

        private final String title;

        SellStrategy(String title) {
            this.title = title;
        }

        @Override
        public String toString() {
            return title;
        }
    }

    public AutoSell() {
        super(AddonTemplate.CATEGORY, "auto-sell", "Automatically sells items from whitelist when they appear in inventory.");
    }

    @Override
    public void onActivate() {
        lastSellTime = System.currentTimeMillis();
        lastChestTime = System.currentTimeMillis();
        isProcessing = false;
        isProcessingChest = false;
        updateInventoryCount();

        // Check if reset statistics is enabled
        if (resetStats.get()) {
            resetStatistics();
            resetStats.set(false);
        }

        if (showStats.get()) {
            ChatUtils.info("Auto Sell activated! Monitoring " + whitelist.get().size() + " items.");
            if (chestAutoCollect.get()) {
                ChatUtils.info("Chest auto-collect enabled!");
            }
        }
    }

    @Override
    public void onDeactivate() {
        isProcessing = false;
        isProcessingChest = false;
        currentInventoryCount.clear();

        if (showStats.get()) {
            ChatUtils.info("Auto Sell deactivated!");
        }
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null || mc.world == null || isProcessing) return;

        // Check delay
        if (System.currentTimeMillis() - lastSellTime < delay.get()) return;

        // Update inventory count
        updateInventoryCount();

        // Check if inventory is full (if required)
        if (onlyWhenFull.get() && !isInventoryFull()) return;

        // Find items to sell
        Item itemToSell = findItemToSell();
        if (itemToSell == null) return;

        // Execute sell
        executeSell(itemToSell);
    }

    @EventHandler
    private void onScreenOpen(OpenScreenEvent event) {
        if (!chestAutoCollect.get() || isProcessingChest) return;

        if (event.screen instanceof GenericContainerScreen) {
            // Schedule chest processing with a slight delay to ensure GUI is fully loaded
            new Thread(() -> {
                try {
                    Thread.sleep(200); // Small delay to ensure chest is fully loaded
                    processChestItems();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }).start();
        }
    }

    private void updateInventoryCount() {
        currentInventoryCount.clear();

        if (mc.player == null) return;

        for (int i = 0; i < mc.player.getInventory().size(); i++) {
            ItemStack stack = mc.player.getInventory().getStack(i);
            if (!stack.isEmpty()) {
                Item item = stack.getItem();
                currentInventoryCount.merge(item, stack.getCount(), Integer::sum);
            }
        }
    }

    private boolean isInventoryFull() {
        if (mc.player == null) return false;

        for (int i = 0; i < 36; i++) { // Main inventory slots (0-35)
            if (mc.player.getInventory().getStack(i).isEmpty()) {
                return false;
            }
        }
        return true;
    }

    private Item findItemToSell() {
        List<Item> itemsToCheck = new ArrayList<>(whitelist.get());

        // If priority mode is disabled, shuffle the list
        if (!priorityMode.get()) {
            Collections.shuffle(itemsToCheck);
        }

        for (Item item : itemsToCheck) {
            int currentCount = currentInventoryCount.getOrDefault(item, 0);
            int requiredCount = minThreshold.get() + keepAmount.get();

            if (currentCount >= requiredCount) {
                return item;
            }
        }

        return null;
    }

    private void processChestItems() {
        if (mc.player == null || mc.currentScreen == null || isProcessingChest) return;

        if (!(mc.currentScreen instanceof GenericContainerScreen containerScreen)) return;

        isProcessingChest = true;

        try {
            GenericContainerScreenHandler handler = containerScreen.getScreenHandler();

            // Get chest size more accurately
            // Container inventory size includes player inventory (36 slots)
            // So actual chest size = total - 36
            int totalSlots = handler.slots.size();
            int chestSize = totalSlots - 36; // Player inventory is always 36 slots

            // Make sure we don't go beyond chest slots
            if (chestSize <= 0) {
                if (showStats.get()) {
                    ChatUtils.error("Could not determine chest size properly");
                }
                return;
            }

            if (showStats.get()) {
                ChatUtils.info("Processing chest with " + chestSize + " slots, checking for whitelisted items...");
                if (bulkCollect.get()) {
                    ChatUtils.info("Bulk collect mode: collecting all items at once");
                } else {
                    ChatUtils.info("Sequential mode: collecting items one by one");
                }
            }

            if (bulkCollect.get()) {
                processBulkCollect(handler);
            } else {
                processSequentialCollect(handler);
            }
        } catch (Exception e) {
            if (showStats.get()) {
                ChatUtils.error("Error processing chest: " + e.getMessage());
            }
        } finally {
            isProcessingChest = false;
        }
    }

    private void processBulkCollect(ScreenHandler handler) {
        List<Integer> chestSlots = new ArrayList<>();

        // Collect all slots that have items to be collected
        for (int i = 0; i < handler.slots.size() - 36; i++) {
            Slot slot = handler.slots.get(i);
            if (slot.hasStack()) {
                ItemStack stack = slot.getStack();
                if (hasSpaceForItem(stack.getItem(), stack.getCount())) {
                    chestSlots.add(i);
                }
            }
        }

        // Move all items at once
        for (int slotIndex : chestSlots) {
            try {
                moveItemFromChest(slotIndex, (GenericContainerScreenHandler) handler);
            } catch (Exception e) {
                if (showStats.get()) {
                    ChatUtils.error("Error moving item from slot " + slotIndex + ": " + e.getMessage());
                }
            }
        }

        if (showStats.get() && !chestSlots.isEmpty()) {
            ChatUtils.info("Bulk collected " + chestSlots.size() + " item stacks from chest");
        }
    }

    private void processSequentialCollect(ScreenHandler handler) {
        for (int i = 0; i < handler.slots.size() - 36; i++) {
            Slot slot = handler.slots.get(i);
            if (slot.hasStack()) {
                ItemStack stack = slot.getStack();
                if (hasSpaceForItem(stack.getItem(), stack.getCount())) {
                    try {
                        moveItemFromChest(i, (GenericContainerScreenHandler) handler);

                        if (showStats.get()) {
                            ChatUtils.info("Collected " + stack.getName().getString() + " x" + stack.getCount());
                        }

                        // Add delay for sequential collection
                        try {
                            Thread.sleep(chestDelay.get());
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            break;
                        }
                    } catch (Exception e) {
                        if (showStats.get()) {
                            ChatUtils.error("Error collecting item: " + e.getMessage());
                        }
                    }
                }
            }
        }
    }    private boolean hasEmptySlot() {
        if (mc.player == null) return false;

        int emptySlots = 0;

        // Check hotbar (slots 0-8)
        for (int i = 0; i < 9; i++) {
            if (mc.player.getInventory().getStack(i).isEmpty()) {
                emptySlots++;
            }
        }

        // Check main inventory (slots 9-35)
        for (int i = 9; i < 36; i++) {
            if (mc.player.getInventory().getStack(i).isEmpty()) {
                emptySlots++;
            }
        }

        // Debug info
        if (showStats.get() && emptySlots > 0) {
            ChatUtils.info("Found " + emptySlots + " empty slots in inventory");
        }

        return emptySlots > 0;
    }

    private boolean hasSpaceForItem(Item item, int count) {
        if (mc.player == null) return false;

        int remainingCount = count;

        // First, try to add to existing stacks of the same item
        for (int i = 0; i < 36; i++) {
            ItemStack stack = mc.player.getInventory().getStack(i);
            if (!stack.isEmpty() && stack.getItem() == item) {
                int maxStackSize = stack.getMaxCount();
                int canAdd = maxStackSize - stack.getCount();
                if (canAdd > 0) {
                    remainingCount -= Math.min(canAdd, remainingCount);
                    if (remainingCount <= 0) {
                        if (showStats.get()) {
                            ChatUtils.info("Can stack " + count + "x " + getItemDisplayName(item) + " with existing items");
                        }
                        return true;
                    }
                }
            }
        }

        // Then, check for empty slots for remaining items
        int emptySlots = 0;
        for (int i = 0; i < 36; i++) {
            if (mc.player.getInventory().getStack(i).isEmpty()) {
                emptySlots++;
                remainingCount -= item.getDefaultStack().getMaxCount();
                if (remainingCount <= 0) {
                    if (showStats.get()) {
                        ChatUtils.info("Can fit " + count + "x " + getItemDisplayName(item) + " in empty slots");
                    }
                    return true;
                }
            }
        }

        if (showStats.get()) {
            ChatUtils.info("No space for " + count + "x " + getItemDisplayName(item) + " (need " + remainingCount + " more space)");
        }

        return false;
    }

    private void moveItemFromChest(int chestSlot, GenericContainerScreenHandler handler) {
        if (mc.player == null || mc.interactionManager == null) return;

        try {
            // Shift-click to move item to inventory
            mc.interactionManager.clickSlot(
                handler.syncId,
                chestSlot,
                0,
                SlotActionType.QUICK_MOVE,
                mc.player
            );
        } catch (Exception e) {
            // Fallback: try regular click
            try {
                mc.interactionManager.clickSlot(
                    handler.syncId,
                    chestSlot,
                    0,
                    SlotActionType.PICKUP,
                    mc.player
                );
            } catch (Exception ignored) {
                // Silent fail
            }
        }
    }

    private void executeSell(Item item) {
        if (mc.player == null) return;

        isProcessing = true;

        try {
            switch (sellStrategy.get()) {
                case SELL_ALL -> executeSellAll();
                case SELL_HAND -> executeSellHand(item);
                case SPECIFIC_AMOUNT -> executeSellSpecificAmount(item);
            }

            // Update statistics
            int soldAmount = calculateSoldAmount(item);
            sellStats.merge(item, soldAmount, Integer::sum);

            // Play sound notification
            if (soundNotification.get()) {
                mc.player.playSound(SoundEvents.ENTITY_EXPERIENCE_ORB_PICKUP, 0.5f, 1.0f);
            }

            // Show statistics
            if (showStats.get()) {
                String itemName = getItemDisplayName(item);
                ChatUtils.info("Sold " + soldAmount + "x " + itemName + " (Total: " + sellStats.get(item) + ")");
            }

            lastSellTime = System.currentTimeMillis();

        } catch (Exception e) {
            ChatUtils.error("Error while selling: " + e.getMessage());
        } finally {
            isProcessing = false;
        }
    }

    private void executeSellAll() {
        if (mc.player == null) return;

        String command = sellCommand.get();
        mc.player.networkHandler.sendChatCommand(command.startsWith("/") ? command.substring(1) : command);
    }

    private void executeSellHand(Item item) {
        if (mc.player == null) return;

        // Find the item in inventory
        int slot = InvUtils.find(itemStack -> itemStack.getItem() == item).slot();
        if (slot == -1) return;

        // Move to hand
        InvUtils.move().from(slot).toHotbar(mc.player.getInventory().selectedSlot);

        // Execute sell command
        String command = sellCommand.get();
        mc.player.networkHandler.sendChatCommand(command.startsWith("/") ? command.substring(1) : command);
    }

    private void executeSellSpecificAmount(Item item) {
        if (mc.player == null) return;

        // For specific amount, we might need to implement more complex logic
        // For now, we'll use the same as sell all
        executeSellAll();
    }

    private int calculateSoldAmount(Item item) {
        // This is a simplified calculation
        // In reality, you might want to track before/after inventory states
        int currentCount = currentInventoryCount.getOrDefault(item, 0);
        int keepCount = keepAmount.get();

        switch (sellStrategy.get()) {
            case SELL_ALL -> {
                return Math.max(0, currentCount - keepCount);
            }
            case SELL_HAND -> {
                return Math.min(64, Math.max(0, currentCount - keepCount)); // Assume stack size
            }
            case SPECIFIC_AMOUNT -> {
                return Math.min(sellAmount.get(), Math.max(0, currentCount - keepCount));
            }
        }
        return 0;
    }

    private String getItemDisplayName(Item item) {
        Identifier id = Registries.ITEM.getId(item);
        return id.getPath().replace('_', ' ');
    }

    private void resetStatistics() {
        sellStats.clear();
        if (showStats.get()) {
            ChatUtils.info("Auto Sell statistics have been reset!");
        }
    }

    public Map<Item, Integer> getSellStats() {
        return new HashMap<>(sellStats);
    }

    public int getTotalItemsSold() {
        return sellStats.values().stream().mapToInt(Integer::intValue).sum();
    }

    public boolean isCurrentlyProcessing() {
        return isProcessing;
    }

    public boolean isCurrentlyProcessingChest() {
        return isProcessingChest;
    }

    public boolean isChestAutoCollectEnabled() {
        return chestAutoCollect.get();
    }

    public void setChestAutoCollect(boolean enabled) {
        chestAutoCollect.set(enabled);
    }

    // Getter and setter for GUI access
    public List<Item> getWhitelist() {
        return new ArrayList<>(whitelist.get());
    }

    public void setWhitelist(List<Item> items) {
        whitelist.set(items);
    }
}
