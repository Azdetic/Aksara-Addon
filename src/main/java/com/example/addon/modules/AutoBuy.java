package com.example.addon.modules;

import com.example.addon.AddonTemplate;
import meteordevelopment.meteorclient.events.game.OpenScreenEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
import meteordevelopment.meteorclient.utils.player.ChatUtils;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.screen.slot.SlotActionType;

import java.util.ArrayList;
import java.util.List;

public class AutoBuy extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgSlots = settings.createGroup("Slot Management");
    private final SettingGroup sgTiming = settings.createGroup("Timing");
    private final SettingGroup sgAutoDetection = settings.createGroup("Auto Detection");

    // #region Settings
    private final Setting<String> shopCommand = sgGeneral.add(new StringSetting.Builder()
        .name("Shop Command")
        .description("Command to open shop GUI (e.g., /shop, /warp shop)")
        .defaultValue("/shop")
        .build()
    );

    private final Setting<List<String>> slotList = sgSlots.add(new StringListSetting.Builder()
        .name("Slot Configuration")
        .description("Slot numbers to click.")
        .defaultValue(List.of("28", "37", "38"))
        .build()
    );

    private final Setting<Integer> openDelay = sgTiming.add(new IntSetting.Builder()
        .name("Open Delay")
        .description("Delay after opening shop before clicking slots (ticks)")
        .defaultValue(20)
        .min(1)
        .sliderMax(100)
        .build()
    );

    private final Setting<Integer> clickDelay = sgTiming.add(new IntSetting.Builder()
        .name("Click Delay")
        .description("Delay between slot clicks (ticks)")
        .defaultValue(5)
        .min(1)
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
        .sliderMax(60)
        .visible(repeatMode::get)
        .build()
    );

    private final Setting<Integer> closeDelay = sgTiming.add(new IntSetting.Builder()
        .name("Close Delay")
        .description("Delay before closing GUI after completing all clicks (ticks)")
        .defaultValue(10)
        .min(0)
        .sliderMax(60)
        .build()
    );

    private final Setting<Boolean> autoCloseGui = sgTiming.add(new BoolSetting.Builder()
        .name("Auto Close GUI")
        .description("Automatically close shop GUI after completing all slot clicks")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> enableAutoDetection = sgAutoDetection.add(new BoolSetting.Builder()
        .name("Enable Auto Detection")
        .description("Automatically activate AutoBuy when whitelist items are below threshold")
        .defaultValue(false)
        .build()
    );

    private final Setting<List<Item>> itemWhitelist = sgAutoDetection.add(new ItemListSetting.Builder()
        .name("Item Whitelist")
        .description("Items to monitor.")
        .defaultValue(List.of(Items.BONE_MEAL, Items.BONE, Items.ARROW))
        .visible(enableAutoDetection::get)
        .build()
    );

    private final Setting<Integer> minimumThreshold = sgAutoDetection.add(new IntSetting.Builder()
        .name("Minimum Threshold")
        .description("Minimum items required before auto-stopping (per item type)")
        .defaultValue(32)
        .min(1)
        .sliderMax(320)
        .visible(enableAutoDetection::get)
        .build()
    );

    private final Setting<Integer> checkInterval = sgAutoDetection.add(new IntSetting.Builder()
        .name("Check Interval")
        .description("How often to check inventory (in ticks)")
        .defaultValue(20)
        .min(5)
        .sliderMax(100)
        .visible(enableAutoDetection::get)
        .build()
    );
    // #endregion

    // #region State Variables
    private enum Phase { IDLE, WAITING_FOR_GUI, PROCESSING, WAITING_TO_CLOSE }
    private Phase phase = Phase.IDLE;

    private int tickCounter = 0;
    private int currentSlotIndex = 0;
    private long lastRepeatTime = 0;
    private int autoDetectionTimer = 0;

    private boolean autoActivated = false;
    private Item currentItemTarget = null;
    // #endregion

    public AutoBuy() {
        super(AddonTemplate.CATEGORY, "auto-buy", "Automatically buys items from a shop.");
    }

    // #region Module Lifecycle (onActivate/onDeactivate)
    @Override
    public void onActivate() {
        if (getValidSlots().isEmpty()) {
            error("No valid slots configured! Add slot numbers in settings.");
            toggle();
            return;
        }

        // Reset basic counters
        tickCounter = 0;
        currentSlotIndex = 0;
        lastRepeatTime = 0;

        if (enableAutoDetection.get()) {
            // In auto-detection mode, don't force a buy cycle on enable; monitor instead
            autoActivated = false;
            currentItemTarget = findFirstItemBelowThreshold();
            if (currentItemTarget != null) {
                info("Auto-Detection: %s is at/below threshold. Starting buy cycle.", itemName(currentItemTarget));
                autoActivated = true;
                startCycle();
            } else {
                info("AutoBuy monitoring enabled. Will buy when whitelisted items reach minimum.");
                phase = Phase.IDLE;
            }
        } else {
            // Legacy behavior when auto-detection is off
            currentItemTarget = null;
            info("AutoBuy started.");
            startCycle();
        }
    }

    @Override
    public void onDeactivate() {
        resetAllState();
        info("AutoBuy disabled.");
    }
    // #endregion

    // #region Event Handlers (onTick/onOpenScreen)
    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null || mc.world == null) return;

        if (enableAutoDetection.get()) {
            autoDetectionTimer++;
            if (autoDetectionTimer >= checkInterval.get()) {
                checkInventoryAndActivateIfNeeded();
                autoDetectionTimer = 0;
            }
        }

        if (!isActive()) return;

        tickCounter++;
        switch (phase) {
            case WAITING_FOR_GUI:
                break;
            case PROCESSING:
                handleProcessingPhase();
                break;
            case WAITING_TO_CLOSE:
                if (tickCounter >= closeDelay.get()) {
                    closeShopGui();
                    handleCompletion();
                }
                break;
            case IDLE:
                // Only use generic repeat timer when auto-detection is OFF.
                if (!enableAutoDetection.get() && repeatMode.get() && !autoActivated && lastRepeatTime > 0) {
                    if (System.currentTimeMillis() - lastRepeatTime >= repeatDelay.get() * 1000) {
                        info("Repeat timer finished. Starting new cycle.");
                        startCycle();
                    }
                }
                break;
        }
    }

    @EventHandler
    private void onOpenScreen(OpenScreenEvent event) {
        // Consider any handled screen as a valid shop UI; more robust across servers
        if (!isActive() || phase != Phase.WAITING_FOR_GUI) return;
        if (mc.player != null && mc.player.currentScreenHandler != null) {
            info("Shop GUI detected.");
            phase = Phase.PROCESSING;
            tickCounter = 0;
            currentSlotIndex = 0;
        }
    }
    // #endregion

    // #region Core Logic
    private void handleProcessingPhase() {
        if (tickCounter < openDelay.get()) return;

        List<Integer> slots = getValidSlots();
        if (bulkMode.get()) {
            info("Bulk clicking %d slots...", slots.size());
            processBulkClicks(slots);
            if (autoCloseGui.get()) {
                phase = Phase.WAITING_TO_CLOSE;
                tickCounter = 0;
            } else {
                handleCompletion();
            }
        } else {
            if (tickCounter >= (openDelay.get() + clickDelay.get())) {
                if (currentSlotIndex < slots.size()) {
                    clickSlot(slots.get(currentSlotIndex));
                    currentSlotIndex++;
                    tickCounter = openDelay.get();
                } else {
                    if (autoCloseGui.get()) {
                        phase = Phase.WAITING_TO_CLOSE;
                        tickCounter = 0;
                    } else {
                        handleCompletion();
                    }
                }
            }
        }
    }

    private void handleCompletion() {
        info("Buy cycle completed.");
        lastRepeatTime = System.currentTimeMillis();

        if (autoActivated) {
            if (hasEnoughItems()) {
                info("Target for %s reached. Returning to monitoring state.", itemName(currentItemTarget));
                resetForMonitoring();
            } else {
                info("Still need more %s. Starting next cycle.", itemName(currentItemTarget));
                startCycle();
            }
        } else if (enableAutoDetection.get()) {
            // In auto-detection mode (manual enable), keep module active for monitoring instead of repeating blindly
            info("Returning to monitoring state.");
            phase = Phase.IDLE;
        } else if (repeatMode.get()) {
            info("Waiting %d seconds for next repeat cycle.", repeatDelay.get());
            phase = Phase.IDLE;
        } else {
            info("Single run complete. Disabling module.");
            toggle();
        }
    }

    private void checkInventoryAndActivateIfNeeded() {
        if (isActive() && phase != Phase.IDLE) return;

        Item itemToBuy = findFirstItemBelowThreshold();
        if (itemToBuy != null) {
            currentItemTarget = itemToBuy;
            autoActivated = true;
            if (!isActive()) {
                info("Auto-Detection: %s is below threshold. Activating AutoBuy.", itemName(itemToBuy));
                toggle();
            } else {
                info("Auto-Detection: While monitoring, detected %s is low. Starting buy cycle.", itemName(itemToBuy));
                startCycle();
            }
        }
    }
    // #endregion

    // #region Helper Methods
    private void startCycle() {
        phase = Phase.WAITING_FOR_GUI;
        tickCounter = 0;
        currentSlotIndex = 0;
        executeShopCommand();
    }

    private void resetAllState() {
        phase = Phase.IDLE;
        tickCounter = 0;
        currentSlotIndex = 0;
        lastRepeatTime = 0;
        autoDetectionTimer = 0;
        autoActivated = false;
        currentItemTarget = null;
    }

    private void resetForMonitoring() {
        phase = Phase.IDLE;
        autoActivated = false;
        currentItemTarget = null;
    }

    private void executeShopCommand() {
        if (mc.player != null) {
            // Send the command like a normal chat message; works across mappings
            ChatUtils.sendPlayerMsg(shopCommand.get());
        }
    }

    private void clickSlot(int slotNumber) {
        if (mc.player != null && mc.player.currentScreenHandler != null && mc.interactionManager != null) {
            ScreenHandler handler = mc.player.currentScreenHandler;
            if (slotNumber >= 0 && slotNumber < handler.slots.size()) {
                mc.interactionManager.clickSlot(handler.syncId, slotNumber, 0, SlotActionType.PICKUP, mc.player);
                info("Clicked slot #%d", slotNumber);
            } else {
                error("Invalid slot #%d. GUI has %d slots.", slotNumber, handler.slots.size());
            }
        }
    }

    private void processBulkClicks(List<Integer> validSlots) {
        if (mc.player != null && mc.player.currentScreenHandler != null && mc.interactionManager != null) {
            ScreenHandler handler = mc.player.currentScreenHandler;
            for (int slotNumber : validSlots) {
                if (slotNumber >= 0 && slotNumber < handler.slots.size()) {
                    mc.interactionManager.clickSlot(handler.syncId, slotNumber, 0, SlotActionType.PICKUP, mc.player);
                } else {
                    error("Skipped invalid bulk slot #%d.", slotNumber);
                }
            }
        }
    }

    private void closeShopGui() {
        if (mc.player != null) {
            mc.player.closeHandledScreen();
            info("Shop GUI closed.");
        }
    }

    private boolean hasEnoughItems() {
        if (mc.player == null || currentItemTarget == null) return true;
        return countItemInInventory(currentItemTarget) >= minimumThreshold.get();
    }

    private int countItemInInventory(Item item) {
        if (mc.player == null) return 0;
        int totalCount = 0;
        for (int i = 0; i < mc.player.getInventory().size(); i++) {
            ItemStack stack = mc.player.getInventory().getStack(i);
            if (!stack.isEmpty() && stack.getItem() == item) {
                totalCount += stack.getCount();
            }
        }
        return totalCount;
    }

    private Item findFirstItemBelowThreshold() {
        for (Item item : itemWhitelist.get()) {
            // Trigger when at or below the minimum threshold
            if (countItemInInventory(item) <= minimumThreshold.get()) {
                return item;
            }
        }
        return null;
    }

    private List<Integer> getValidSlots() {
        List<Integer> slots = new ArrayList<>();
        for (String slotStr : slotList.get()) {
            try {
                slots.add(Integer.parseInt(slotStr.trim()));
            } catch (NumberFormatException e) {
                // Ignore invalid entries
            }
        }
        return slots;
    }

    private String itemName(Item item) {
        if (item == null) return "nothing";
        return item.getName().getString();
    }
}
