package com.example.addon.modules;

import com.example.addon.AddonTemplate;
import meteordevelopment.meteorclient.events.world.TickEvent;
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
    private final Map<Item, Integer> sellStats = new ConcurrentHashMap<>();
    private final Map<Item, Integer> currentInventoryCount = new ConcurrentHashMap<>();
    private boolean isProcessing = false;

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
        isProcessing = false;
        updateInventoryCount();

        // Check if reset statistics is enabled
        if (resetStats.get()) {
            resetStatistics();
            resetStats.set(false);
        }

        if (showStats.get()) {
            ChatUtils.info("Auto Sell activated! Monitoring " + whitelist.get().size() + " items.");
        }
    }

    @Override
    public void onDeactivate() {
        isProcessing = false;
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

    // Getter and setter for GUI access
    public List<Item> getWhitelist() {
        return new ArrayList<>(whitelist.get());
    }

    public void setWhitelist(List<Item> items) {
        whitelist.set(items);
    }
}
