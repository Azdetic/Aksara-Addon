package com.example.addon.gui;

import com.example.addon.modules.AutoSell;
import meteordevelopment.meteorclient.gui.GuiTheme;
import meteordevelopment.meteorclient.gui.WindowScreen;
import meteordevelopment.meteorclient.gui.widgets.containers.WTable;
import meteordevelopment.meteorclient.gui.widgets.containers.WVerticalList;
import meteordevelopment.meteorclient.gui.widgets.input.WTextBox;
import meteordevelopment.meteorclient.gui.widgets.pressable.WButton;
import meteordevelopment.meteorclient.systems.modules.Modules;
import net.minecraft.item.Item;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

public class AutoSellConfigScreen extends WindowScreen {
    private final AutoSell autoSell;
    private WVerticalList itemList;
    private WTextBox searchBox;
    private final List<Item> allItems;
    private final List<Item> selectedItems;

    public AutoSellConfigScreen(GuiTheme theme) {
        super(theme, "Auto Sell Configuration");
        this.autoSell = Modules.get().get(AutoSell.class);
        this.allItems = new ArrayList<>();
        this.selectedItems = new ArrayList<>();

        // Load all items from registry
        loadAllItems();

        // Load currently selected items
        loadSelectedItems();
    }

    @Override
    public void initWidgets() {
        // Title
        add(theme.label("Auto Sell Item Configuration")).expandX();

        // Search box
        add(theme.label("Search Items:")).padTop(10);

        searchBox = add(theme.textBox("")).expandX().widget();
        searchBox.action = this::updateItemList;

        // Current selected items
        add(theme.label("Selected Items:")).padTop(15);

        WTable selectedTable = add(theme.table()).expandX().widget();
        updateSelectedItemsTable(selectedTable);

        // Available items list
        add(theme.label("Available Items (Click to add):")).padTop(15);

        itemList = add(theme.verticalList()).expandX().widget();
        updateItemList();

        // Control buttons
        WTable controlTable = add(theme.table()).expandX().widget();

        WButton clearAllButton = controlTable.add(theme.button("Clear All")).expandX().widget();
        clearAllButton.action = this::clearAllItems;

        WButton addCommonButton = controlTable.add(theme.button("Add Common")).expandX().widget();
        addCommonButton.action = this::addCommonItems;

        WButton saveButton = controlTable.add(theme.button("Save & Close")).expandX().widget();
        saveButton.action = this::saveAndClose;
    }

    private void loadAllItems() {
        allItems.clear();

        // Add all items from the registry
        for (Item item : Registries.ITEM) {
            if (item != Items.AIR) { // Skip air item
                allItems.add(item);
            }
        }

        // Sort alphabetically
        allItems.sort((item1, item2) -> {
            String name1 = getItemDisplayName(item1);
            String name2 = getItemDisplayName(item2);
            return name1.compareToIgnoreCase(name2);
        });
    }

    private void loadSelectedItems() {
        selectedItems.clear();
        if (autoSell != null) {
            selectedItems.addAll(autoSell.getWhitelist());
        }
    }

    private void updateSelectedItemsTable(WTable table) {
        table.clear();

        if (selectedItems.isEmpty()) {
            table.add(theme.label("No items selected")).expandX();
        } else {
            for (Item item : selectedItems) {
                String itemName = getItemDisplayName(item);

                table.add(theme.label(itemName)).expandX();

                WButton removeButton = table.add(theme.button("Remove")).widget();
                removeButton.action = () -> {
                    selectedItems.remove(item);
                    updateSelectedItemsTable(table);
                };

                table.row();
            }
        }
    }

    private void updateItemList() {
        itemList.clear();

        String searchText = searchBox.get().toLowerCase();
        List<Item> filteredItems = allItems.stream()
            .filter(item -> {
                String itemName = getItemDisplayName(item).toLowerCase();
                return itemName.contains(searchText);
            })
            .limit(50) // Limit to prevent lag
            .collect(Collectors.toList());

        for (Item item : filteredItems) {
            String itemName = getItemDisplayName(item);
            String modName = getModName(item);

            WButton itemButton = itemList.add(theme.button(itemName + " (" + modName + ")")).expandX().widget();
            itemButton.action = () -> addItem(item);
        }
    }

    private void addItem(Item item) {
        if (!selectedItems.contains(item)) {
            selectedItems.add(item);
            updateItemList(); // Refresh to show the item as selected

            // Refresh the selected items table
            initWidgets();
        }
    }

    private void clearAllItems() {
        selectedItems.clear();
        initWidgets();
    }

    private void addCommonItems() {
        // Add commonly sold items
        List<Item> commonItems = List.of(
            Items.COBBLESTONE,
            Items.DIRT,
            Items.SAND,
            Items.GRAVEL,
            Items.CACTUS,
            Items.SUGAR_CANE,
            Items.WHEAT,
            Items.CARROT,
            Items.POTATO,
            Items.COAL,
            Items.IRON_INGOT,
            Items.GOLD_INGOT,
            Items.DIAMOND,
            Items.EMERALD,
            Items.ROTTEN_FLESH,
            Items.BONE,
            Items.STRING,
            Items.GUNPOWDER
        );

        for (Item item : commonItems) {
            if (!selectedItems.contains(item)) {
                selectedItems.add(item);
            }
        }

        initWidgets();
    }

    private void saveAndClose() {
        if (autoSell != null) {
            autoSell.setWhitelist(selectedItems);
        }
        close();
    }

    private String getItemDisplayName(Item item) {
        Identifier id = Registries.ITEM.getId(item);
        String path = id.getPath();

        // Convert snake_case to Title Case
        String[] words = path.replace('_', ' ').split(" ");
        StringBuilder result = new StringBuilder();

        for (String word : words) {
            if (!word.isEmpty()) {
                result.append(Character.toUpperCase(word.charAt(0)))
                      .append(word.substring(1).toLowerCase())
                      .append(" ");
            }
        }

        return result.toString().trim();
    }

    private String getModName(Item item) {
        Identifier id = Registries.ITEM.getId(item);
        String namespace = id.getNamespace();

        switch (namespace) {
            case "minecraft" -> {
                return "Minecraft";
            }
            default -> {
                return namespace.substring(0, 1).toUpperCase() + namespace.substring(1);
            }
        }
    }
}
