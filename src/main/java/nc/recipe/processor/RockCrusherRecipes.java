package nc.recipe.processor;

import com.google.common.collect.Lists;

import nc.recipe.ProcessorRecipeHandler;

public class RockCrusherRecipes extends ProcessorRecipeHandler {
	
	public RockCrusherRecipes() {
		super("rock_crusher", 1, 0, 3, 0);
	}

	@Override
	public void addRecipes() {
		addRecipe(oreStackList(Lists.newArrayList("stoneGranite", "stoneGranitePolished"), 1), chanceOreStack("dustRhodochrosite", 2, 37), chanceOreStack("dustSulfur", 2, 32), chanceOreStack("dustVilliaumite", 1, 25), 1D, 1D);
		addRecipe(oreStackList(Lists.newArrayList("stoneDiorite", "stoneDioritePolished"), 1), chanceOreStack("dustFluorite", 2, 33), chanceOreStack("dustCarobbiite", 2, 25), chanceOreStack("dustZirconium", 1, 40), 1D, 1D);
		addRecipe(oreStackList(Lists.newArrayList("stoneAndesite", "stoneAndesitePolished"), 1), chanceOreStack("dustBeryllium", 2, 50), chanceOreStack("dustFluorite", 1, 30), chanceOreStack("dustArsenic", 1, 28), 1D, 1D);
	}
}
