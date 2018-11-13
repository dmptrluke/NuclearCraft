package nc.tile.generator;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import li.cil.oc.api.machine.Arguments;
import li.cil.oc.api.machine.Callback;
import li.cil.oc.api.machine.Context;
import li.cil.oc.api.network.SimpleComponent;
import nc.Global;
import nc.ModCheck;
import nc.block.tile.generator.BlockFissionController;
import nc.block.tile.generator.BlockFissionControllerNewFixed;
import nc.config.NCConfig;
import nc.enumm.MetaEnums.CoolerType;
import nc.init.NCBlocks;
import nc.recipe.NCRecipes;
import nc.tile.fluid.TileActiveCooler;
import nc.tile.internal.fluid.Tank;
import nc.util.BlockFinder;
import nc.util.BlockPosHelper;
import nc.util.EnergyHelper;
import nc.util.Lang;
import nc.util.NCMath;
import nc.util.RadiationHelper;
import nc.util.RegistryHelper;
import net.minecraft.block.Block;
import net.minecraft.block.state.IBlockState;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.init.Blocks;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraftforge.fml.common.Optional;

@Optional.Interface(iface = "li.cil.oc.api.network.SimpleComponent", modid = "opencomputers")
public class TileFissionController extends TileItemGenerator implements SimpleComponent {
	
	private Random rand = new Random();
	
	public double baseProcessHeat = 0;
	public double heat, cooling, heatChange, efficiency, heatMult;
	public int cells;
	public int minX, minY, minZ;
	public int maxX, maxY, maxZ;
	public int lengthX, lengthY, lengthZ = 3;
	public int complete, ready;
	public int ports = 1, currentEnergyStored = 0, energyChange = 0;
	
	public String problem = CASING_INCOMPLETE;
	public String problemPos = "";
	public int problemPosX = 0, problemPosY = 0, problemPosZ = 0;
	
	public static final int BASE_CAPACITY = 64000, BASE_MAX_HEAT = 25000;
	
	public boolean computerActivated = false;
	
	public boolean readLayout = false;
	public Object[] layout;
	
	public static final String NO_FUEL = Lang.localise("gui.container.fission_controller.no_fuel");
	public static final String GENERIC_FUEL = Lang.localise("gui.container.fission_controller.generic_fuel");
	
	public static final String CASING_INCOMPLETE = Lang.localise("gui.container.fission_controller.casing_incomplete");
	public static final String INVALID_STRUCTURE = Lang.localise("gui.container.fission_controller.invalid_structure");
	public static final String CASING_IN_INTERIOR_AT = Lang.localise("gui.container.fission_controller.casing_in_interior");
	public static final String EXTRA_CONTROLLER_AT = Lang.localise("gui.container.fission_controller.multiple_controllers");
	public static final String CASING_INCOMPLETE_AT = Lang.localise("gui.container.fission_controller.casing_incomplete_at");
	public static final String POS = Lang.localise("gui.container.fission_controller.pos");
	public static final String NO_PROBLEM = Lang.localise("gui.container.fission_controller.no_problem");
	
	private BlockFinder finder;
	
	private boolean newRules;
	
	public static class New extends TileFissionController {
		
		public New() {
			super(true);
		}
	}
	
	public static class Old extends TileFissionController {
		
		public Old() {
			super(false);
		}
	}

	private TileFissionController(boolean newRules) {
		super("fission_controller", 1, 1, 0, BASE_CAPACITY, NCRecipes.Type.FISSION);
		this.newRules = newRules;
	}
	
	@Override
	public int getGuiID() {
		return 100;
	}
	
	@Override
	public void onAdded() {
		finder = new BlockFinder(pos, world, getBlockMetadata() & 7);
		super.onAdded();
		tickCount = -1;
	}
	
	@Override
	public void updateProcessor() {
		if (fixControllerBlock()) return;
		recipe = getRecipeHandler().getRecipeFromInputs(getItemInputs(hasConsumed), new ArrayList<Tank>());
		canProcessInputs = canProcessInputs();
		boolean wasProcessing = isProcessing;
		isProcessing = isProcessing();
		boolean shouldUpdate = false;
		tickTile();
		checkStructure();
		if(!world.isRemote) {
			energyChange = getEnergyStored() - currentEnergyStored;
			if (newRules) newRun(); else run();
			if (overheat()) return;
			if (isProcessing) process();
			else getRadiationSource().setRadiationLevel(0D);
			consumeInputs();
			if (wasProcessing != isProcessing) {
				shouldUpdate = true;
				updateBlockType();
			}
			pushEnergy();
			currentEnergyStored = getEnergyStored();
			if (findAdjacentComparator() && shouldTileCheck()) shouldUpdate = true;
		}
		if (shouldUpdate) markDirty();
	}
	
	private boolean fixControllerBlock() {
		if (!newRules) return false;
		IBlockState state = world.getBlockState(pos);
		if (state.getBlock() instanceof BlockFissionController) {
			BlockFissionController controller = (BlockFissionController) state.getBlock();
			if (!controller.isNew) {
				newRules = false;
				return false;
			}
			int meta = NCBlocks.fission_controller_idle.getMetaFromState(state);
			if (controller.isActive) meta |= 8;
			
			world.setBlockState(pos, NCBlocks.fission_controller_new_fixed.getStateFromMeta(meta));
			return true;
		}
		return false;
	}
	
	@Override
	public void tickTile() {
		tickCount++; tickCount %= 8*NCConfig.machine_update_rate;
	}
	
	public boolean findAdjacentComparator() {
		return finder.adjacent(pos, 1, Blocks.UNPOWERED_COMPARATOR, Blocks.POWERED_COMPARATOR);
	}
	
	public boolean overheat() {
		if (heat >= getMaxHeat() && NCConfig.fission_overheat) {
			meltdown();
			return true;
		}
		return false;
	}
	
	public void meltdown() {
		BlockPos middle = finder.position((minX + maxX)/2, (minY + maxY)/2, (minZ + maxZ)/2);
		
		RadiationHelper.addToChunkRadiation(world.getChunkFromBlockCoords(middle), baseProcessRadiation*cells*NCConfig.fission_fuel_use);
		
		Block corium = RegistryHelper.getBlock(Global.MOD_ID + ":fluid_corium");
		world.removeTileEntity(pos);
		world.setBlockState(pos, corium.getDefaultState());
		
		if (NCConfig.fission_explosions) {
			world.createExplosion(null, middle.getX(), middle.getY(), middle.getZ(), lengthX + lengthY + lengthZ, true);
		}
		
		for (int i = minX; i <= maxX; i++) {
			for (int j = minY; j <= maxY; j++) {
				for (int k = minZ; k <= maxZ; k++) {
					if (rand.nextDouble() < 0.18D) world.setBlockState(finder.position(i, j, k), corium.getDefaultState());
				}
			}
		}
	}
	
	@Override
	public boolean isProcessing() {
		return readyToProcess() && isActivated();
	}
	
	private boolean isActivated() {
		return isRedstonePowered() || computerActivated;
	}
	
	@Override
	public boolean readyToProcess() {
		return canProcessInputs && hasConsumed && complete == 1;
	}
	
	@Override
	public void updateBlockType() {
		super.updateBlockType();
		tickCount = -1;
	}
	
	@Override
	public void setState(boolean isActive) {
		super.setState(isActive);
		if (getBlockType() instanceof BlockFissionControllerNewFixed) ((BlockFissionControllerNewFixed)getBlockType()).setActiveState(world.getBlockState(pos), world, pos, isActive);
	}
	
	// IC2 Tiers
	
	@Override
	public int getEUSourceTier() {
		return EnergyHelper.getEUTier(processPower);
	}
	
	@Override
	public int getEUSinkTier() {
		return 4;
	}
	
	// Generating
	
	@Override
	public void setRecipeStats() {
		if (recipe == null) {
			setDefaultRecipeStats();
			return;
		}
		
		baseProcessTime = recipe.getFissionFuelTime();
		baseProcessPower = recipe.getFissionFuelPower();
		baseProcessHeat = recipe.getFissionFuelHeat();
		baseProcessRadiation = recipe.getFissionFuelRadiation();
	}
	
	public void setDefaultRecipeStats() {
		baseProcessTime = defaultProcessTime;
		baseProcessPower = defaultProcessPower;
		baseProcessHeat = 0D;
		baseProcessRadiation = 0D;
	}
	
	public String getFuelName() {
		if (recipe == null) return NO_FUEL;
		if (recipe.extras().size() < 4) return GENERIC_FUEL;
		Object fuelNameInfo = recipe.extras().get(3);
		if (fuelNameInfo instanceof String) return ((String) fuelNameInfo).replace('_', '-');
		else return GENERIC_FUEL;
	}
	
	public int getLengthX() {
		return lengthX - 2;
	}
	
	public int getLengthY() {
		return lengthY - 2;
	}
	
	public int getLengthZ() {
		return lengthZ - 2;
	}
	
	public int getMaxHeat() {
		if (NCMath.atIntLimit(getLengthX()*getLengthY()*getLengthZ(), BASE_MAX_HEAT)) return Integer.MAX_VALUE;
		if (getLengthX() <= 0 || getLengthY() <= 0 || getLengthZ() <= 0) return BASE_MAX_HEAT;
		return BASE_MAX_HEAT*getLengthX()*getLengthY()*getLengthZ();
	}
	
	// Finding Blocks
	
	private boolean findCell(BlockPos pos) {
		return finder.find(pos, NCBlocks.cell_block);
	}
	
	private boolean findCell(int x, int y, int z) {
		return findCell(finder.position(x, y, z));
	}
	
	private boolean findModerator(BlockPos pos) {
		return finder.find(pos, "blockGraphite", "blockBeryllium");
	}
	
	private boolean findModerator(int x, int y, int z) {
		return findModerator(finder.position(x, y, z));
	}
	
	private boolean findCellOnSide(int x, int y, int z, EnumFacing side) {
		return findCell(finder.position(x, y, z).offset(side));
	}
	
	private boolean findModeratorThenCellOnSide(int x, int y, int z, EnumFacing side) {
		return findModerator(finder.position(x, y, z).offset(side)) && findCell(finder.position(x, y, z).offset(side, 2));
	}
	
	private boolean newFindModeratorThenCellOnSide(int x, int y, int z, EnumFacing side) {
		for (int i = 1; i <= NCConfig.fission_neutron_reach; i++) {
			for (int j = 1; j <= i; j++) if (!findModerator(finder.position(x, y, z).offset(side, j))) return false;
			if (findCell(finder.position(x, y, z).offset(side, i + 1))) return true;
		}
		return false;
	}
	
	private int moderatorAdjacentCount(BlockPos pos) {
		int count = 0;
		BlockPosHelper helper = new BlockPosHelper(pos);
		for (BlockPos blockPos : helper.adjacents()) if (findModerator(blockPos)) count++;
		return count;
	}
	
	private int moderatorAdjacentCount(int x, int y, int z) {
		return moderatorAdjacentCount(finder.position(x, y, z));
	}
	
	private int activeModeratorAdjacentCount(BlockPos pos) {
		int count = 0;
		BlockPosHelper helper = new BlockPosHelper(pos);
		for (BlockPos blockPos : helper.adjacents()) {
			if (findModerator(blockPos) && cellAdjacent(blockPos)) count++;
		}
		return count;
	}
	
	private int activeModeratorAdjacentCount(int x, int y, int z) {
		return activeModeratorAdjacentCount(finder.position(x, y, z));
	}
	
	private boolean cellAdjacent(BlockPos pos) {
		return finder.adjacent(pos, 1, NCBlocks.cell_block);
	}
	
	private boolean cellAdjacent(int x, int y, int z) {
		return finder.adjacent(x, y, z, 1, NCBlocks.cell_block);
	}
	
	private int cellAdjacentCount(BlockPos pos) {
		return finder.adjacentCount(pos, 1, NCBlocks.cell_block);
	}
	
	private int cellAdjacentCount(int x, int y, int z) {
		return cellAdjacentCount(finder.position(x, y, z));
	}
	
	private boolean activeModeratorAdjacent(BlockPos pos) {
		BlockPosHelper helper = new BlockPosHelper(pos);
		for (BlockPos blockPos : helper.adjacents()) {
			if (findModerator(blockPos) && cellAdjacent(blockPos)) return true;
		}
		return false;
	}
	
	private boolean activeModeratorAdjacent(int x, int y, int z) {
		return activeModeratorAdjacent(finder.position(x, y, z));
	}
	
	private boolean findCooler(BlockPos pos, int meta) {
		return finder.find(pos, NCBlocks.cooler.getStateFromMeta(meta));
	}
	
	private boolean findCooler(int x, int y, int z, int meta) {
		return findCooler(finder.position(x, y, z), meta);
	}
	
	private boolean activeCoolerAdjacent(BlockPos pos, int meta) {
		BlockPosHelper helper = new BlockPosHelper(pos);
		for (BlockPos blockPos : helper.adjacents()) {
			if (findCooler(blockPos, meta)) if (coolerRequirements(blockPos, meta)) return true;
		}
		return false;
	}
	
	private boolean activeCoolerAdjacent(int x, int y, int z, int meta) {
		return activeCoolerAdjacent(finder.position(x, y, z), meta);
	}
	
	private int activeCoolerAdjacentCount(BlockPos pos, int meta) {
		int count = 0;
		BlockPosHelper helper = new BlockPosHelper(pos);
		for (BlockPos blockPos : helper.adjacents()) {
			if (findCooler(blockPos, meta)) if (coolerRequirements(blockPos, meta)) count++;
		}
		return count;
	}
	
	private int activeCoolerAdjacentCount(int x, int y, int z, int meta) {
		return activeCoolerAdjacentCount(finder.position(x, y, z), meta);
	}
	
	private boolean activeCoolerConfiguration(BlockPos pos, int meta, List<BlockPos[]> posArrays) {
		for (BlockPos[] posArray : posArrays) {
			if (finder.configuration(posArray, NCBlocks.cooler.getStateFromMeta(meta))) {
				for (BlockPos blockPos : posArray) if (!coolerRequirements(blockPos, meta)) return false;
				return true;
			}
		}
		return false;
	}
	
	private boolean activeCoolerHorizontal(BlockPos pos, int meta) {
		return activeCoolerConfiguration(pos, meta, new BlockPosHelper(pos).horizontalsList());
	}
	
	private boolean activeCoolerAxial(BlockPos pos, int meta) {
		return activeCoolerConfiguration(pos, meta, new BlockPosHelper(pos).axialsList());
	}
	
	private boolean coolerRequirements(BlockPos pos, int meta) {
		switch (meta) {
		case 1: // Water
			return !NCConfig.fission_water_cooler_requirement || (newRules ? cellAdjacent(pos) || activeModeratorAdjacent(pos) : casingAllAdjacent(pos));
		case 2: // Redstone
			return cellAdjacent(pos);
		case 3: // Quartz
			return activeModeratorAdjacent(pos);
		case 4: // Gold
			return activeCoolerAdjacent(pos, 1) && activeCoolerAdjacent(pos, 2);
		case 5: // Glowstone
			return activeModeratorAdjacentCount(pos) >= 2;
		case 6: // Lapis
			return cellAdjacent(pos) && casingAllAdjacent(pos);
		case 7: // Diamond
			return newRules ? activeCoolerAdjacent(pos, 1) && activeCoolerAdjacent(pos, 3) : activeCoolerHorizontal(pos, 1) && casingAllAdjacent(pos);
		case 8: // Liquid Helium
			return newRules ? activeCoolerAdjacentCount(pos, 2) == 1 && casingAllAdjacent(pos) : activeCoolerAdjacent(pos, 3) && casingAllAdjacent(pos);
		case 9: // Enderium
			return casingAllOneVertex(pos);
		case 10: // Cryotheum
			return cellAdjacentCount(pos) >= 2;
		case 11: // Iron
			return activeCoolerAdjacent(pos, 4);
		case 12: // Emerald
			return activeModeratorAdjacent(pos) && cellAdjacent(pos);
		case 13: // Copper
			return activeCoolerAdjacent(pos, 5);
		case 14: // Tin
			return activeCoolerAxial(pos, 6);
		case 15: // Magnesium
			return newRules ? casingAllAdjacent(pos) && activeModeratorAdjacent(pos) : casingAllAdjacent(pos) && activeCoolerAdjacent(pos, 8);
		default:
			return false;
		}
	}
	
	private boolean coolerRequirements(int x, int y, int z, int meta) {
		return coolerRequirements(finder.position(x, y, z), meta);
	}
	
	private boolean findCasing(BlockPos pos) {
		return finder.find(pos, NCBlocks.fission_block.getStateFromMeta(0), NCBlocks.reactor_casing_transparent, NCBlocks.fission_port, NCBlocks.buffer, NCBlocks.reactor_door, NCBlocks.reactor_trapdoor);
	}
	
	private boolean findCasing(int x, int y, int z) {
		return findCasing(finder.position(x, y, z));
	}
	
	private boolean findController(BlockPos pos) {
		return finder.find(pos, NCBlocks.fission_controller_new_fixed, NCBlocks.fission_controller_idle, NCBlocks.fission_controller_active, NCBlocks.fission_controller_new_idle, NCBlocks.fission_controller_new_active);
	}
	
	private boolean findController(int x, int y, int z) {
		return findController(finder.position(x, y, z));
	}
	
	private boolean findCasingAll(BlockPos pos) {
		return findCasing(pos) || findController(pos);
	}
	
	private boolean findCasingAll(int x, int y, int z) {
		return findCasingAll(finder.position(x, y, z));
	}
	
	private boolean findPort(BlockPos pos) {
		return finder.find(pos, NCBlocks.fission_port);
	}
	
	private boolean findPort(int x, int y, int z) {
		return findPort(finder.position(x, y, z));
	}
	
	private boolean casingAllAdjacent(BlockPos pos) {
		BlockPosHelper posHelper = new BlockPosHelper(pos);
		for (BlockPos blockPos : posHelper.adjacents()) if (findCasingAll(blockPos)) return true;
		return false;
	}
	
	private boolean casingAllOneVertex(BlockPos pos) {
		int count = 0;
		BlockPosHelper posHelper = new BlockPosHelper(pos);
		posList: for (BlockPos[] vertexPosList : posHelper.vertexList()) {
			for (BlockPos blockPos : vertexPosList) if (!findCasingAll(blockPos)) continue posList;
			count++;
			if (count > 1) return false;
		}
		return count == 1;
	}
	
	// Finding Structure
	
	public boolean checkStructure() {
		if (shouldTileCheck()) {
			int maxLength = NCConfig.fission_max_size + 1;
			boolean validStructure = false;
			int maxZCheck = 0;
			int minZ = 0, minX = 0, minY = 0;
			int maxZ = 0, maxX = 0, maxY = 0;
			int portCount = 0;
			for (int z = 0; z <= maxLength; z++) {
				if ((findCasingAll(0, 1, 0) || findCasingAll(0, -1, 0)) || ((findCasingAll(1, 1, 0) || findCasingAll(1, -1, 0)) && findCasingAll(1, 0, 0)) || ((findCasingAll(1, 1, 0) && !findCasingAll(1, -1, 0)) && !findCasingAll(1, 0, 0)) || ((!findCasingAll(1, 1, 0) && findCasingAll(1, -1, 0)) && !findCasingAll(1, 0, 0))) {
					if (!findCasingAll(0, 1, -z) && !findCasingAll(0, -1, -z) && (findCasingAll(0, 0, -z + 1) || findCasingAll(0, 1, -z + 1) || findCasingAll(0, -1, -z + 1))) {
						maxZCheck = maxLength - z;
						minZ = -z;
						validStructure = true;
						break;
					}
				} else if (!findCasing(0, 0, -z) && !findCasing(1, 1, -z) && !findCasing(1, -1, -z) && findCasingAll(0, 0, -z + 1) && findCasing(1, 0, -z) && findCasing(1, 1, -z + 1) && findCasing(1, -1, -z + 1)) {
					maxZCheck = maxLength - z;
					minZ = -z;
					validStructure = true;
					break;
				}
			}
			if (!validStructure) {
				complete = 0;
				problem = CASING_INCOMPLETE;
				problemPos = "";
				return false;
			}
			validStructure = false;
			for (int y = 0; y <= maxLength; y++) {
				if (!findCasing(minX, -y + 1, minZ) && !findCasing(minX + 1, -y, minZ) && !findCasing(minX, -y, minZ + 1) && findCasingAll(minX + 1, -y, minZ + 1) && findCasingAll(minX, -y + 1, minZ + 1) && findCasingAll(minX + 1, -y + 1, minZ)) {
					minY = -y;
					validStructure = true;
					break;
				}
			}
			if (!validStructure) {
				complete = 0;
				problem = CASING_INCOMPLETE;
				problemPos = "";
				return false;
			}
			validStructure = false;
			for (int z = 0; z <= maxZCheck; z++) {
				if (!findCasing(minX, minY + 1, z) && !findCasing(minX + 1, minY, z) && !findCasing(minX, minY, z - 1) && findCasingAll(minX + 1, minY, z - 1) && findCasingAll(minX, minY + 1, z - 1) && findCasingAll(minX + 1, minY + 1, z)) {
					maxZ = z;
					validStructure = true;
					break;
				}
			}
			if (!validStructure) {
				complete = 0;
				problem = CASING_INCOMPLETE;
				problemPos = "";
				return false;
			}
			validStructure = false;
			for (int x = 0; x <= maxLength; x++) {
				if (!findCasing(minX + x, minY + 1, minZ) && !findCasing(minX + x - 1, minY, minZ) && !findCasing(minX + x, minY, minZ + 1) && findCasingAll(minX + x - 1, minY, minZ + 1) && findCasingAll(minX + x, minY + 1, minZ + 1) && findCasingAll(minX + x - 1, minY + 1, minZ)) {
					maxX = minX + x;
					validStructure = true;
					break;
				}
			}
			if (!validStructure) {
				complete = 0;
				problem = CASING_INCOMPLETE;
				problemPos = "";
				return false;
			}
			validStructure = false;
			for (int y = 0; y <= maxLength; y++) {
				if (!findCasing(minX, minY + y - 1, minZ) && !findCasing(minX + 1, minY + y, minZ) && !findCasing(minX, minY + y, minZ + 1) && findCasingAll(minX + 1, minY + y, minZ + 1) && findCasingAll(minX, minY + y - 1, minZ + 1) && findCasingAll(minX + 1, minY + y - 1, minZ)) {
					maxY = minY + y;
					validStructure = true;
					break;
				}
			}
			if (!validStructure) {
				complete = 0;
				problem = CASING_INCOMPLETE;
				problemPos = "";
				return false;
			}
			if ((minX > 0 || maxX < 0) || (minY > 0 || maxY < 0) || (minZ > 0 || maxZ < 0) || maxX - minX < 1 || maxY - minY < 1 || maxZ - minZ < 1) {
				problem = INVALID_STRUCTURE;
				complete = 0;
				problemPos = "";
				return false;
			}
			for (int z = minZ; z <= maxZ; z++) for (int x = minX; x <= maxX; x++) for (int y = minY; y <= maxY; y++) {
				if (findController(x, y, z)) {
					if (!(x == 0 && y == 0 && z == 0)) {
						problem = EXTRA_CONTROLLER_AT;
						complete = 0;
						problemPosX = x; problemPosY = y; problemPosZ = z;
						problemPos = POS + " " + BlockPosHelper.stringPos(finder.position(problemPosX, problemPosY, problemPosZ));
						return false;
					}
				}
			}
			for (int z = minZ + 1; z <= maxZ - 1; z++) for (int x = minX + 1; x <= maxX - 1; x++) {
				if (!findCasing(x, minY, z) && !(x == 0 && minY == 0 && z == 0)) {
					problem = CASING_INCOMPLETE_AT;
					complete = 0;
					problemPosX = x; problemPosY = minY; problemPosZ = z;
					problemPos = POS + " " + BlockPosHelper.stringPos(finder.position(problemPosX, problemPosY, problemPosZ));
					return false;
				}
				if (!findCasing(x, maxY, z) && !(x == 0 && maxY == 0 && z == 0)) {
					problem = CASING_INCOMPLETE_AT;
					complete = 0;
					problemPosX = x; problemPosY = maxY; problemPosZ = z;
					problemPos = POS + " " + BlockPosHelper.stringPos(finder.position(problemPosX, problemPosY, problemPosZ));
					return false;
				}
			}
			for (int y = minY + 1; y <= maxY - 1; y++) {
				for (int x = minX + 1; x <= maxX - 1; x++) {
					if (!findCasing(x, y, minZ) && !(x == 0 && y == 0 && minZ == 0)) {
						problem = CASING_INCOMPLETE_AT;
						complete = 0;
						problemPosX = x; problemPosY = y; problemPosZ = minZ;
						problemPos = POS + " " + BlockPosHelper.stringPos(finder.position(problemPosX, problemPosY, problemPosZ));
						return false;
					}
					if (!findCasing(x, y, maxZ) && !(x == 0 && y == 0 && maxZ == 0)) {
						problem = CASING_INCOMPLETE_AT;
						complete = 0;
						problemPosX = x; problemPosY = y; problemPosZ = maxZ;
						problemPos = POS + " " + BlockPosHelper.stringPos(finder.position(problemPosX, problemPosY, problemPosZ));
						return false;
					}
					if (findPort(x, y, minZ)) portCount++;
					if (findPort(x, y, maxZ)) portCount++;
				}
				for (int z = minZ + 1; z <= maxZ - 1; z++) {
					if (!findCasing(minX, y, z) && !(minX == 0 && y == 0 && z == 0)) {
						problem = CASING_INCOMPLETE_AT;
						complete = 0;
						problemPosX = minX; problemPosY = y; problemPosZ = z;
						problemPos = POS + " " + BlockPosHelper.stringPos(finder.position(problemPosX, problemPosY, problemPosZ));
						return false;
					}
					if (!findCasing(maxX, y, z) && !(maxX == 0 && y == 0 && z == 0)) {
						problem = CASING_INCOMPLETE_AT;
						complete = 0;
						problemPosX = maxX; problemPosY = y; problemPosZ = z;
						problemPos = POS + " " + BlockPosHelper.stringPos(finder.position(problemPosX, problemPosY, problemPosZ));
						return false;
					}
					if (findPort(minX, y, z)) portCount++;
					if (findPort(maxX, y, z)) portCount++;
				}
			}
			for (int z = minZ + 1; z <= maxZ - 1; z++) for (int x = minX + 1; x <= maxX - 1; x++) for (int y = minY + 1; y <= maxY - 1; y++) {
				if (findCasingAll(x, y, z)) {
					problem = CASING_IN_INTERIOR_AT;
					complete = 0;
					problemPosX = x; problemPosY = y; problemPosZ = z;
					problemPos = POS + " " + BlockPosHelper.stringPos(finder.position(problemPosX, problemPosY, problemPosZ));
					return false;
				}
			}
			problem = NO_PROBLEM;
			complete = 1;
			problemPos = "";
			this.minX = minX; this.minY = minY; this.minZ = minZ;
			this.maxX = maxX; this.maxY = maxY; this.maxZ = maxZ;
			lengthX = maxX + 1 - minX; lengthY = maxY + 1 - minY; lengthZ = maxZ + 1 - minZ;
			ports = Math.max(1, portCount);
			setCapacity();
			
			return true;
		} else return complete == 1;
	}
	
	private void setCapacity() {
		getEnergyStorage().setStorageCapacity(getNewCapacity());
		getEnergyStorage().setMaxTransfer(getNewCapacity());
	}
	
	private int getNewCapacity() {
		if (NCMath.atIntLimit(getLengthX()*getLengthY()*getLengthZ(), BASE_CAPACITY)) return Integer.MAX_VALUE;
		if (getLengthX() <= 0 || getLengthY() <= 0 || getLengthZ() <= 0) return BASE_CAPACITY;
		return BASE_CAPACITY*getLengthX()*getLengthY()*getLengthZ();
	}
	
	// Set Fuel and Power and Modify Heat

	public boolean shouldTileCheckOffset() {
		return tickCount == NCConfig.machine_update_rate*4;
	}
	
	private void run() {
		double energyThisTick = 0;
		double fuelThisTick = 0;
		double heatThisTick = 0;
		double coolerHeatThisTick = 0;
		int cellCount = 0;
		double energyMultThisTick = 0, heatMultThisTick = 0;
		
		double baseRF = baseProcessPower;
		double baseHeat = baseProcessHeat;
		
		ready = readyToProcess() && !isActivated() ? 1 : 0;

		if (shouldTileCheckOffset()) {
			if (complete == 1) {
				for (int z = minZ + 1; z <= maxZ - 1; z++) for (int x = minX + 1; x <= maxX - 1; x++) for (int y = minY + 1; y <= maxY - 1; y++) {
					
					// Cells
					if (findCell(x, y, z)) {
						int extraCells = 0;
						for (EnumFacing side : EnumFacing.VALUES) {
							if (findCellOnSide(x, y, z, side) || findModeratorThenCellOnSide(x, y, z, side)) extraCells += 1;
						}
						
						cellCount++;
						energyMultThisTick += extraCells + 1;
						heatMultThisTick += (extraCells + 1)*(extraCells + 2)/2;
						if (readyToProcess()) {
							energyThisTick += baseRF*(extraCells + 1);
							heatThisTick += baseHeat*(extraCells + 1)*(extraCells + 2)/2;
						}
						
						if (isProcessing) fuelThisTick += NCConfig.fission_fuel_use;
					}
					
					// Moderators
					if (findModerator(x, y, z)) {
						if (readyToProcess()) {
							heatMultThisTick += cellCount/16D;
							heatThisTick += NCConfig.fission_heat_generation*baseRF*cellCount/16D;
						}
						if (cellAdjacent(x, y, z)) {
							energyMultThisTick += cellCount/8D;
							if (readyToProcess()) energyThisTick += NCConfig.fission_power*baseRF*cellCount/8D;
						}
					}
					
					// Passive Coolers
					for (int i = 1; i < CoolerType.values().length; i++) {
						if (findCooler(x, y, z, i)) if (coolerRequirements(x, y, z, i)) {
							coolerHeatThisTick -= CoolerType.values()[i].getCooling();
							break;
						}
					}
				}
				
				for (int z = minZ + 1; z <= maxZ - 1; z++) for (int x = minX + 1; x <= maxX - 1; x++) for (int y = minY + 1; y <= maxY - 1; y++) {
					
					// Active Coolers
					if (finder.find(x, y, z, NCBlocks.active_cooler)) {
						TileEntity tile = world.getTileEntity(finder.position(x, y, z));
						if (tile != null) if (tile instanceof TileActiveCooler) {
							Tank tank = ((TileActiveCooler) tile).getTanks().get(0);
							int fluidAmount = Math.min(tank.getFluidAmount(), 4*NCConfig.machine_update_rate*NCConfig.active_cooler_max_rate/20);
							if (fluidAmount > 0) {
								double currentHeat = heat + (isProcessing ? heatThisTick : 0) + coolerHeatThisTick;
								for (int i = 1; i < CoolerType.values().length; i++) {
									if (tank.getFluidName() == CoolerType.values()[i].getFluidName()) {
										if (coolerRequirements(x, y, z, i)) {
											coolerHeatThisTick -= (NCConfig.fission_active_cooling_rate[i - 1]*fluidAmount)/(4*NCConfig.machine_update_rate);
											break;
										}
									}
								}
								if (currentHeat > 0) {
									double newHeat = heat + (isProcessing ? heatThisTick : 0) + coolerHeatThisTick;
									if (newHeat >= 0) tank.drain(MathHelper.ceil(fluidAmount), true); else {
										double heatFraction = currentHeat/(currentHeat - newHeat);
										tank.drain(MathHelper.ceil(fluidAmount*heatFraction), true);
									}
								}
							}
						}
					}
				}
			}
			
			if (complete == 1) {
				heatChange = heatThisTick + coolerHeatThisTick;
				cooling = coolerHeatThisTick;
				cells = cellCount;
				efficiency = cellCount == 0 ? 0D : 100D*energyMultThisTick/cellCount;
				heatMult = cellCount == 0 ? 0D : 100D*heatMultThisTick/cellCount;
				processPower = energyThisTick;
				speedMultiplier = fuelThisTick;
			} else {
				heatChange = 0;
				cooling = 0;
				efficiency = 0;
				heatMult = 0;
				cells = 0;
				processPower = 0;
				speedMultiplier = 0;
			}
		}
		
		if (isProcessing) {
			if (heat + heatChange >= 0) {
				heat += heatChange;
			} else {
				heat = 0;
			}
		} else if (ready == 1 || complete == 1) {
			if (heat + cooling >= 0) {
				heat += cooling;
			} else {
				heat = 0;
			}
		}
	}
	
	private void newRun() {
		double energyThisTick = 0D;
		double fuelThisTick = 0D;
		double heatThisTick = 0D;
		double coolerHeatThisTick = 0D;
		int cellCount = 0;
		double energyMultThisTick = 0D, heatMultThisTick = 0D;
		
		double baseRF = NCConfig.fission_power*baseProcessPower;
		double baseHeat = NCConfig.fission_heat_generation*baseProcessHeat;
		
		double moderatorPowerMultiplier = NCConfig.fission_moderator_extra_power/6D;
		double moderatorHeatMultiplier = NCConfig.fission_moderator_extra_heat/6D;
		
		ready = readyToProcess() && !isActivated() ? 1 : 0;

		if (shouldTileCheck()) {
			if (complete == 1) {
				if (ModCheck.openComputersLoaded() && readLayout) layout = new Object[getLengthX()*getLengthY()*getLengthZ()];
				
				for (int z = minZ + 1; z <= maxZ - 1; z++) for (int x = minX + 1; x <= maxX - 1; x++) for (int y = minY + 1; y <= maxY - 1; y++) {
					
					// Cells
					if (findCell(x, y, z)) {
						int extraCells = 0;
						for (EnumFacing side : EnumFacing.VALUES) {
							if (findCellOnSide(x, y, z, side) || newFindModeratorThenCellOnSide(x, y, z, side)) extraCells += 1;
						}
						
						cellCount++;
						energyMultThisTick += extraCells + 1D;
						heatMultThisTick += (extraCells + 1D)*(extraCells + 2D)/2D;
						if (readyToProcess()) {
							energyThisTick += baseRF*(extraCells + 1D);
							heatThisTick += baseHeat*(extraCells + 1D)*(extraCells + 2D)/2D;
						}
						
						if (isProcessing) fuelThisTick += NCConfig.fission_fuel_use;
						
						// Adjacent Moderators
						int moderatorAdjacentCount = moderatorAdjacentCount(x, y, z);
						
						energyMultThisTick += moderatorPowerMultiplier*moderatorAdjacentCount*(extraCells + 1D);
						heatMultThisTick += moderatorHeatMultiplier*moderatorAdjacentCount*(extraCells + 1D);
						
						energyThisTick += baseRF*moderatorPowerMultiplier*moderatorAdjacentCount*(extraCells + 1D);
						heatThisTick += baseHeat*moderatorHeatMultiplier*moderatorAdjacentCount*(extraCells + 1D);
					}
					
					// Extra Moderators
					if (readyToProcess()) if (findModerator(x, y, z)) {
						if (!cellAdjacent(x, y, z)) heatThisTick += baseHeat;
					}
					
					// Passive Coolers
					for (int i = 1; i < CoolerType.values().length; i++) {
						if (findCooler(x, y, z, i)) if (coolerRequirements(x, y, z, i)) {
							coolerHeatThisTick -= CoolerType.values()[i].getCooling();
							break;
						}
					}	
				}
				
				for (int z = minZ + 1; z <= maxZ - 1; z++) for (int x = minX + 1; x <= maxX - 1; x++) for (int y = minY + 1; y <= maxY - 1; y++) {
					
					// Active Coolers
					if (finder.find(x, y, z, NCBlocks.active_cooler)) {
						TileEntity tile = world.getTileEntity(finder.position(x, y, z));
						if (tile != null) if (tile instanceof TileActiveCooler) {
							Tank tank = ((TileActiveCooler) tile).getTanks().get(0);
							int fluidAmount = Math.min(tank.getFluidAmount(), 4*NCConfig.machine_update_rate*NCConfig.active_cooler_max_rate/20);
							if (fluidAmount > 0) {
								double currentHeat = heat + (isProcessing ? heatThisTick : 0D) + coolerHeatThisTick;
								for (int i = 1; i < CoolerType.values().length; i++) {
									if (tank.getFluidName() == CoolerType.values()[i].getFluidName()) {
										if (coolerRequirements(x, y, z, i)) {
											coolerHeatThisTick -= (NCConfig.fission_active_cooling_rate[i - 1]*fluidAmount)/(4D*NCConfig.machine_update_rate);
											break;
										}
									}
								}
								if (currentHeat > 0) {
									double newHeat = heat + (isProcessing ? heatThisTick : 0D) + coolerHeatThisTick;
									if (newHeat >= 0) tank.drain(MathHelper.ceil(fluidAmount), true); else {
										double heatFraction = currentHeat/(currentHeat - newHeat);
										tank.drain(MathHelper.ceil(fluidAmount*heatFraction), true);
									}
								}
							}
						}
					}
					if (ModCheck.openComputersLoaded() && readLayout) {
						int arrayX = x - minX - 1; int arrayY = y - minY - 1; int arrayZ = z - minZ - 1;
						IBlockState layoutState = world.getBlockState(finder.position(x, y, z));
						String mainName = layoutState.getBlock().getRegistryName().toString();
						int meta = layoutState.getBlock().getMetaFromState(layoutState);
						layout[arrayX + getLengthX()*arrayY + getLengthX()*getLengthY()*arrayZ] = new Object[] {new Object[] {x, y, z}, new Object[] {mainName, meta}};
					}
				}
			}
			
			if (complete == 1) {
				heatChange = heatThisTick + coolerHeatThisTick;
				cooling = coolerHeatThisTick;
				cells = cellCount;
				efficiency = cellCount == 0 ? 0D : 100D*energyMultThisTick/(double)cellCount;
				heatMult = cellCount == 0 ? 0D : 100D*heatMultThisTick/(double)cellCount;
				processPower = energyThisTick;
				speedMultiplier = fuelThisTick;
			} else {
				heatChange = 0D;
				cooling = 0D;
				efficiency = 0D;
				heatMult = 0D;
				cells = 0;
				processPower = 0D;
				speedMultiplier = 0D;
			}
		}
		
		if (isProcessing) {
			if (heat + heatChange >= 0D) {
				heat += heatChange;
			} else {
				heat = 0D;
			}
		} else if (ready == 1 || complete == 1) {
			if (heat + cooling >= 0D) {
				heat += cooling;
			} else {
				heat = 0D;
			}
		}
	}
	
	// NBT
	
	@Override
	public NBTTagCompound writeAll(NBTTagCompound nbt) {
		super.writeAll(nbt);
		nbt.setDouble("processPower", processPower);
		nbt.setDouble("speedMultiplier", speedMultiplier);
		nbt.setDouble("baseProcessTime", baseProcessTime);
		nbt.setDouble("heat", heat);
		nbt.setDouble("cooling", cooling);
		nbt.setDouble("efficiency", efficiency);
		nbt.setInteger("cells", cells);
		nbt.setInteger("minX", minX);
		nbt.setInteger("minY", minY);
		nbt.setInteger("minZ", minZ);
		nbt.setInteger("maxX", maxX);
		nbt.setInteger("maxY", maxY);
		nbt.setInteger("maxZ", maxZ);
		nbt.setInteger("lengthX", lengthX);
		nbt.setInteger("lengthY", lengthY);
		nbt.setInteger("lengthZ", lengthZ);
		nbt.setDouble("heatChange", heatChange);
		nbt.setDouble("heatMult", heatMult);
		nbt.setInteger("complete", complete);
		nbt.setInteger("ready", ready);
		nbt.setString("problem", problem);
		nbt.setString("problemPos", problemPos);
		nbt.setInteger("problemPosX", problemPosX);
		nbt.setInteger("problemPosY", problemPosY);
		nbt.setInteger("problemPosZ", problemPosZ);
		nbt.setBoolean("newRules", newRules);
		nbt.setInteger("ports", ports);
		nbt.setInteger("currentEnergyStored", currentEnergyStored);
		nbt.setBoolean("computerActivated", computerActivated);
		nbt.setBoolean("readLayout", readLayout);
		return nbt;
	}
			
	@Override
	public void readAll(NBTTagCompound nbt) {
		lengthX = nbt.getInteger("lengthX");
		lengthY = nbt.getInteger("lengthY");
		lengthZ = nbt.getInteger("lengthZ");
		getEnergyStorage().setStorageCapacity(getNewCapacity());
		super.readAll(nbt);
		processPower = nbt.getDouble("processPower");
		speedMultiplier = nbt.getDouble("speedMultiplier");
		baseProcessTime = nbt.getDouble("baseProcessTime");
		heat = nbt.getDouble("heat");
		cooling = nbt.getDouble("cooling");
		efficiency = nbt.getDouble("efficiency");
		cells = nbt.getInteger("cells");
		minX = nbt.getInteger("minX");
		minY = nbt.getInteger("minY");
		minZ = nbt.getInteger("minZ");
		maxX = nbt.getInteger("maxX");
		maxY = nbt.getInteger("maxY");
		maxZ = nbt.getInteger("maxZ");
		heatChange = nbt.getDouble("heatChange");
		heatMult = nbt.getDouble("heatMult");
		complete = nbt.getInteger("complete");
		ready = nbt.getInteger("ready");
		problem = nbt.getString("problem");
		problemPos = nbt.getString("problemPos");
		problemPosX = nbt.getInteger("problemPosX");
		problemPosY = nbt.getInteger("problemPosY");
		problemPosZ = nbt.getInteger("problemPosZ");
		newRules = nbt.getBoolean("newRules");
		ports = nbt.getInteger("ports");
		currentEnergyStored = nbt.getInteger("currentEnergyStored");
		computerActivated = nbt.getBoolean("computerActivated");
		readLayout = nbt.getBoolean("readLayout");
	}
	
	// Inventory Fields

	@Override
	public int getFieldCount() {
		return 21;
	}

	@Override
	public int getField(int id) {
		switch (id) {
		case 0:
			return (int) time;
		case 1:
			return getEnergyStored();
		case 2:
			return (int) baseProcessTime;
		case 3:
			return (int) processPower;
		case 4:
			return (int) heat;
		case 5:
			return (int) cooling;
		case 6:
			return (int) (efficiency*10);
		case 7:
			return cells;
		case 8:
			return (int) speedMultiplier;
		case 9:
			return lengthX;
		case 10:
			return lengthY;
		case 11:
			return lengthZ;
		case 12:
			return (int) heatChange;
		case 13:
			return complete;
		case 14:
			return ready;
		case 15:
			return problemPosX;
		case 16:
			return problemPosY;
		case 17:
			return problemPosZ;
		case 18:
			return (int) (heatMult*10);
		case 19:
			return hasConsumed ? 1 : 0;
		case 20:
			return computerActivated ? 1 : 0;
		default:
			return 0;
		}
	}

	@Override
	public void setField(int id, int value) {
		switch (id) {
		case 0:
			time = value;
			break;
		case 1:
			getEnergyStorage().setEnergyStored(value);
			break;
		case 2:
			baseProcessTime = value;
			break;
		case 3:
			processPower = value;
			break;
		case 4:
			heat = value;
			break;
		case 5:
			cooling = value;
			break;
		case 6:
			efficiency = value/10D;
			break;
		case 7:
			cells = value;
			break;
		case 8:
			speedMultiplier = value;
			break;
		case 9:
			lengthX = value;
			break;
		case 10:
			lengthY = value;
			break;
		case 11:
			lengthZ = value;
			break;
		case 12:
			heatChange = value;
			break;
		case 13:
			complete = value;
			break;
		case 14:
			ready = value;
			break;
		case 15:
			problemPosX = value;
			break;
		case 16:
			problemPosY = value;
			break;
		case 17:
			problemPosZ = value;
			break;
		case 18:
			heatMult = value/10D;
			break;
		case 19:
			hasConsumed = value == 1;
			break;
		case 20:
			computerActivated = value == 1;
		}
	}
	
	// Allow ports to open GUI
	
	@Override
	public boolean isUsableByPlayer(EntityPlayer player) {
		return world.getTileEntity(pos) != this ? false : player.getDistanceSq((double) pos.getX() + 0.5D, (double) pos.getY() + 0.5D, (double) pos.getZ() + 0.5D) <= Math.max(lengthX*lengthX + lengthY*lengthY + lengthZ*lengthZ, 64.0D);
	}
	
	// OpenComputers
	
	@Override
	@Optional.Method(modid = "opencomputers")
	public String getComponentName() {
		return Global.MOD_SHORT_ID + "_fission_reactor";
	}
	
	@Callback
	@Optional.Method(modid = "opencomputers")
	public Object[] isComplete(Context context, Arguments args) {
		return new Object[] {complete == 1};
	}
	
	@Callback
	@Optional.Method(modid = "opencomputers")
	public Object[] isProcessing(Context context, Arguments args) {
		return new Object[] {isProcessing};
	}
	
	@Callback
	@Optional.Method(modid = "opencomputers")
	public Object[] getProblem(Context context, Arguments args) {
		return new Object[] {problem};
	}
	
	@Callback
	@Optional.Method(modid = "opencomputers")
	public Object[] getLengthX(Context context, Arguments args) {
		return new Object[] {getLengthX()};
	}
	
	@Callback
	@Optional.Method(modid = "opencomputers")
	public Object[] getLengthY(Context context, Arguments args) {
		return new Object[] {getLengthY()};
	}
	
	@Callback
	@Optional.Method(modid = "opencomputers")
	public Object[] getLengthZ(Context context, Arguments args) {
		return new Object[] {getLengthZ()};
	}
	
	@Callback
	@Optional.Method(modid = "opencomputers")
	public Object[] getEnergyStored(Context context, Arguments args) {
		return new Object[] {getEnergyStored()};
	}
	
	@Callback
	@Optional.Method(modid = "opencomputers")
	public Object[] getMaxEnergyStored(Context context, Arguments args) {
		return new Object[] {getMaxEnergyStored()};
	}
	
	@Callback
	@Optional.Method(modid = "opencomputers")
	public Object[] getEnergyChange(Context context, Arguments args) {
		return new Object[] {energyChange};
	}
	
	@Callback
	@Optional.Method(modid = "opencomputers")
	public Object[] getCurrentProcessTime(Context context, Arguments args) {
		return new Object[] {time};
	}
	
	@Callback
	@Optional.Method(modid = "opencomputers")
	public Object[] getHeatLevel(Context context, Arguments args) {
		return new Object[] {heat};
	}
	
	@Callback
	@Optional.Method(modid = "opencomputers")
	public Object[] getMaxHeatLevel(Context context, Arguments args) {
		return new Object[] {getMaxHeat()};
	}
	
	@Callback
	@Optional.Method(modid = "opencomputers")
	public Object[] getEfficiency(Context context, Arguments args) {
		return new Object[] {efficiency};
	}
	
	@Callback
	@Optional.Method(modid = "opencomputers")
	public Object[] getHeatMultiplier(Context context, Arguments args) {
		return new Object[] {heatMult};
	}
	
	@Callback
	@Optional.Method(modid = "opencomputers")
	public Object[] getFissionFuelTime(Context context, Arguments args) {
		return new Object[] {recipe != null ? baseProcessTime : 0};
	}
	
	@Callback
	@Optional.Method(modid = "opencomputers")
	public Object[] getFissionFuelPower(Context context, Arguments args) {
		return new Object[] {baseProcessPower};
	}
	
	@Callback
	@Optional.Method(modid = "opencomputers")
	public Object[] getFissionFuelHeat(Context context, Arguments args) {
		return new Object[] {baseProcessHeat};
	}
	
	@Callback
	@Optional.Method(modid = "opencomputers")
	public Object[] getFissionFuelName(Context context, Arguments args) {
		return new Object[] {getFuelName()};
	}
	
	@Callback
	@Optional.Method(modid = "opencomputers")
	public Object[] getReactorProcessTime(Context context, Arguments args) {
		return new Object[] {recipe != null ? (cells == 0 ? baseProcessTime : baseProcessTime/cells) : 0};
	}
	
	@Callback
	@Optional.Method(modid = "opencomputers")
	public Object[] getReactorProcessPower(Context context, Arguments args) {
		return new Object[] {processPower};
	}
	
	@Callback
	@Optional.Method(modid = "opencomputers")
	public Object[] getReactorProcessHeat(Context context, Arguments args) {
		return new Object[] {heatChange};
	}
	
	@Callback
	@Optional.Method(modid = "opencomputers")
	public Object[] getReactorCoolingRate(Context context, Arguments args) {
		return new Object[] {cooling};
	}
	
	@Callback
	@Optional.Method(modid = "opencomputers")
	public Object[] trackReactorLayout(Context context, Arguments args) {
		if (args.isBoolean(0)) readLayout = args.checkBoolean(0);
		return new Object[] {};
	}
	
	@Callback
	@Optional.Method(modid = "opencomputers")
	public Object[] getReactorLayout(Context context, Arguments args) {
		return complete == 1 && readLayout ? layout : new Object[] {};
	}
	
	@Callback
	@Optional.Method(modid = "opencomputers")
	public Object[] activate(Context context, Arguments args) {
		computerActivated = true;
		return new Object[] {};
	}
	
	@Callback
	@Optional.Method(modid = "opencomputers")
	public Object[] deactivate(Context context, Arguments args) {
		computerActivated = false;
		return new Object[] {};
	}
}
