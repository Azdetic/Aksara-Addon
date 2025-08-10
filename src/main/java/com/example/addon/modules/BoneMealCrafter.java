package com.example.addon.modules;

import com.example.addon.AddonTemplate;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.player.ChatUtils;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.meteorclient.utils.Utils;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ingame.CraftingScreen;
import net.minecraft.client.gui.screen.ingame.InventoryScreen;
import net.minecraft.block.Blocks;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.item.Items;
import net.minecraft.item.ItemStack;
import net.minecraft.screen.AbstractCraftingScreenHandler;
import net.minecraft.screen.CraftingScreenHandler;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.util.Hand;

public class BoneMealCrafter extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    // Minecraft client instance
    private final MinecraftClient mc = MinecraftClient.getInstance();

    // === GENERAL SETTINGS ===
    private final Setting<Mode> mode = sgGeneral.add(new EnumSetting.Builder<Mode>()
        .name("Mode")
        .description("Crafting method - NOTE: Bone meal recipe may not work in 2x2 inventory grid!")
        .defaultValue(Mode.CraftingTable)
        .build()
    );

    private final Setting<Integer> minBones = sgGeneral.add(new IntSetting.Builder()
        .name("Minimum Bones")
        .description("Minimum bones required before crafting")
        .defaultValue(0)
        .min(0)
        .max(64)
        .sliderMax(64)
        .build()
    );

    private final Setting<Boolean> keepBones = sgGeneral.add(new BoolSetting.Builder()
        .name("Keep Bones")
        .description("Keep some bones instead of crafting all")
        .defaultValue(false)
        .build()
    );

    private final Setting<Integer> bonesToKeep = sgGeneral.add(new IntSetting.Builder()
        .name("Bones to Keep")
        .description("Number of bones to keep in inventory")
        .defaultValue(8)
        .min(0)
        .max(64)
        .sliderMax(64)
        .visible(() -> keepBones.get())
        .build()
    );

    private final Setting<Boolean> autoDisable = sgGeneral.add(new BoolSetting.Builder()
        .name("Auto Disable")
        .description("Auto disable when no bones left")
        .defaultValue(true)
        .build()
    );

    // Internal state
    private int tickCounter = 0;
    private int lastBoneCount = 0;
    private boolean isCrafting = false;
    private int waitingForResult = 0; // Ticks waiting for bone meal to appear
    private BlockPos craftingTablePos = null;

    // Debug state tracking
    private String lastAction = "None";
    private String lastError = "None";
    private boolean debugMode = true; // Enable detailed debugging

    public enum Mode {
        Inventory("Inventory"),
        CraftingTable("Crafting Table");

        private final String title;

        Mode(String title) {
            this.title = title;
        }

        @Override
        public String toString() {
            return title;
        }
    }

    public BoneMealCrafter() {
        super(AddonTemplate.CATEGORY, "bone-meal-crafter", "Automatically craft bones into bone meal. Use Crafting Table mode for best results!");
    }

    @Override
    public void onActivate() {
        tickCounter = 0;
        lastBoneCount = getBoneCount();
        isCrafting = false;
        waitingForResult = 0;
        craftingTablePos = null; // Reset crafting table position

        if (lastBoneCount < minBones.get()) {
            ChatUtils.info("Not enough bones to start crafting (need " + minBones.get() + ", have " + lastBoneCount + ")");
            if (autoDisable.get()) {
                toggle();
                return;
            }
        }

        // Warn about bone meal recipe compatibility
        if (mode.get() == Mode.Inventory) {
            ChatUtils.warning("Inventory mode may not work - bone meal recipe might require 3x3 crafting table!");
            ChatUtils.info("Consider switching to Crafting Table mode for better results.");
        } else {
            ChatUtils.info("Crafting Table mode selected - searching for nearby crafting table...");
        }

        ChatUtils.info("BoneMeal Crafter activated - Mode: " + mode.get() + " | Bones: " + lastBoneCount);
    }

    @Override
    public void onDeactivate() {
        tickCounter = 0;
        isCrafting = false;
        waitingForResult = 0;
        ChatUtils.info("BoneMeal Crafter deactivated");
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (!Utils.canUpdate() || mc.interactionManager == null) {
            debugLog("Tick skipped: Utils.canUpdate=" + Utils.canUpdate() + ", interactionManager=" + (mc.interactionManager != null));
            return;
        }
        if (mc.player == null || mc.world == null) {
            debugLog("Tick skipped: player=" + (mc.player != null) + ", world=" + (mc.world != null));
            return;
        }

        tickCounter++;

        // Check every 5 ticks (0.25 seconds) for faster response
        if (tickCounter % 5 != 0) return;

        int currentBones = getBoneCount();
        debugLog("Tick " + tickCounter + " | Bones: " + currentBones + " | isCrafting: " + isCrafting + " | waitingForResult: " + waitingForResult);
        debugLog("Current screen: " + (mc.currentScreen != null ? mc.currentScreen.getClass().getSimpleName() : "null"));
        debugLog("Screen handler: " + (mc.player.currentScreenHandler != null ? mc.player.currentScreenHandler.getClass().getSimpleName() : "null"));

        // Don't auto disable while crafting - give time to collect bone meal
        if (!isCrafting && autoDisable.get() && currentBones < minBones.get()) {
            ChatUtils.info("Auto disabling - not enough bones (need " + minBones.get() + ", have " + currentBones + ")");
            lastAction = "Auto disabled - insufficient bones";
            toggle();
            return;
        }

        // Check if we should craft
        if (shouldCraft(currentBones)) {
            debugLog("Should craft: true | Mode: " + mode.get());
            if (mode.get() == Mode.Inventory) {
                lastAction = "Performing inventory crafting";
                performInventoryCrafting();
            } else {
                lastAction = "Performing crafting table crafting";
                performCraftingTableCrafting();
            }
        } else {
            debugLog("Should craft: false | Reason: " + getShouldCraftReason(currentBones));
        }

        lastBoneCount = currentBones;
    }

    private void debugLog(String message) {
        if (debugMode) {
            ChatUtils.info("[DEBUG] " + message);
        }
    }

    private String getShouldCraftReason(int boneCount) {
        if (boneCount < minBones.get()) return "Not enough bones (" + boneCount + "/" + minBones.get() + ")";
        if (keepBones.get() && boneCount <= bonesToKeep.get()) return "Keeping bones (" + boneCount + "/" + bonesToKeep.get() + ")";
        return "Unknown";
    }

    private boolean shouldCraft(int boneCount) {
        if (boneCount < minBones.get()) return false;

        if (keepBones.get()) {
            return boneCount > bonesToKeep.get();
        }

        return true;
    }

    private void performInventoryCrafting() {
        debugLog("=== INVENTORY CRAFTING START ===");
        debugLog("Current screen type: " + (mc.currentScreen != null ? mc.currentScreen.getClass().getSimpleName() : "null"));

        // Open inventory if not already open
        if (!(mc.currentScreen instanceof InventoryScreen)) {
            debugLog("Opening inventory screen...");
            try {
                mc.setScreen(new InventoryScreen(mc.player));
                isCrafting = true;
                ChatUtils.info("Opening inventory for bone meal crafting...");
                waitingForResult = 0; // Reset counter when opening
                lastAction = "Opened inventory screen";
                debugLog("Inventory screen opened successfully");
                return;
            } catch (Exception e) {
                lastError = "Failed to open inventory: " + e.getMessage();
                debugLog("ERROR opening inventory: " + e.getMessage());
                e.printStackTrace();
                return;
            }
        }

        // Now we're in inventory screen - try recipe system
        if (mc.currentScreen instanceof InventoryScreen) {
            debugLog("In inventory screen, proceeding to recipe search...");
            ChatUtils.info("Inventory opened! Searching for bone meal recipe...");

            // Use Recipe System approach - check if we're in inventory crafting
            if (!(mc.player.currentScreenHandler instanceof AbstractCraftingScreenHandler)) {
                debugLog("Screen handler is not AbstractCraftingScreenHandler: " + mc.player.currentScreenHandler.getClass().getSimpleName());
                ChatUtils.warning("Not in crafting screen - switching to crafting table mode");
                mode.set(Mode.CraftingTable);
                mc.setScreen(null);
                isCrafting = false;
                lastAction = "Switched to crafting table mode - invalid screen handler";
                return;
            }

            // Try to find and craft bone meal recipe using Recipe System
            debugLog("Attempting recipe system crafting...");
            boolean recipeCrafted = false;
            try {
                recipeCrafted = craftBoneMealUsingRecipeSystem();
                debugLog("Recipe crafting result: " + recipeCrafted);
            } catch (Exception e) {
                lastError = "Recipe system error: " + e.getMessage();
                debugLog("ERROR in recipe system: " + e.getMessage());
                e.printStackTrace();
            }

            if (recipeCrafted) {
                ChatUtils.info("Recipe crafting attempted!");
                waitingForResult = 0;
                lastAction = "Recipe crafted successfully";
            } else {
                waitingForResult++;
                debugLog("Recipe not found, attempt " + waitingForResult + "/20");
                ChatUtils.info("No bone meal recipe found in inventory, attempt " + waitingForResult + "/20");

                if (waitingForResult > 20) { // 1 second timeout
                    ChatUtils.error("IMPORTANT: Bone meal recipe doesn't exist in vanilla Minecraft 1.21.4!");
                    ChatUtils.info("Bones are moving correctly to crafting grid, but recipe doesn't exist.");
                    ChatUtils.info("Switching to Crafting Table mode for final confirmation...");
                    mode.set(Mode.CraftingTable);
                    waitingForResult = 0;
                    mc.setScreen(null);
                    isCrafting = false;
                    lastAction = "Confirmed: No bone meal recipe in vanilla - switching to table";
                }
            }
        }

        // Close inventory after some time
        if (isCrafting && tickCounter % 100 == 0) {
            int remainingBones = getBoneCount();
            debugLog("Checking inventory close condition: bones=" + remainingBones + ", minBones=" + minBones.get());
            if (remainingBones < minBones.get() || !shouldCraft(remainingBones)) {
                mc.setScreen(null);
                isCrafting = false;
                ChatUtils.info("Closing inventory after crafting cycle - Remaining bones: " + remainingBones);
                lastAction = "Closed inventory - crafting complete";
            }
        }
        debugLog("=== INVENTORY CRAFTING END ===");
    }

    private void performCraftingTableCrafting() {
        debugLog("=== CRAFTING TABLE START ===");
        debugLog("Crafting table pos: " + craftingTablePos);

        // Find nearby crafting table
        if (craftingTablePos == null) {
            debugLog("Searching for nearby crafting table...");
            craftingTablePos = findNearbyCraftingTable();
            if (craftingTablePos == null) {
                lastError = "No crafting table found within 5 blocks";
                debugLog("ERROR: No crafting table found nearby!");
                ChatUtils.error("No crafting table found nearby! Place a crafting table within 5 blocks.");
                toggle();
                return;
            }
            debugLog("Found crafting table at: " + craftingTablePos);
        }

        // Check if crafting table is still there
        if (mc.world.getBlockState(craftingTablePos).getBlock() != Blocks.CRAFTING_TABLE) {
            debugLog("Crafting table missing at: " + craftingTablePos);
            craftingTablePos = null;
            ChatUtils.warning("Crafting table removed! Searching for new one...");
            lastAction = "Crafting table removed - searching new one";
            return;
        }

        debugLog("Current screen: " + (mc.currentScreen != null ? mc.currentScreen.getClass().getSimpleName() : "null"));

        // Open crafting table if not already open
        if (!(mc.currentScreen instanceof CraftingScreen)) {
            debugLog("Opening crafting table...");
            try {
                Vec3d hitPos = Vec3d.ofCenter(craftingTablePos);
                BlockHitResult hitResult = new BlockHitResult(hitPos, Direction.UP, craftingTablePos, false);
                var interactResult = mc.interactionManager.interactBlock(mc.player, Hand.MAIN_HAND, hitResult);
                debugLog("Interact block result: " + interactResult);

                isCrafting = true;
                ChatUtils.info("Opening crafting table for bone meal crafting...");
                waitingForResult = 0; // Reset counter when opening
                lastAction = "Opened crafting table";
                return;
            } catch (Exception e) {
                lastError = "Failed to open crafting table: " + e.getMessage();
                debugLog("ERROR opening crafting table: " + e.getMessage());
                e.printStackTrace();
                return;
            }
        }

        // Now we're in crafting table screen - try recipe system
        if (mc.currentScreen instanceof CraftingScreen) {
            debugLog("In crafting table screen, proceeding to recipe search...");
            ChatUtils.info("Crafting table opened! Searching for bone meal recipe...");

            // Try to craft using Recipe System
            debugLog("Attempting recipe system crafting...");
            boolean recipeCrafted = false;
            try {
                recipeCrafted = craftBoneMealUsingRecipeSystem();
                debugLog("Recipe crafting result: " + recipeCrafted);
            } catch (Exception e) {
                lastError = "Recipe system error in crafting table: " + e.getMessage();
                debugLog("ERROR in recipe system: " + e.getMessage());
                e.printStackTrace();
            }

            if (recipeCrafted) {
                ChatUtils.info("Recipe crafting attempted!");
                waitingForResult = 0;
                lastAction = "Recipe crafted successfully in crafting table";
            } else {
                waitingForResult++;
                debugLog("Recipe not found, attempt " + waitingForResult + "/20");
                ChatUtils.info("No bone meal recipe found, attempt " + waitingForResult + "/20");

                if (waitingForResult > 20) { // 1 second timeout
                    ChatUtils.error("No bone meal recipe found!");
                    ChatUtils.error("Bone meal recipe doesn't exist in this Minecraft version!");
                    ChatUtils.info("You need to:");
                    ChatUtils.info("1. Kill skeletons for bone meal drops");
                    ChatUtils.info("2. Use bone meal from dungeon/structure loot");
                    ChatUtils.info("3. Use mods that add bone meal recipe");
                    ChatUtils.info("4. Or use creative mode to get bone meal");
                    lastAction = "No recipe found - auto disabled";
                    toggle(); // Auto disable since recipe doesn't exist
                    return;
                }
            }
        } else {
            debugLog("Not in crafting screen despite opening: " + (mc.currentScreen != null ? mc.currentScreen.getClass().getSimpleName() : "null"));
        }

        // Auto close crafting table when done
        if (isCrafting && tickCounter % 60 == 0) {
            int remainingBones = getBoneCount();
            debugLog("Checking crafting table close condition: bones=" + remainingBones + ", minBones=" + minBones.get());
            if (remainingBones < minBones.get() || !shouldCraft(remainingBones)) {
                mc.setScreen(null);
                isCrafting = false;
                ChatUtils.info("Closing crafting table - Remaining bones: " + remainingBones);
                lastAction = "Closed crafting table - crafting complete";
            }
        }
        debugLog("=== CRAFTING TABLE END ===");
    }

    /**
     * NEW: Manual crafting approach since bone meal recipe doesn't exist in vanilla
     * This directly places bone and checks if bone meal appears in result slot
     */
    private boolean craftBoneMealUsingRecipeSystem() {
        debugLog("=== MANUAL CRAFTING START ===");
        try {
            ChatUtils.info("Starting Manual Crafting approach...");

            // Get current crafting screen handler
            if (mc.player.currentScreenHandler == null) {
                debugLog("ERROR: currentScreenHandler is null");
                return false;
            }

            if (!(mc.player.currentScreenHandler instanceof AbstractCraftingScreenHandler)) {
                debugLog("ERROR: Screen handler is not AbstractCraftingScreenHandler: " + mc.player.currentScreenHandler.getClass().getSimpleName());
                return false;
            }

            AbstractCraftingScreenHandler currentScreenHandler = (AbstractCraftingScreenHandler) mc.player.currentScreenHandler;
            debugLog("Got crafting screen handler: " + currentScreenHandler.getClass().getSimpleName());
            debugLog("Screen handler syncId: " + currentScreenHandler.syncId);
            ChatUtils.info("Got crafting screen handler: " + currentScreenHandler.getClass().getSimpleName());

            // FIRST: Move bone to crafting grid BEFORE checking result
            debugLog("=== MOVING BONE TO CRAFTING GRID ===");
            if (!moveBoneToCraftingGrid(currentScreenHandler)) {
                debugLog("Failed to move bone to crafting grid");
                ChatUtils.error("Failed to move bone to crafting grid!");
                return false;
            }

            // Wait for the game to process the crafting
            try {
                Thread.sleep(200); // 200ms delay for game to process
            } catch (InterruptedException e) {
                debugLog("Sleep interrupted: " + e.getMessage());
            }

            // CHECK: Does result slot contain bone meal now?
            debugLog("=== CHECKING RESULT SLOT ===");
            try {
                // Result slot is always slot 0 in crafting handlers
                var resultSlot = currentScreenHandler.getSlot(0);
                if (resultSlot != null && resultSlot.hasStack()) {
                    ItemStack resultStack = resultSlot.getStack();
                    String resultItem = resultStack.getItem().toString();
                    debugLog("Result slot contains: " + resultItem + " x" + resultStack.getCount());
                    ChatUtils.info("Result slot contains: " + resultItem + " x" + resultStack.getCount());

                    if (resultStack.getItem() == Items.BONE_MEAL) {
                        // Cap: use at most 64 bones per bulk session (each bone => 3 bone meal)
                        final int maxBonesToUse = 64;
                        final int maxBoneMealToCollect = maxBonesToUse * 3;
                        debugLog("BONE MEAL FOUND IN RESULT SLOT! Bulk collecting with cap " + maxBonesToUse + " bones (" + maxBoneMealToCollect + " BM)");

                        int totalCrafted = 0;
                        int attempts = 0;
                        boolean stoppedByCap = false;

                        while (true) {
                            var outSlot = currentScreenHandler.getSlot(0);
                            if (outSlot == null || !outSlot.hasStack()) break;

                            ItemStack outStack = outSlot.getStack();
                            if (outStack.getItem() != Items.BONE_MEAL) break;

                            int canTake = outStack.getCount();
                            if (totalCrafted + canTake > maxBoneMealToCollect) {
                                debugLog("Stopping to respect 64-bone cap. total=" + totalCrafted + ", next=" + canTake);
                                stoppedByCap = true;
                                break;
                            }

                            mc.interactionManager.clickSlot(currentScreenHandler.syncId, 0, 0, SlotActionType.QUICK_MOVE, mc.player);
                            totalCrafted += canTake;
                            debugLog("Collected from result: +" + canTake + " (total=" + totalCrafted + ")");

                            attempts++;
                            if (attempts > 512) { debugLog("Stopping bulk collect due to safety limit"); break; }
                            if (totalCrafted >= maxBoneMealToCollect) { stoppedByCap = true; break; }
                        }

                        // If we stopped due to the cap, pull any leftover bones from the grid back to inventory
                        if (stoppedByCap) {
                            int start = getCraftingSlotStart(currentScreenHandler);
                            int count = getCraftingInputCount(currentScreenHandler);
                            debugLog("Cap reached, clearing leftover bones from grid back to inventory");
                            clearCraftingGrid(currentScreenHandler, start, count);
                        }

                        // Ensure at least one craft if possible
                        if (totalCrafted == 0) {
                            var outSlot = currentScreenHandler.getSlot(0);
                            if (outSlot != null && outSlot.hasStack()) {
                                ItemStack outStack = outSlot.getStack();
                                if (outStack.getItem() == Items.BONE_MEAL) {
                                    mc.interactionManager.clickSlot(currentScreenHandler.syncId, 0, 0, SlotActionType.QUICK_MOVE, mc.player);
                                    totalCrafted += outStack.getCount();
                                    debugLog("Forced single craft to meet minimum: +" + outStack.getCount());
                                }
                            }
                        }

                        ChatUtils.info("Bone meal crafted (bulk, capped): +" + totalCrafted + " (max bones 64)");
                        waitingForResult = 0;
                        lastAction = "Bulk crafted (cap) bone meal x" + totalCrafted;
                        debugLog("=== BULK CRAFTING COMPLETE (CAP) ===");
                        return totalCrafted > 0;
                    } else {
                        debugLog("Result slot contains wrong item: " + resultItem);
                        ChatUtils.warning("Expected bone meal but found: " + resultItem);
                    }
                } else {
                    debugLog("Result slot is empty");
                    ChatUtils.info("Result slot is empty - bone meal recipe doesn't exist");
                }
            } catch (Exception e) {
                lastError = "Failed to check result slot: " + e.getMessage();
                debugLog("ERROR checking result slot: " + e.getMessage());
                e.printStackTrace();
            }

            debugLog("Manual crafting completed - no bone meal found");
            lastAction = "Manual crafting completed - no bone meal recipe";

            // If we reach here, no bone meal was created
            debugLog("=== MANUAL CRAFTING END - NO BONE MEAL ===");
            return false;

        } catch (Exception e) {
            lastError = "Manual Crafting critical error: " + e.getMessage();
            debugLog("CRITICAL ERROR in Manual Crafting: " + e.getMessage());
            ChatUtils.error("Error in Manual Crafting: " + e.getMessage());
            e.printStackTrace();
            debugLog("=== MANUAL CRAFTING ERROR ===");
            return false;
        }
    }

    /**
     * Move bone from inventory to crafting grid using robust screen-handler slot indices.
     * Steps:
     * 1) Determine crafting grid start and size.
     * 2) Clear any items from the crafting grid (shift-click back to inventory).
     * 3) Find a bone stack in the current screen handler outside the crafting grid.
     * 4) Pick up that stack, right-click to place exactly 1 bone into the first grid slot.
     * 5) Put remaining bones back to the original inventory slot.
     */
    private boolean moveBoneToCraftingGrid(AbstractCraftingScreenHandler screenHandler) {
        debugLog("=== MOVING BONE TO CRAFTING GRID START (robust) ===");
        try {
            // 1) Resolve crafting grid indices
            int craftingSlotStart = getCraftingSlotStart(screenHandler);
            int craftingInputCount = getCraftingInputCount(screenHandler);
            if (craftingSlotStart < 0 || craftingInputCount <= 0) {
                lastError = "Could not resolve crafting grid indices";
                debugLog("ERROR: " + lastError);
                ChatUtils.error("Gagal menentukan slot crafting!");
                return false;
            }
            debugLog("Crafting grid: start=" + craftingSlotStart + ", count=" + craftingInputCount);

            // 2) Clear grid first to avoid mixing items
            clearCraftingGrid(screenHandler, craftingSlotStart, craftingInputCount);

            // 3) Find bone slot index in current screen handler (exclude crafting grid area)
            int boneSlotIndex = findBoneSlotIndex(screenHandler, craftingSlotStart, craftingInputCount);
            if (boneSlotIndex == -1) {
                debugLog("ERROR: No bone stack found in handler slots");
                ChatUtils.error("Tidak ada bone di inventory!");
                return false;
            }
            ItemStack boneStack = screenHandler.getSlot(boneSlotIndex).getStack();
            debugLog("Found bone at handler slot " + boneSlotIndex + " x" + boneStack.getCount());

            // 4) Pick up bones from that slot
            mc.interactionManager.clickSlot(screenHandler.syncId, boneSlotIndex, 0, SlotActionType.PICKUP, mc.player);
            debugLog("Picked up bones from slot " + boneSlotIndex);

            // FAST: place the entire stack into the first crafting grid slot (left-click)
            int targetGridSlot = getCraftingSlotStart(screenHandler);
            mc.interactionManager.clickSlot(screenHandler.syncId, targetGridSlot, 0, SlotActionType.PICKUP, mc.player);
            debugLog("Placed FULL STACK of bones into crafting grid slot " + targetGridSlot);

            // Do NOT try to put remaining bones back; we intentionally moved the whole stack.
            ChatUtils.info("Bone 1 stack dipindah ke crafting grid!");
            lastAction = "Moved full stack into grid (fast)";
            debugLog("=== MOVING BONE TO CRAFTING GRID SUCCESS ===");
            return true;
        } catch (Exception e) {
            lastError = "Failed to move bone (robust): " + e.getMessage();
            debugLog("ERROR moving bone: " + e.getMessage());
            e.printStackTrace();
            ChatUtils.error("Error memindahkan bone ke grid: " + e.getMessage());
            debugLog("=== MOVING BONE TO CRAFTING GRID ERROR ===");
            return false;
        }
    }

    // Determine the starting slot index for the crafting grid (result is always 0)
    private int getCraftingSlotStart(AbstractCraftingScreenHandler screenHandler) {
        return 1; // both Inventory (2x2) and Crafting Table (3x3) start at 1
    }

    // Determine how many input slots the crafting grid has
    private int getCraftingInputCount(AbstractCraftingScreenHandler screenHandler) {
        return (screenHandler instanceof CraftingScreenHandler) ? 9 : 4; // 3x3 vs 2x2
    }

    // Shift-click any item from crafting input slots back to inventory to start clean
    private void clearCraftingGrid(AbstractCraftingScreenHandler screenHandler, int start, int count) {
        try {
            for (int i = 0; i < count; i++) {
                int idx = start + i;
                var slot = screenHandler.getSlot(idx);
                if (slot != null && slot.hasStack()) {
                    debugLog("Clearing grid slot " + idx + " (" + slot.getStack().getItem() + ")");
                    mc.interactionManager.clickSlot(screenHandler.syncId, idx, 0, SlotActionType.QUICK_MOVE, mc.player);
                }
            }
        } catch (Exception e) {
            debugLog("WARN: Failed to clear crafting grid: " + e.getMessage());
        }
    }

    // Find a handler slot containing bones outside the crafting grid area
    private int findBoneSlotIndex(AbstractCraftingScreenHandler screenHandler, int gridStart, int gridCount) {
        // Prefer scanning after the grid (player inventory area usually comes after)
        int total = screenHandler.slots.size();
        for (int i = gridStart + gridCount; i < total; i++) {
            var slot = screenHandler.getSlot(i);
            if (slot != null && slot.hasStack()) {
                ItemStack st = slot.getStack();
                if (st.getItem() == Items.BONE && st.getCount() > 0) return i;
            }
        }
        // Fallback: scan the whole handler excluding result and grid
        for (int i = 0; i < total; i++) {
            if (i == 0 || (i >= gridStart && i < gridStart + gridCount)) continue; // skip result and grid
            var slot = screenHandler.getSlot(i);
            if (slot != null && slot.hasStack()) {
                ItemStack st = slot.getStack();
                if (st.getItem() == Items.BONE && st.getCount() > 0) return i;
            }
        }
        return -1;
    }

    private BlockPos findNearbyCraftingTable() {
        if (mc.player == null || mc.world == null) return null;

        BlockPos playerPos = mc.player.getBlockPos();
        int range = 5; // 5 block radius

        for (int x = -range; x <= range; x++) {
            for (int y = -range; y <= range; y++) {
                for (int z = -range; z <= range; z++) {
                    BlockPos pos = playerPos.add(x, y, z);
                    if (mc.world.getBlockState(pos).getBlock() == Blocks.CRAFTING_TABLE) {
                        return pos;
                    }
                }
            }
        }

        return null;
    }

    private int getBoneCount() {
        return InvUtils.find(Items.BONE).count();
    }

    private int getBoneMealCount() {
        return InvUtils.find(Items.BONE_MEAL).count();
    }

    @Override
    public String getInfoString() {
        int bones = getBoneCount();
        int boneMeal = getBoneMealCount();
        String modeStr = mode.get() == Mode.Inventory ? "Inv" : "Table";

        if (debugMode) {
            return modeStr + " | Bones: " + bones + " | BM: " + boneMeal + " | " + lastAction;
        } else {
            return modeStr + " | Bones: " + bones + " | Bone Meal: " + boneMeal;
        }
    }

    // Debug command for toggling debug mode
    public void toggleDebugMode() {
        debugMode = !debugMode;
        ChatUtils.info("Debug mode: " + (debugMode ? "ON" : "OFF"));
        if (debugMode) {
            ChatUtils.info("Last action: " + lastAction);
            ChatUtils.info("Last error: " + lastError);
        }
    }
}
