package com.example.addon.modules;

import com.example.addon.AddonTemplate;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.player.ChatUtils;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.item.*;
import net.minecraft.sound.SoundEvents;
import net.minecraft.entity.player.PlayerEntity;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

import static meteordevelopment.meteorclient.MeteorClient.mc;

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
    private final Setting<Boolean> protectTools = sgSafety.add(new BoolSetting.Builder()
        .name("protect-tools")
        .description("Never drop any tools (pickaxe, axe, shovel, sword, etc.)")
        .defaultValue(true)
        .build()
    );

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

                // Safety check: never drop tools if protection is enabled
                if (protectTools.get() && isToolItem(item)) {
                    if (enableNotifications.get()) {
                        ChatUtils.warning("§e[AUTO DROP+] §cSkipped tool item: §f" + item.getName().getString());
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

        // SAFETY CHECK: Double verify item is in drop list and not in protected list
        if (!dropList.get().contains(item)) {
            if (enableNotifications.get()) {
                ChatUtils.warning("§e[AUTO DROP+] §cPrevented dropping item not in list: §f" + item.getName().getString());
            }
            return false;
        }

        if (protectedItems.get().contains(item)) {
            if (enableNotifications.get()) {
                ChatUtils.warning("§e[AUTO DROP+] §cPrevented dropping protected item: §f" + item.getName().getString());
            }
            return false;
        }

        // Find and drop items more carefully
        while (dropped < amount) {
            // First, look for the EXACT item in hotbar
            int targetSlot = -1;
            for (int i = 0; i < 9; i++) {
                ItemStack stack = mc.player.getInventory().getStack(i);
                if (!stack.isEmpty() && stack.getItem() == item) {
                    targetSlot = i;
                    break;
                }
            }

            // If not in hotbar, look in main inventory
            if (targetSlot == -1) {
                for (int i = 9; i < 36; i++) {
                    ItemStack stack = mc.player.getInventory().getStack(i);
                    if (!stack.isEmpty() && stack.getItem() == item) {
                        // Find empty hotbar slot to move to
                        int emptyHotbarSlot = -1;
                        for (int j = 0; j < 9; j++) {
                            if (mc.player.getInventory().getStack(j).isEmpty()) {
                                emptyHotbarSlot = j;
                                break;
                            }
                        }

                        if (emptyHotbarSlot != -1) {
                            // Move ONLY the specific item to hotbar
                            InvUtils.move().from(i).to(emptyHotbarSlot);
                            targetSlot = emptyHotbarSlot;
                            break;
                        }
                    }
                }
            }

            // Drop ONLY if we found the exact item
            if (targetSlot != -1) {
                // Double check the slot still contains our target item
                ItemStack slotStack = mc.player.getInventory().getStack(targetSlot);
                if (!slotStack.isEmpty() && slotStack.getItem() == item) {
                    InvUtils.drop().slot(targetSlot);
                    dropped++;
                } else {
                    // Something went wrong, stop to prevent dropping wrong items
                    if (enableNotifications.get()) {
                        ChatUtils.error("§e[AUTO DROP+] §cStopped dropping - slot mismatch detected!");
                    }
                    break;
                }
            } else {
                // No more items found
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

    // Check if an item is a tool that should be protected
    private boolean isToolItem(Item item) {
        // Check for common tools by comparing to known tool items
        return item == Items.WOODEN_PICKAXE || item == Items.STONE_PICKAXE ||
               item == Items.IRON_PICKAXE || item == Items.GOLDEN_PICKAXE ||
               item == Items.DIAMOND_PICKAXE || item == Items.NETHERITE_PICKAXE ||
               item == Items.WOODEN_AXE || item == Items.STONE_AXE ||
               item == Items.IRON_AXE || item == Items.GOLDEN_AXE ||
               item == Items.DIAMOND_AXE || item == Items.NETHERITE_AXE ||
               item == Items.WOODEN_SHOVEL || item == Items.STONE_SHOVEL ||
               item == Items.IRON_SHOVEL || item == Items.GOLDEN_SHOVEL ||
               item == Items.DIAMOND_SHOVEL || item == Items.NETHERITE_SHOVEL ||
               item == Items.WOODEN_HOE || item == Items.STONE_HOE ||
               item == Items.IRON_HOE || item == Items.GOLDEN_HOE ||
               item == Items.DIAMOND_HOE || item == Items.NETHERITE_HOE ||
               item == Items.WOODEN_SWORD || item == Items.STONE_SWORD ||
               item == Items.IRON_SWORD || item == Items.GOLDEN_SWORD ||
               item == Items.DIAMOND_SWORD || item == Items.NETHERITE_SWORD ||
               item == Items.BOW || item == Items.CROSSBOW || item == Items.TRIDENT ||
               item == Items.FISHING_ROD || item == Items.SHEARS || item == Items.FLINT_AND_STEEL;
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
