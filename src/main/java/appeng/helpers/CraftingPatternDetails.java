/*
 * This file is part of Applied Energistics 2.
 * Copyright (c) 2013 - 2014, AlgorithmX2, All rights reserved.
 *
 * Applied Energistics 2 is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Applied Energistics 2 is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with Applied Energistics 2.  If not, see <http://www.gnu.org/licenses/lgpl>.
 */

package appeng.helpers;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;

import net.minecraft.inventory.CraftingInventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.recipe.CraftingRecipe;
import net.minecraft.recipe.Ingredient;
import net.minecraft.recipe.Recipe;
import net.minecraft.recipe.RecipeType;
import net.minecraft.recipe.ShapedRecipe;
import net.minecraft.util.Identifier;
import net.minecraft.util.collection.DefaultedList;
import net.minecraft.world.World;

import appeng.api.networking.crafting.ICraftingPatternDetails;
import appeng.api.storage.channels.IItemStorageChannel;
import appeng.api.storage.data.IAEItemStack;
import appeng.container.ContainerNull;
import appeng.core.Api;
import appeng.items.misc.EncodedPatternItem;
import appeng.mixins.IngredientAccessor;
import appeng.util.Platform;
import appeng.util.item.AEItemStack;

public class CraftingPatternDetails implements ICraftingPatternDetails, Comparable<CraftingPatternDetails> {

    private static final int CRAFTING_GRID_DIMENSION = 3;
    private static final int ALL_INPUT_LIMIT = CRAFTING_GRID_DIMENSION * CRAFTING_GRID_DIMENSION;
    private static final int CRAFTING_OUTPUT_LIMIT = 1;
    private static final int PROCESSING_OUTPUT_LIMIT = 3;

    private static final Comparator<IAEItemStack> COMPARE_BY_STACKSIZE = (left, right) -> Long
            .compare(right.getStackSize(), left.getStackSize());

    private final CraftingInventory crafting = new CraftingInventory(new ContainerNull(), 3, 3);
    private final CraftingInventory testFrame = new CraftingInventory(new ContainerNull(), 3, 3);
    private final ItemStack correctOutput;
    private final CraftingRecipe standardRecipe;
    private final List<IAEItemStack> inputs;
    private final List<IAEItemStack> outputs;
    private final IAEItemStack[] sparseInputs;
    private final IAEItemStack[] sparseOutputs;
    private final Map<Integer, List<IAEItemStack>> substituteInputs;
    private final boolean isCraftable;
    private final boolean canSubstitute;
    private final Set<TestLookup> failCache = new HashSet<>();
    private final Set<TestLookup> passCache = new HashSet<>();
    private final IAEItemStack pattern;
    private int priority = 0;

    public CraftingPatternDetails(final IAEItemStack is, final World w) {
        Preconditions.checkArgument(is.getItem() instanceof EncodedPatternItem,
                "itemStack is not a ICraftingPatternItem");

        final EncodedPatternItem templateItem = (EncodedPatternItem) is.getItem();
        final ItemStack itemStack = is.createItemStack();

        final List<IAEItemStack> ingredients = templateItem.getIngredients(itemStack);
        final List<IAEItemStack> products = templateItem.getProducts(itemStack);
        final Identifier recipeId = templateItem.getCraftingRecipeId(itemStack);

        this.pattern = is.copy();
        this.isCraftable = recipeId != null;
        this.canSubstitute = templateItem.allowsSubstitution(itemStack);

        final List<IAEItemStack> in = new ArrayList<>();
        final List<IAEItemStack> out = new ArrayList<>();

        for (int x = 0; x < ALL_INPUT_LIMIT; x++) {
            final IAEItemStack ais = ingredients.get(x);
            final ItemStack gs = ais != null ? ais.createItemStack() : ItemStack.EMPTY;

            this.crafting.setStack(x, gs);

            if (!gs.isEmpty() && (!this.isCraftable) || !gs.hasTag()) {
                this.markItemAs(x, gs, TestStatus.ACCEPT);
            }

            in.add(ais != null ? ais.copy() : null);
            this.testFrame.setStack(x, gs);
        }

        if (this.isCraftable) {
            Recipe<?> recipe = w.getRecipeManager().getAllOfType(RecipeType.CRAFTING).get(recipeId);

            if (recipe == null || recipe.getType() != RecipeType.CRAFTING) {
                throw new IllegalStateException("recipe id is not a crafting recipe");
            }

            this.standardRecipe = (CraftingRecipe) recipe;
            this.correctOutput = this.standardRecipe.craft(this.crafting);

            out.add(Api.instance().storage().getStorageChannel(IItemStorageChannel.class)
                    .createStack(this.correctOutput));
        } else {
            this.standardRecipe = null;
            this.correctOutput = ItemStack.EMPTY;

            for (int x = 0; x < PROCESSING_OUTPUT_LIMIT; x++) {
                final IAEItemStack ais = products.get(x);

                out.add(ais != null ? ais.copy() : null);
            }
        }

        final int outputLength = this.isCraftable ? CRAFTING_OUTPUT_LIMIT : PROCESSING_OUTPUT_LIMIT;
        this.sparseInputs = in.toArray(new IAEItemStack[ALL_INPUT_LIMIT]);
        this.sparseOutputs = out.toArray(new IAEItemStack[outputLength]);
        this.substituteInputs = new HashMap<>(ALL_INPUT_LIMIT);

        this.inputs = this.condenseStacks(in);
        this.outputs = this.condenseStacks(out);
    }

    private void markItemAs(final int slotIndex, final ItemStack i, final TestStatus b) {
        if (b == TestStatus.TEST || i.hasTag()) {
            return;
        }

        (b == TestStatus.ACCEPT ? this.passCache : this.failCache).add(new TestLookup(slotIndex, i));
    }

    @Override
    public ItemStack getPattern() {
        return this.pattern.createItemStack();
    }

    @Override
    public synchronized boolean isValidItemForSlot(final int slotIndex, final ItemStack i, final World w) {
        if (!this.isCraftable) {
            throw new IllegalStateException("Only crafting recipes supported.");
        }

        final TestStatus result = this.getStatus(slotIndex, i);

        switch (result) {
            case ACCEPT:
                return true;
            case DECLINE:
                return false;
            case TEST:
            default:
                break;
        }

        for (int x = 0; x < this.crafting.size(); x++) {
            this.testFrame.setStack(x, this.crafting.getStack(x));
        }

        this.testFrame.setStack(slotIndex, i);

        // If we cannot substitute, the items must match exactly
        if (!canSubstitute && slotIndex < sparseInputs.length) {
            if (!sparseInputs[slotIndex].isSameType(i)) {
                this.markItemAs(slotIndex, i, TestStatus.DECLINE);
                return false;
            }
        }

        if (this.standardRecipe.matches(this.testFrame, w)) {
            final ItemStack testOutput = this.standardRecipe.craft(this.testFrame);

            if (Platform.itemComparisons().isSameItem(this.correctOutput, testOutput)) {
                this.testFrame.setStack(slotIndex, this.crafting.getStack(slotIndex));
                this.markItemAs(slotIndex, i, TestStatus.ACCEPT);
                return true;
            }
        }

        this.markItemAs(slotIndex, i, TestStatus.DECLINE);
        return false;
    }

    @Override
    public boolean isCraftable() {
        return this.isCraftable;
    }

    @Override
    public IAEItemStack[] getSparseInputs() {
        return this.sparseInputs;
    }

    @Override
    public List<IAEItemStack> getInputs() {
        return this.inputs;
    }

    @Override
    public List<IAEItemStack> getOutputs() {
        return this.outputs;
    }

    @Override
    public IAEItemStack[] getSparseOutputs() {
        return this.sparseOutputs;
    }

    @Override
    public boolean canSubstitute() {
        return this.canSubstitute;
    }

    public List<IAEItemStack> getSubstituteInputs(int slot) {
        if (this.sparseInputs[slot] == null) {
            return Collections.emptyList();
        }

        return this.substituteInputs.computeIfAbsent(slot, value -> {
            ItemStack[] matchingStacks = ((IngredientAccessor) (Object) getRecipeIngredient(slot)).getMatchingStacks();
            List<IAEItemStack> itemList = new ArrayList<>(matchingStacks.length + 1);
            for (ItemStack matchingStack : matchingStacks) {
                itemList.add(AEItemStack.fromItemStack(matchingStack));
            }

            // Ensure that the specific item put in by the user is at the beginning,
            // so that it takes precedence over substitutions
            itemList.add(0, this.sparseInputs[slot]);
            return itemList;
        });
    }

    /**
     * Gets the {@link Ingredient} from the actual used recipe for a given slot-index into {@link #getSparseInputs()}.
     * <p/>
     * Conversion is needed for two reasons: our sparse ingredients are always organized in a 3x3 grid, while Vanilla's
     * ingredient list will be condensed to the actual recipe's grid size. In addition, in our 3x3 grid, the user can
     * shift the actual recipe input to the right and down.
     */
    private Ingredient getRecipeIngredient(int slot) {

        if (standardRecipe instanceof ShapedRecipe) {
            ShapedRecipe shapedRecipe = (ShapedRecipe) standardRecipe;

            return getShapedRecipeIngredient(slot, shapedRecipe.getWidth());
        } else {
            return getShapelessRecipeIngredient(slot);
        }
    }

    private Ingredient getShapedRecipeIngredient(int slot, int recipeWidth) {
        // Compute the offset of the user's input vs. crafting grid origin
        // Which is >0 if they have empty rows above or to the left of their input
        int leftOffset = 0, topOffset = 0;
        for (int i = 0; i < sparseInputs.length; i++) {
            if (sparseInputs[i] != null) {
                // Found the first non-null input
                leftOffset = i % CRAFTING_GRID_DIMENSION;
                topOffset = i / CRAFTING_GRID_DIMENSION;
                break;
            }
        }

        // Compute the x,y of the slot, as-if the recipe was anchored to 0,0
        int slotX = (slot % CRAFTING_GRID_DIMENSION) - leftOffset;
        int slotY = (slot / CRAFTING_GRID_DIMENSION) - topOffset;

        // Compute the index into the recipe's ingredient list now
        int ingredientIndex = slotY * recipeWidth + slotX;

        DefaultedList<Ingredient> ingredients = standardRecipe.getPreviewInputs();

        if (ingredientIndex < 0 || ingredientIndex > ingredients.size()) {
            return Ingredient.EMPTY;
        }

        return ingredients.get(ingredientIndex);
    }

    private Ingredient getShapelessRecipeIngredient(int slot) {
        // We map the list of *filled* sparse inputs to the shapeless (ergo unordered)
        // ingredients. While these do not actually correspond to each other,
        // since both lists have the same length, the mapping is at least stable.
        int ingredientIndex = 0;
        for (int i = 0; i < slot; i++) {
            if (sparseInputs[i] != null) {
                ingredientIndex++;
            }
        }

        DefaultedList<Ingredient> ingredients = standardRecipe.getPreviewInputs();
        if (ingredientIndex < ingredients.size()) {
            return ingredients.get(ingredientIndex);
        }

        return Ingredient.EMPTY;
    }

    @Override
    public ItemStack getOutput(final CraftingInventory craftingInv, final World w) {
        if (!this.isCraftable) {
            throw new IllegalStateException("Only crafting recipes supported.");
        }

        for (int x = 0; x < craftingInv.size(); x++) {
            if (!this.isValidItemForSlot(x, craftingInv.getStack(x), w)) {
                return ItemStack.EMPTY;
            }
        }

        if (this.sparseOutputs != null && this.sparseOutputs.length > 0) {
            return this.sparseOutputs[0].createItemStack();
        }

        return ItemStack.EMPTY;
    }

    private TestStatus getStatus(final int slotIndex, final ItemStack i) {
        if (this.crafting.getStack(slotIndex).isEmpty()) {
            return i.isEmpty() ? TestStatus.ACCEPT : TestStatus.DECLINE;
        }

        if (i.isEmpty()) {
            return TestStatus.DECLINE;
        }

        if (i.hasTag()) {
            return TestStatus.TEST;
        }

        if (this.passCache.contains(new TestLookup(slotIndex, i))) {
            return TestStatus.ACCEPT;
        }

        if (this.failCache.contains(new TestLookup(slotIndex, i))) {
            return TestStatus.DECLINE;
        }

        return TestStatus.TEST;
    }

    @Override
    public int getPriority() {
        return this.priority;
    }

    @Override
    public void setPriority(final int priority) {
        this.priority = priority;
    }

    @Override
    public int compareTo(final CraftingPatternDetails o) {
        return Integer.compare(o.priority, this.priority);
    }

    @Override
    public int hashCode() {
        return this.pattern.hashCode();
    }

    @Override
    public boolean equals(final Object obj) {
        if (obj == null) {
            return false;
        }
        if (this.getClass() != obj.getClass()) {
            return false;
        }

        final CraftingPatternDetails other = (CraftingPatternDetails) obj;

        if (this.pattern != null && other.pattern != null) {
            return this.pattern.equals(other.pattern);
        }
        return false;
    }

    /**
     * Merges all equal entries into a single one while adding their total stack sizes.
     *
     * @param collection the collection to condense
     * @return a non empty list of condensed stacks.
     * @throws IllegalStateException if the result would be empty.
     */
    private List<IAEItemStack> condenseStacks(Collection<IAEItemStack> collection) {
        final List<IAEItemStack> merged = collection.stream().filter(Objects::nonNull)
                .collect(Collectors.toMap(Function.identity(), IAEItemStack::copy,
                        (left, right) -> left.setStackSize(left.getStackSize() + right.getStackSize())))
                .values().stream().sorted(COMPARE_BY_STACKSIZE).collect(ImmutableList.toImmutableList());

        if (merged.isEmpty()) {
            throw new IllegalStateException("No pattern here!");
        }

        return merged;
    }

    private enum TestStatus {
        ACCEPT, DECLINE, TEST
    }

    private static final class TestLookup {

        private final int slot;
        private final int ref;
        private final int hash;

        public TestLookup(final int slot, final ItemStack i) {
            this(slot, i.getItem(), i.getDamage());
        }

        public TestLookup(final int slot, final Item item, final int dmg) {
            this.slot = slot;
            this.ref = (dmg << Platform.DEF_OFFSET) | (Item.getRawId(item) & 0xffff);
            final int offset = 3 * slot;
            this.hash = (this.ref << offset) | (this.ref >> (offset + 32));
        }

        @Override
        public int hashCode() {
            return this.hash;
        }

        @Override
        public boolean equals(final Object obj) {
            final boolean equality;

            if (obj instanceof TestLookup) {
                final TestLookup b = (TestLookup) obj;

                equality = b.slot == this.slot && b.ref == this.ref;
            } else {
                equality = false;
            }

            return equality;
        }
    }
}
