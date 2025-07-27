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

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class AutoDrop extends Module {
    private final SettingGroup sgGeneral = this.settings.getDefaultGroup();
    private final SettingGroup sgItems = this.settings.createGroup("Items");
    private final SettingGroup sgSafety = this.settings.createGroup("Safety");

    // === GENERAL SETTINGS ===
    private final Setting<DropMode> dropMode = sgGeneral.add(new EnumSetting.Builder<DropMode>()
        .name("drop-mode")
        .description("How to drop items")
        .defaultValue(DropMode.DROP_ALL)
        .build()
    );

    private final Setting<Integer> delay = sgGeneral.add(new IntSetting.Builder()
        .name("delay")
        .description("Delay between drops in milliseconds")
        .defaultValue(100)
        .min(50)
        .max(2000)
        .sliderMax(1000)
        .build()
    );

    private final Setting<Boolean> onlyWhenFull = sgGeneral.add(new BoolSetting.Builder()
        .name("only-when-inventory-full")
        .description("Only drop when inventory is full")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> soundNotification = sgGeneral.add(new BoolSetting.Builder()
        .name("sound-notification")
        .description("Play sound when dropping items")
        .defaultValue(true)
        .build()
    );

    // === ITEM SETTINGS ===
    private final Setting<List<Item>> dropList = sgItems.add(new ItemListSetting.Builder()
        .name("drop-list")
        .description("Items to auto-drop. Click + to add items.")
        .defaultValue(List.of(Items.COBBLESTONE, Items.DIRT, Items.STONE))
        .build()
    );

    private final Setting<Integer> dropAmount = sgItems.add(new IntSetting.Builder()
        .name("drop-amount")
        .description("Amount to drop per item (for Specific Amount mode)")
        .defaultValue(64)
        .min(1)
        .max(2304)
        .sliderMax(320)
        .visible(() -> dropMode.get() == DropMode.SPECIFIC_AMOUNT)
        .build()
    );

    // === SAFETY SETTINGS ===
    private final Setting<List<Item>> protectedItems = sgSafety.add(new ItemListSetting.Builder()
        .name("protected-items")
        .description("Items that will NEVER be dropped (safety blacklist)")
        .defaultValue(List.of(
            Items.DIAMOND, Items.EMERALD, Items.NETHERITE_INGOT,
            Items.DIAMOND_SWORD, Items.DIAMOND_PICKAXE, Items.DIAMOND_AXE,
            Items.ENCHANTED_GOLDEN_APPLE, Items.GOLDEN_APPLE,
            Items.ENDER_PEARL, Items.TOTEM_OF_UNDYING
        ))
        .build()
    );

    private final Setting<Double> playerDetectionRange = sgSafety.add(new DoubleSetting.Builder()
        .name("player-detection-range")
        .description("Don't drop if other players are within this range (0 = disabled)")
        .defaultValue(0.0)
        .min(0.0)
        .max(32.0)
        .sliderMax(16.0)
        .build()
    );

    private final Setting<Boolean> enableNotifications = sgSafety.add(new BoolSetting.Builder()
        .name("chat-notifications")
        .description("Show drop notifications in chat")
        .defaultValue(true)
        .build()
    );

    // === INTERNAL VARIABLES ===
    private long lastDropTime = 0;
    private int totalDropped = 0;
    private final Map<Item, Integer> droppedItems = new ConcurrentHashMap<>();
    private boolean isProcessing = false;

    public AutoDrop() {
        super(AddonTemplate.CATEGORY, "auto-drop+", "Automatically drops specified items from inventory.");
    }

    @Override
    public void onActivate() {
        lastDropTime = 0;
        totalDropped = 0;
        droppedItems.clear();
        isProcessing = false;

        if (enableNotifications.get()) {
            ChatUtils.info("§e[AUTO DROP+] §fActivated! Will drop items from list when conditions are met.");
        }
    }

    @Override
    public void onDeactivate() {
        isProcessing = false;

        if (enableNotifications.get()) {
            ChatUtils.info("§e[AUTO DROP+] §fDeactivated! Total items dropped: §6" + totalDropped);
        }
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null || mc.world == null || isProcessing) return;

        // Check if we should drop items
        if (shouldDrop()) {
            dropItems();
        }
    }

    private boolean shouldDrop() {
        if (mc.player == null) return false;

        // Check delay
        if (System.currentTimeMillis() - lastDropTime < delay.get()) return false;

        // Check player detection
        if (playerDetectionRange.get() > 0 && arePlayersNearby()) return false;

        // Always drop items from list (no inventory threshold)
        if (onlyWhenFull.get()) {
            return isInventoryFull();
        } else {
            return true; // Always drop when items are in the list
        }
    }

    private void dropItems() {
        if (mc.player == null) return;

        isProcessing = true;

        try {
            List<Item> itemsToDrop = new ArrayList<>(dropList.get());

            for (Item item : itemsToDrop) {
                // Safety check: never drop protected items
                if (protectedItems.get().contains(item)) {
                    if (enableNotifications.get()) {
                        ChatUtils.warning("§e[AUTO DROP+] §cSkipped protected item: §f" + item.getName().getString());
                    }
                    continue;
                }

                int itemCount = InvUtils.find(item).count();

                // Skip if no items found
                if (itemCount == 0) continue;

                // Calculate how many to drop
                int toDrop = calculateDropAmount(item, itemCount);
                if (toDrop <= 0) continue;

                // Drop the items
                if (dropItemFromInventory(item, toDrop)) {
                    totalDropped += toDrop;
                    droppedItems.merge(item, toDrop, Integer::sum);

                    if (enableNotifications.get()) {
                        ChatUtils.info("§e[AUTO DROP+] §fDropped §6" + toDrop + "x §f" + item.getName().getString());
                    }

                    // Play sound
                    if (soundNotification.get()) {
                        try {
                            mc.player.playSound(SoundEvents.ENTITY_ITEM_PICKUP, 0.3f, 0.8f);
                        } catch (Exception e) {
                            // Ignore sound errors
                        }
                    }

                    break; // Drop one type at a time to prevent lag
                }
            }

            lastDropTime = System.currentTimeMillis();

        } catch (Exception e) {
            if (enableNotifications.get()) {
                ChatUtils.error("§e[AUTO DROP+] §cError: " + e.getMessage());
            }
        } finally {
            isProcessing = false;
        }
    }

    private int calculateDropAmount(Item item, int itemCount) {
        switch (dropMode.get()) {
            case DROP_ALL:
                return itemCount;
            case SPECIFIC_AMOUNT:
                return Math.min(dropAmount.get(), itemCount);
            case KEEP_STACK:
                return Math.max(0, itemCount - 64); // Keep one stack
            default:
                return 0;
        }
    }

    private boolean dropItemFromInventory(Item item, int amount) {
        if (mc.player == null) return false;

        int dropped = 0;

        // Use InvUtils for safer item dropping
        while (dropped < amount) {
            int slot = InvUtils.findInHotbar(item).slot();
            if (slot == -1) {
                // Try to find in main inventory and move to hotbar
                slot = InvUtils.find(item).slot();
                if (slot == -1) break; // No more items found

                // Find empty hotbar slot
                int emptyHotbarSlot = -1;
                for (int i = 0; i < 9; i++) {
                    if (mc.player.getInventory().getStack(i).isEmpty()) {
                        emptyHotbarSlot = i;
                        break;
                    }
                }

                if (emptyHotbarSlot != -1) {
                    // Move item to hotbar
                    InvUtils.move().from(slot).to(emptyHotbarSlot);
                    slot = emptyHotbarSlot;
                } else {
                    // Use current selected slot
                    InvUtils.move().from(slot).to(mc.player.getInventory().selectedSlot);
                    slot = mc.player.getInventory().selectedSlot;
                }
            }

            // Drop one item from the slot
            if (slot != -1) {
                InvUtils.drop().slot(slot);
                dropped++;
            } else {
                break;
            }
        }

        return dropped > 0;
    }

    // === UTILITY METHODS ===
    private boolean isInventoryFull() {
        if (mc.player == null) return false;

        for (int i = 9; i < 36; i++) { // Main inventory slots
            if (mc.player.getInventory().getStack(i).isEmpty()) {
                return false;
            }
        }
        return true;
    }

    private boolean arePlayersNearby() {
        if (mc.world == null || mc.player == null) return false;

        double range = playerDetectionRange.get();
        return mc.world.getPlayers().stream()
            .anyMatch(player -> player != mc.player &&
                     mc.player.distanceTo(player) <= range);
    }

    public int getTotalDropped() {
        return totalDropped;
    }

    public Map<Item, Integer> getDroppedItems() {
        return new HashMap<>(droppedItems);
    }

    public String getStatusText() {
        if (mc.player == null) return "No Player";

        String status = "AutoDrop+ Active";

        if (isProcessing) {
            status += " §e[Dropping...]";
        } else if (arePlayersNearby() && playerDetectionRange.get() > 0) {
            status += " §c[Players Nearby]";
        }

        return status;
    }

    public enum DropMode {
        DROP_ALL("Drop All"),
        SPECIFIC_AMOUNT("Specific Amount"),
        KEEP_STACK("Keep One Stack");

        private final String title;

        DropMode(String title) {
            this.title = title;
        }

        @Override
        public String toString() {
            return title;
        }
    }
}
