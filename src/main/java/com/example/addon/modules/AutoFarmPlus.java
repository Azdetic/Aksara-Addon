package com.example.addon.modules;

import com.example.addon.AddonTemplate;
import meteordevelopment.meteorclient.events.entity.player.BreakBlockEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.Utils;
import meteordevelopment.meteorclient.utils.misc.Pool;
import meteordevelopment.meteorclient.utils.player.ChatUtils;
import meteordevelopment.meteorclient.utils.player.FindItemResult;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.meteorclient.utils.world.BlockIterator;
import meteordevelopment.meteorclient.utils.world.BlockUtils;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.block.*;
import net.minecraft.item.HoeItem;
import net.minecraft.item.Item;
import net.minecraft.item.Items;
import net.minecraft.registry.tag.FluidTags;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.WorldView;

import java.util.*;

public class AutoFarmPlus extends Module {
    // Setting groups
    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgTill = settings.createGroup("Till");
    private final SettingGroup sgHarvest = settings.createGroup("Harvest");
    private final SettingGroup sgPlant = settings.createGroup("Plant");
    private final SettingGroup sgBonemeal = settings.createGroup("Bonemeal");
    private final SettingGroup sgGlowBerries = settings.createGroup("Glow Berries");

    // Internal maps and pools
    private final Map<BlockPos, Item> replantMap = new HashMap<>();
    private final Pool<BlockPos.Mutable> blockPosPool = new Pool<>(BlockPos.Mutable::new);
    private final List<BlockPos.Mutable> blocks = new ArrayList<>();
    private final List<BlockPos> bonemealQueue = new ArrayList<>();
    private int actions = 0;

    // === GENERAL SETTINGS ===
    private final Setting<Integer> range = sgGeneral.add(new IntSetting.Builder()
        .name("range")
        .description("Auto farm range for all operations")
        .defaultValue(4)
        .min(1)
        .max(10)
        .sliderMax(8)
        .build()
    );

    private final Setting<Integer> bpt = sgGeneral.add(new IntSetting.Builder()
        .name("blocks-per-tick")
        .description("Amount of operations that can be applied in one tick")
        .min(1)
        .max(20)
        .defaultValue(1)
        .sliderMax(10)
        .build()
    );

    private final Setting<Boolean> fastMode = sgGeneral.add(new BoolSetting.Builder()
        .name("fast-mode")
        .description("Enable fast mode for quicker operations (higher BPT)")
        .defaultValue(false)
        .build()
    );

    private final Setting<Integer> fastModeBpt = sgGeneral.add(new IntSetting.Builder()
        .name("fast-mode-bpt")
        .description("Blocks per tick when fast mode is enabled")
        .min(5)
        .max(50)
        .defaultValue(15)
        .sliderMax(30)
        .visible(() -> fastMode.get())
        .build()
    );

    private final Setting<Boolean> rotate = sgGeneral.add(new BoolSetting.Builder()
        .name("rotate")
        .description("Whether or not to rotate towards block")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> bypassLook = sgGeneral.add(new BoolSetting.Builder()
        .name("bypass-look")
        .description("Bypass auto look to blocks (player doesn't need to look at crops)")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> chatInfo = sgGeneral.add(new BoolSetting.Builder()
        .name("chat-info")
        .description("Show chat information about farm operations")
        .defaultValue(false)
        .build()
    );

    // === TILL SETTINGS ===
    private final Setting<Boolean> till = sgTill.add(new BoolSetting.Builder()
        .name("till")
        .description("Turn nearby dirt into farmland")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> moist = sgTill.add(new BoolSetting.Builder()
        .name("moist")
        .description("Only till moist blocks (near water)")
        .defaultValue(true)
        .build()
    );

    private final Setting<Integer> tillRange = sgTill.add(new IntSetting.Builder()
        .name("till-range")
        .description("Range for tilling operations")
        .defaultValue(4)
        .min(1)
        .max(8)
        .sliderMax(6)
        .visible(() -> till.get())
        .build()
    );

    // === HARVEST SETTINGS ===
    private final Setting<Boolean> harvest = sgHarvest.add(new BoolSetting.Builder()
        .name("harvest")
        .description("Harvest crops when they are mature")
        .defaultValue(true)
        .build()
    );

    private final Setting<List<Block>> harvestBlocks = sgHarvest.add(new BlockListSetting.Builder()
        .name("harvest-blocks")
        .description("Which crops to harvest")
        .defaultValue(getDefaultHarvestBlocks())
        .filter(this::harvestFilter)
        .visible(() -> harvest.get())
        .build()
    );

    private final Setting<Integer> harvestRange = sgHarvest.add(new IntSetting.Builder()
        .name("harvest-range")
        .description("Range for harvesting operations")
        .defaultValue(4)
        .min(1)
        .max(8)
        .sliderMax(6)
        .visible(() -> harvest.get())
        .build()
    );

    // === PLANT SETTINGS ===
    private final Setting<Boolean> plant = sgPlant.add(new BoolSetting.Builder()
        .name("plant")
        .description("Plant crops after harvesting")
        .defaultValue(true)
        .build()
    );

    private final Setting<List<Item>> plantItems = sgPlant.add(new ItemListSetting.Builder()
        .name("plant-items")
        .description("Which crops to plant")
        .defaultValue(getDefaultPlantItems())
        .filter(this::plantFilter)
        .visible(() -> plant.get())
        .build()
    );

    private final Setting<Boolean> onlyReplant = sgPlant.add(new BoolSetting.Builder()
        .name("only-replant")
        .description("Only replant crops that were previously planted (smart replanting)")
        .defaultValue(true)
        .onChanged(b -> replantMap.clear())
        .visible(() -> plant.get())
        .build()
    );

    private final Setting<Boolean> smartReplant = sgPlant.add(new BoolSetting.Builder()
        .name("smart-replant")
        .description("Prioritize replanting the same crop type that was harvested")
        .defaultValue(true)
        .visible(() -> plant.get())
        .build()
    );

    // === BONEMEAL SETTINGS ===
    private final Setting<Boolean> bonemeal = sgBonemeal.add(new BoolSetting.Builder()
        .name("bonemeal")
        .description("Use bone meal to accelerate crop growth")
        .defaultValue(true)
        .build()
    );

    private final Setting<List<Block>> bonemealBlocks = sgBonemeal.add(new BlockListSetting.Builder()
        .name("bonemeal-blocks")
        .description("Which crops to apply bone meal to")
        .defaultValue(getDefaultBonemealBlocks())
        .filter(this::bonemealFilter)
        .visible(() -> bonemeal.get())
        .build()
    );

    private final Setting<Integer> bonemealRange = sgBonemeal.add(new IntSetting.Builder()
        .name("bonemeal-range")
        .description("Range for bone meal operations")
        .defaultValue(3)
        .min(1)
        .max(8)
        .sliderMax(6)
        .visible(() -> bonemeal.get())
        .build()
    );

    private final Setting<Boolean> smartBonemeal = sgBonemeal.add(new BoolSetting.Builder()
        .name("smart-bonemeal")
        .description("Only use bone meal on crops that are not mature")
        .defaultValue(true)
        .visible(() -> bonemeal.get())
        .build()
    );

    private final Setting<Integer> bonemealDelay = sgBonemeal.add(new IntSetting.Builder()
        .name("bonemeal-delay")
        .description("Delay between bone meal applications (in ticks)")
        .defaultValue(10)
        .min(1)
        .max(60)
        .sliderMax(30)
        .visible(() -> bonemeal.get())
        .build()
    );

    private final Setting<Boolean> batchBonemeal = sgBonemeal.add(new BoolSetting.Builder()
        .name("batch-bonemeal")
        .description("Apply bone meal to multiple crops simultaneously (not 1 by 1)")
        .defaultValue(true)
        .visible(() -> bonemeal.get())
        .build()
    );

    private final Setting<Integer> batchSize = sgBonemeal.add(new IntSetting.Builder()
        .name("batch-size")
        .description("How many crops to bonemeal at once when batch mode is enabled")
        .defaultValue(8)
        .min(1)
        .max(100)
        .sliderMax(64)
        .visible(() -> bonemeal.get() && batchBonemeal.get())
        .build()
    );

    private final Setting<Boolean> ultraFastBonemeal = sgBonemeal.add(new BoolSetting.Builder()
        .name("ultra-fast-bonemeal")
        .description("Ultra fast bonemeal mode - up to 64 per second (WARNING: May cause lag)")
        .defaultValue(false)
        .visible(() -> bonemeal.get() && batchBonemeal.get())
        .build()
    );

    private final Setting<Integer> ultraBatchSize = sgBonemeal.add(new IntSetting.Builder()
        .name("ultra-batch-size")
        .description("Ultra fast batch size - max 64 per tick")
        .defaultValue(32)
        .min(20)
        .max(64)
        .sliderMax(64)
        .visible(() -> bonemeal.get() && batchBonemeal.get() && ultraFastBonemeal.get())
        .build()
    );

    private final Setting<Boolean> insaneSpeed = sgBonemeal.add(new BoolSetting.Builder()
        .name("insane-speed")
        .description("INSANE MODE: 64 bonemeals per second! Independent mode (WARNING: EXTREME LAG RISK)")
        .defaultValue(false)
        .visible(() -> bonemeal.get())
        .build()
    );

    private final Setting<Integer> insaneTickRate = sgBonemeal.add(new IntSetting.Builder()
        .name("insane-tick-rate")
        .description("How many times per tick to apply bonemeal in insane mode")
        .defaultValue(4)
        .min(1)
        .max(10)
        .sliderMax(8)
        .visible(() -> bonemeal.get() && insaneSpeed.get())
        .build()
    );

    private final Setting<Integer> insaneBatchSize = sgBonemeal.add(new IntSetting.Builder()
        .name("insane-batch-size")
        .description("Insane mode batch size - independent of other settings")
        .defaultValue(64)
        .min(32)
        .max(64)
        .sliderMax(64)
        .visible(() -> bonemeal.get() && insaneSpeed.get())
        .build()
    );

    // === GLOW BERRIES SETTINGS ===
    private final Setting<Boolean> enableGlowBerries = sgGlowBerries.add(new BoolSetting.Builder()
        .name("enable-glow-berries")
        .description("Enable glow berries farming support")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> harvestGlowBerries = sgGlowBerries.add(new BoolSetting.Builder()
        .name("harvest-glow-berries")
        .description("Harvest glow berries from cave vines")
        .defaultValue(true)
        .visible(() -> enableGlowBerries.get())
        .build()
    );

    private final Setting<Boolean> plantGlowBerries = sgGlowBerries.add(new BoolSetting.Builder()
        .name("plant-glow-berries")
        .description("Plant glow berries on suitable blocks")
        .defaultValue(true)
        .visible(() -> enableGlowBerries.get())
        .build()
    );

    private final Setting<Boolean> bonemealGlowBerries = sgGlowBerries.add(new BoolSetting.Builder()
        .name("bonemeal-glow-berries")
        .description("Use bone meal on glow berry vines to accelerate growth")
        .defaultValue(true)
        .visible(() -> enableGlowBerries.get())
        .build()
    );

    private final Setting<Integer> glowBerriesRange = sgGlowBerries.add(new IntSetting.Builder()
        .name("glow-berries-range")
        .description("Range for glow berries operations")
        .defaultValue(4)
        .min(1)
        .max(8)
        .sliderMax(6)
        .visible(() -> enableGlowBerries.get())
        .build()
    );

    // Internal variables
    private long lastBonemealTime = 0;

    public AutoFarmPlus() {
        super(AddonTemplate.CATEGORY, "auto-farm-plus", "Advanced all-in-one farm utility with glow berries support and fast mode.");
    }

    @Override
    public void onActivate() {
        replantMap.clear();
        lastBonemealTime = 0;
        if (chatInfo.get()) {
            ChatUtils.info("Auto Farm+ activated!");
        }
    }

    @Override
    public void onDeactivate() {
        replantMap.clear();
        if (chatInfo.get()) {
            ChatUtils.info("Auto Farm+ deactivated!");
        }
    }

    @EventHandler
    private void onBreakBlock(BreakBlockEvent event) {
        if (!smartReplant.get() && !onlyReplant.get()) return;

        BlockState state = mc.world.getBlockState(event.blockPos);
        Block block = state.getBlock();

        if (onlyReplant.get() || smartReplant.get()) {
            Item item = getReplantItem(block);
            if (item != null) {
                replantMap.put(event.blockPos, item);
                if (chatInfo.get()) {
                    ChatUtils.info("Marked " + block.getName().getString() + " for replanting with " + item.getName().getString());
                }
            }
        }
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null || mc.world == null) return;

        actions = 0;
        bonemealQueue.clear();

        // Insane mode gets the highest BPT (INDEPENDENT)
        int currentBpt;
        if (insaneSpeed.get()) {
            currentBpt = 500; // INSANE BPT for 64 bonemeal/sec - highest priority
        } else if (ultraFastBonemeal.get()) {
            currentBpt = 200; // Ultra high for ultra fast mode
        } else if (fastMode.get()) {
            currentBpt = fastModeBpt.get();
        } else {
            currentBpt = bpt.get();
        }

        BlockIterator.register(range.get(), range.get(), (pos, state) -> {
            if (mc.player.getEyePos().distanceTo(Vec3d.ofCenter(pos)) <= range.get()) {
                blocks.add(blockPosPool.get().set(pos));
            }
        });

        BlockIterator.after(() -> {
            // Sort blocks by distance for efficiency
            blocks.sort(Comparator.comparingDouble(value -> mc.player.getEyePos().distanceTo(Vec3d.ofCenter(value))));

            if (chatInfo.get() && !blocks.isEmpty()) {
                ChatUtils.info("Processing " + blocks.size() + " blocks in range");
            }

            // First pass: collect operations and bonemeal candidates
            for (BlockPos pos : blocks) {
                BlockState state = mc.world.getBlockState(pos);
                Block block = state.getBlock();

                // Try different operations in priority order
                if (till(pos, block) ||
                    harvest(pos, state, block) ||
                    harvestGlowBerries(pos, state, block) ||
                    plant(pos, block) ||
                    plantGlowBerries(pos, block)) {
                    actions++;
                }

                // Collect bonemeal candidates for insane mode (INDEPENDENT)
                if (insaneSpeed.get() && shouldBonemeal(pos, state, block)) {
                    bonemealQueue.add(pos.toImmutable());
                    if (chatInfo.get()) {
                        ChatUtils.info("§4[INSANE MODE] Added " + block.getName().getString() + " to queue at " + pos.toString() + " (Queue: " + bonemealQueue.size() + ")");
                    }
                }
                // Collect bonemeal candidates for batch processing
                else if (batchBonemeal.get() && shouldBonemeal(pos, state, block)) {
                    bonemealQueue.add(pos.toImmutable());
                    if (chatInfo.get()) {
                        ChatUtils.info("Added " + block.getName().getString() + " to bonemeal queue at " + pos.toString() + " (Queue size: " + bonemealQueue.size() + ")");
                    }
                }

                // Single bonemeal if neither insane nor batch is enabled
                if (!insaneSpeed.get() && !batchBonemeal.get() && bonemeal(pos, state, block)) {
                    actions++;
                }

                if (actions >= currentBpt && !insaneSpeed.get() && !batchBonemeal.get()) break;
            }

            // Apply insane mode bonemeal (INDEPENDENT - highest priority)
            if (insaneSpeed.get() && !bonemealQueue.isEmpty()) {
                if (chatInfo.get()) {
                    ChatUtils.info("§4§lINSANE MODE: Applying ultra-fast bonemeal to " + bonemealQueue.size() + " crops");

                    // Check if we have bone meal
                    FindItemResult checkBonemeal = InvUtils.find(Items.BONE_MEAL);
                    if (checkBonemeal.found()) {
                        ChatUtils.info("§4[INSANE] Bone meal found in slot " + checkBonemeal.slot());
                    } else {
                        ChatUtils.warning("§4[INSANE] No bone meal found in inventory!");
                    }
                }

                        applyInsaneBonemeal(); // Use separate method for insane mode
            }
            // Apply batch bonemeal if enabled and insane is disabled
            else if (batchBonemeal.get() && !bonemealQueue.isEmpty()) {
                if (chatInfo.get()) {
                    ChatUtils.info("Applying batch bonemeal to " + bonemealQueue.size() + " crops");

                    // Check if we have bone meal
                    FindItemResult checkBonemeal = InvUtils.find(Items.BONE_MEAL);
                    if (checkBonemeal.found()) {
                        ChatUtils.info("Bone meal found in slot " + checkBonemeal.slot());
                    } else {
                        ChatUtils.warning("No bone meal found in inventory!");
                    }
                }

                // Ultra fast mode: multiple passes for maximum speed
                if (ultraFastBonemeal.get()) {
                    int totalApplied = 0;
                    int passes = 0;
                    int maxPasses = 4; // Up to 4 passes per tick for ultra speed

                    while (!bonemealQueue.isEmpty() && passes < maxPasses && totalApplied < 64) {
                        int beforeSize = bonemealQueue.size();
                        applyBatchBonemeal();
                        int afterSize = bonemealQueue.size();
                        totalApplied += (beforeSize - afterSize);
                        passes++;

                        if (chatInfo.get()) {
                            ChatUtils.info("Ultra Fast Pass " + passes + ": Applied " + (beforeSize - afterSize) + " bonemeals (Total: " + totalApplied + ")");
                        }
                    }
                } else {
                    applyBatchBonemeal();
                }
            }            // Clean up
            for (BlockPos.Mutable blockPos : blocks) blockPosPool.free(blockPos);
            blocks.clear();
        });
    }

    private boolean till(BlockPos pos, Block block) {
        if (!till.get()) return false;
        if (mc.player.getEyePos().distanceTo(Vec3d.ofCenter(pos)) > tillRange.get()) return false;

        boolean moistCheck = !moist.get() || isWaterNearby(mc.world, pos);
        boolean tillable = block == Blocks.GRASS_BLOCK ||
                          block == Blocks.DIRT_PATH ||
                          block == Blocks.DIRT ||
                          block == Blocks.COARSE_DIRT ||
                          block == Blocks.ROOTED_DIRT;

        if (moistCheck && tillable && mc.world.getBlockState(pos.up()).isAir()) {
            FindItemResult hoe = InvUtils.findInHotbar(itemStack -> itemStack.getItem() instanceof HoeItem);
            if (hoe.found()) {
                if (rotate.get() && !bypassLook.get()) {
                    Vec3d center = Vec3d.ofCenter(pos);
                    double yaw = Math.atan2(center.z - mc.player.getZ(), center.x - mc.player.getX()) * 180.0 / Math.PI - 90.0;
                    double pitch = -Math.atan2(center.y - mc.player.getEyeY(), Math.sqrt(Math.pow(center.x - mc.player.getX(), 2) + Math.pow(center.z - mc.player.getZ(), 2))) * 180.0 / Math.PI;
                    mc.player.setYaw((float) yaw);
                    mc.player.setPitch((float) pitch);
                }
                mc.interactionManager.interactBlock(mc.player, Hand.MAIN_HAND,
                    new BlockHitResult(Utils.vec3d(pos), Direction.UP, pos, false));

                if (chatInfo.get()) {
                    ChatUtils.info("Tilled " + block.getName().getString() + " at " + pos.toString());
                }
                return true;
            }
        }
        return false;
    }

    private boolean harvest(BlockPos pos, BlockState state, Block block) {
        if (!harvest.get()) return false;
        if (mc.player.getEyePos().distanceTo(Vec3d.ofCenter(pos)) > harvestRange.get()) return false;
        if (!harvestBlocks.get().contains(block)) return false;
        if (!isMature(state, block)) return false;

        if (block instanceof SweetBerryBushBlock) {
            if (rotate.get() && !bypassLook.get()) {
                Vec3d center = Vec3d.ofCenter(pos);
                double yaw = Math.atan2(center.z - mc.player.getZ(), center.x - mc.player.getX()) * 180.0 / Math.PI - 90.0;
                double pitch = -Math.atan2(center.y - mc.player.getEyeY(), Math.sqrt(Math.pow(center.x - mc.player.getX(), 2) + Math.pow(center.z - mc.player.getZ(), 2))) * 180.0 / Math.PI;
                mc.player.setYaw((float) yaw);
                mc.player.setPitch((float) pitch);
            }
            mc.interactionManager.interactBlock(mc.player, Hand.MAIN_HAND,
                new BlockHitResult(Utils.vec3d(pos), Direction.UP, pos, false));
        } else {
            if (rotate.get() && !bypassLook.get()) {
                Vec3d center = Vec3d.ofCenter(pos);
                double yaw = Math.atan2(center.z - mc.player.getZ(), center.x - mc.player.getX()) * 180.0 / Math.PI - 90.0;
                double pitch = -Math.atan2(center.y - mc.player.getEyeY(), Math.sqrt(Math.pow(center.x - mc.player.getX(), 2) + Math.pow(center.z - mc.player.getZ(), 2))) * 180.0 / Math.PI;
                mc.player.setYaw((float) yaw);
                mc.player.setPitch((float) pitch);
            }
            mc.interactionManager.updateBlockBreakingProgress(pos, Direction.UP);
        }

        if (chatInfo.get()) {
            ChatUtils.info("Harvested " + block.getName().getString() + " at " + pos.toString());
        }
        return true;
    }

    private boolean harvestGlowBerries(BlockPos pos, BlockState state, Block block) {
        if (!enableGlowBerries.get() || !harvestGlowBerries.get()) return false;
        if (mc.player.getEyePos().distanceTo(Vec3d.ofCenter(pos)) > glowBerriesRange.get()) return false;

        if (block == Blocks.CAVE_VINES || block == Blocks.CAVE_VINES_PLANT) {
            // Check if the vine has berries (simplified check since CaveVines might not be accessible)
            if (rotate.get() && !bypassLook.get()) {
                Vec3d center = Vec3d.ofCenter(pos);
                double yaw = Math.atan2(center.z - mc.player.getZ(), center.x - mc.player.getX()) * 180.0 / Math.PI - 90.0;
                double pitch = -Math.atan2(center.y - mc.player.getEyeY(), Math.sqrt(Math.pow(center.x - mc.player.getX(), 2) + Math.pow(center.z - mc.player.getZ(), 2))) * 180.0 / Math.PI;
                mc.player.setYaw((float) yaw);
                mc.player.setPitch((float) pitch);
            }
            mc.interactionManager.interactBlock(mc.player, Hand.MAIN_HAND,
                new BlockHitResult(Utils.vec3d(pos), Direction.UP, pos, false));

            if (chatInfo.get()) {
                ChatUtils.info("Harvested glow berries at " + pos.toString());
            }
            return true;
        }
        return false;
    }

    private boolean plant(BlockPos pos, Block block) {
        if (!plant.get()) return false;
        if (!mc.world.isAir(pos.up())) return false;

        FindItemResult findItemResult = null;

        if (onlyReplant.get()) {
            // Check if this position was marked for replanting
            for (BlockPos replantPos : replantMap.keySet()) {
                if (replantPos.equals(pos.up())) {
                    findItemResult = InvUtils.find(replantMap.get(replantPos));
                    replantMap.remove(replantPos);
                    break;
                }
            }
        } else if (block instanceof FarmlandBlock) {
            findItemResult = InvUtils.find(itemStack -> {
                Item item = itemStack.getItem();
                return item != Items.NETHER_WART && plantItems.get().contains(item);
            });
        } else if (block instanceof SoulSandBlock) {
            findItemResult = InvUtils.find(itemStack -> {
                Item item = itemStack.getItem();
                return item == Items.NETHER_WART && plantItems.get().contains(Items.NETHER_WART);
            });
        }

        if (findItemResult != null && findItemResult.found()) {
            BlockUtils.place(pos.up(), findItemResult, rotate.get(), -100, false);
            if (chatInfo.get()) {
                ChatUtils.info("Planted " + findItemResult.getHand().toString() + " at " + pos.up().toString());
            }
            return true;
        }
        return false;
    }

    private boolean plantGlowBerries(BlockPos pos, Block block) {
        if (!enableGlowBerries.get() || !plantGlowBerries.get()) return false;
        if (mc.player.getEyePos().distanceTo(Vec3d.ofCenter(pos)) > glowBerriesRange.get()) return false;

        // Check if we can plant glow berries below this block
        if (mc.world.isAir(pos.down()) && (block == Blocks.STONE || block == Blocks.DEEPSLATE ||
            block == Blocks.GRANITE || block == Blocks.DIORITE || block == Blocks.ANDESITE ||
            block.getName().getString().toLowerCase().contains("stone"))) {

            FindItemResult glowBerries = InvUtils.find(Items.GLOW_BERRIES);
            if (glowBerries.found()) {
                BlockUtils.place(pos.down(), glowBerries, rotate.get(), -100, false);
                if (chatInfo.get()) {
                    ChatUtils.info("Planted glow berries at " + pos.down().toString());
                }
                return true;
            }
        }
        return false;
    }

    private boolean bonemeal(BlockPos pos, BlockState state, Block block) {
        if (!bonemeal.get()) return false;
        if (System.currentTimeMillis() - lastBonemealTime < bonemealDelay.get() * 50) return false;
        if (mc.player.getEyePos().distanceTo(Vec3d.ofCenter(pos)) > bonemealRange.get()) return false;

        boolean shouldBonemeal = false;

        // Check regular crops
        if (bonemealBlocks.get().contains(block)) {
            if (smartBonemeal.get()) {
                shouldBonemeal = !isMature(state, block);
            } else {
                shouldBonemeal = true;
            }
        }

        // Check glow berries
        if (enableGlowBerries.get() && bonemealGlowBerries.get() &&
            (block == Blocks.CAVE_VINES || block == Blocks.CAVE_VINES_PLANT)) {
            shouldBonemeal = true;
        }

        if (shouldBonemeal) {
            FindItemResult bonemealItem = InvUtils.findInHotbar(Items.BONE_MEAL);
            if (bonemealItem.found()) {
                if (rotate.get() && !bypassLook.get()) {
                    Vec3d center = Vec3d.ofCenter(pos);
                    double yaw = Math.atan2(center.z - mc.player.getZ(), center.x - mc.player.getX()) * 180.0 / Math.PI - 90.0;
                    double pitch = -Math.atan2(center.y - mc.player.getEyeY(), Math.sqrt(Math.pow(center.x - mc.player.getX(), 2) + Math.pow(center.z - mc.player.getZ(), 2))) * 180.0 / Math.PI;
                    mc.player.setYaw((float) yaw);
                    mc.player.setPitch((float) pitch);
                }
                mc.interactionManager.interactBlock(mc.player, Hand.MAIN_HAND,
                    new BlockHitResult(Utils.vec3d(pos), Direction.UP, pos, false));

                lastBonemealTime = System.currentTimeMillis();
                if (chatInfo.get()) {
                    ChatUtils.info("Applied bone meal to " + block.getName().getString() + " at " + pos.toString());
                }
                return true;
            }
        }
        return false;
    }

    private boolean shouldBonemeal(BlockPos pos, BlockState state, Block block) {
        if (!bonemeal.get()) return false;
        if (mc.player.getEyePos().distanceTo(Vec3d.ofCenter(pos)) > bonemealRange.get()) return false;

        // Skip delay check for batch mode to allow fast collection
        if (!batchBonemeal.get() && System.currentTimeMillis() - lastBonemealTime < bonemealDelay.get() * 50) {
            return false;
        }

        boolean canBonemeal = false;

        // Check regular crops
        if (bonemealBlocks.get().contains(block)) {
            if (smartBonemeal.get()) {
                canBonemeal = !isMature(state, block);
                if (chatInfo.get() && canBonemeal) {
                    ChatUtils.info("Found immature crop " + block.getName().getString() + " for bonemeal");
                }
            } else {
                canBonemeal = true;
                if (chatInfo.get()) {
                    ChatUtils.info("Found crop " + block.getName().getString() + " for bonemeal (smart disabled)");
                }
            }
        }

        // Check glow berries
        if (enableGlowBerries.get() && bonemealGlowBerries.get() &&
            (block == Blocks.CAVE_VINES || block == Blocks.CAVE_VINES_PLANT)) {
            canBonemeal = true;
            if (chatInfo.get()) {
                ChatUtils.info("Found glow berries vine for bonemeal");
            }
        }

        return canBonemeal;
    }

    private void applyBatchBonemeal() {
        if (bonemealQueue.isEmpty()) return;

        // Check if we have bone meal in inventory (not just hotbar)
        FindItemResult bonemealItem = InvUtils.find(Items.BONE_MEAL);
        if (!bonemealItem.found()) {
            if (chatInfo.get()) {
                ChatUtils.warning("No bone meal found in inventory for batch application!");
            }
            return;
        }

        int applied = 0;
        int maxBatch;
        boolean isInsane = insaneSpeed.get(); // Independent of other modes

        // Insane mode - 64 per second (INDEPENDENT)
        if (isInsane) {
            maxBatch = insaneBatchSize.get(); // Use its own batch size
            if (chatInfo.get()) {
                ChatUtils.info("§4§lINSANE MODE: Starting independent 64 bonemeal/sec burst with " + bonemealQueue.size() + " candidates");
            }
        }
        // Ultra fast mode
        else if (ultraFastBonemeal.get()) {
            maxBatch = ultraBatchSize.get();
            if (chatInfo.get()) {
                ChatUtils.info("ULTRA FAST MODE: Starting batch bonemeal with " + bonemealQueue.size() + " candidates (Max: " + maxBatch + ")");
            }
        }
        // Batch bonemeal mode
        else if (batchBonemeal.get()) {
            maxBatch = batchSize.get();
            if (chatInfo.get()) {
                ChatUtils.info("Starting batch bonemeal application with " + bonemealQueue.size() + " candidates");
            }
        }
        // Single bonemeal mode
        else {
            maxBatch = 1;
            if (chatInfo.get()) {
                ChatUtils.info("Starting single bonemeal application");
            }
        }

        // Remove delays for insane mode and ultra fast mode
        if (!isInsane && !ultraFastBonemeal.get() && System.currentTimeMillis() - lastBonemealTime < bonemealDelay.get() * 50) {
            return;
        }

        // For INSANE mode - apply multiple passes per tick (INDEPENDENT)
        int passes = isInsane ? insaneTickRate.get() : (ultraFastBonemeal.get() ? 4 : 1);

        for (int pass = 0; pass < passes && !bonemealQueue.isEmpty(); pass++) {
            applied = 0;
            for (BlockPos pos : new ArrayList<>(bonemealQueue)) {
                if (applied >= maxBatch) break;

                try {
                    // Switch to bone meal slot only on first application
                    if (applied == 0 && pass == 0 && bonemealItem.slot() != mc.player.getInventory().selectedSlot) {
                        InvUtils.swap(bonemealItem.slot(), false);
                    }

                    // Skip rotation for ultra fast and insane mode to save time
                    if (!ultraFastBonemeal.get() && rotate.get() && !bypassLook.get()) {
                        Vec3d center = Vec3d.ofCenter(pos);
                        double yaw = Math.atan2(center.z - mc.player.getZ(), center.x - mc.player.getX()) * 180.0 / Math.PI - 90.0;
                        double pitch = -Math.atan2(center.y - mc.player.getEyeY(), Math.sqrt(Math.pow(center.x - mc.player.getX(), 2) + Math.pow(center.z - mc.player.getZ(), 2))) * 180.0 / Math.PI;
                        mc.player.setYaw((float) yaw);
                        mc.player.setPitch((float) pitch);
                    }

                    mc.interactionManager.interactBlock(mc.player, Hand.MAIN_HAND,
                        new BlockHitResult(Utils.vec3d(pos), Direction.UP, pos, false));

                    applied++;

                    // Ultra fast and insane mode: no individual logging or delays
                    if (!ultraFastBonemeal.get()) {
                        if (chatInfo.get()) {
                            ChatUtils.info("Applied bonemeal " + applied + "/" + maxBatch + " at " + pos.toString());
                        }

                        // Small delay between individual applications in normal batch
                        try {
                            Thread.sleep(2);
                        } catch (InterruptedException e) {
                            break;
                        }
                    }
                    // Ultra fast and insane mode: no delays at all

                } catch (Exception e) {
                    if (chatInfo.get() && !ultraFastBonemeal.get()) {
                        ChatUtils.error("Failed to apply bonemeal at " + pos.toString() + ": " + e.getMessage());
                    }
                }
            }

            // For insane mode, show pass progress
            if (isInsane && chatInfo.get()) {
                ChatUtils.info("§4§lINSANE MODE Pass " + (pass + 1) + "/" + passes + ": Applied " + applied + " bonemeals");
            }
        }

        lastBonemealTime = System.currentTimeMillis();
        if (chatInfo.get()) {
            if (isInsane) {
                ChatUtils.info("§4§lINSANE MODE COMPLETE: Applied up to " + (passes * maxBatch) + " bone meals across " + passes + " passes!");
            } else if (ultraFastBonemeal.get()) {
                ChatUtils.info("ULTRA FAST: Applied " + applied + " bone meals instantly!");
            } else {
                ChatUtils.info("Applied bone meal to " + applied + " crops simultaneously");
            }
        }
    }

    private void applyInsaneBonemeal() {
        if (bonemealQueue.isEmpty()) return;

        // Check if we have bone meal in inventory
        FindItemResult bonemealItem = InvUtils.find(Items.BONE_MEAL);
        if (!bonemealItem.found()) {
            if (chatInfo.get()) {
                ChatUtils.warning("§4[INSANE MODE] No bone meal found in inventory!");
            }
            return;
        }

        int applied = 0;
        int maxBatch = insaneBatchSize.get(); // Use insane batch size
        int passes = insaneTickRate.get(); // Use insane tick rate

        if (chatInfo.get()) {
            ChatUtils.info("§4§lINSANE MODE: Starting independent 64 bonemeal/sec with " + bonemealQueue.size() + " candidates");
            ChatUtils.info("§4[INSANE] Max batch: " + maxBatch + ", Passes: " + passes);
        }

        // INSANE MODE: Multiple passes per tick for maximum speed
        for (int pass = 0; pass < passes && !bonemealQueue.isEmpty(); pass++) {
            applied = 0;
            for (BlockPos pos : new ArrayList<>(bonemealQueue)) {
                if (applied >= maxBatch) break;

                try {
                    // Switch to bone meal slot only on first application
                    if (applied == 0 && pass == 0 && bonemealItem.slot() != mc.player.getInventory().selectedSlot) {
                        InvUtils.swap(bonemealItem.slot(), false);
                    }

                    // Skip rotation completely for maximum speed
                    mc.interactionManager.interactBlock(mc.player, Hand.MAIN_HAND,
                        new BlockHitResult(Utils.vec3d(pos), Direction.UP, pos, false));

                    applied++;

                    // No delays at all for insane mode

                } catch (Exception e) {
                    if (chatInfo.get()) {
                        ChatUtils.error("§4[INSANE] Failed to apply bonemeal at " + pos.toString() + ": " + e.getMessage());
                    }
                }
            }

            // Show pass progress
            if (chatInfo.get()) {
                ChatUtils.info("§4§lINSANE Pass " + (pass + 1) + "/" + passes + ": Applied " + applied + "/" + maxBatch + " bonemeals");
            }
        }

        lastBonemealTime = System.currentTimeMillis();
        if (chatInfo.get()) {
            ChatUtils.info("§4§lINSANE MODE COMPLETE: Applied up to " + (passes * maxBatch) + " bone meals across " + passes + " passes!");
        }
    }

    // Helper methods
    private boolean isWaterNearby(WorldView world, BlockPos pos) {
        for (BlockPos blockPos : BlockPos.iterate(pos.add(-4, 0, -4), pos.add(4, 1, 4))) {
            if (world.getFluidState(blockPos).isIn(FluidTags.WATER)) return true;
        }
        return false;
    }

    private boolean isMature(BlockState state, Block block) {
        if (block instanceof CropBlock cropBlock) {
            return cropBlock.isMature(state);
        } else if (block instanceof CocoaBlock) {
            return state.get(CocoaBlock.AGE) >= 2;
        } else if (block instanceof StemBlock) {
            return state.get(StemBlock.AGE) == StemBlock.MAX_AGE;
        } else if (block instanceof SweetBerryBushBlock) {
            return state.get(SweetBerryBushBlock.AGE) >= 2;
        } else if (block instanceof NetherWartBlock) {
            return state.get(NetherWartBlock.AGE) >= 3;
        } else if (block instanceof PitcherCropBlock) {
            return state.get(PitcherCropBlock.AGE) >= 4;
        }
        return true;
    }

    private Item getReplantItem(Block block) {
        if (block == Blocks.WHEAT) return Items.WHEAT_SEEDS;
        else if (block == Blocks.CARROTS) return Items.CARROT;
        else if (block == Blocks.POTATOES) return Items.POTATO;
        else if (block == Blocks.BEETROOTS) return Items.BEETROOT_SEEDS;
        else if (block == Blocks.NETHER_WART) return Items.NETHER_WART;
        else if (block == Blocks.PITCHER_CROP) return Items.PITCHER_POD;
        else if (block == Blocks.TORCHFLOWER) return Items.TORCHFLOWER_SEEDS;
        else if (block == Blocks.CAVE_VINES || block == Blocks.CAVE_VINES_PLANT) return Items.GLOW_BERRIES;
        return null;
    }

    // Filter methods
    private boolean bonemealFilter(Block block) {
        return block instanceof CropBlock ||
               block instanceof StemBlock ||
               block instanceof MushroomPlantBlock ||
               block instanceof AzaleaBlock ||
               block instanceof SaplingBlock ||
               block == Blocks.COCOA ||
               block == Blocks.SWEET_BERRY_BUSH ||
               block == Blocks.PITCHER_CROP ||
               block == Blocks.TORCHFLOWER ||
               block == Blocks.CAVE_VINES ||
               block == Blocks.CAVE_VINES_PLANT;
    }

    private boolean harvestFilter(Block block) {
        return block instanceof CropBlock ||
               block == Blocks.PUMPKIN ||
               block == Blocks.MELON ||
               block == Blocks.NETHER_WART ||
               block == Blocks.SWEET_BERRY_BUSH ||
               block == Blocks.COCOA ||
               block == Blocks.PITCHER_CROP ||
               block == Blocks.TORCHFLOWER ||
               block == Blocks.CAVE_VINES ||
               block == Blocks.CAVE_VINES_PLANT;
    }

    private boolean plantFilter(Item item) {
        return item == Items.WHEAT_SEEDS ||
               item == Items.CARROT ||
               item == Items.POTATO ||
               item == Items.BEETROOT_SEEDS ||
               item == Items.PUMPKIN_SEEDS ||
               item == Items.MELON_SEEDS ||
               item == Items.NETHER_WART ||
               item == Items.PITCHER_POD ||
               item == Items.TORCHFLOWER_SEEDS ||
               item == Items.GLOW_BERRIES;
    }

    // Default settings methods
    private List<Block> getDefaultHarvestBlocks() {
        return Arrays.asList(
            Blocks.WHEAT, Blocks.CARROTS, Blocks.POTATOES, Blocks.BEETROOTS,
            Blocks.PUMPKIN, Blocks.MELON, Blocks.NETHER_WART,
            Blocks.SWEET_BERRY_BUSH, Blocks.COCOA,
            Blocks.PITCHER_CROP, Blocks.TORCHFLOWER,
            Blocks.CAVE_VINES, Blocks.CAVE_VINES_PLANT
        );
    }

    private List<Item> getDefaultPlantItems() {
        return Arrays.asList(
            Items.WHEAT_SEEDS, Items.CARROT, Items.POTATO, Items.BEETROOT_SEEDS,
            Items.PUMPKIN_SEEDS, Items.MELON_SEEDS, Items.NETHER_WART,
            Items.PITCHER_POD, Items.TORCHFLOWER_SEEDS, Items.GLOW_BERRIES
        );
    }

    private List<Block> getDefaultBonemealBlocks() {
        return Arrays.asList(
            Blocks.WHEAT, Blocks.CARROTS, Blocks.POTATOES, Blocks.BEETROOTS,
            Blocks.PUMPKIN_STEM, Blocks.MELON_STEM, Blocks.SWEET_BERRY_BUSH,
            Blocks.COCOA, Blocks.PITCHER_CROP, Blocks.TORCHFLOWER,
            Blocks.CAVE_VINES, Blocks.CAVE_VINES_PLANT
        );
    }
}
