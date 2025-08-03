package com.example.addon.modules;

import com.example.addon.AddonTemplate;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.player.ChatUtils;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.item.*;
import net.minecraft.sound.SoundEvents;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class AutoSaver extends Module {
    private final SettingGroup sgGeneral = this.settings.getDefaultGroup();
    private final SettingGroup sgTools = this.settings.createGroup("Tools Protection");
    private final SettingGroup sgArmor = this.settings.createGroup("Armor Protection");
    private final SettingGroup sgThresholds = this.settings.createGroup("Durability Thresholds");
    private final SettingGroup sgNotifications = this.settings.createGroup("Notifications");
    private final SettingGroup sgInventory = this.settings.createGroup("Inventory Management");

    // === GENERAL SETTINGS ===
    private final Setting<Boolean> enableAutoSaver = sgGeneral.add(new BoolSetting.Builder()
        .name("enable-auto-saver")
        .description("Enable Auto Saver protection system")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> preventReuse = sgGeneral.add(new BoolSetting.Builder()
        .name("prevent-reuse")
        .description("Prevent using saved items until they are repaired")
        .defaultValue(false)
        .build()
    );

    private final Setting<Integer> checkDelay = sgGeneral.add(new IntSetting.Builder()
        .name("check-delay")
        .description("Delay between durability checks in milliseconds")
        .defaultValue(500)
        .min(100)
        .max(2000)
        .sliderMax(1000)
        .build()
    );

    // === TOOLS PROTECTION ===
    private final Setting<Boolean> protectSwords = sgTools.add(new BoolSetting.Builder()
        .name("protect-swords")
        .description("Protect swords from breaking")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> protectAxes = sgTools.add(new BoolSetting.Builder()
        .name("protect-axes")
        .description("Protect axes from breaking")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> protectPickaxes = sgTools.add(new BoolSetting.Builder()
        .name("protect-pickaxes")
        .description("Protect pickaxes from breaking")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> protectShovels = sgTools.add(new BoolSetting.Builder()
        .name("protect-shovels")
        .description("Protect shovels from breaking")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> protectHoes = sgTools.add(new BoolSetting.Builder()
        .name("protect-hoes")
        .description("Protect hoes from breaking")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> protectBows = sgTools.add(new BoolSetting.Builder()
        .name("protect-bows")
        .description("Protect bows from breaking")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> protectFishingRods = sgTools.add(new BoolSetting.Builder()
        .name("protect-fishing-rods")
        .description("Protect fishing rods from breaking")
        .defaultValue(true)
        .build()
    );

    // === ARMOR PROTECTION ===
    private final Setting<Boolean> protectHelmet = sgArmor.add(new BoolSetting.Builder()
        .name("protect-helmet")
        .description("Protect helmet from breaking")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> protectChestplate = sgArmor.add(new BoolSetting.Builder()
        .name("protect-chestplate")
        .description("Protect chestplate from breaking")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> protectLeggings = sgArmor.add(new BoolSetting.Builder()
        .name("protect-leggings")
        .description("Protect leggings from breaking")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> protectBoots = sgArmor.add(new BoolSetting.Builder()
        .name("protect-boots")
        .description("Protect boots from breaking")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> autoEquipBackup = sgArmor.add(new BoolSetting.Builder()
        .name("auto-equip-backup")
        .description("Automatically equip backup armor when removing damaged armor")
        .defaultValue(true)
        .build()
    );

    // === DURABILITY THRESHOLDS ===
    private final Setting<Integer> toolsThreshold = sgThresholds.add(new IntSetting.Builder()
        .name("tools-threshold")
        .description("Durability threshold for tools (points remaining)")
        .defaultValue(20)
        .min(1)
        .max(100)
        .sliderMax(50)
        .build()
    );

    private final Setting<Integer> armorThreshold = sgThresholds.add(new IntSetting.Builder()
        .name("armor-threshold")
        .description("Durability threshold for armor (points remaining)")
        .defaultValue(10)
        .min(1)
        .max(100)
        .sliderMax(50)
        .build()
    );

    private final Setting<Integer> enchantedThreshold = sgThresholds.add(new IntSetting.Builder()
        .name("enchanted-threshold")
        .description("Higher threshold for enchanted items (points remaining)")
        .defaultValue(30)
        .min(1)
        .max(200)
        .sliderMax(100)
        .build()
    );

    private final Setting<Boolean> usePercentage = sgThresholds.add(new BoolSetting.Builder()
        .name("use-percentage")
        .description("Use percentage-based thresholds instead of points")
        .defaultValue(false)
        .build()
    );

    private final Setting<Integer> percentageThreshold = sgThresholds.add(new IntSetting.Builder()
        .name("percentage-threshold")
        .description("Durability threshold as percentage (% remaining)")
        .defaultValue(5)
        .min(1)
        .max(50)
        .sliderMax(25)
        .visible(() -> usePercentage.get())
        .build()
    );

    // === NOTIFICATIONS ===
    private final Setting<Boolean> chatWarnings = sgNotifications.add(new BoolSetting.Builder()
        .name("chat-warnings")
        .description("Show chat warnings when items are saved")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> soundNotifications = sgNotifications.add(new BoolSetting.Builder()
        .name("sound-notifications")
        .description("Play sound when items are saved")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> earlyWarning = sgNotifications.add(new BoolSetting.Builder()
        .name("early-warning")
        .description("Show early warnings when durability gets low")
        .defaultValue(false)
        .build()
    );

    private final Setting<Integer> earlyWarningThreshold = sgNotifications.add(new IntSetting.Builder()
        .name("early-warning-threshold")
        .description("Early warning threshold (points above main threshold)")
        .defaultValue(20)
        .min(5)
        .max(100)
        .sliderMax(50)
        .visible(() -> earlyWarning.get())
        .build()
    );

    // === INVENTORY MANAGEMENT ===
    private final Setting<Boolean> enableInventoryManagement = sgInventory.add(new BoolSetting.Builder()
        .name("enable-inventory-management")
        .description("Enable inventory management when inventory is full")
        .defaultValue(true)
        .build()
    );

    private final Setting<List<Item>> dropWhitelist = sgInventory.add(new ItemListSetting.Builder()
        .name("drop-whitelist")
        .description("Items that can be dropped/swapped to make space for saved items")
        .defaultValue(List.of(Items.BLAZE_ROD, Items.COBBLESTONE, Items.DIRT))
        .visible(() -> enableInventoryManagement.get())
        .build()
    );

    private final Setting<Boolean> debugMode = sgInventory.add(new BoolSetting.Builder()
        .name("debug-mode")
        .description("Show detailed debug information about inventory management")
        .defaultValue(false)
        .visible(() -> enableInventoryManagement.get())
        .build()
    );

    private final Setting<Boolean> prioritizeEnchanted = sgInventory.add(new BoolSetting.Builder()
        .name("prioritize-enchanted")
        .description("Give higher priority to enchanted items when managing inventory")
        .defaultValue(true)
        .visible(() -> enableInventoryManagement.get())
        .build()
    );

    // === INTERNAL VARIABLES ===
    private long lastCheckTime = 0;
    private final Set<ItemStack> savedItems = ConcurrentHashMap.newKeySet();
    private final Set<String> noBackupNotified = ConcurrentHashMap.newKeySet();
    private final Set<ItemStack> earlyWarned = ConcurrentHashMap.newKeySet();
    private boolean isProcessing = false;

    public enum ItemType {
        SWORD, AXE, PICKAXE, SHOVEL, HOE, BOW, FISHING_ROD,
        HELMET, CHESTPLATE, LEGGINGS, BOOTS, OTHER;

        public static ItemType getArmorType(EquipmentSlot slot) {
            return switch (slot) {
                case HEAD -> HELMET;
                case CHEST -> CHESTPLATE;
                case LEGS -> LEGGINGS;
                case FEET -> BOOTS;
                default -> OTHER;
            };
        }
    }

    public AutoSaver() {
        super(AddonTemplate.CATEGORY, "auto-saver", "Automatically saves tools and armor from breaking by monitoring durability.");
    }

    @Override
    public void onActivate() {
        lastCheckTime = System.currentTimeMillis();
        savedItems.clear();
        noBackupNotified.clear();
        earlyWarned.clear();
        isProcessing = false;

        if (chatWarnings.get()) {
            ChatUtils.info("Auto Saver activated! Protecting your valuable items from breaking.");
        }
    }

    @Override
    public void onDeactivate() {
        savedItems.clear();
        noBackupNotified.clear();
        earlyWarned.clear();
        isProcessing = false;

        if (chatWarnings.get()) {
            ChatUtils.info("Auto Saver deactivated!");
        }
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (!enableAutoSaver.get() || mc.player == null || mc.world == null || isProcessing) return;

        // Check delay
        if (System.currentTimeMillis() - lastCheckTime < checkDelay.get()) return;

        isProcessing = true;

        try {
            // Check equipped armor
            checkEquippedArmor();

            // Check tools in hotbar and inventory
            checkTools();

            // Clean up saved items list (remove items that are no longer in inventory)
            cleanupSavedItems();

            lastCheckTime = System.currentTimeMillis();
        } catch (Exception e) {
            if (chatWarnings.get()) {
                ChatUtils.error("Auto Saver error: " + e.getMessage());
            }
        } finally {
            isProcessing = false;
        }
    }

    private void checkEquippedArmor() {
        if (mc.player == null) return;

        // Check helmet
        if (protectHelmet.get()) {
            ItemStack helmet = mc.player.getEquippedStack(EquipmentSlot.HEAD);
            checkAndSaveArmor(helmet, EquipmentSlot.HEAD, "Helmet");
        }

        // Check chestplate
        if (protectChestplate.get()) {
            ItemStack chestplate = mc.player.getEquippedStack(EquipmentSlot.CHEST);
            checkAndSaveArmor(chestplate, EquipmentSlot.CHEST, "Chestplate");
        }

        // Check leggings
        if (protectLeggings.get()) {
            ItemStack leggings = mc.player.getEquippedStack(EquipmentSlot.LEGS);
            checkAndSaveArmor(leggings, EquipmentSlot.LEGS, "Leggings");
        }

        // Check boots
        if (protectBoots.get()) {
            ItemStack boots = mc.player.getEquippedStack(EquipmentSlot.FEET);
            checkAndSaveArmor(boots, EquipmentSlot.FEET, "Boots");
        }
    }

    private void checkAndSaveArmor(ItemStack armorStack, EquipmentSlot slot, String armorName) {
        if (armorStack.isEmpty() || !armorStack.isDamageable()) return;

        if (shouldSaveItem(armorStack, ItemType.getArmorType(slot))) {
            // Try to unequip the armor
            if (unequipArmor(slot, armorName)) {
                savedItems.add(armorStack);

                // Try to equip backup armor
                if (autoEquipBackup.get()) {
                    equipBackupArmor(slot, armorName);
                }
            }
        } else if (earlyWarning.get() && shouldShowEarlyWarning(armorStack, ItemType.getArmorType(slot))) {
            showEarlyWarning(armorStack, armorName);
        }
    }

    private void checkTools() {
        if (mc.player == null) return;

        // Check all inventory slots for tools
        for (int i = 0; i < mc.player.getInventory().size(); i++) {
            ItemStack stack = mc.player.getInventory().getStack(i);
            if (stack.isEmpty() || !stack.isDamageable()) continue;

            ItemType type = getItemType(stack.getItem());
            if (type == ItemType.OTHER || !isToolProtectionEnabled(type)) continue;

            // Skip if item is already saved and prevent reuse is enabled
            if (preventReuse.get() && isSavedItem(stack)) continue;

            if (shouldSaveItem(stack, type)) {
                // For tools, we move them to inventory (if they're in hotbar) or mark as saved
                if (i < 9) { // Tool is in hotbar
                    if (moveToolToInventory(i, stack)) {
                        savedItems.add(stack);
                    }
                } else {
                    // Tool is already in inventory, just mark as saved
                    savedItems.add(stack);
                    if (chatWarnings.get()) {
                        ChatUtils.info("Saved " + getItemDisplayName(stack) + " from breaking (moved to safe storage)");
                    }
                }
            } else if (earlyWarning.get() && shouldShowEarlyWarning(stack, type)) {
                showEarlyWarning(stack, getItemDisplayName(stack));
            }
        }
    }

    private boolean shouldSaveItem(ItemStack stack, ItemType type) {
        if (!stack.isDamageable()) return false;

        int threshold = getThresholdForType(type, stack);
        int remaining = stack.getMaxDamage() - stack.getDamage();

        if (usePercentage.get()) {
            double percentage = (double) remaining / stack.getMaxDamage() * 100;
            return percentage <= percentageThreshold.get();
        } else {
            return remaining <= threshold;
        }
    }

    private boolean shouldShowEarlyWarning(ItemStack stack, ItemType type) {
        if (!stack.isDamageable() || earlyWarned.contains(stack)) return false;

        int threshold = getThresholdForType(type, stack) + earlyWarningThreshold.get();
        int remaining = stack.getMaxDamage() - stack.getDamage();

        return remaining <= threshold;
    }

    private void showEarlyWarning(ItemStack stack, String itemName) {
        if (earlyWarned.contains(stack)) return;

        earlyWarned.add(stack);
        int remaining = stack.getMaxDamage() - stack.getDamage();

        if (chatWarnings.get()) {
            ChatUtils.warning("Warning: " + itemName + " durability is getting low (" + remaining + " points remaining)");
        }

        if (soundNotifications.get() && mc.player != null) {
            mc.player.playSound(SoundEvents.BLOCK_NOTE_BLOCK_BELL.value(), 0.3f, 1.5f);
        }
    }

    private int getThresholdForType(ItemType type, ItemStack stack) {
        // Check if item has valuable enchantments
        if (hasValuableEnchantments(stack)) {
            return enchantedThreshold.get();
        }

        // Return appropriate threshold based on type
        return switch (type) {
            case HELMET, CHESTPLATE, LEGGINGS, BOOTS -> armorThreshold.get();
            default -> toolsThreshold.get();
        };
    }

    private boolean hasValuableEnchantments(ItemStack stack) {
        if (!stack.hasEnchantments()) return false;

        // Consider an item valuable if it has any enchantments
        return stack.hasEnchantments();
    }

    private boolean unequipArmor(EquipmentSlot slot, String armorName) {
        if (mc.player == null) return false;

        try {
            // Try to find empty slot in inventory
            if (!hasInventorySpace() && enableInventoryManagement.get()) {
                if (!makeInventorySpace()) {
                    if (chatWarnings.get()) {
                        ChatUtils.error("Cannot save " + armorName + " - inventory full and no droppable items found!");
                    }
                    return false;
                }
            }

            // Unequip the armor (set to empty)
            mc.player.equipStack(slot, ItemStack.EMPTY);

            if (chatWarnings.get()) {
                ChatUtils.info("Saved " + armorName + " from breaking (unequipped)");
            }

            if (soundNotifications.get()) {
                mc.player.playSound(SoundEvents.ITEM_ARMOR_EQUIP_GENERIC.value(), 0.5f, 1.0f);
            }

            return true;
        } catch (Exception e) {
            if (chatWarnings.get()) {
                ChatUtils.error("Failed to unequip " + armorName + ": " + e.getMessage());
            }
            return false;
        }
    }

    private void equipBackupArmor(EquipmentSlot slot, String armorName) {
        if (mc.player == null) return;

        // Find backup armor of the same type
        ItemStack backup = findBackupArmor(slot);
        if (!backup.isEmpty()) {
            // Remove backup from inventory and equip it
            for (int i = 0; i < mc.player.getInventory().size(); i++) {
                if (mc.player.getInventory().getStack(i) == backup) {
                    mc.player.getInventory().setStack(i, ItemStack.EMPTY);
                    mc.player.equipStack(slot, backup);

                    if (chatWarnings.get()) {
                        ChatUtils.info("Equipped backup " + armorName.toLowerCase() + ": " + getItemDisplayName(backup));
                    }
                    return;
                }
            }
        } else {
            // Show no backup notification (only once per armor type)
            String notificationKey = armorName.toLowerCase();
            if (!noBackupNotified.contains(notificationKey)) {
                noBackupNotified.add(notificationKey);
                if (chatWarnings.get()) {
                    ChatUtils.warning("No backup " + armorName.toLowerCase() + " found in inventory");
                }
            }
        }
    }

    private ItemStack findBackupArmor(EquipmentSlot slot) {
        if (mc.player == null) return ItemStack.EMPTY;

        for (int i = 0; i < mc.player.getInventory().size(); i++) {
            ItemStack stack = mc.player.getInventory().getStack(i);
            if (stack.isEmpty()) continue;

            // Check if this is armor of the correct type and not already saved
            if (isCorrectArmorType(stack, slot) && !isSavedItem(stack)) {
                return stack;
            }
        }

        return ItemStack.EMPTY;
    }

    private boolean isCorrectArmorType(ItemStack stack, EquipmentSlot slot) {
        Item item = stack.getItem();
        if (!(item instanceof ArmorItem)) return false;

        // Simple check by item type
        return switch (slot) {
            case HEAD -> item == Items.LEATHER_HELMET || item == Items.CHAINMAIL_HELMET ||
                        item == Items.IRON_HELMET || item == Items.GOLDEN_HELMET ||
                        item == Items.DIAMOND_HELMET || item == Items.NETHERITE_HELMET ||
                        item == Items.TURTLE_HELMET;
            case CHEST -> item == Items.LEATHER_CHESTPLATE || item == Items.CHAINMAIL_CHESTPLATE ||
                         item == Items.IRON_CHESTPLATE || item == Items.GOLDEN_CHESTPLATE ||
                         item == Items.DIAMOND_CHESTPLATE || item == Items.NETHERITE_CHESTPLATE;
            case LEGS -> item == Items.LEATHER_LEGGINGS || item == Items.CHAINMAIL_LEGGINGS ||
                        item == Items.IRON_LEGGINGS || item == Items.GOLDEN_LEGGINGS ||
                        item == Items.DIAMOND_LEGGINGS || item == Items.NETHERITE_LEGGINGS;
            case FEET -> item == Items.LEATHER_BOOTS || item == Items.CHAINMAIL_BOOTS ||
                        item == Items.IRON_BOOTS || item == Items.GOLDEN_BOOTS ||
                        item == Items.DIAMOND_BOOTS || item == Items.NETHERITE_BOOTS;
            default -> false;
        };
    }

    private boolean moveToolToInventory(int hotbarSlot, ItemStack tool) {
        if (mc.player == null) return false;

        try {
            // Try to find empty slot in main inventory (slots 9-35)
            for (int i = 9; i < 36; i++) {
                if (mc.player.getInventory().getStack(i).isEmpty()) {
                    // Move tool from hotbar to inventory
                    mc.player.getInventory().setStack(i, tool.copy());
                    mc.player.getInventory().setStack(hotbarSlot, ItemStack.EMPTY);

                    if (chatWarnings.get()) {
                        ChatUtils.info("Saved " + getItemDisplayName(tool) + " from breaking (moved to inventory)");
                    }

                    if (soundNotifications.get()) {
                        mc.player.playSound(SoundEvents.ENTITY_ITEM_PICKUP, 0.5f, 1.2f);
                    }

                    return true;
                }
            }

            // If no empty slots and inventory management is enabled
            if (enableInventoryManagement.get() && makeInventorySpace()) {
                return moveToolToInventory(hotbarSlot, tool); // Try again
            }

            if (chatWarnings.get()) {
                ChatUtils.error("Cannot save " + getItemDisplayName(tool) + " - inventory full!");
            }
            return false;

        } catch (Exception e) {
            if (chatWarnings.get()) {
                ChatUtils.error("Failed to save tool: " + e.getMessage());
            }
            return false;
        }
    }

    private boolean hasInventorySpace() {
        if (mc.player == null) return false;

        for (int i = 9; i < 36; i++) { // Main inventory slots
            if (mc.player.getInventory().getStack(i).isEmpty()) {
                return true;
            }
        }
        return false;
    }

    private boolean makeInventorySpace() {
        if (mc.player == null) return false;

        List<Item> droppableItems = dropWhitelist.get();
        if (droppableItems.isEmpty()) {
            if (chatWarnings.get() || debugMode.get()) {
                ChatUtils.error("Drop whitelist is empty - cannot make inventory space!");
            }
            return false;
        }

        // Debug: Show what items are in whitelist and inventory content
        if (debugMode.get()) {
            ChatUtils.info("=== Auto Saver Debug: Making Inventory Space ===");
            ChatUtils.info("Drop whitelist items: " + droppableItems.size());
            for (Item item : droppableItems) {
                ChatUtils.info("- Whitelisted: " + item.toString());
            }

            ChatUtils.info("Current inventory content:");
            for (int i = 0; i < 36; i++) {
                ItemStack stack = mc.player.getInventory().getStack(i);
                if (!stack.isEmpty()) {
                    ChatUtils.info("Slot " + i + ": " + getItemDisplayName(stack) + " (" + stack.getItem().toString() + ")");
                }
            }
        }

        // Check all inventory slots (including hotbar 0-8 and main inventory 9-35)
        for (int i = 0; i < 36; i++) {
            ItemStack stack = mc.player.getInventory().getStack(i);
            if (!stack.isEmpty()) {
                boolean isDroppable = droppableItems.contains(stack.getItem());

                if (debugMode.get()) {
                    ChatUtils.info("Checking slot " + i + ": " + getItemDisplayName(stack) + " - Droppable: " + isDroppable);
                }

                if (isDroppable) {
                    if (chatWarnings.get() || debugMode.get()) {
                        ChatUtils.info("Found droppable item: " + getItemDisplayName(stack) + " in slot " + i);
                    }

                    // Safely drop the item using proper method
                    try {
                        // Clear the slot first
                        mc.player.getInventory().setStack(i, ItemStack.EMPTY);

                        // Then drop the item
                        mc.player.dropItem(stack, false);

                        if (chatWarnings.get()) {
                            ChatUtils.info("Successfully dropped " + getItemDisplayName(stack) + " to make space for protected item");
                        }
                        return true;
                    } catch (Exception e) {
                        if (chatWarnings.get() || debugMode.get()) {
                            ChatUtils.error("Failed to drop item: " + e.getMessage());
                        }
                        return false;
                    }
                }
            }
        }

        if (chatWarnings.get() || debugMode.get()) {
            ChatUtils.error("No droppable items found in inventory! Check your drop whitelist settings.");
        }
        return false;
    }    private boolean isSavedItem(ItemStack stack) {
        return savedItems.contains(stack);
    }

    private void cleanupSavedItems() {
        if (mc.player == null) return;

        // Remove items that are no longer in inventory
        savedItems.removeIf(savedStack -> {
            boolean found = false;
            for (int i = 0; i < mc.player.getInventory().size(); i++) {
                if (mc.player.getInventory().getStack(i) == savedStack) {
                    found = true;
                    break;
                }
            }
            return !found;
        });

        // Clean up early warning set
        earlyWarned.removeIf(stack -> {
            boolean found = false;
            for (int i = 0; i < mc.player.getInventory().size(); i++) {
                if (mc.player.getInventory().getStack(i) == stack) {
                    found = true;
                    break;
                }
            }
            return !found;
        });
    }

    private ItemType getItemType(Item item) {
        if (item instanceof SwordItem) return ItemType.SWORD;
        if (item instanceof AxeItem) return ItemType.AXE;
        if (item instanceof PickaxeItem) return ItemType.PICKAXE;
        if (item instanceof ShovelItem) return ItemType.SHOVEL;
        if (item instanceof HoeItem) return ItemType.HOE;
        if (item instanceof BowItem) return ItemType.BOW;
        if (item instanceof FishingRodItem) return ItemType.FISHING_ROD;

        // Check armor by item type
        if (item == Items.LEATHER_HELMET || item == Items.CHAINMAIL_HELMET ||
            item == Items.IRON_HELMET || item == Items.GOLDEN_HELMET ||
            item == Items.DIAMOND_HELMET || item == Items.NETHERITE_HELMET ||
            item == Items.TURTLE_HELMET) {
            return ItemType.HELMET;
        }
        if (item == Items.LEATHER_CHESTPLATE || item == Items.CHAINMAIL_CHESTPLATE ||
            item == Items.IRON_CHESTPLATE || item == Items.GOLDEN_CHESTPLATE ||
            item == Items.DIAMOND_CHESTPLATE || item == Items.NETHERITE_CHESTPLATE) {
            return ItemType.CHESTPLATE;
        }
        if (item == Items.LEATHER_LEGGINGS || item == Items.CHAINMAIL_LEGGINGS ||
            item == Items.IRON_LEGGINGS || item == Items.GOLDEN_LEGGINGS ||
            item == Items.DIAMOND_LEGGINGS || item == Items.NETHERITE_LEGGINGS) {
            return ItemType.LEGGINGS;
        }
        if (item == Items.LEATHER_BOOTS || item == Items.CHAINMAIL_BOOTS ||
            item == Items.IRON_BOOTS || item == Items.GOLDEN_BOOTS ||
            item == Items.DIAMOND_BOOTS || item == Items.NETHERITE_BOOTS) {
            return ItemType.BOOTS;
        }

        return ItemType.OTHER;
    }

    private boolean isToolProtectionEnabled(ItemType type) {
        return switch (type) {
            case SWORD -> protectSwords.get();
            case AXE -> protectAxes.get();
            case PICKAXE -> protectPickaxes.get();
            case SHOVEL -> protectShovels.get();
            case HOE -> protectHoes.get();
            case BOW -> protectBows.get();
            case FISHING_ROD -> protectFishingRods.get();
            default -> false;
        };
    }

    private String getItemDisplayName(ItemStack stack) {
        return stack.getName().getString();
    }

    // Getter methods for external access
    public boolean isItemSaved(ItemStack stack) {
        return isSavedItem(stack);
    }

    public Set<ItemStack> getSavedItems() {
        return new HashSet<>(savedItems);
    }

    public boolean isCurrentlyProcessing() {
        return isProcessing;
    }
}
